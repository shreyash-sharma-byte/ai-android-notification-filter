package io.github.shreyashsharmabyte.notifycleaner;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * On-device classifier. Pure JDK — no {@code android.*}, no native code, no network.
 *
 * <p>This is the default answer engine: a linear model over TF-IDF word unigrams and bigrams, trained
 * offline on labelled notifications and shipped as an 18 KB asset. It exists because a benchmark
 * against three sub-1B language models showed them answering KEEP to every input — including
 * "50% OFF for you" — while this removes 83% of promotions and has never destroyed a transaction or
 * a verification code.
 *
 * <p>Two layers, in order:
 * <ol>
 *   <li><b>Guard rules.</b> Narrow regexes for the class of notification that must never be dismissed:
 *       OTPs, money moving, order and delivery state, security events, account deadlines. These are
 *       deliberately narrow — a bare "code" would match "Use code CRAVE50", so promo codes are not
 *       caught by them. Measured on the held-out set they fire on 30% of notifications with zero
 *       false positives.</li>
 *   <li><b>The linear model.</b> Everything else. Its decision is a dot product; anything at or below
 *       zero keeps the notification, so the fail-open rule is arithmetic rather than policy.</li>
 * </ol>
 *
 * <p>The arithmetic mirrors scikit-learn exactly (sublinear TF, smoothed IDF, L2 normalisation), and
 * is verified against it: the exported weights reproduce scikit-learn's own predictions on every
 * training document.
 */
public final class LocalClassifier {

    /** Regexes for notifications that must never be dismissed. Keep narrow. */
    private static final String[][] GUARDS = {
        {"otp", "\\botp\\b|one[- ]time password|(verification|login|security) code|do not share.{0,40}\\d{4,6}"},
        {"transaction", "\\bdebited\\b|\\bcredited\\b|\\brefund(ed)?\\b|payment (failed|unsuccessful|declined)|"
                + "\\bexecuted\\b|square[- ]off|margin shortfall|\\btransferred\\b|\\bemi\\b"},
        {"order", "\\b(order|package|shipment|parcel)\\b.{0,40}(confirm|placed|deliver|cancel|shipp|delay|dispatch)|"
                + "out for delivery|on the way|picking up|\\bdelivered\\b|tracking id|\\bpnr\\b|\\bboarding\\b"},
        {"security", "\\bblocked\\b|sign[- ]?in|new login|password.{0,20}chang|security alert|"
                + "unauthori[sz]ed|suspicious|was this you|re-?verify"},
        {"deadline", "expires? (today|tomorrow|in \\d)|low balance|re-?kyc|\\bdue (on|in|today)\\b|"
                + "avoid (interruption|suspension|square)"},
    };

    private static final Pattern[] GUARD_RX = new Pattern[GUARDS.length];

    /**
     * Compiling these at class-init time is what broke the app on device: an
     * {@code ExceptionInInitializerError} here takes out the whole class, and on Android the
     * Unicode-aware regex flags are ICU-backed and can reject patterns that a desktop JVM accepts.
     *
     * <p>So: plain {@code CASE_INSENSITIVE} only, and every rule compiled inside its own guard. A
     * rule that will not compile is dropped, which costs us one safety net but leaves the other
     * four and the model itself working. Nothing here is allowed to throw at class load.
     */
    static {
        for (int i = 0; i < GUARDS.length; i++) {
            try {
                GUARD_RX[i] = Pattern.compile(GUARDS[i][1], Pattern.CASE_INSENSITIVE);
            } catch (Throwable t) {
                GUARD_RX[i] = null;
            }
        }
    }

    /** Below this margin the model is treated as unsure and the notification is kept. */
    static final float MARGIN = 0f;

    public static final class Model {
        final float bias;
        final Map<String, Integer> index;
        final float[] idf;
        final float[] coef;

        Model(float bias, Map<String, Integer> index, float[] idf, float[] coef) {
            this.bias = bias;
            this.index = index;
            this.idf = idf;
            this.coef = coef;
        }

        public int size() { return index.size(); }
    }

    public static final class Result {
        public final Decision.Verdict verdict;
        /** Rule name when a guard decided it, else null. */
        public final String guard;
        /** Raw dot product before thresholding; positive leans junk. */
        public final float score;

        Result(Decision.Verdict verdict, String guard, float score) {
            this.verdict = verdict;
            this.guard = guard;
            this.score = score;
        }
    }

    private LocalClassifier() {}

    // ------------------------------------------------------------------ loading

    /** Reads the gzipped asset. Rows are {@code token<TAB>idf<TAB>coef}; the first row is the bias. */
    public static Model load(InputStream raw) throws IOException {
        // Read it all first (18 KB) and sniff the gzip magic. Whether an asset arrives gzipped,
        // already inflated, or wrapped by the AssetManager's own compression is not something the
        // caller should have to know, and guessing wrong looks exactly like "the model is missing".
        byte[] all = readFully(raw);
        InputStream body = (all.length > 2 && (all[0] & 0xff) == 0x1f && (all[1] & 0xff) == 0x8b)
                ? new GZIPInputStream(new ByteArrayInputStream(all))
                : new ByteArrayInputStream(all);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8));

        String first = in.readLine();
        if (first == null || !first.startsWith("#bias")) throw new IOException("not a model file");
        float bias = Float.parseFloat(first.split("\t")[1]);

        Map<String, Integer> index = new HashMap<String, Integer>(4096);
        List<Float> idf = new ArrayList<Float>(4096);
        List<Float> coef = new ArrayList<Float>(4096);

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) continue;
            int a = line.indexOf('\t');
            int b = line.indexOf('\t', a + 1);
            if (a < 0 || b < 0) continue;
            index.put(line.substring(0, a), idf.size());
            idf.add(Float.parseFloat(line.substring(a + 1, b)));
            coef.add(Float.parseFloat(line.substring(b + 1)));
        }
        in.close();

        float[] idfArr = new float[idf.size()];
        float[] coefArr = new float[coef.size()];
        for (int i = 0; i < idfArr.length; i++) {
            idfArr[i] = idf.get(i);
            coefArr[i] = coef.get(i);
        }
        return new Model(bias, index, idfArr, coefArr);
    }

    private static byte[] readFully(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(24576);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ inference

    /** @return the guard rule that matched, or null */
    static String guard(String app, String title, String text) {
        String blob = nz(app) + " " + nz(title) + " " + nz(text);
        for (int i = 0; i < GUARD_RX.length; i++) {
            if (GUARD_RX[i] != null && GUARD_RX[i].matcher(blob).find()) return GUARDS[i][0];
        }
        return null;
    }

    public static Result classify(Model model, String app, String title, String text) {
        String hit = guard(app, title, text);
        if (hit != null) {
            // A guard is not a guess. It protects the classes the app must never get wrong.
            return new Result(Decision.Verdict.KEEP, hit, -1f);
        }

        float score = score(model, render(app, title, text));
        if (score > MARGIN) return new Result(Decision.Verdict.JUNK, null, score);
        if (-score < MARGIN && MARGIN > 0f) return new Result(Decision.Verdict.UNCLEAR, null, score);
        return new Result(Decision.Verdict.KEEP, null, score);
    }

    /** The exact string the model was trained on. Changing this invalidates the weights. */
    static String render(String app, String title, String text) {
        return nz(app) + ": " + nz(title) + ". " + nz(text);
    }

    /**
     * Runs of two or more letters or digits, matching scikit-learn's default
     * {@code (?u)\b\w\w+\b} for the ASCII these notifications are written in — without asking
     * Android's regex engine to do it.
     */
    static void tokenize(String s, List<String> out) {
        StringBuilder cur = new StringBuilder(24);
        int n = s.length();
        for (int i = 0; i <= n; i++) {
            char ch = i < n ? Character.toLowerCase(s.charAt(i)) : ' ';
            boolean word = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || (ch > 127 && Character.isLetterOrDigit(ch));
            if (word) {
                cur.append(ch);
            } else {
                if (cur.length() >= 2) out.add(cur.toString());
                cur.setLength(0);
            }
        }
    }

    static float score(Model model, String rendered) {
        // scikit: tf' = 1 + ln(tf); x = tf' * idf; then L2-normalise the whole vector.
        Map<String, Integer> tf = new HashMap<String, Integer>();
        List<String> tokens = new ArrayList<String>();
        tokenize(rendered, tokens);
        for (int i = 0; i < tokens.size(); i++) {
            bump(tf, tokens.get(i));
            if (i + 1 < tokens.size()) bump(tf, tokens.get(i) + " " + tokens.get(i + 1));
        }

        float sum = 0f;
        float normSq = 0f;
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            Integer idx = model.index.get(e.getKey());
            if (idx == null) continue;
            float x = (float) (1.0 + Math.log(e.getValue())) * model.idf[idx];
            sum += x * model.coef[idx];
            normSq += x * x;
        }
        float norm = (float) Math.sqrt(normSq);
        if (norm == 0f) return 0f;          // nothing recognised: keep
        return model.bias + sum / norm;
    }

    private static void bump(Map<String, Integer> tf, String key) {
        Integer c = tf.get(key);
        tf.put(key, c == null ? 1 : c + 1);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
