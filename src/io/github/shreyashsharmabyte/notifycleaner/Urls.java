package io.github.shreyashsharmabyte.notifycleaner;

import java.net.URI;
import java.util.Locale;

/**
 * Endpoint validation. Pure logic, plain JDK.
 *
 * <p>HTTPS is required everywhere except on a private network, because a model running on the user's
 * own machine cannot offer a certificate. That exception is deliberately narrow: loopback, RFC 1918
 * ranges, and {@code .local} names only. A public host over plain HTTP is always refused, so a typo
 * or a shoulder-surfed edit cannot silently send notification text across the internet in clear.
 */
public final class Urls {

    private Urls() {}

    /** @return true when this base URL may be used at all */
    public static boolean allowed(String base) {
        if (base == null) return false;
        String b = base.trim();
        if (b.isEmpty()) return false;
        String lower = b.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) return true;
        if (!lower.startsWith("http://")) return false;
        return isPrivateHost(hostOf(b));
    }

    /** True for loopback, RFC 1918 literals, and mDNS names. */
    public static boolean isPrivateHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return false;
        if (h.equals("localhost") || h.equals("::1") || h.equals("[::1]")) return true;
        if (h.endsWith(".local")) return true;
        if (h.equals("127.0.0.1") || h.startsWith("127.")) return true;
        if (h.startsWith("10.")) return true;
        if (h.startsWith("192.168.")) return true;
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length >= 2) {
                int second;
                try {
                    second = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
                return second >= 16 && second <= 31;
            }
        }
        return false;
    }

    /** Host component of a URL, without userinfo, port or path. Empty when unparseable. */
    static String hostOf(String base) {
        try {
            URI uri = new URI(base.trim());
            String host = uri.getHost();
            if (host != null) return host;
        } catch (Exception ignored) {
            // Fall through to the manual parse below.
        }
        String s = base.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    /** True when the endpoint is plaintext, so the UI can warn about it. */
    public static boolean isCleartext(String base) {
        return base != null && base.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }

    /** Strips trailing slashes; never returns null. */
    public static String normalise(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
