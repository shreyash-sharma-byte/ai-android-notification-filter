package io.github.shreyashsharmabyte.notifycleaner;

import java.util.ArrayList;
import java.util.List;

/** Local capture of judged notifications for retraining. Redacts before storing. */
public final class TrainingLog {
    public static final int CAP = 600;
    private TrainingLog() {}

    private static final java.util.regex.Pattern CARD =
            java.util.regex.Pattern.compile("\\b\\d{4}[ ]?\\d{4}[ ]?\\d{4}[ ]?\\d{4}\\b");
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final java.util.regex.Pattern PHONE =
            java.util.regex.Pattern.compile("(?:\\+91[\\s-]?)?\\b[6-9]\\d{9}\\b");
    private static final java.util.regex.Pattern AMOUNT =
            java.util.regex.Pattern.compile("(?i)(?:rs\\.?|inr|\\u20b9)\\s?[0-9][0-9,]*(?:\\.[0-9]{1,2})?");
    private static final java.util.regex.Pattern NUM =
            java.util.regex.Pattern.compile("\\b\\d{4,10}\\b");

    public static String redact(String s) {
        if (s == null) return "";
        String out = CARD.matcher(s).replaceAll("[CARD]");
        out = EMAIL.matcher(out).replaceAll("[EMAIL]");
        out = AMOUNT.matcher(out).replaceAll("[AMOUNT]");
        out = PHONE.matcher(out).replaceAll("[PHONE]");
        out = NUM.matcher(out).replaceAll("[NUM]");
        return out;
    }

    private static String clean(String s) {
        String out = redact(s).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        return out.length() > 240 ? out.substring(0, 240) : out;
    }

    public static String encode(long now, String app, String title, String text,
                               String pkg, String verdict, float score) {
        return now + "\t" + clean(app) + "\t" + clean(title) + "\t" + clean(text)
                + "\t" + clean(pkg) + "\t" + verdict + "\t" + Math.round(score * 100.0f);
    }

    public static String append(String log, String entry) {
        List<String> all = new ArrayList<String>();
        if (entry != null && entry.length() > 0) all.add(entry);
        if (log != null && log.length() > 0) {
            for (String l : log.split("\n")) {
                if (l.length() > 0 && all.size() < CAP) all.add(l);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(all.get(i));
        }
        return sb.toString();
    }
}
