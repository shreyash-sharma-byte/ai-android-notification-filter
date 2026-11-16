package io.github.shreyashsharmabyte.notifycleaner;

import java.util.Locale;

/**
 * Matching and key derivation. Pure logic, plain JDK.
 *
 * <p>Both methods sit on the fail-open boundary, which is why they live here with tests rather than
 * inline in the service: a bug in {@link #isProtected} silently classifies a bank app, and a bug in
 * {@link #cacheKey} silently serves one app's verdict to another.
 */
public final class Filters {

    /**
     * Entries shorter than this are ignored. A one- or two-letter entry would otherwise match
     * almost every package on the device and protect the entire notification stream by accident.
     */
    public static final int MIN_ENTRY_LENGTH = 3;

    /** Cache keys keep only this much of each field, so a 100 KB BIG_TEXT cannot bloat the LRU. */
    static final int MAX_KEY_FIELD = 400;

    private Filters() {}

    /**
     * Substring match of any allow-list entry against the package name, deliberately generous:
     * over-protecting an app is harmless, under-protecting one is not.
     *
     * <p>Entries may be separated by commas, semicolons, spaces or newlines. Accepting all four
     * matters: the protected list is the only thing guaranteeing that OTP and messaging
     * notifications are never classified, and a list typed with semicolons used to collapse into a
     * single unmatchable token — silently protecting nothing.
     *
     * @param allowList the user's list, may be null
     * @param pkg       the posting package, may be null
     */
    public static boolean isProtected(String allowList, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (allowList == null || allowList.isEmpty()) return false;
        String haystack = pkg.toLowerCase(Locale.ROOT);
        for (String raw : entries(allowList)) {
            if (haystack.contains(raw)) return true;
        }
        return false;
    }

    /**
     * How many of the apps that have actually posted a notification are protected. This is what the
     * dashboard means by "apps protected": counting allow-list entries instead over-reports, because
     * one fragment such as "hdfc" covers several packages, and under-reports when a package is
     * covered by a shorter entry and so was never added to the list.
     */
    public static int protectedSeen(String allowList, java.util.List<String[]> seen) {
        if (seen == null || seen.isEmpty()) return protectedCount(allowList);
        int n = 0;
        for (String[] row : seen) {
            if (isProtected(allowList, row[0])) n++;
        }
        return n;
    }

    /** Number of entries that will actually match something. The UI shows this back to the user. */
    public static int protectedCount(String allowList) {
        return allowList == null ? 0 : entries(allowList).length;
    }

    private static String[] entries(String allowList) {
        String[] parts = allowList.split("[,\\s;]+");
        // Deduplicated: "hdfc, hdfc, uber" is two protections, and the UI shows this count back to
        // the user as a number of protected apps. Counting a repeated entry twice would overstate it.
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        for (String part : parts) {
            String entry = part.trim().toLowerCase(Locale.ROOT);
            if (entry.length() < MIN_ENTRY_LENGTH) continue;
            seen.add(entry);
        }
        return seen.toArray(new String[0]);
    }

    /**
     * Cache key for a classification. Includes the config fingerprint so that changing model, base
     * URL or prompt invalidates every cached verdict instead of silently reusing the old policy.
     *
     * <p>U+0001 is the separator. {@link #sanitize} removes any literal occurrence from the fields
     * first, so notification content cannot forge a field boundary, and each field is bounded so a
     * huge notification cannot bloat the cache.
     */
    public static String cacheKey(String pkg, String title, String text,
                                  String model, String base, String prompt) {
        String config = field(model) + "|" + field(base) + "|" + field(prompt);
        // The prefix is bounded for size, so a fingerprint of the *whole* text is included as well:
        // without it, two notifications from one app that share their first 400 characters but differ
        // later (template spam with a code at the end) would share a key, and the second would be
        // dismissed on a cached verdict about text the model never saw.
        String full = text == null ? "" : text.replace("\u0001", " ");
        return field(pkg) + '\u0001' + field(title) + '\u0001' + field(text)
                + '\u0001' + full.length() + ':' + Integer.toHexString(full.hashCode())
                + '\u0001' + Integer.toHexString(config.hashCode());
    }

    private static String field(String s) {
        String t = s == null ? "" : s.replace("\u0001", " ");
        return t.length() > MAX_KEY_FIELD ? t.substring(0, MAX_KEY_FIELD) : t;
    }
}
