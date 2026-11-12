package io.github.shreyashsharmabyte.notifycleaner;

import java.util.Locale;

/**
 * Parsing of the model's one-word answer.
 *
 * <p>Pure logic: no {@code android.*} imports, so it runs under a plain JDK in {@code test/LogicTest}.
 * Every ambiguous or unexpected answer resolves to {@link Verdict#UNCLEAR}, and callers must treat
 * UNCLEAR as KEEP. This is the class that stands between a sloppy model answer and a destroyed
 * notification, so it errs toward keeping.
 *
 * <p>Locale is pinned to {@link Locale#ROOT}: under a Turkish default locale, {@code toUpperCase}
 * turns {@code i} into {@code İ} and {@code I} into {@code ı}, which would silently stop verdicts
 * from matching on those devices.
 */
public final class Decision {

    public enum Verdict { JUNK, KEEP, UNCLEAR }

    private Decision() {}

    /**
     * @param raw whatever the model returned, possibly null or full of prose
     * @return JUNK only when the answer contains the JUNK token and nothing contradicting it
     */
    public static Verdict parse(String raw) {
        if (raw == null) return Verdict.UNCLEAR;
        String trimmed = raw.trim();
        // A trailing question mark is the model hedging ("JUNK?"), not a verdict.
        if (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == '?') return Verdict.UNCLEAR;
        String s = stripEdge(raw);
        if (s.isEmpty()) return Verdict.UNCLEAR;

        // Any negation poisons the answer: "not junk", "don't remove" both mean keep.
        if (s.contains("NOT") || s.contains("N'T")) return Verdict.UNCLEAR;

        boolean junk = hasWord(s, "JUNK");
        boolean keep = hasWord(s, "KEEP");
        if (junk && keep) return Verdict.UNCLEAR;   // "JUNK KEEP", "JUNK or KEEP?" — no bet
        if (junk) return Verdict.JUNK;
        if (keep) return Verdict.KEEP;
        return Verdict.UNCLEAR;
    }

    /** True when the verdict permits dismissal. The single place that decision is made. */
    public static boolean isDismissable(Verdict v) { return v == Verdict.JUNK; }

    /** Uppercases and trims every leading/trailing character that is not A-Z. */
    static String stripEdge(String raw) {
        String s = raw.toUpperCase(Locale.ROOT);
        int from = 0, to = s.length();
        while (from < to && !isLetter(s.charAt(from))) from++;
        while (to > from && !isLetter(s.charAt(to - 1))) to--;
        return s.substring(from, to);
    }

    private static boolean isLetter(char c) { return c >= 'A' && c <= 'Z'; }

    /** Whole-word search so "JUNKY" or "KEEPER" never registers as a verdict. */
    static boolean hasWord(String s, String word) {
        int i = s.indexOf(word);
        while (i >= 0) {
            boolean leftOk = i == 0 || !isLetter(s.charAt(i - 1));
            int end = i + word.length();
            boolean rightOk = end == s.length() || !isLetter(s.charAt(end));
            if (leftOk && rightOk) return true;
            i = s.indexOf(word, i + 1);
        }
        return false;
    }
}
