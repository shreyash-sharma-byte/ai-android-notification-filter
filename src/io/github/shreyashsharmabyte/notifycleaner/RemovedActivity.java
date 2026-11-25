package io.github.shreyashsharmabyte.notifycleaner;

import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * What was actually removed, newest first, set as a dated record rather than a list of cards.
 *
 * <p>Entries group under the day they happened, with the time in a narrow margin column and the app
 * name in small caps — the shape of a printed log. Everything a row needs to be judged is on it:
 * when, which app, the title, and the first lines of the text. Tapping a row still offers to protect
 * the app, which is the feedback loop that used to be missing.
 */
public class RemovedActivity extends Screen {

    private LinearLayout list;
    private LinearLayout whyBlock;
    private TextView summaryFigure;
    private TextView summaryLabel;
    private TextView listCaption;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = vertical();
        header(root, "Removed", "Back", "Clear", new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmClear(); }
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        summaryFigure = figure("0", 56, c.ink);
        summaryFigure.setSingleLine(true);
        summaryFigure.setMaxLines(1);
        summaryFigure.setAutoSizeTextTypeUniformWithConfiguration(28, 56, 2,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        head.addView(summaryFigure);
        summaryLabel = kicker("Notifications removed");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(10));
        lp.bottomMargin = dp(12);
        head.addView(summaryLabel, lp);
        root.addView(head, margins(16, 20, 20, 0));

        root.addView(rule(), margins(14, 20, 20, 0));

        listCaption = text("", 12, c.faint, false);
        root.addView(listCaption, margins(10, 20, 24, 0));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        root.addView(whyBlock());

        setScreenContent(root);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    /**
     * The one line the log cannot say for itself: why an entry went, and what tapping it does. It
     * is hidden while the list is empty — a sentence starting "each was removed" under a page that
     * says "nothing removed yet" is exactly the kind of note that made the screen noisy.
     */
    private View whyBlock() {
        whyBlock = new LinearLayout(this);
        whyBlock.setOrientation(LinearLayout.VERTICAL);
        whyBlock.addView(rule(), margins(0, 20, 20, 12));

        TextView why = text("Each was removed because the app judged it an advert. Tap one to "
                + "protect that app.", 12, c.muted, false);
        why.setLineSpacing(dp(4), 1f);
        whyBlock.addView(why, margins(0, 20, 24, 0));
        return whyBlock;
    }

    private void rebuild() {
        if (list == null) return;
        list.removeAllViews();
        String log = prefs.log();
        int shown = 0;
        String currentDay = null;
        int dayCount = 0;

        java.util.List<String[]> entries = new java.util.ArrayList<String[]>();
        for (String line : log == null ? new String[0] : log.split("\n")) {
            String[] fields = LogCodec.decodeEntry(line);
            if (fields != null) entries.add(fields);
        }

        for (int i = 0; i < entries.size(); i++) {
            String[] fields = entries.get(i);
            String day = dayLabel(fields[0]);
            if (currentDay == null || !day.equals(currentDay)) {
                if (currentDay != null) list.addView(rule(), margins(4, 20, 20, 0));
                currentDay = day;
                dayCount = 0;
                for (int j = i; j < entries.size(); j++) {
                    if (dayLabel(entries.get(j)[0]).equals(day)) dayCount++;
                }
                list.addView(dayHeader(day, dayCount), margins(26, 20, 20, 0));
            }
            if (shown > 0) list.addView(rule(), margins(0, 20, 20, 0));
            list.addView(entryRow(fields));
            shown++;
        }

        int total = Math.max(prefs.removedTotal(), shown);
        if (shown == 0) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(new Ornaments.Overture(this, c.line),
                    new LinearLayout.LayoutParams(dp(116), dp(116)));
            TextView t = display(total == 0 ? "Nothing removed\nyet." : "The list is\nempty.",
                    26, c.ink);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(t, margins(22, 0, 0, 0));
            list.addView(empty, margins(24, 40, 40, 24));
        }

        // The big figure is every removal ever; the list is only the last 100. Saying which is
        // which is the difference between an honest record and a counter that stops at 100.
        summaryFigure.setText(String.valueOf(total));
        // With the list cleared the figure is all-time, and a big "7 Notifications removed" over
        // "The list is empty" reads as a contradiction. Name the window the number describes.
        if (shown == 0 && total > 0) {
            summaryLabel.setText("Removed all time");
        } else {
            summaryLabel.setText(total == 1 ? "Notification removed" : "Notifications removed");
        }
        summaryFigure.setContentDescription(total
                + (total == 1 ? " notification removed, all time" : " notifications removed, all time"));
        if (listCaption != null) {
            if (shown == 0) {
                listCaption.setText(total == 0 ? "" : "This record has been cleared.");
                listCaption.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
            } else if (total > shown) {
                listCaption.setText("Showing the last " + shown + " of " + total + ".");
                listCaption.setVisibility(View.VISIBLE);
            } else {
                listCaption.setText("Showing all " + shown + ".");
                listCaption.setVisibility(View.VISIBLE);
            }
        }
        if (whyBlock != null) {
            whyBlock.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private View dayHeader(String day, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(4));
        row.addView(kicker(day, c.ink),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(kicker(String.valueOf(count), c.faint));
        return row;
    }

    private View entryRow(final String[] fields) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(dp(20), dp(14), dp(20), dp(14));

        TextView time = text(hhmm(fields[0]), 12, c.faint, false);
        time.setTypeface(android.graphics.Typeface.create("monospace",
                android.graphics.Typeface.NORMAL));
        time.setSingleLine(true);
        float fontScale = getResources().getConfiguration().fontScale;
        int timeWidth = dp(46 * Math.max(1f, Math.min(fontScale, 1.8f)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(timeWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(2);
        item.addView(time, tp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        body.addView(kicker(fields[1], c.accent));
        if (!fields[2].isEmpty()) {
            TextView title = display(fields[2], 15, c.ink);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(title, margins(7, 0, 0, 0));
        }
        if (!fields[3].isEmpty()) {
            TextView textView = text(fields[3], 12, c.muted, false);
            textView.setLineSpacing(dp(3), 1f);
            textView.setMaxLines(2);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(textView, margins(6, 0, 0, 0));
        }
        item.addView(body, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Rows written before the package column existed have no package, so they cannot offer it.
        if (!fields[4].isEmpty()) {
            item.setBackground(ripple());
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { offerProtection(fields[1], fields[4]); }
            });
            item.setContentDescription(fields[1] + " at " + hhmm(fields[0]) + ". "
                    + (fields[2].isEmpty() ? "" : fields[2] + "."));
            makeButton(item, "Opens protection options for " + fields[1]);
        }
        return item;
    }

    /**
     * The time as this phone shows time. Hard-coding "HH:mm" meant a user on a 12-hour clock saw
     * 00:39 for a notification removed at 12:39 PM — the record looked wrong, which for the only
     * record of an irreversible action is the worst place to look wrong.
     */
    private String hhmm(String stamp) {
        try {
            long t = Long.parseLong(stamp);
            return android.text.format.DateFormat.getTimeFormat(this).format(new Date(t));
        } catch (Exception e) {
            return "";
        }
    }

    private String dayLabel(String stamp) {
        try {
            long t = Long.parseLong(stamp);
            if (DateUtils.isToday(t)) return "Today";
            Calendar then = Calendar.getInstance();
            then.setTimeInMillis(t);
            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            if (then.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR)
                    && then.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)) {
                return "Yesterday";
            }
            return new SimpleDateFormat("d MMMM", Locale.getDefault()).format(new Date(t));
        } catch (Exception e) {
            return "Earlier";
        }
    }

    /**
     * Clearing is a list operation, not an undo: the notifications are already gone. A question is
     * still the standard thing to do, because the list is the only record the user has.
     */
    private void confirmClear() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clear the list?")
                .setMessage("This forgets the record of what was removed. Notifications that were "
                        + "already dismissed stay dismissed, and the all-time count stays.")
                .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        prefs.clearLog();
                        rebuild();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void offerProtection(final String app, final String pkg) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Was this not an advert?")
                .setMessage("\"" + app + "\" was removed because the app judged it an advert.\n\n"
                        + "Adding it to Protected apps means every notification from it is kept, is "
                        + "never read, and never leaves the device.")
                .setPositiveButton("Never filter this app",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                prefs.setAllowList(AllowList.add(prefs.allowList(), pkg));
                                android.widget.Toast.makeText(RemovedActivity.this,
                                        app + " is now protected",
                                        android.widget.Toast.LENGTH_SHORT).show();
                                rebuild();
                            }
                        })
                .setNegativeButton("Keep filtering it", null)
                .show();
    }
}
