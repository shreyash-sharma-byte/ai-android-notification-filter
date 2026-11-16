package io.github.shreyashsharmabyte.notifycleaner;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed access to the one preferences file. Named Prefs, not Settings, so that
 * {@code android.provider.Settings} can be used unqualified in the UI.
 *
 * <p>Writes are granular: several screens edit different fields, and a bulk save from one screen
 * would clobber another's work.
 */
public final class Prefs {

    public static final String FILE = "cfg";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CONSENT = "consent";
    public static final String KEY_ENGINE = "engine";
    public static final String KEY_PROVIDER_CONSENT = "pc";
    public static final String KEY_API = "key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_BASE = "base";
    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_STRICTNESS = "strictness";
    public static final String KEY_ALLOW = "allow";
    public static final String KEY_PROMPT = "prompt";
    public static final String KEY_LOG = "log";
    public static final String KEY_SEEN = "seen";
    public static final String KEY_LAST_AT = "last_t";
    public static final String KEY_LAST_VERDICT = "last_v";
    public static final String KEY_LAST_ERROR = "last_e";
    public static final String KEY_DROPPED = "dropped";

    /** Total removals ever, so the home figure is not capped by the 100-entry log. */
    public static final String KEY_REMOVED_TOTAL = "removed_total";

    /** Epoch ms of the last notification the listener received. Metadata only, never content. */
    public static final String KEY_LAST_NOTIFY = "last_notify";

    public static final int MAX_SEEN = 300;

    /**
     * Messaging, dialer and banking/OTP apps are protected out of the box; the user can edit it.
     *
     * <p>Every entry here is a promise that its notifications are never classified. The list used to
     * cover only WhatsApp, Telegram, Google/Samsung Messages and dialers, which left Signal, Slack,
     * Discord, Messenger and the rest of the chat long tail exposed to a classifier trained on
     * promotions — and it dismissed ordinary conversational messages. Over-protecting an app costs a
     * missed promotion; under-protecting one costs a message. Keep this list generous.
     *
     * <p>The list is built from {@link Senders#PERSONAL} rather than typed here, so the one table of
     * "apps that carry a person's words or money" is the only list of its kind in the codebase.
     */
    public static final String DEFAULT_ALLOW = Senders.defaultAllow();

    private final SharedPreferences p;

    /** Shared by the service and every screen; guards log, seen-apps and counter writes. */
    private static final Object WRITE_LOCK = new Object();

    public Prefs(Context c) {
        this.p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- gating

    /**
     * Filtering never runs before the user has been shown, in the app, what is read and where it
     * goes. Notification content is personal data, so it waits for an affirmative answer rather
     * than a default.
     */
    public boolean consented() { return p.getBoolean(KEY_CONSENT, false); }

    public void setConsented(boolean value) { p.edit().putBoolean(KEY_CONSENT, value).apply(); }

    /**
     * Filtering runs only when the user has both agreed to the disclosure and switched it on.
     *
     * <p>The stored flag defaults to <b>false</b> deliberately. It used to default to true, which
     * meant that accepting the privacy notice — even from the informational "What leaves your
     * device" row, which the user may only be reading — silently switched filtering on, because
     * consent alone satisfied this method. Consent and activation are two separate decisions and
     * activation must always be an explicit act.
     */
    public boolean enabled() { return consented() && p.getBoolean(KEY_ENABLED, false); }

    public void setEnabled(boolean on) { p.edit().putBoolean(KEY_ENABLED, on).apply(); }

    /** True when every field a cloud provider needs is present and the endpoint is acceptable. */
    public boolean configured() {
        Providers.Preset preset = provider();
        if (!preset.local && apiKey().isEmpty()) return false;
        if (model().isEmpty()) return false;
        return Urls.allowed(base());
    }

    // ---------------------------------------------------------------- engine

    /** On-device classification. No key, no provider, no account, no network. */
    public static final String ENGINE_OFFLINE = "offline";

    /** The user's own provider. Only reachable after the provider disclosure is agreed to. */
    public static final String ENGINE_PROVIDER = "provider";

    /**
     * Which engine answers. Defaults to {@link #ENGINE_OFFLINE}: a fresh install must work with no
     * key and no network, so the on-device classifier is the resting state and a provider is an
     * opt-in. Unknown values fall back to offline rather than to a network path.
     */
    public String engine() {
        String value = p.getString(KEY_ENGINE, ENGINE_OFFLINE);
        return value != null && ENGINE_PROVIDER.equalsIgnoreCase(value.trim())
                ? ENGINE_PROVIDER : ENGINE_OFFLINE;
    }

    public void setEngine(String value) {
        boolean provider = value != null && ENGINE_PROVIDER.equalsIgnoreCase(value.trim());
        p.edit().putString(KEY_ENGINE, provider ? ENGINE_PROVIDER : ENGINE_OFFLINE).apply();
    }

    public boolean usesProvider() { return ENGINE_PROVIDER.equals(engine()); }

    /**
     * The provider id the user has been told about and agreed to, or an empty string. Consent is
     * per provider: switching provider voids it and requires the second disclosure again.
     */
    public String providerConsent() { return p.getString(KEY_PROVIDER_CONSENT, ""); }

    public boolean providerAgreed() {
        return usesProvider() && provider().id.equals(providerConsent());
    }

    public void setProviderConsent(String providerId) {
        p.edit().putString(KEY_PROVIDER_CONSENT, providerId == null ? "" : providerId.trim()).apply();
    }

    public void clearProviderConsent() { p.edit().remove(KEY_PROVIDER_CONSENT).apply(); }

    /**
     * True when the selected engine can actually run.
     *
     * <p>Offline is always ready — that is the point of it. A provider is ready only when it is
     * fully configured <em>and</em> the user has agreed to the specific disclosure for that
     * provider. Anything short of that keeps notifications: the listener returns before reading
     * text, so no request is made and nothing is dismissed.
     */
    public boolean ready() {
        if (!usesProvider()) return true;
        return configured() && provider().id.equals(providerConsent());
    }

    // ---------------------------------------------------------------- provider

    public Providers.Preset provider() { return Providers.byId(p.getString(KEY_PROVIDER, "anthropic")); }

    public void setProvider(String id) { p.edit().putString(KEY_PROVIDER, id).apply(); }

    public String apiKey() { return p.getString(KEY_API, "").trim(); }
    public void setApiKey(String value) { p.edit().putString(KEY_API, value == null ? "" : value.trim()).apply(); }

    public String model() { return p.getString(KEY_MODEL, "").trim(); }
    public void setModel(String value) { p.edit().putString(KEY_MODEL, value == null ? "" : value.trim()).apply(); }

    public String base() { return p.getString(KEY_BASE, "").trim(); }
    public void setBase(String value) { p.edit().putString(KEY_BASE, value == null ? "" : value.trim()).apply(); }

    // ---------------------------------------------------------------- policy

    public Strictness.Level strictness() {
        return Strictness.parse(p.getString(KEY_STRICTNESS, "BALANCED"));
    }

    public void setStrictness(Strictness.Level level) {
        p.edit().putString(KEY_STRICTNESS, Strictness.id(level)).apply();
    }

    /** The raw custom prompt, only used when strictness is CUSTOM. */
    public String customPrompt() { return p.getString(KEY_PROMPT, ""); }
    public void setCustomPrompt(String value) {
        p.edit().putString(KEY_PROMPT, value == null ? "" : value.trim()).apply();
    }

    /** The prompt the classifier will actually use, before the fixed safety rules are appended. */
    public String promptText() {
        Strictness.Level level = strictness();
        if (level == Strictness.Level.CUSTOM) {
            String custom = customPrompt();
            return custom.isEmpty() ? Strictness.prompt(Strictness.Level.BALANCED) : custom;
        }
        return Strictness.prompt(level);
    }

    // ---------------------------------------------------------------- protected apps

    /**
     * The protected list. Never configured ({@code null}) means {@link #DEFAULT_ALLOW}; a list the
     * user has actually emptied stays empty, because "I turned everything off" is a decision and a
     * control that snaps back on is worse than one that obeys.
     */
    public String allowList() {
        String a = p.getString(KEY_ALLOW, null);
        if (a == null) return DEFAULT_ALLOW;
        return a.trim().isEmpty() ? "" : a;
    }

    public void setAllowList(String value) {
        p.edit().putString(KEY_ALLOW, value == null ? "" : value.trim()).apply();
    }

    // ---------------------------------------------------------------- removed log

    public String log() { return p.getString(KEY_LOG, ""); }

    public void appendLog(String entry) {
        synchronized (WRITE_LOCK) {
            String merged = LogCodec.prepend(p.getString(KEY_LOG, ""), entry, LogCodec.MAX_ENTRIES);
            // The log is capped at 100, so the all-time figure is counted separately. It is the
            // only number on the home page that keeps growing, and it must not silently stop.
            p.edit()
                    .putString(KEY_LOG, merged)
                    .putInt(KEY_REMOVED_TOTAL, p.getInt(KEY_REMOVED_TOTAL, 0) + 1)
                    .apply();
        }
    }

    /** Every removal ever, including those aged out of the 100-entry log. */
    public int removedTotal() { return p.getInt(KEY_REMOVED_TOTAL, 0); }

    /** Epoch ms of the last notification the listener received, or 0. Metadata only. */
    public long lastNotificationAt() { return p.getLong(KEY_LAST_NOTIFY, 0L); }

    /** Records only that a notification arrived. No content, no sender, no verdict. */
    public void recordNotification(long nowMs) {
        p.edit().putLong(KEY_LAST_NOTIFY, nowMs).apply();
    }

    public void clearLog() {
        synchronized (WRITE_LOCK) {
            p.edit().remove(KEY_LOG).apply();
        }
    }

    // ---------------------------------------------------------------- seen apps

    /**
     * Packages that have posted a notification. Collected before any filtering so the protected-apps
     * screen can offer real choices without the app needing permission to enumerate everything
     * installed.
     */
    public List<String[]> seenApps() {
        List<String[]> out = new ArrayList<String[]>();
        String raw = p.getString(KEY_SEEN, "");
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t", 2);
            if (f.length != 2 || f[0].isEmpty()) continue;
            out.add(new String[] { LogCodec.unescape(f[0]), LogCodec.unescape(f[1]) });
        }
        return out;
    }

    public boolean hasSeenApp(String pkg) {
        if (pkg == null) return false;
        String needle = LogCodec.escape(pkg) + "\t";
        String raw = p.getString(KEY_SEEN, "");
        return raw.startsWith(needle) || raw.contains("\n" + needle);
    }

    public void recordSeenApp(String pkg, String label) {
        if (pkg == null || pkg.isEmpty() || hasSeenApp(pkg)) return;
        synchronized (WRITE_LOCK) {
            String entry = LogCodec.escape(pkg) + "\t" + LogCodec.escape(label == null ? pkg : label);
            String merged = LogCodec.prepend(p.getString(KEY_SEEN, ""), entry, MAX_SEEN);
            p.edit().putString(KEY_SEEN, merged).apply();
        }
    }

    // ---------------------------------------------------------------- status

    public long lastAt() { return p.getLong(KEY_LAST_AT, 0L); }
    public String lastVerdict() { return p.getString(KEY_LAST_VERDICT, ""); }
    public String lastError() { return p.getString(KEY_LAST_ERROR, ""); }

    public void recordSuccess(String verdict, long nowMs) {
        p.edit()
                .putLong(KEY_LAST_AT, nowMs)
                .putString(KEY_LAST_VERDICT, verdict)
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    public void recordError(String message, long nowMs) {
        p.edit()
                .putLong(KEY_LAST_AT, nowMs)
                .putString(KEY_LAST_ERROR, message == null ? "unknown error" : message)
                .apply();
    }

    public int dropped() { return p.getInt(KEY_DROPPED, 0); }

    /** Serialised on the same monitor as the log: this is a read-modify-write too. */
    public void bumpDropped() {
        synchronized (WRITE_LOCK) {
            p.edit().putInt(KEY_DROPPED, dropped() + 1).apply();
        }
    }

    public void clearDropped() { p.edit().remove(KEY_DROPPED).apply(); }
}
