package io.github.shreyashsharmabyte.notifycleaner;

/**
 * Retry timing policy. Pure logic, plain JDK.
 *
 * <p>The app classifies notifications, which are perishable: a verdict that arrives 30 seconds late
 * is worthless because the notification has already been seen. So the retry budget is deliberately
 * tiny — at most one retry, and only when the wait is short enough to matter.
 */
public final class Backoff {

    /** First retry delay when the server gives no Retry-After. */
    public static final long BASE_MS = 1000L;
    /** Ceiling for any computed backoff. */
    public static final long CAP_MS = 30000L;
    /**
     * A retry longer than this is pointless for a notification: give up and keep it.
     * Keeps worst-case latency bounded so the Removed list stays honest.
     */
    public static final long MAX_RETRY_WAIT_MS = 5000L;
    /** Deterministic jitter ceiling, applied so simultaneous workers drift apart. */
    public static final long JITTER_MS = 250L;

    private Backoff() {}

    /** Exponential delay for {@code attempt} (0-based), capped. */
    public static long delayMs(int attempt) {
        if (attempt < 0) attempt = 0;
        int shift = Math.min(attempt, 5);           // 2^5 * 1000 already exceeds the cap
        long v = BASE_MS << shift;
        return v > CAP_MS ? CAP_MS : v;
    }

    /** Adds stable jitter derived from {@code seed} (never random in tests). */
    public static long withJitter(long baseMs, int seed) {
        long j = Math.abs((long) seed) % (JITTER_MS + 1);
        return baseMs + j;
    }

    /**
     * Decides whether an HTTP status is worth retrying and how long to wait.
     *
     * @return wait in milliseconds, or {@code -1} when the request must not be retried
     */
    public static long retryWaitMs(int status, String retryAfterHeader, int attempt) {
        if (status == 429) {
            long secs = parseRetryAfterSeconds(retryAfterHeader);
            return secs >= 0 ? secs * 1000L : delayMs(attempt);
        }
        if (status >= 500 && status <= 599) return delayMs(attempt);
        return -1;
    }

    /**
     * Parses the seconds form of Retry-After. The HTTP-date form is ignored (returns -1) because
     * a clock skew would produce absurd waits; falling back to exponential backoff is safer.
     *
     * @return seconds, or -1 when absent/unparseable/negative
     */
    public static long parseRetryAfterSeconds(String header) {
        if (header == null) return -1;
        String h = header.trim();
        if (h.isEmpty() || h.length() > 9) return -1;
        long v = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /** Combined policy helper: is this status retryable at all? */
    public static boolean isRetryable(int status) {
        return status == 429 || (status >= 500 && status <= 599);
    }
}
