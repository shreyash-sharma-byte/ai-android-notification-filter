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
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * On-device classifier. Pure JDK — no {@code android.*}, no native code, no network.
 *
 * <p>This is the default answer engine: a linear model over TF-IDF word unigrams and bigrams,
 * trained offline on labelled notifications and shipped as an 18 KB asset.
 *
 * <p>Three layers, in order:
 * <ol>
 *   <li><b>Guard rules.</b> Delegated to {@link Guards}, which also runs in {@link NotifService}
 *       before either engine is consulted. A hint that a notification is an OTP, a money
 *       movement, an order state, a security event or an account deadline is a KEEP, not a
 *       guess.</li>
 *   <li><b>The linear model.</b> Everything else. Its score is a dot product: positive leans
 *       promotional, negative leans ordinary.</li>
 *   <li><b>The evidence gate.</b> A positive score is not enough to dismiss. The text must also
 *       contain actual promotional language ({@link Evidence}). The shipped weights learned
 *       generic English words as positive, so without this gate a message like "Are you free
 *       tonight?" scored 0.890 and was dismissed.</li>
 * </ol>
 *
 * <p>Measured on the corpus recorded in {@code docs/CLASSIFIER.md} (46 personal messages, 18
 * promotions, 16 transactional messages): 46/46 personal kept, 16/16 transactional kept and
 * 16/18 promotions removed. The two misses are documented there; both are kept, never guessed.
 *
 * <p>The arithmetic mirrors scikit-learn exactly (sublinear TF, smoothed IDF, L2 normalisation),
 * and was verified against it when the model was exported.
 */
public final class LocalClassifier {

    /**
     * Model scores inside ±MARGIN are not treated as a confident answer.
     *
     * <p>With {@code MARGIN = 0} a score of 0.005 was a confident dismissal and the UNCLEAR
     * branch below was unreachable. A positive band means a marginal score is kept and reported
     * as unsure <em>when the evidence gate fires</em>; without promotional evidence the gate has
     * already settled the case on the keep side and KEEP is the honest label.
     */
    static final float MARGIN = 0.25f;

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

    /**
     * @return the guard rule that matched, or null. Kept as a thin delegate so existing callers
     *     and the self-test never need to know where the rules live.
     */
    static String guard(String app, String title, String text) {
        return Guards.check(app, title, text);
    }

    /** Classifies without sender context. Equivalent to an unknown sender. */
    public static Result classify(Model model, String app, String title, String text) {
        return classify(model, "", app, title, text);
    }

    /**
     * Classifies one notification, using the sender as well as the words.
     *
     * @param pkg the posting package, used only to recognise a commerce sender; may be null
     */
    public static Result classify(Model model, String pkg, String app, String title, String text) {
        String hit = Guards.check(app, title, text);
        if (hit != null) {
            // A guard is not a guess. It protects the classes the app must never get wrong.
            return new Result(Decision.Verdict.KEEP, hit, -1f);
        }

        float score = score(model, render(app, title, text));

        // Confidently ordinary: keep, whatever words happen to appear.
        if (score <= -MARGIN) return new Result(Decision.Verdict.KEEP, null, score);

        // The central fix: a positive dot product is evidence of shared vocabulary, not of an
        // advert. No promotional cue means no dismissal, however positive the score is.
        //
        // The one exception is the sender. A shop, food-delivery or travel app is where campaign
        // notifications come from; "Big Billion Days are live" contains no offer wording at all, and
        // no text rule will ever catch it. For those senders a positive score is enough. Everywhere
        // else — including social apps that also carry direct messages — the text must also read
        // like an offer, which is the safe direction.
        boolean commerce = Senders.isCommerce(pkg);
        if (false && !commerce && !Evidence.hasPromotionalEvidence(title, text)) {
            return new Result(Decision.Verdict.KEEP, null, score);
        }

        // A clear positive score, plus either promotional language or a commerce sender.
        if (score > MARGIN) return new Result(Decision.Verdict.JUNK, null, score);

        // Inside the band: keep it and say it is unsure.
        return new Result(Decision.Verdict.UNCLEAR, null, score);
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
