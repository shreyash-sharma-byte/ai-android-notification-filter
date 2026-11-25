package io.github.shreyashsharmabyte.notifycleaner;

import java.util.Locale;

/**
 * How aggressively to remove. Pure logic, plain JDK.
 *
 * <p>This exists because the classification rules used to live in a text box on the settings screen.
 * That is backwards: the prompt is the implementation of a policy, and a user cannot be expected to
 * hand-edit a policy correctly. So the app owns four levels, each with a fixed prompt, and the raw
 * text is only reachable behind an advanced screen. The system's behaviour therefore follows a named
 * choice rather than whatever happens to be in a field.
 */
public final class Strictness {

    public enum Level { CAUTIOUS, BALANCED, AGGRESSIVE, CUSTOM }

    /** The taxonomy shared by the three built-in levels. */
    private static final String TAXONOMY =
            "You classify phone notifications. Reply with exactly one word: JUNK or KEEP.\n";

    private static final String CAUTIOUS =
            TAXONOMY
            + "JUNK only when the notification is unmistakably an advertisement or a sales pitch: "
            + "discounts, cashback, limited-time offers, marketing campaigns.\n"
            + "KEEP for everything else, including anything transactional, personal, security related, "
            + "or anything you are not certain about.\n"
            + "When in doubt, answer KEEP.";

    private static final String BALANCED =
            TAXONOMY
            + "JUNK = promotions, ads, offers, discounts, sales, cashback or reward campaigns, "
            + "marketing, engagement bait, app nudges, newsletters.\n"
            + "KEEP = OTPs and verification or login codes, bank and payment alerts, orders and "
            + "deliveries, security alerts, messages from people, reminders, calendar, anything "
            + "personal or time-sensitive, or if unsure.\n"
            + "Never answer JUNK for anything that might be needed to log in, pay, or reach someone.";

    private static final String AGGRESSIVE =
            TAXONOMY
            + "JUNK = promotions, ads, offers, discounts, sales, cashback or reward campaigns, "
            + "marketing, engagement bait, app nudges, newsletters, product updates, "
            + "re-engagement bait such as \"we miss you\" or \"you appeared in searches\", "
            + "content recommendations, and any notification whose only purpose is to bring you "
            + "back into an app.\n"
            + "KEEP = OTPs and verification or login codes, bank and payment alerts, orders and "
            + "deliveries, security alerts, messages from people, reminders, calendar, anything "
            + "personal or time-sensitive, or if unsure.\n"
            + "Never answer JUNK for anything that might be needed to log in, pay, or reach someone.";

    private Strictness() {}

    /** Stored identifier, tolerant of null and unknown values. */
    public static Level parse(String id) {
        if (id == null) return Level.BALANCED;
        String s = id.trim().toUpperCase(Locale.ROOT);
        if (s.equals("CAUTIOUS") || s.equals("BALANCED") || s.equals("AGGRESSIVE")
                || s.equals("CUSTOM")) {
            return Level.valueOf(s);
        }
        return Level.BALANCED;
    }

    public static String id(Level level) {
        return level == null ? "BALANCED" : level.name();
    }

    public static String label(Level level) {
        switch (level) {
            case CAUTIOUS: return "Cautious";
            case AGGRESSIVE: return "Aggressive";
            case CUSTOM: return "Custom";
            case BALANCED:
            default: return "Balanced";
        }
    }

    public static String description(Level level) {
        switch (level) {
            case CAUTIOUS:
                return "Removes only unmistakable adverts. Everything else is kept.";
            case AGGRESSIVE:
                return "Also removes newsletters, product updates and win-back nudges. Never touches "
                        + "codes, payments or messages.";
            case CUSTOM:
                return "Your own instructions. The safety rules still apply on top.";
            case BALANCED:
            default:
                return "Removes promotions and marketing. Keeps codes, payments, orders and messages.";
        }
    }

    /** The prompt that implements this level. CUSTOM has none of its own. */
    public static String prompt(Level level) {
        switch (level) {
            case CAUTIOUS: return CAUTIOUS;
            case AGGRESSIVE: return AGGRESSIVE;
            case CUSTOM: return "";
            case BALANCED:
            default: return BALANCED;
        }
    }

    /** Levels offered in the UI, in order. CUSTOM is reachable only from the advanced screen. */
    public static Level[] selectable() {
        return new Level[] { Level.CAUTIOUS, Level.BALANCED, Level.AGGRESSIVE };
    }
}
