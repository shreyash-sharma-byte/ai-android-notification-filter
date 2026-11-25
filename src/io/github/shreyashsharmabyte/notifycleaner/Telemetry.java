package io.github.shreyashsharmabyte.notifycleaner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the instrument screen knows, derived from what is already stored.
 *
 * <p>Pure logic: no {@code android.*} imports, so {@code ./test.sh} can exercise it on a plain JDK.
 *
 * <p>The point of this class is honesty. The app records exactly one kind of event — a removal, in
 * {@link LogCodec} form — plus a single last-verdict slot and a count of notifications skipped while
 * the queue was full. Nothing else is instrumented, and nothing here invents the rest: the feed is
 * the removal log, the last verdict is the last verdict, and the counters are counts of things that
 * were actually written down. A panel that showed a made-up "kept" counter would be a lie about a
 * privacy tool, which is the one place a lie is least affordable.
 *
 * <p>The twelve hourly buckets are real time buckets, not a decorative waveform: each bucket counts
 * removals whose timestamp falls in that hour, and anything older than the window is excluded from
 * the trace while still counting towards the all-time total. A quiet hour is a gap because it was
 * quiet.
 */
public final class Telemetry {

    /** Hours shown in the activity trace. */
    public static final int BUCKETS = 12;
    public static final long BUCKET_MS = 60L * 60L * 1000L;
    public static final long SPAN_MS = BUCKET_MS * BUCKETS;

    /** Senders listed in the ranking. */
    public static final int TOP_SENDERS = 5;
    /** Removal rows handed to the feed. */
    public static final int FEED = 5;

    private Telemetry() {}

    /** One app that lost notifications, and how many. */
    public static final class Sender {
        public final String app;
        public final String pkg;
        public final int removed;

        Sender(String app, String pkg, int removed) {
            this.app = app;
            this.pkg = pkg;
            this.removed = removed;
        }
    }

    /** One removal, as stored. */
    public static final class Event {
        public final long when;
        public final String app;
        public final String pkg;
        public final String title;
        public final String text;

        Event(long when, String app, String pkg, String title, String text) {
            this.when = when;
            this.app = app;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
        }
    }

    /** An immutable read of the instrument at one instant. */
    public static final class Snapshot {
        /** Every removal in the log, all time. */
        public final int removed;
        /** Removals inside the twelve-hour window. */
        public final int removedInSpan;
        /** Apps the user has shielded; these are never read. */
        public final int protectedApps;
        /** Apps that have posted at least one notification since install. */
        public final int watched;
        /** Notifications kept because the work queue was full. */
        public final int busyKept;
        public final int[] buckets;
        public final int peak;
        public final List<Sender> top;
        public final List<Event> feed;
        public final long newestAt;
        public final String lastVerdict;
        public final long lastAt;
        public final boolean failed;

        Snapshot(int removed, int removedInSpan, int protectedApps, int watched, int busyKept,
                 int[] buckets, int peak, List<Sender> top, List<Event> feed, long newestAt,
                 String lastVerdict, long lastAt, boolean failed) {
            this.removed = removed;
            this.removedInSpan = removedInSpan;
            this.protectedApps = protectedApps;
            this.watched = watched;
            this.busyKept = busyKept;
            this.buckets = buckets;
            this.peak = peak;
            this.top = top;
            this.feed = feed;
            this.newestAt = newestAt;
            this.lastVerdict = lastVerdict;
            this.lastAt = lastAt;
            this.failed = failed;
        }

        /** True when the log has nothing to show, so the trace is a flat line by fact. */
        public boolean idle() { return removed == 0; }

        /** Height of the busiest hour as a fraction of the window, for a load meter. */
        public float load() {
            if (peak <= 0) return 0f;
            return Math.min(1f, buckets[BUCKETS - 1] / (float) peak);
        }
    }

    /**
     * @param log       the stored removal log, may be null or empty
     * @param protectedApps apps on the protected list
     * @param watched   apps that have posted a notification
     * @param busyKept  the dropped counter
     * @param now       milliseconds, injected so a test can pin the window
     */
    public static Snapshot of(String log, int protectedApps, int watched, int busyKept,
                              String lastVerdict, long lastAt, String lastError, long now) {
        List<Event> events = new ArrayList<Event>();
        if (log != null && !log.isEmpty()) {
            for (String line : log.split("\n")) {
                String[] f = LogCodec.decodeEntry(line);
                if (f == null) continue;
                long when;
                try {
                    when = Long.parseLong(f[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                events.add(new Event(when, f[1], f[4], f[2], f[3]));
            }
        }
        // The log is stored newest-first, but a clock change or a line written by an older build can
        // break that. Ordering here means the feed and the ranking never depend on storage order.
        Collections.sort(events, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                if (a.when != b.when) return a.when > b.when ? -1 : 1;
                return 0;
            }
        });

        int[] buckets = new int[BUCKETS];
        long start = now - SPAN_MS;
        int inSpan = 0;
        for (Event e : events) {
            if (e.when < start || e.when > now + BUCKET_MS) continue;
            int index = (int) ((e.when - start) / BUCKET_MS);
            if (index < 0) index = 0;
            if (index >= BUCKETS) index = BUCKETS - 1;
            buckets[index]++;
            inSpan++;
        }
        int peak = 0;
        for (int v : buckets) if (v > peak) peak = v;
        // The scale floor is one, so an empty window still has a denominator. It is a divisor, not
        // a reading: nothing in the UI prints it.
        if (peak < 1) peak = 1;

        Map<String, Sender> byKey = new LinkedHashMap<String, Sender>();
        Map<String, Integer> tallies = new LinkedHashMap<String, Integer>();
        for (Event e : events) {
            // Package is the identity; the label is only what to print. Entries written before the
            // package column existed group by label, which is the best that record allows.
            String key = e.pkg.isEmpty() ? "label:" + e.app : "pkg:" + e.pkg;
            Integer seen = tallies.get(key);
            tallies.put(key, seen == null ? 1 : seen + 1);
            if (!byKey.containsKey(key)) byKey.put(key, new Sender(e.app, e.pkg, 0));
        }
        List<Sender> ranked = new ArrayList<Sender>();
        for (Map.Entry<String, Sender> entry : byKey.entrySet()) {
            Sender s = entry.getValue();
            int n = tallies.get(entry.getKey());
            ranked.add(new Sender(s.app, s.pkg, n));
        }
        Collections.sort(ranked, new Comparator<Sender>() {
            @Override
            public int compare(Sender a, Sender b) {
                if (a.removed != b.removed) return b.removed - a.removed;
                return a.app.compareToIgnoreCase(b.app);
            }
        });
        if (ranked.size() > TOP_SENDERS) ranked = new ArrayList<Sender>(ranked.subList(0, TOP_SENDERS));

        List<Event> feed = new ArrayList<Event>();
        for (int i = 0; i < events.size() && feed.size() < FEED; i++) feed.add(events.get(i));

        String verdict = lastVerdict == null ? "" : lastVerdict;
        boolean failed = lastError != null && !lastError.isEmpty();

        return new Snapshot(events.size(), inSpan, protectedApps, watched, busyKept, buckets, peak,
                ranked, feed, events.isEmpty() ? 0L : events.get(0).when, verdict, lastAt, failed);
    }

    /** Clock label for a stored instant: 09:41. Deliberately not locale-aware, like a machine log. */
    public static String clock(long whenMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(whenMs);
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int m = cal.get(java.util.Calendar.MINUTE);
        return two(h) + ":" + two(m);
    }

    private static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    /**
     * Short relative age, for a panel where "how long ago" matters more than the wall clock.
     * Machine voice: 4m, 2h, 3d.
     */
    public static String age(long whenMs, long nowMs) {
        if (whenMs <= 0) return "--";
        long delta = nowMs - whenMs;
        if (delta < 0) delta = 0;
        long minutes = delta / 60000L;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60L;
        if (hours < 24) return hours + "h";
        return (hours / 24L) + "d";
    }

    /** One-line excerpt for a feed row: collapse newlines, cap the length. */
    public static String excerpt(String title, String text, int max) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) {
            sb.append(title.trim());
        }
        String body = text == null ? "" : text.replace('\n', ' ').trim();
        if (!body.isEmpty()) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(body);
        }
        String s = sb.toString().trim();
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)).trim() + "\u2026";
    }
}
