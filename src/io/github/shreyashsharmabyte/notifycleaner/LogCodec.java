package io.github.shreyashsharmabyte.notifycleaner;

/**
 * Encoding of the "Removed" list. Pure logic.
 *
 * <p>Two bugs are being prevented here.
 *
 * <p>The original stored four tab-separated fields joined by newlines directly in SharedPreferences.
 * Any tab or newline inside a notification title shifted every following column, so the list
 * rendered garbage.
 *
 * <p>Worse, only tab/CR/LF were neutralised. SharedPreferences is serialised as XML 1.0, where other
 * C0 control characters are illegal: one such character in a logged title could produce a malformed
 * preferences file, which Android then fails to parse and silently replaces with an empty map —
 * losing the API key, model, prompt and protected list with no error shown. So every control
 * character is escaped, not just the three obvious ones.
 */
public final class LogCodec {

    public static final int MAX_ENTRIES = 100;
    /** Field caps. The count bound is worthless without a size bound. */
    public static final int MAX_APP = 80;
    public static final int MAX_TITLE = 120;
    public static final int MAX_TEXT = 300;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LogCodec() {}

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default:
                    // C0 controls, DEL, and the Unicode line/paragraph separators are all unsafe
                    // inside both XML text and the newline-delimited record format.
                    if (ch < 0x20 || ch == 0x7F || ch == '\u2028' || ch == '\u2029') {
                        sb.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            sb.append(HEX[(ch >> shift) & 0xF]);
                        }
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) {
                sb.append(ch);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 't': sb.append('\t'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case '\\': sb.append('\\'); break;
                case 'u': {
                    int value = readHex4(s, i + 1);
                    if (value < 0) {
                        sb.append('u');
                    } else {
                        sb.append((char) value);
                        i += 4;
                    }
                    break;
                }
                default: sb.append(next);
            }
        }
        return sb.toString();
    }

    /** @return the value of four hex digits starting at {@code from}, or -1 */
    private static int readHex4(String s, int from) {
        if (from + 4 > s.length()) return -1;
        int value = 0;
        for (int k = 0; k < 4; k++) {
            int digit = Character.digit(s.charAt(from + k), 16);
            if (digit < 0) return -1;
            value = value * 16 + digit;
        }
        return value;
    }

    /**
     * One stored line: {@code millis \t app \t title \t text \t package}.
     *
     * <p>The package is last, not second, so lines written by earlier versions (four fields, no
     * package) still decode: the position of title and text never moves.
     */
    public static String encodeEntry(long whenMs, String app, String title, String text, String pkg) {
        // Capped here, not at the call site: notification bodies can reach the Binder limit (~1 MB),
        // and 100 uncapped entries would mean a multi-megabyte preferences file that Android parses
        // synchronously on the main thread at launch.
        return whenMs + "\t" + escape(cut(app, MAX_APP)) + "\t" + escape(cut(title, MAX_TITLE))
                + "\t" + escape(cut(text, MAX_TEXT)) + "\t" + escape(cut(pkg, MAX_APP));
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** @return four decoded fields, or null when the line is malformed */
    public static String[] decodeEntry(String line) {
        if (line == null) return null;
        String[] f = line.split("\t", 5);
        if (f.length != 4 && f.length != 5) return null;
        long when;
        try {
            when = Long.parseLong(f[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (when <= 0) return null;
        // A four-field line predates the package column; its package is simply unknown.
        String pkg = f.length == 5 ? unescape(f[4]) : "";
        return new String[] { String.valueOf(when), unescape(f[1]), unescape(f[2]), unescape(f[3]), pkg };
    }

    /** Newest-first insert, capped at {@code max}. */
    public static String prepend(String existing, String entry, int max) {
        StringBuilder sb = new StringBuilder(entry);
        int count = 1;
        if (existing != null && !existing.isEmpty()) {
            for (String line : existing.split("\n", -1)) {
                if (line.isEmpty()) continue;
                if (count >= max) break;
                sb.append('\n').append(line);
                count++;
            }
        }
        return sb.toString();
    }

    /** Number of well-formed entries. */
    public static int count(String log) {
        if (log == null || log.isEmpty()) return 0;
        int n = 0;
        for (String line : log.split("\n")) {
            if (decodeEntry(line) != null) n++;
        }
        return n;
    }
}
