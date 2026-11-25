package io.github.shreyashsharmabyte.notifycleaner;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How many apps on this device the protected list actually shields.
 *
 * <p>Three different numbers were available and two of them lie. Counting allow-list <em>entries</em>
 * says 28 on a phone with six of those apps installed. Counting entries that have <em>posted a
 * notification</em> says "None" on a fresh install and then jumps to the real figure the moment
 * something arrives, which reads as a bug. The number a person wants is this one: installed apps
 * that the list matches.
 *
 * <p>It is a scan, so it runs once per process and is cached against the list it was computed for;
 * the protected screen changes the list, which changes the key and forces a recount. A <em>per
 * resume</em> scan was what made the home screen stutter, so this must stay cached.
 */
final class ProtectedCount {

    /** Never scanned yet. */
    private static final int UNKNOWN = -1;

    private static volatile int cached = UNKNOWN;
    private static volatile String cachedFor;

    private ProtectedCount() {}

    /**
     * @param allowList the user's protected list
     * @param seen      apps that have posted a notification, so an app with no launcher entry counts
     * @return installed apps the list protects; falls back to the entry count if the scan fails
     */
    static int of(Context context, String allowList, List<String[]> seen) {
        String key = allowList == null ? "" : allowList;
        int value = cached;
        if (value != UNKNOWN && key.equals(cachedFor)) return value;

        int counted = scan(context, key, seen);
        cachedFor = key;
        cached = counted;
        return counted;
    }

    private static int scan(Context context, String allowList, List<String[]> seen) {
        try {
            Set<String> installed = new HashSet<String>();
            PackageManager pm = context.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN, null);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
                if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
                installed.add(info.activityInfo.packageName);
            }
            if (seen != null) {
                for (String[] row : seen) {
                    if (row != null && row.length > 0 && row[0] != null) installed.add(row[0]);
                }
            }
            if (installed.isEmpty()) return Filters.protectedCount(allowList);
            int n = 0;
            for (String pkg : installed) {
                if (Filters.isProtected(allowList, pkg)) n++;
            }
            return n;
        } catch (Throwable t) {
            // A package manager that will not answer is not a reason to claim nothing is protected.
            return Filters.protectedCount(allowList);
        }
    }
}
