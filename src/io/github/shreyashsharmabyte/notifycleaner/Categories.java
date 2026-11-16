package io.github.shreyashsharmabyte.notifycleaner;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Notification categories that are functional, and therefore never classified.
 *
 * <p>This is deliberately a pure class rather than a private field in
 * {@link NotifService}: the list is the cheapest safety net in the app, it has already
 * been wrong once (chat, mail and calendar were missing), and a list that cannot be
 * unit-tested quietly rots. {@code test/LogicTest} pins every entry.
 *
 * <p>The values are the platform's {@code Notification.CATEGORY_*} constants, which are
 * lowercase. Matching is case-insensitive and tolerant of surrounding space so a caller
 * cannot accidentally lose protection by passing a differently-cased string.
 *
 * <p><b>The trade-off is real and accepted.</b> A brand's promotional SMS or RCS message
 * often arrives with {@code CATEGORY_MESSAGE}, and a marketing email with
 * {@code CATEGORY_EMAIL}. Those are kept. That is the correct trade for a tool whose
 * promise is that it never eats a message: over-keeping costs an advert, under-keeping
 * costs a conversation, and only one of those is recoverable.
 */
public final class Categories {

    /**
     * Media, calls, alarms, progress, navigation and status are control surfaces.
     * {@code msg}, {@code email} and {@code event} are added because they are ordinary
     * person-to-person or calendar notifications, never advertising.
     */
    private static final Set<String> FUNCTIONAL = new HashSet<String>(Arrays.asList(
            "call", "alarm", "progress", "transport", "sys", "service", "status", "err",
            "navigation", "reminder",
            // Chat, mail and calendar. These are the categories almost every
            // messaging app sets, and they were the missing half of the original list.
            "msg", "email", "event"));

    private Categories() {}

    /**
     * @param category the raw {@code Notification.category}, possibly null or unknown
     * @return true when the notification is functional and must not be classified
     */
    public static boolean isFunctional(String category) {
        if (category == null) return false;
        String value = category.trim().toLowerCase(Locale.ROOT);
        return !value.isEmpty() && FUNCTIONAL.contains(value);
    }

    /** The number of categories protected here. Used by documentation checks. */
    public static int count() { return FUNCTIONAL.size(); }
}
