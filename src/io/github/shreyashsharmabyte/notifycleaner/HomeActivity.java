package io.github.shreyashsharmabyte.notifycleaner;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * The whole app on one page, set three ways.
 *
 * <ol>
 *   <li><b>The cover.</b> Before anything is read: the pledge, the disclosure as a pull-quote, and
 *       one affirmative button. Nothing else is offered, because nothing else may happen yet.</li>
 *   <li><b>The contents.</b> Consented but not ready: only the missing items, numbered, each row
 *       fixing exactly that one thing, with one button for the next one. No dial here — a setup
 *       list is not an instrument, and the dial is never allowed to stand in for progress.</li>
 *   <li><b>The page.</b> Ready or running: the state on one line, then the page's instrument set
 *       large enough to be the thing you look at first — a dial whose pointer only turns while
 *       filtering runs, with the hour's figure and the removal load inside it — then three
 *       counters, a twelve-hour trace, a ranked sender table, and Editorial's own closing
 *       furniture: the rule, and the contents.</li>
 * </ol>
 *
 * <p>Nothing on this page explains what the page already shows. The state line states the state,
 * the dial states the load, the counters are counts of things the app wrote down, and the
 * disclosure — the one paragraph that must be complete — is on the cover where consent is given.
 *
 * <p>Every drawn element on the page reads from what the app already stores: the removal log, the
 * protected list, the seen-apps index, the last verdict, the dropped counter. Nothing is
 * instrumented for the look of it, and nothing is invented when a reading is absent — an empty
 * table says so in a sentence instead of drawing a placeholder.
 *
 * <p>Activation is still the only thing that enables filtering, and it is still a button on this
 * screen that says so. Consent writes consent; reading the disclosure writes nothing; editing a
 * field writes that field. This class only ever calls {@code setConsented} from the cover's button
 * and {@code setEnabled} from the on/off button.
 *
 * <p>Content is built in {@code onResume} because that is the hook that also fires when the user
 * returns from Android's notification-access screen, which is the one place this flow leaves the
 * app and must survive coming back.
 */
public class HomeActivity extends Screen {

    /** One snapshot per build: the page is a single reading, not five staggered ones. */
    private Telemetry.Snapshot t;
    private long now;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        now = System.currentTimeMillis();
        t = Telemetry.of(prefs.log(), Filters.protectedCount(prefs.allowList()),
                prefs.seenApps().size(), prefs.dropped(),
                prefs.lastVerdict(), prefs.lastAt(), prefs.lastError(), now);

        LinearLayout root = vertical();
        masthead(root, "No. " + BuildConfig.VERSION_NAME);
        View footer = null;
        if (prefs.consented()) buildState(root);
        else footer = buildCover(root);
        setScreenContent(root, footer);
    }

    // ------------------------------------------------------------------ the cover

    /**
     * The disclosure, shown before anything can be read, and one affirmative button.
     *
     * <p>The closing line is not decoration: Play requires that consent is informed and specific,
     * and the user deserves to know that agreeing is not the same as switching filtering on.
     */
    private View buildCover(LinearLayout root) {
        root.addView(kicker("Before anything is read", c.accent), margins(34, 20, 20, 14));

        TextView headline = display("Your notifications,\nedited.", 36, c.ink);
        root.addView(headline, margins(0, 20, 24, 16));

        TextView lede = text("AI Android Notification Filter reads each notification and asks one question, on this "
                + "phone: is this junk? Promotions, sales and newsletters go. Messages, codes, "
                + "payments and anything uncertain stay.", 14, c.muted, false);
        lede.setLineSpacing(dp(5), 1f);
        root.addView(lede, margins(0, 20, 24, 0));

        root.addView(accentRule(44), margins(26, 20, 20, 26));

        root.addView(kicker("Nothing leaves your device", c.ink), margins(0, 20, 20, 12));
        pullQuote(root, DISCLOSURE_TEXT, 13);

        root.addView(meta("\u2014 Nothing is read, and nothing leaves this phone, until you agree."),
                margins(16, 20, 24, 0));

        // Sea room at the end of the notice, so its last line is never the thing under the fade.
        root.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        return consentFooter();
    }

    /** The agreement, pinned: read as much of the notice as you like, the decision stays in reach. */
    private View consentFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(c.bg);
        footer.addView(rule());

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(20), dp(12), dp(20), dp(14));
        inner.addView(primaryButton("I agree", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setConsented(true);
                render();
            }
        }));
        TextView note = text("Agreeing lets the app read notifications. Filtering stays off until "
                + "you turn it on yourself.", 11.5f, c.faint, false);
        note.setLineSpacing(dp(3), 1f);
        inner.addView(note, margins(9, 0, 4, 0));
        footer.addView(inner);
        return footer;
    }

    // ------------------------------------------------------------------ the page

    private void buildState(LinearLayout root) {
        boolean granted = notificationAccessGranted();
        // Offline is ready with nothing configured; a provider is ready only once it is complete
        // and agreed to. Everything else about this page is unchanged.
        boolean ready = prefs.ready();
        boolean on = prefs.enabled();

        if (granted && ready) {
            buildPage(root, on);
        } else {
            buildContents(root, granted, ready, on);
        }
    }

    /**
     * The page. Ready and running are the same page in two tenses: identical structure, identical
     * evidence, and the one control relabelled with what pressing it will do.
     *
     * <p>There is no headline. The state line already says whether the app is ready, running or in
     * trouble, and the instrument below it is the page's statement; a sentence repeating either
     * would only be another thing to read before the figure.
     */
    private void buildPage(LinearLayout root, boolean running) {
        boolean failed = t.failed;

        root.addView(stateLine(running ? (failed ? c.bad : c.good) : c.muted,
                running ? (failed ? "Status \u00B7 needs attention" : "Status \u00B7 live")
                        : "Status \u00B7 ready"),
                margins(26, 20, 20, 0));

        root.addView(gauge(running), margins(16, 20, 20, 0));

        // The primary control sits directly under the dial. It is the one thing the user opens this
        // screen to do, so it must not require scrolling past counters, a trace and a sender table.
        if (running) {
            addButton(root, secondaryButton("Pause filtering", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setEnabled(false);
                    render();
                }
            }));
        } else {
            addButton(root, primaryButton("Turn on filtering", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setEnabled(true);
                    render();
                }
            }));
        }

        root.addView(rule(), margins(28, 20, 20, 0));
        root.addView(counters());
        root.addView(rule());

        String last = lastLine();
        if (last != null) root.addView(meta(last), margins(12, 20, 24, 0));

        root.addView(rule(), margins(28, 20, 20, 0));
        root.addView(kicker("Activity \u00B7 last twelve hours"), margins(26, 20, 20, 10));
        root.addView(trace());

        root.addView(rule(), margins(30, 20, 20, 0));
        root.addView(kicker("Removed by sender"), margins(26, 20, 20, 10));
        senders(root);

        promise(root);
        contents(root);
    }

    /**
     * Not ready. The list contains only what is missing, so finishing an item makes it vanish and
     * leaves the next one; there is no counter to fall behind and no order to lose.
     *
     * <p>The rows say what is missing and the button says what to do about it, so there is no
     * headline and no paragraph above them. The one line that survives is the only fact the rows
     * cannot carry: notification access is granted in Android's own settings screen, which is
     * outside this app — knowing that before the screen changes is the difference between a
     * confusing detour and an expected one.
     */
    private void buildContents(LinearLayout root, boolean granted, boolean ready, boolean on) {

        root.addView(stateLine(on ? c.bad : c.muted,
                on ? "Status \u00B7 cannot run" : "Status \u00B7 not set up"),
                margins(26, 20, 20, 0));

        if (!granted) {
            TextView why = text("Android grants notification access in its own settings screen.",
                    12.5f, c.muted, false);
            why.setLineSpacing(dp(4), 1f);
            root.addView(why, margins(12, 20, 24, 0));
        }

        root.addView(kicker("To finish setup"), margins(28, 20, 20, 6));

        int index = 1;
        if (!granted) {
            root.addView(checkRow(index++, "Notification access", "Not allowed",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { openNotificationSettings(); }
                    }));
        }
        // Only a user who has actively switched to a provider sees this row, and only until that
        // provider setup is finished. The default install never has a second requirement.
        if (!ready) {
            root.addView(checkRow(index, "Your provider", "Not finished",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { start(AnswersActivity.class); }
                    }));
        }
        root.addView(rule());

        if (!granted) {
            addButton(root, primaryButton("Open notification access", new View.OnClickListener() {
                @Override
                public void onClick(View v) { openNotificationSettings(); }
            }));
        } else if (!ready) {
            addButton(root, primaryButton("Finish provider setup",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { start(AnswersActivity.class); }
                    }));
        }

        root.addView(settingsRow("Settings", null, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(SettingsActivity.class); }
        }));
    }

    // ------------------------------------------------------------------ the instrument

    /**
     * The gauge, centred and set at roughly two thirds of the page width, with its reading inside
     * the face: this hour's removals, the unit, and the instrument's own word for whether it is
     * running. The load sentence sits beneath it as a caption rather than beside it as a rival, so
     * the dial is the one thing the eye lands on and the words only qualify it.
     *
     * <p>The ring is lit from the real ratio whether or not filtering is running — a paused
     * instrument still shows how much of the hour's peak was reached — but the pointer turns only
     * while filtering does, so a still pointer is a true statement that nothing is being judged.
     */
    private View gauge(boolean running) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);

        int size = Math.min(dp(246),
                getResources().getDisplayMetrics().widthPixels - dp(48));

        FrameLayout frame = new FrameLayout(this);
        DialView dial = new DialView(this);
        dial.palette(c);
        dial.set(running ? DialView.RUNNING : DialView.REST, t.load());
        dial.setContentDescription((running ? "Filtering on, pointer turning. " : "Filtering off, pointer still. ")
                + loadSentence());
        frame.addView(dial, new FrameLayout.LayoutParams(size, size));

        LinearLayout reading = new LinearLayout(this);
        reading.setOrientation(LinearLayout.VERTICAL);
        reading.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView hour = figure(String.valueOf(thisHour()), 44, c.ink);
        hour.setGravity(Gravity.CENTER);
        reading.addView(hour);

        TextView unit = micro("this hour", c.faint);
        unit.setGravity(Gravity.CENTER);
        reading.addView(unit, margins(2, 0, 0, 0));

        TextView word = kicker(running ? "On" : "Off", running ? c.accent : c.faint);
        word.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        word.setLetterSpacing(0.16f);
        word.setGravity(Gravity.CENTER);
        reading.addView(word, margins(7, 0, 0, 0));

        frame.addView(reading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        block.addView(frame, new LinearLayout.LayoutParams(size, size));

        TextView sentence = display(loadSentence(), 14, c.muted);
        sentence.setGravity(Gravity.CENTER);
        sentence.setLineSpacing(dp(3), 1f);
        block.addView(sentence, margins(12, 0, 0, 0));
        return block;
    }

    /** This hour's removals: the number the dial's face carries and its ring is lit from. */
    private int thisHour() {
        return t.buckets[Telemetry.BUCKETS - 1];
    }

    /** The same two numbers the dial's ring is lit from, in words. */
    private String loadSentence() {
        int thisHour = t.buckets[Telemetry.BUCKETS - 1];
        int peak = peakOfWindow();
        if (t.removed == 0) return "Nothing removed yet.";
        if (peak == 0) return "Nothing in the last twelve hours.";
        if (thisHour == 0) return "None in this hour, against a peak of " + peak + ".";
        if (thisHour == 1) return "1 in this hour, against a peak of " + peak + ".";
        return thisHour + " in this hour, against a peak of " + peak + ".";
    }

    /** The true busiest bucket. Telemetry floors its scale at one; a printed reading must not be. */
    private int peakOfWindow() {
        int peak = 0;
        for (int v : t.buckets) if (v > peak) peak = v;
        return peak;
    }

    /**
     * Three counts, one rule. The removed total leads, but at 48sp rather than a display size: the
     * dial is the page's dominant figure now, and two large numbers on one screen would leave the
     * reader choosing between them. The other two counts are qualifiers and are set small beside
     * it. Every one of them is a count of something the app actually wrote down.
     */
    private View counters() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.BOTTOM);
        block.setPadding(dp(20), dp(14), dp(20), dp(14));

        block.addView(counter(t.removed, true, "Notifications", "removed"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
        block.addView(counter(t.protectedApps, false, "Protected", "never read"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        block.addView(counter(t.watched, false, "Watched", "apps seen"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return block;
    }

    private View counter(int value, boolean lead, String label, String sub) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        int size = value >= 10000 ? 26 : (value >= 1000 ? 34 : (lead ? 48 : 26));
        final TextView num = figure("0", size, c.ink);
        col.addView(num);
        col.addView(kicker(label), margins(7, 2, 0, 0));
        col.addView(kicker(sub, c.faint), margins(3, 2, 0, 0));
        countUp(num, value);
        return col;
    }

    /** The drawn twelve-hour trace, with its axis in small caps. */
    private View trace() {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(20), 0, dp(20), 0);

        TraceView chart = new TraceView(this);
        chart.palette(c);
        chart.setData(t.buckets, peakOfWindow());
        holder.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        chart.playGrow();

        LinearLayout axis = new LinearLayout(this);
        axis.setOrientation(LinearLayout.HORIZONTAL);
        axis.setPadding(0, dp(7), 0, 0);
        axis.addView(kicker("12h", c.faint),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mid = kicker("6h", c.faint);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        axis.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView end = kicker("Now", c.faint);
        end.setGravity(Gravity.END);
        axis.addView(end, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        holder.addView(axis);
        return holder;
    }

    /**
     * The ranking. Rank and tally are the app's only monospaced numerals: they stack in a column
     * and a column that does not line up is not a ranking. The bar is proportional to the leader,
     * so the table reads as a comparison and not as five separate numbers.
     */
    private void senders(LinearLayout root) {
        List<Telemetry.Sender> ranked = t.top;
        if (ranked == null || ranked.isEmpty()) {
            root.addView(meta("\u2014 No sender has lost a notification yet."),
                    margins(0, 20, 24, 6));
            return;
        }
        int leader = ranked.get(0).removed;
        for (int i = 0; i < ranked.size(); i++) {
            Telemetry.Sender s = ranked.get(i);
            float share = leader <= 0 ? 0f : s.removed / (float) leader;
            root.addView(senderRow(i + 1, s.app, s.removed, share));
        }
    }

    private View senderRow(int rank, String app, int count, float share) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView index = mono(String.format(Locale.ROOT, "%02d", rank), 10, c.faint, false);
        index.setLetterSpacing(0.14f);
        row.addView(index, new LinearLayout.LayoutParams(dp(30),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.rightMargin = dp(16);
        row.addView(middle, mp);

        TextView name = text(app, 13.5f, c.ink, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        middle.addView(name);

        MeterView meter = new MeterView(this);
        meter.palette(c);
        meter.setFraction(share);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
        mlp.topMargin = dp(6);
        middle.addView(meter, mlp);

        TextView tally = mono(String.valueOf(count), 11.5f, c.ink, true);
        tally.setGravity(Gravity.END);
        row.addView(tally, new LinearLayout.LayoutParams(dp(30),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ------------------------------------------------------------------ pieces

    /** A state dot and a line of small caps: the only status colour on the page. */
    private LinearLayout stateLine(int colour, String label) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(colour);
        dot.setBackground(oval);
        line.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));

        TextView t = kicker(label, colour == c.muted ? c.muted : colour);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(10);
        line.addView(t, p);
        return line;
    }

    /** The three destinations, numbered like a contents page. */
    private void contents(LinearLayout root) {
        int removed = t.removed;
        root.addView(rule(), margins(30, 20, 20, 0));
        root.addView(kicker("Contents"), margins(20, 20, 20, 2));
        root.addView(indexedRow("01", "Removed notifications",
                removed == 0 ? "None" : String.valueOf(removed), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { start(RemovedActivity.class); }
                }));
        root.addView(indexedRow("02", "Where answers come from",
                prefs.usesProvider() ? "Your provider" : "On this device",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showAnswersChooser(new Runnable() {
                            @Override
                            public void run() { render(); }
                        });
                    }
                }));
        root.addView(indexedRow("03", "Settings", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(SettingsActivity.class); }
        }));
        root.addView(indexedRow("04", "What leaves your device", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) { showDisclosureDialog(); }
        }));
    }

    /** The promise, set as the page's closing line. It is the product in one sentence. */
    private void promise(LinearLayout root) {
        int dropped = prefs.dropped();
        if (dropped > 0) {
            root.addView(meta("\u2014 " + dropped
                    + (dropped == 1 ? " notification was" : " notifications were")
                    + " skipped while busy, and kept."), margins(18, 20, 24, 0));
        }
        root.addView(rule(), margins(14, 20, 20, 0));
        root.addView(kicker("The rule", c.faint), margins(20, 20, 20, 4));
        TextView close = italic("\u201CWhen the classifier is unsure, fails, or does not recognise "
                + "a notification, it is kept.\u201D", 15, c.muted);
        close.setLineSpacing(dp(5), 1f);
        root.addView(close, margins(12, 20, 28, 0));
    }

    /** A numbered checklist item: figure, label, state, arrow. */
    private LinearLayout checkRow(int index, String label, String value,
                                  View.OnClickListener listener) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(66));
        TextView n = figure(String.format(Locale.ROOT, "%02d", index), 21, c.accent);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(38),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        r.addView(n, np);
        r.addView(text(label, 15, c.ink, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = kicker(value, c.accent);
        v.setLetterSpacing(0.12f);
        r.addView(v);
        r.addView(chevron());
        r.setBackground(ripple());
        r.setOnClickListener(listener);
        return r;
    }

    /** Metadata in the small caps that carry tracking, for lines that sit inside a figure block. */
    private TextView micro(String value, int colour) {
        TextView t = kicker(value, colour);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9.5f);
        t.setLetterSpacing(0.14f);
        return t;
    }

    private TextView meta(String value) {
        TextView t = text(value, 12, c.faint, false);
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    /**
     * "Last check 4 minutes ago · junk." The verdict is the app's own record, lower-cased.
     *
     * <p>Null when nothing has been checked: a line saying so beside a visibly flat trace, three
     * zero counters and an idle dial is the definition of a note that explains the page back to
     * itself.
     */
    private String lastLine() {
        if (t.lastAt == 0L) return null;
        String ago = DateUtils.getRelativeTimeSpanString(t.lastAt, now,
                DateUtils.MINUTE_IN_MILLIS).toString();
        if (t.failed) {
            String error = prefs.lastError();
            if (error != null && error.length() > 90) error = error.substring(0, 89) + "\u2026";
            return "Last check " + ago + " \u00B7 failed: " + error;
        }
        String verdict = t.lastVerdict;
        if (verdict == null || verdict.isEmpty()) return "Last check " + ago + ".";
        return "Last check " + ago + " \u00B7 " + verdict.toLowerCase(Locale.ROOT) + ".";
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings, Notifications, Device and app notifications",
                    Toast.LENGTH_LONG).show();
        }
    }
}
