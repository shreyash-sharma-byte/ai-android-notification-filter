package io.github.shreyashsharmabyte.notifycleaner;

import java.util.regex.Pattern;

/**
 * The five guard rules: notifications that must never be dismissed, by either engine.
 *
 * <p>These used to live inside {@link LocalClassifier}, which meant the cloud-provider path
 * never ran them — a provider that answered JUNK for an OTP dismissed it. They are now a pure,
 * JDK-testable class that {@link NotifService} calls once, before it splits between the
 * on-device model and the provider. No future engine can forget them, and guarded text never
 * leaves the device even when a provider is configured.
 *
 * <p>The rules are deliberately narrow. A bare {@code code} would match "Use code CRAVE50", so
 * promo codes are not caught by the OTP rule; a bare {@code order} would catch "order now", so
 * the order rule requires an order-state verb. A guard hit is not a guess: it is a KEEP.
 *
 * <p>Compiling at class-init time is what once broke the app on device: an
 * {@code ExceptionInInitializerError} here would take out the whole class, and Android's
 * Unicode-aware regex engine can reject patterns a desktop JVM accepts. So: plain
 * {@code CASE_INSENSITIVE} only, and each rule compiled inside its own guard. A rule that will
 * not compile is dropped, costing one safety net while the other four keep working. Nothing
 * here is allowed to throw at class load.
 */
public final class Guards {

    /** The five rule names, in evaluation order. Tests and the Settings self-test use these. */
    public static final String[] NAMES = {
            "otp", "transaction", "order", "security", "deadline",
    };

    private static final String[] PATTERNS = {
            // One-time codes. "verification code" / "login code" are common phrasings; the
            // "do not share" clause catches codes announced without the word OTP.
            "\\botp\\b|one[- ]time password|(verification|login|security) code|"
                    + "do not share.{0,40}\\d{4,6}",

            // Money moving, or a financial state that needs the user's attention.
            "\\bdebited\\b|\\bcredited\\b|\\brefund(ed)?\\b|"
                    + "payment (failed|unsuccessful|declined)|\\bexecuted\\b|square[- ]off|"
                    + "margin shortfall|\\btransferred\\b|\\bemi\\b",

            // Order and delivery state. The first alternative needs one of the state verbs, so
            // "order now" (an advert) does not match.
            "\\b(order|package|shipment|parcel)\\b.{0,40}"
                    + "(confirm|placed|deliver|cancel|shipp|delay|dispatch)|"
                    + "out for delivery|on the way|picking up|\\bdelivered\\b|tracking id|"
                    + "\\bpnr\\b|\\bboarding\\b",

            // Security events.
            "\\bblocked\\b|sign[- ]?in|new login|password.{0,20}chang|security alert|"
                    + "unauthori[sz]ed|suspicious|was this you|re-?verify",

            // Account deadlines and warnings that would cost the user money or access.
            "expires? (today|tomorrow|in \\d)|low balance|re-?kyc|\\bdue (on|in|today)\\b|"
                    + "avoid (interruption|suspension|square)",
    };

    private static final Pattern[] COMPILED = new Pattern[PATTERNS.length];

    static {
        for (int i = 0; i < PATTERNS.length; i++) {
            try {
                COMPILED[i] = Pattern.compile(PATTERNS[i], Pattern.CASE_INSENSITIVE);
            } catch (Throwable t) {
                COMPILED[i] = null;
            }
        }
    }

    private Guards() {}

    /**
     * @param app   posting app label or package, may be null
     * @param title notification title, may be null
     * @param text  notification body, may be null
     * @return the name of the first matching rule, or null when nothing matched
     */
    public static String check(String app, String title, String text) {
        String blob = nz(app) + " " + nz(title) + " " + nz(text);
        for (int i = 0; i < COMPILED.length; i++) {
            if (COMPILED[i] != null && COMPILED[i].matcher(blob).find()) return NAMES[i];
        }
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
