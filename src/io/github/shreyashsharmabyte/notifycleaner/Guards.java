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
            "call", "meeting", "travel", "renewal", "health", "exam", "govt", "job",
            "emergency", "device",
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
                    + "avoid (interruption|suspension|square)|premium (is )?due",

            // A call is happening now. "Calling all shoppers" is an advert, so the patterns
            // require the grammatical shape of a real call.
            "missed (voice |video )?call|is calling you|\\bincoming (voice |video )?call\\b|"
                    + "call from \\w",

            // A scheduled meeting is about to start; the useful window is minutes, not days.
            "starts in \\d{1,3} ?min|meeting (is )?(starting|starts)|huddle started|"
                    + "\\b(join|starting) (now|the call)\\b.{0,20}(meeting|call)",

            // Travel disruptions and boarding state, where acting late costs money.
            "(train|flight|bus) .{0,25}(delay|cancel|diverted|reschedul)|platform (changed|no\\.)|"
                    + "gate (change|clos)|check-?in (is )?(open|closing)",

            // Money leaving on a schedule. Unattended, these become overdrafts.
            "\\bmandate\\b|auto[- ]?debit|auto[- ]?renew|will be (charged|debited)|"
                    + "subscription (renews|expires)",

            // Health events where a missed message means a missed appointment or result.
            "appointment (is )?(confirmed|scheduled|booked)|appointment reminder|"
                    + "report is ready|prescription|medicine reminder|lab report",

            // Exams and admissions.
            "admit card|hall ticket|result (declared|is out)|exam (centre|center|date)",

            // Identity and tax paperwork: state verbs only, so "get your PAN card in 5 min"
            // (an advert) does not match.
            "aadhaar.{0,20}(update|verif|link)|pan (card )?(ready|dispatch|update)|"
                    + "passport (ready|dispatch|approved)|visa (approved|granted)|itr (filed|due)",

            // Job progress. "shortlisted" and an invitation are outcomes; "practice interview
            // questions" is an advert and is deliberately not matched.
            "\\bshortlisted\\b|offer letter|interview (is )?(scheduled|confirmed|invitation)|"
                    + "interview call",

            // Public safety alerts. Bare "emergency" is excluded: lenders advertise
            // "emergency loans".
            "public safety|amber alert|evacuat|cyclone (warning|alert)|flood (warning|alert)|"
                    + "earthquake",

            // Device state that needs action now.
            "battery (is )?low|storage (is )?(full|running out)|sim (card )?(blocked|not detected)",
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
