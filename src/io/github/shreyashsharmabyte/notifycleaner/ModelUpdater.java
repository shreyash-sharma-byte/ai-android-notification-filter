package io.github.shreyashsharmabyte.notifycleaner;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Fetches, verifies and activates new classifier weights. */
public final class ModelUpdater {
    public static final String SOURCE = "https://github.com/shreyash-sharma-byte/notifycleaner-classifier";
    public static final String MANIFEST =
            "https://raw.githubusercontent.com/shreyash-sharma-byte/ai-android-notification-filter/main/classifier.json";
    /** Version of the weights compiled into this APK. Bump when assets/local_model.tsv.gz changes. */
    public static final long BUNDLED_VERSION = 8;

    private ModelUpdater() {}

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "classifier");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File active(Context c) {
        return new File(dir(c), "model.tsv.gz");
    }

    public static String check(Context c, Prefs p) {
        p.setLastCheck(System.currentTimeMillis());
        try {
            String manifest = fetch(MANIFEST, 20000);
            long remote = ModelStore.version(manifest);
            long current = Math.max(p.modelVersion(), BUNDLED_VERSION);
            if (remote <= current) return "up to date (v" + current + ")";
            if (remote < p.minAppVersion()) return "needs newer app";
            String url = ModelStore.field(manifest, "url");
            String wantSha = ModelStore.field(manifest, "sha256");
            if (url.isEmpty() || wantSha.isEmpty()) return "bad manifest";
            byte[] gz = download(url);
            if (gz == null) return "download failed";
            if (!wantSha.equalsIgnoreCase(ModelStore.sha256(gz))) return "checksum mismatch";
            if (!ModelStore.acceptable(gz)) return "model unreadable";
            String probe = probe(gz);
            if (!probe.isEmpty()) return "probe failed: " + probe;
            File tmp = new File(dir(c), "model.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(gz);
                out.flush();
                out.getFD().sync();
            }
            if (!ModelStore.swap(tmp, active(c), new File(dir(c), "model.prev"))) return "swap failed";
            p.setModelMeta(ModelStore.meta(remote, wantSha, p.minAppVersion(),
                    ModelStore.field(manifest, "notes")), remote);
            return "updated to v" + remote;
        } catch (Exception e) {
            return "check failed: " + e.getClass().getSimpleName();
        }
    }

    public static boolean revert(Context c, Prefs p) {
        File prev = new File(dir(c), "model.prev");
        File target = active(c);
        if (!prev.isFile()) return false;
        if (target.exists() && !target.delete()) return false;
        if (!prev.renameTo(target)) return false;
        p.setModelMeta("", 0);
        return true;
    }

    /** Runs the golden probes against a candidate model; returns "" when it behaves. */
    static String probe(byte[] gz) {
        LocalClassifier.Model m;
        try {
            m = LocalClassifier.load(new ByteArrayInputStream(gz));
        } catch (Throwable t) {
            return "load";
        }
        if (m == null) return "load";
        LocalClassifier.Result otp = LocalClassifier.classify(m, "com.android.mms", "Messages",
                "482913 is your OTP for login. Do not share.");
        if (otp.verdict == Decision.Verdict.JUNK) return "otp dismissed";
        LocalClassifier.Result promo = LocalClassifier.classify(m, "com.application.zomato", "Zomato",
                "50% OFF on your next order. Order now!");
        if (promo.verdict != Decision.Verdict.JUNK) return "promo kept";
        LocalClassifier.Result chat = LocalClassifier.classify(m, "org.telegram.messenger", "Telegram",
                "Are you free tonight?");
        if (chat.verdict == Decision.Verdict.JUNK) return "chat dismissed";
        return "";
    }

    static byte[] download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept-Encoding", "identity");
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String fetch(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
