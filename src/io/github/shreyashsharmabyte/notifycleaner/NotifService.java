package io.github.shreyashsharmabyte.notifycleaner;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The notification listener.
 *
 * <p>Its whole job is to decide, cheaply and safely, which notifications are worth acting on. Anything
 * that is functional (media, calls, alarms, downloads, ongoing) never reaches a classifier. Everything
 * else is queued, and the default answer for every uncertain path is "keep it".
 *
 * <p>Two engines, chosen by {@link Prefs#engine()}. The default is the on-device
 * {@link LocalClassifier}: an 18 KB linear model loaded once from the APK's assets, no network, no
 * key, no account. The other is the user's own cloud provider, reachable only after they opt in and
 * agree to a provider-specific disclosure. The five guard rules run in {@link #inspect} before the
 * engine split, and both paths return the same three verdicts and share the same fail-open handling,
 * so nothing about dismissal changes with the engine.
 */
public class NotifService extends NotificationListenerService {

    private static final String TAG = "NotifyCleaner";

    static final int WORKERS = 2;
    static final int QUEUE_DEPTH = 30;
    static final int CACHE_SIZE = 300;

    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            WORKERS, WORKERS, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_DEPTH),
            new ThreadPoolExecutor.AbortPolicy());

    /** LRU of settled verdicts, so a repeated notification costs nothing. */
    private final Map<String, Boolean> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    /** Cache keys queued or in flight, so a burst of identical spam costs one call, not ten. */
    private final Set<String> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final CircuitBreaker breaker = new CircuitBreaker();
    private final RateLimiter limiter = new RateLimiter();

    /** Packaged by build.sh via {@code -A assets}. */
    static final String MODEL_ASSET = "local_model.tsv.gz";

    /**
     * The on-device model, loaded once per process and shared by every listener instance and worker
     * thread. Loading reads and parses an 18 KB gzip; doing that per notification would be absurd,
     * and {@link LocalClassifier} is thread-safe once loaded.
     */
    private static volatile LocalClassifier.Model localModel;

    /** Set once a load has failed, so a broken asset cannot turn into I/O per notification. */
    private static volatile boolean localModelFailed;

    /** Why the load failed, for the in-app self-test. Null when it succeeded. */
    static volatile String localModelError;

    // ---------------------------------------------------------------- liveness
    // The UI used to decide whether filtering was working from a stored preference, so it could
    // say "live" while Android had killed or unbound the listener. These statics are the truth
    // for this process: they are only true while the system has actually bound the service, and
    // they die with the process — which is precisely the failure the screen must be able to show.

    private static volatile boolean bound;
    private static volatile long boundAtMs;

    /** True while the system has this listener bound to this process. */
    public static boolean isConnected() { return bound; }

    /** Epoch ms of the last successful bind, or 0. */
    public static long boundAt() { return boundAtMs; }

    /**
     * @return the cached on-device model, or null when the asset could not be read. Null means the
     *     caller must keep the notification — an unavailable classifier is never a dismissal.
     */
    static LocalClassifier.Model localModel(Context context) {
        LocalClassifier.Model loaded = localModel;
        if (loaded != null || localModelFailed) return loaded;
        synchronized (NotifService.class) {
            if (localModel != null || localModelFailed) return localModel;
            InputStream in = null;
            try {
                // A verified download wins; the bundled asset is the floor we can never fall below.
                java.io.File dl = ModelUpdater.active(context);
                if (dl.isFile()) {
                    byte[] blob = ModelStore.read(dl);
                    if (ModelStore.acceptable(blob)) in = new java.io.ByteArrayInputStream(blob);
                }
                boolean downloaded = in != null;
                if (in == null) in = context.getAssets().open(MODEL_ASSET);
                localModel = LocalClassifier.load(in);
                Log.i(TAG, "on-device model loaded: " + localModel.size() + " features"
                        + (downloaded ? " (downloaded v" + new Prefs(context).modelVersion() + ")" : " (built-in)"));
            } catch (Throwable t) {
                localModelFailed = true;
                localModelError = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                Log.w(TAG, "on-device model unavailable: " + t);
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Throwable ignored) { }
                }
            }
            return localModel;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            inspect(sbn);
        } catch (Throwable t) {
            // A listener that throws is a listener the system stops calling. Never let that happen.
            Log.w(TAG, "inspect failed: " + t);
        }
    }

    private void inspect(StatusBarNotification sbn) {
        if (sbn == null) return;
        final String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;

        final Prefs settings = new Prefs(this);
        // Liveness is a timestamp, never content, and never before consent. It is what lets the
        // home screen say "last checked 2 minutes ago" instead of guessing from a preference.
        if (settings.consented()) settings.recordNotification(System.currentTimeMillis());
        if (!settings.enabled()) return;
        // Learned only after consent AND activation. This used to run before both, so every app
        // that posted was written to SharedPreferences while the user had not agreed and while
        // filtering was paused — the opposite of what the first screen promises. The protected-
        // apps screen can still offer real choices once filtering is on; until then, nothing.
        if (!settings.hasSeenApp(pkg)) settings.recordSeenApp(pkg, label(pkg));
        // Offline is always ready. A provider is ready only when configured and agreed to; until
        // then the listener stops here, before any text is read for classification, so nothing is
        // sent and nothing is dismissed.
        if (!settings.ready()) return;

        final boolean offline = !settings.usesProvider();

        final Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        final long postTime = sbn.getPostTime();

        if (sbn.isOngoing() || !sbn.isClearable()) return;
        if ((n.flags & (Notification.FLAG_GROUP_SUMMARY
                | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_NO_CLEAR)) != 0) return;
        if (n.fullScreenIntent != null) return;

        // Functional notifications: media players, calls, alarms, downloads, navigation, and
        // Chat, mail and calendar. The list lives in Categories so it can be tested.
        if (Categories.isFunctional(n.category)) return;
        if (n.extras.containsKey("android.mediaSession")) return;
        String template = n.extras.getString("android.template");
        if (template != null && template.contains("MediaStyle")) return;
        if (n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return;
        if (n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return;

        // Protected apps: never sent anywhere, never dismissed.
        if (Filters.isProtected(settings.allowList(), pkg)) return;

        CharSequence titleSeq = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textSeq = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (isEmpty(textSeq)) textSeq = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        // InboxStyle bodies live in EXTRA_TEXT_LINES and are frequently richer than EXTRA_TEXT:
        // Gmail posts "3 new emails" as the text and the actual subjects as lines. Reading only the
        // text meant the model judged a summary sentence and never saw "Security alert: new sign-in".
        CharSequence lines = joinLines(n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES));
        if (!isEmpty(lines)) textSeq = isEmpty(textSeq) ? lines : textSeq + "\n" + lines;
        if (isEmpty(textSeq)) textSeq = n.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);

        final String title = titleSeq == null ? "" : titleSeq.toString();
        final String text = textSeq == null ? "" : textSeq.toString();
        if (title.trim().isEmpty() && text.trim().isEmpty()) return;

        // The five guard rules run HERE, before the engine split and before the cache. That is
        // the whole point of moving them out of LocalClassifier: the provider path inherited no
        // guards, so a provider answering JUNK for an OTP dismissed it. It also means guarded
        // text (a bank debit, a sign-in alert) is never put in a request at all.
        final String guard = Guards.check(label(pkg), title, text);
        if (guard != null) {
            // A guard hit is an ordinary, successful keep. The rule name stays available to the
            // Settings self-test through LocalClassifier.Result.guard; the service keeps the
            // existing "KEEP" verdict string so the home screen's wording does not change.
            settings.recordSuccess("KEEP", System.currentTimeMillis());
            return;
        }

        final String model = offline ? "" : settings.model();
        final String base = offline ? "" : settings.base();
        final String prompt = offline ? "" : settings.promptText();
        final String providerId = offline ? "" : settings.provider().id;
        // The engine is part of the cache key: switching from on-device to a provider (or back)
        // must not reuse verdicts produced by the other one.
        final String key = Filters.cacheKey(pkg, title, text,
                offline ? "on-device" : model, base, prompt);
        final String sbnKey = sbn.getKey();

        Boolean settled = cache.get(key);
        if (settled != null) {
            if (settled.booleanValue()) dismiss(sbnKey);
            return;
        }
        if (!inFlight.add(key)) return;   // identical notification already being judged

        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    judge(settings, pkg, title, text, offline, model, base, prompt, providerId,
                            key, sbnKey, postTime);
                }
            });
        } catch (RejectedExecutionException e) {
            // Queue full: keep the notification, but make the overload visible in the UI.
            inFlight.remove(key);
            settings.bumpDropped();
        }
    }

    /**
     * Runs one engine and applies its verdict. The decision handling below is shared, so the
     * on-device path and the provider path cannot drift apart on the one rule that matters: only a
     * clear JUNK dismisses. The five guard rules have already run in {@link #inspect}, so neither
     * engine can be the reason a protected notification is dismissed.
     */
    private void judge(Prefs settings, String pkg, String title, String text, boolean offline,
                       String model, String base, String prompt, String providerId,
                       String cacheKey, String sbnKey, long postTime) {
        long now = System.currentTimeMillis();
        try {
            final Decision.Verdict verdict;
            float scoreBuf = -99f;
            final String note;
            final boolean problem;

            if (offline) {
                LocalClassifier.Model onDevice = localModel(this);
                if (onDevice == null) {
                    // The asset is unreadable. Keep the notification and make it visible.
                    settings.recordError("On-device model unavailable", now);
                    return;
                }
                LocalClassifier.Result result = LocalClassifier.classify(onDevice, pkg, label(pkg), title, text);
                scoreBuf = result.score;
                verdict = result.verdict;
                note = null;
                problem = false;
            } else {
                Classifier classifier = new Classifier(
                        settings.apiKey(), model, base, prompt, providerId, breaker, limiter);
                Classifier.Outcome outcome = classifier.classify(pkg, title, text, now);
                verdict = outcome.verdict;
                note = outcome.note;
                problem = outcome.problem;
            }

            if (verdict == Decision.Verdict.JUNK) {
                if (!settings.enabled()) {
                    // The user paused filtering while this verdict was in flight. Leave it alone.
                    return;
                }
                if (!stillCurrent(sbnKey, postTime)) {
                    // The app replaced or removed the notification while the model was thinking, so
                    // this verdict describes text that is no longer on screen. Applying it would
                    // dismiss content the model never saw. Keep it, and do not cache it.
                    settings.recordSuccess("KEPT (changed while checking)", now);
                    return;
                }
                cache.put(cacheKey, Boolean.TRUE);
                dismiss(sbnKey);
                settings.appendLog(LogCodec.encodeEntry(now, label(pkg), title, text, pkg));
                capture(settings, now, pkg, title, text, "JUNK", scoreBuf);
                settings.recordSuccess("JUNK", now);
            } else if (verdict == Decision.Verdict.KEEP) {
                cache.put(cacheKey, Boolean.FALSE);
                settings.recordSuccess("KEEP", now);
                capture(settings, now, pkg, title, text, "KEEP", scoreBuf);
            } else {
                // UNCLEAR is a normal outcome, not a fault: the notification is kept and the
                // home screen must say so, not "needs attention". An on-device UNCLEAR is the
                // uncertainty band or promotional language the model was not sure about.
                //
                // A provider failure also arrives as UNCLEAR (Classifier.Outcome.keep marks
                // problem=true). That one stays an error so a broken key, timeout or rate limit
                // is still visible — fail-open means keep, not hide.
                //
                // UNCLEAR is deliberately not cached: it deserves a fresh look next time.
                if (problem) settings.recordError(note, now);
                else settings.recordSuccess("KEPT (unsure)", now);
            }
        } catch (Throwable t) {
            settings.recordError("Unexpected: " + t, now);
        } finally {
            inFlight.remove(cacheKey);
        }
    }

    /**
     * True when the live notification is still exactly the one that was classified.
     *
     * <p>Classification takes up to ~20 seconds. Apps re-post to the same key with new content in
     * that window — "Order placed" becomes "Delivery failed, reschedule now" — and cancelling by key
     * alone would dismiss the replacement on a verdict about the original.
     */
    private boolean stillCurrent(String sbnKey, long postTime) {
        try {
            StatusBarNotification[] live = getActiveNotifications();
            if (live == null) return false;
            for (StatusBarNotification s : live) {
                if (sbnKey.equals(s.getKey())) return s.getPostTime() == postTime;
            }
        } catch (Throwable t) {
            Log.w(TAG, "active notification lookup failed: " + t);
        }
        return false;   // gone already, or unreadable: nothing safe to cancel
    }

    private void dismiss(String sbnKey) {
        try {
            cancelNotification(sbnKey);
        } catch (Throwable t) {
            Log.w(TAG, "cancel failed: " + t);
        }
    }

    /** Best human-readable name for the posting app; falls back to the package name. */
    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
            // Uninstalled mid-flight, or a package we cannot read. The package name is fine.
        }
        return pkg;
    }

    private static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    private static CharSequence joinLines(CharSequence[] lines) {
        if (lines == null || lines.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (CharSequence line : lines) {
            if (isEmpty(line)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.length() == 0 ? null : sb;
    }

    /**
     * The system rebinds listeners on its own; the hidden {@code requestRebind} API is deliberately
     * not used here because it can throw under hidden-API enforcement and buys nothing.
     */
    @Override
    public void onListenerConnected() {
        bound = true;
        boundAtMs = System.currentTimeMillis();
        Log.i(TAG, "listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        // Do not force a rebind: the system does that. Only stop claiming to be running.
        bound = false;
        Log.i(TAG, "listener disconnected; waiting for the system to rebind");
    }

    @Override
    public void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    /** Local, redacted capture for later retraining. Never transmitted. */
    private void capture(Prefs s, long now, String pkg, String title, String text, String v, float score) {
        if (!s.capturesTraining()) return;
        String e = TrainingLog.encode(now, label(pkg), title, text, pkg, v, score);
        s.setTrainingLog(TrainingLog.append(s.trainingLog(), e));
    }
}
