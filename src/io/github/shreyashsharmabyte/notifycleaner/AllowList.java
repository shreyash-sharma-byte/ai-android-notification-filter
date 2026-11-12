package io.github.shreyashsharmabyte.notifycleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Add, remove and test entries in the protected-apps list. Pure logic, plain JDK.
 *
 * <p>The list used to be a free-text field, which meant protecting an app required knowing its
 * package name and typing it correctly. The protected-apps screen now toggles real apps, so the list
 * needs safe mutation: adding must not duplicate, and removing must clear any entry that matches the
 * package — including a partial entry the user typed earlier, or the toggle would snap back on.
 */
public final class AllowList {

    private AllowList() {}

    /** Adds {@code pkg} unless an entry already matches it. */
    public static String add(String list, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return list == null ? "" : list;
        String target = pkg.trim();
        if (Filters.isProtected(list, target)) return list == null ? "" : list;
        String base = list == null ? "" : list.trim();
        return base.isEmpty() ? target : base + "," + target;
    }

    /** Removes every entry that matches {@code pkg}, so a partial entry cannot keep it protected. */
    public static String remove(String list, String pkg) {
        if (list == null || list.isEmpty() || pkg == null) return list == null ? "" : list;
        String target = pkg.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) return list;
        StringBuilder sb = new StringBuilder();
        for (String part : list.split("[,\\s;]+")) {
            String entry = part.trim();
            if (entry.isEmpty()) continue;
            String lower = entry.toLowerCase(Locale.ROOT);
            boolean matches = lower.length() >= Filters.MIN_ENTRY_LENGTH
                    && (target.contains(lower) || lower.contains(target));
            if (matches) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(entry);
        }
        return sb.toString();
    }

    public static boolean contains(String list, String pkg) {
        return Filters.isProtected(list, pkg);
    }

    public static String toggle(String list, String pkg, boolean protect) {
        return protect ? add(list, pkg) : remove(list, pkg);
    }

    /** Entries that no longer match anything the caller passed in; used to show leftovers. */
    public static List<String> entries(String list) {
        List<String> out = new ArrayList<String>();
        if (list == null) return out;
        for (String part : list.split("[,\\s;]+")) {
            String entry = part.trim();
            if (entry.length() >= Filters.MIN_ENTRY_LENGTH) out.add(entry);
        }
        return out;
    }
}
