package io.github.shreyashsharmabyte.notifycleaner;

import java.util.Locale;

/**
 * Who is posting, as a signal in its own right.
 *
 * <p>The classifier reads text and nothing else, and that is its blind spot in both directions. A
 * chat message that happens to mention a discount — "I got 50% off on the tickets" — looks exactly
 * like an advert. A campaign that is only a name — "Big Billion Days are live" — contains no
 * promotional wording at all. No amount of text analysis separates those two cases; the sender
 * does, immediately.
 *
 * <p>So the sender is used twice, and only where it is safe:
 *
 * <ul>
 *   <li><b>{@link #PERSONAL}</b> — apps whose notifications exist to carry a person's words or a
 *       person's money: chat, mail, phone, authenticators, calendars, UPI. These form the default
 *       protected list, so their notifications are never classified at all. That is deliberately a
 *       <em>default</em> and not a hidden rule: the Protected apps screen shows these apps, and a
 *       user who removes one has asked for it to be filtered, which is respected.</li>
 *   <li><b>{@link #COMMERCE}</b> — apps whose notifications are, in practice, marketing plus the
 *       transactional messages the guard rules already protect: shops, food delivery, quick
 *       commerce, travel, ticketing. For these, a positive score is enough; the promotional-evidence
 *       gate is not also required, because the sender already supplies the context the words lack.</li>
 * </ul>
 *
 * <p>Everything else is unknown and gets the strict rule — score <em>and</em> promotional language —
 * which is the safe direction. Social and engagement apps (LinkedIn, YouTube, Instagram) are
 * deliberately unknown rather than commerce: they carry direct messages and comments, and relaxing
 * the rule for them would dismiss a person's words.
 *
 * <p>Pure data and pure matching: no {@code android.*}, so the tables and the default list they
 * build are testable on a plain JDK.
 */
public final class Senders {

    /**
     * Package fragments for apps that carry a person's words or money. Matching is a substring
     * test, like the protected list, so {@code com.whatsapp} also covers the business build.
     */
    static final String[] PERSONAL = {
            // Chat and calls
            "com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.android.messaging", "com.android.mms",
            "org.thoughtcrime.securesms",
            "com.slack", "com.discord", "com.facebook.orca", "com.instagram.android",
            "com.viber.voip", "jp.naver.line.android", "com.microsoft.teams", "com.tencent.mm",
            "com.snapchat.android", "com.google.android.dialer", "com.android.dialer",
            // Mail
            "com.google.android.gm", "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail",
            // Codes, calendar, money
            "com.google.android.apps.authenticator2", "com.azure.authenticator",
            "com.google.android.calendar", "com.android.calendar",
            "com.google.android.apps.walletnfcrel", "net.one97.paytm", "com.phonepe.app",
            "com.google.android.apps.nbu.paisa.user",
    };

    /**
     * Package fragments for apps whose notifications are marketing plus guarded transactions.
     * Deliberately excludes social, video and professional networks.
     */
    static final String[] COMMERCE = {
            "com.amazon.mshop", "com.flipkart", "com.myntra", "com.nykaa", "com.ajio",
            "com.meesho", "com.snapdeal", "com.tatacliq", "com.lenskart", "com.croma",
            "com.swiggy", "com.zomato", "com.zepto", "com.blinkit", "com.bigbasket",
            "com.dominos", "com.makemytrip", "com.goibibo", "com.redbus", "com.ixigo",
            "com.oyo", "com.bookmyshow", "in.bookmyshow",
    };

    private Senders() {}

    /** @return true when {@code pkg} belongs to an app that carries a person's words or money */
    public static boolean isPersonal(String pkg) {
        return matches(PERSONAL, pkg);
    }

    /** @return true when {@code pkg} belongs to a commerce app, where a positive score is enough */
    public static boolean isCommerce(String pkg) {
        return matches(COMMERCE, pkg);
    }

    /**
     * The default protected list, built from {@link #PERSONAL}.
     *
     * <p>It is a default, not a rule: it is written into the user's own list on first run and can be
     * edited app by app. Keeping the two in one place means the table above is the only list of
     * "these are personal apps" in the codebase, rather than one table and a hand-typed string that
     * drift apart.
     */
    public static String defaultAllow() {
        StringBuilder sb = new StringBuilder();
        for (String entry : PERSONAL) {
            if (sb.length() > 0) sb.append(',');
            sb.append(entry);
        }
        return sb.toString();
    }

    private static boolean matches(String[] table, String pkg) {
        if (pkg == null) return false;
        String hay = pkg.toLowerCase(Locale.ROOT);
        for (String fragment : table) {
            if (hay.contains(fragment)) return true;
        }
        return false;
    }
}
