package io.github.shreyashsharmabyte.notifycleaner;

/**
 * Circuit breaker over the classifier endpoint. Pure logic with an injected clock, so tests drive
 * time directly instead of sleeping.
 *
 * <p>Without this, a wrong key or an empty balance produces one failed HTTP request per
 * notification, forever. With it, five consecutive failures stop all traffic for a minute, and the
 * UI can say why nothing is being filtered.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Consecutive failures that open the circuit. */
    public static final int THRESHOLD = 5;
    /** How long the circuit stays open before a single probe is allowed. */
    public static final long OPEN_MS = 60000L;

    private State state = State.CLOSED;
    private int failures;
    private long openedAt;
    private boolean probeInFlight;

    /** @return true when a request may be attempted right now */
    public synchronized boolean allowsRequest(long nowMs) {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (nowMs - openedAt >= OPEN_MS) {
                    state = State.HALF_OPEN;
                    probeInFlight = true;
                    return true;
                }
                return false;
            case HALF_OPEN:
            default:
                // Exactly one probe at a time; the rest wait for its verdict.
                if (probeInFlight) return false;
                probeInFlight = true;
                return true;
        }
    }

    public synchronized void recordSuccess() {
        failures = 0;
        probeInFlight = false;
        state = State.CLOSED;
    }

    public synchronized void recordFailure(long nowMs) {
        probeInFlight = false;
        if (state == State.HALF_OPEN) {
            // Probe failed: back to sleep for another full window.
            state = State.OPEN;
            openedAt = nowMs;
            return;
        }
        failures++;
        if (failures >= THRESHOLD) {
            state = State.OPEN;
            openedAt = nowMs;
        }
    }

    public synchronized State state(long nowMs) {
        if (state == State.OPEN && nowMs - openedAt >= OPEN_MS) return State.HALF_OPEN;
        return state;
    }

    public synchronized int failures() { return failures; }

    /** Milliseconds until the next probe, or 0 when requests are allowed. */
    public synchronized long retryAfterMs(long nowMs) {
        if (state != State.OPEN) return 0;
        long left = OPEN_MS - (nowMs - openedAt);
        return left > 0 ? left : 0;
    }
}
