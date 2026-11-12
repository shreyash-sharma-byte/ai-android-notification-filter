package io.github.shreyashsharmabyte.notifycleaner;

/**
 * Token bucket that caps how many API calls the app will ever make. Pure logic.
 *
 * <p>This is the cost ceiling. A chatty app can post dozens of notifications an hour; without a cap
 * the user discovers the bill before they discover the bug. When the bucket is empty the app stops
 * calling out and keeps notifications, which is the safe direction.
 */
public final class RateLimiter {

    /** Sustained call budget per window. */
    public static final int CAPACITY = 40;
    /** Window over which the budget refills. */
    public static final long WINDOW_MS = 60000L;

    private double tokens = CAPACITY;
    private long lastRefillMs;
    private boolean primed;

    /** @return true when a call may proceed; never throws, never blocks */
    public synchronized boolean tryAcquire(long nowMs) {
        if (!primed) {
            primed = true;
            lastRefillMs = nowMs;
        }
        long elapsed = nowMs - lastRefillMs;
        if (elapsed > 0) {
            double refill = (double) elapsed * CAPACITY / (double) WINDOW_MS;
            tokens = Math.min(CAPACITY, tokens + refill);
            lastRefillMs = nowMs;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized int available(long nowMs) {
        if (!primed) return CAPACITY;
        long elapsed = nowMs - lastRefillMs;
        double t = tokens + (elapsed > 0 ? (double) elapsed * CAPACITY / (double) WINDOW_MS : 0);
        return (int) Math.floor(Math.min(CAPACITY, t));
    }

    /** Tests use this to start from a known state. */
    synchronized void resetForTest() {
        tokens = CAPACITY;
        primed = false;
        lastRefillMs = 0;
    }
}
