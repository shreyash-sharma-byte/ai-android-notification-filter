package io.github.shreyashsharmabyte.notifycleaner;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
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
 *   <li><b>The cover.</b> Before consent: what will actually happen, the disclosure, the next three
 *       steps, and two buttons — agree, or not now.</li>
 *   <li><b>The contents.</b> Consented but not ready: only the missing items, numbered, each row
 *       fixing exactly that one thing, with the on/off control always present.</li>
 *   <li><b>The page.</b> Ready or running: one plain status sentence, then the page's instrument
 *       set large enough to be the thing you look at first — a dial whose pointer only turns while
 *       filtering is actually connected, with the hour's figure inside it — then the counters, a
 *       twelve-hour trace, a ranked sender table, and the closing promise.</li>
 * </ol>
 *
 * <p>The status sentence is the one statement this page may not get wrong. It is derived from
 * {@link NotifService#isConnected()} — the system's own bind — and from the last notification the
 * listener actually received, never from the stored enabled flag alone.
 *
 * <p>Every drawn element reads from what the app already stores: the removal log, the lifetime
 * removal counter, the protected list, the seen-apps index, the last verdict, the dropped counter.
 * Nothing is instrumented for the look of it, and nothing is invented when a reading is absent.
 *
 * <p>Content is rebuilt in {@code onResume} because that is the hook that also fires when the user
 * returns from Android's notification-access screen. The entrance animation and the counters are
 * guarded so a rebuild is a refresh, not a new page.
 */
public class HomeActivity extends Screen {

    /** One snapshot per build: the page is a single reading, not five staggered ones. */
    private Telemetry.Snapshot t;
    private long now;

    /**
     * How long a silent phone has to be silent before a missing listener binding is reported as a
     * fault rather than as "waiting". Six hours is long enough that no ordinary quiet period (a
     * night's sleep, a flight) produces the alarm, and short enough that a listener killed by an
     * OEM battery manager is visible the same day.
     */
    private static final long STALL_MS = 6L * 60L * 60L * 1000L;

    /**
     * The page used to update only in {@code onResume}, so a notification removed while the user
     * was watching it left the figure frozen — which reads as "it has stopped working". This polls
     * a cheap signature and rebuilds only when something actually changed, so the numbers are live
     * without re-laying out the page every few seconds.
     */
    private static final long REFRESH_MS = 4000L;
    private final android.os.Handler refreshHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private String shownSignature;
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (prefs != null && !signature().equals(shownSignature)) render();
            refreshHandler.postDelayed(this, REFRESH_MS);
        }
    };

    /** The trace rises once per visit; a resume is a refresh, not a re-entry. */
    private boolean traceGrown;

    /**
     * "Not now" is a decision for this visit only, so it is deliberately not written anywhere.
     * Leaving the activity or the process returns the cover; consent is never implied by reading.
     */
    private boolean readOnly;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        refreshHandler.removeCallbacks(refreshTick);
        refreshHandler.postDelayed(refreshTick, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshTick);
        super.onPause();
    }

    /** Everything the page shows, in one cheap string, so a poll can tell "changed" from "same". */
    private String signature() {
        String log = prefs.log();
        return prefs.removedTotal() + "|" + prefs.lastAt() + "|" + prefs.lastVerdict()
                + "|" + prefs.lastNotificationAt() + "|" + (log == null ? 0 : log.length())
                + "|" + NotifService.isConnected();
    }

    private void render() {
        now = System.currentTimeMillis();
        // Counts come from what the app has actually recorded, not from a package scan: the
        // protected figure is apps the filter has seen and is shielding, and the second figure is
        // apps that have posted at least once. Asking PackageManager for every installed app on
        // every resume was both slow and a different question from the one the label asks.
        java.util.List<String[]> seen = prefs.seenApps();
        t = Telemetry.of(prefs.log(), ProtectedCount.of(this, prefs.allowList(), seen),
                seen.size(), prefs.dropped(),
                prefs.lastVerdict(), prefs.lastAt(), prefs.lastError(), now);

        LinearLayout root = vertical();
        masthead(root, null, "Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(SettingsActivity.class); }
        });
        View footer = null;
        if (prefs.consented()) {
            buildState(root);
        } else if (readOnly) {
            buildReadOnly(root);
        } else {
            footer = buildCover(root);
        }
        setScreenContent(root, footer);
        shownSignature = signature();
    }

    // ------------------------------------------------------------------ the cover

    /**
     * The disclosure, shown before anything is classified, and two buttons: one affirmative, one
     * that leaves consent and storage alone.
     *
     * <p>The headline says what happens rather than what the app is like. "Edited" read like a
     * digest; removal from the shade is the fact a person has to understand before agreeing.
     */
    private View buildCover(LinearLayout root) {
        root.addView(kicker("Before you agree", c.accent), margins(34, 20, 20, 14));

        TextView headline = display("Adverts out.\nMessages kept.", 36, c.ink);
        root.addView(headline, margins(0, 20, 24, 16));

        TextView lede = text("If a notification looks like an ad, a sale or a newsletter, "
                + PRODUCT + " removes it from your notification shade automatically. It cannot "
                + "be brought back. Messages, codes, payments and anything it is unsure about are "
                + "left alone.", 14, c.muted, false);
        lede.setLineSpacing(dp(5), 1f);
        root.addView(lede, margins(0, 20, 24, 0));

        View accent = accentRule(44);
        root.addView(accent, margins(accent, 26, 20, 20, 26));

        root.addView(kicker("Nothing leaves your device", c.ink), margins(0, 20, 20, 12));
        pullQuote(root, DISCLOSURE_TEXT, 13);

        root.addView(kicker("What happens next", c.ink), margins(26, 20, 20, 10));
        TextView steps = text("1. You allow notification access in Android's settings.\n"
                + "2. You turn filtering on when you are ready.\n"
                + "3. Anything removed appears in the Removed list.", 14, c.muted, false);
        steps.setLineSpacing(dp(5), 1f);
        root.addView(steps, margins(0, 20, 24, 0));

        root.addView(meta("\u2014 Before you agree, no notification is classified and nothing "
                + "leaves this phone."), margins(16, 20, 24, 0));

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
        inner.setPaddingRelative(dp(20), dp(12), dp(20), dp(14));

        // The consequence belongs above the button, at reading size, in the colour the rest of the
        // page's body copy uses — not below it as a caption in the faintest ink.
        TextView note = text("Filtering stays off until you turn it on yourself. Agreeing only lets "
                + "the app read notification content for filtering.", 14, c.muted, false);
        note.setLineSpacing(dp(4), 1f);
        inner.addView(note, margins(0, 0, 4, 12));

        inner.addView(primaryButton("I agree", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setConsented(true);
                render();
            }
        }));

        TextView notNow = secondaryButton("Not now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Nothing is written: this is an in-memory decision for this visit.
                readOnly = true;
                render();
            }
        });
        inner.addView(notNow, margins(10, 0, 0, 0));
        footer.addView(inner);
        return footer;
    }

    /** The app with filtering off and no consent. It can be read; it cannot act. */
    private void buildReadOnly(LinearLayout root) {
        root.addView(stateLine(c.muted, "Filtering is off"), margins(26, 20, 20, 0));
        TextView why = text("You have not agreed to let " + PRODUCT + " read notification content, "
                + "so it cannot filter anything and nothing has been switched on.", 14, c.muted,
                false);
        why.setLineSpacing(dp(4), 1f);
        root.addView(why, margins(12, 20, 24, 0));

        root.addView(kicker("What you can read", c.muted), margins(28, 20, 20, 6));
        root.addView(settingsRow("Removed notifications", removedLabel(), true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { start(RemovedActivity.class); }
                }));
        root.addView(settingsRow("Settings", null, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(SettingsActivity.class); }
        }));
        root.addView(settingsRow("What leaves your device", null, true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { showDisclosureDialog(); }
                }));
        root.addView(rule(), margins(18, 20, 20, 0));

        addButton(root, primaryButton("Read the notice again", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                readOnly = false;
                render();
            }
        }));
    }

    // ------------------------------------------------------------------ the page

    private void buildState(LinearLayout root) {
        boolean granted = notificationAccessGranted();
        // Offline is ready with nothing configured; a provider is ready only once it is complete
        // and agreed to. Both are independent of whether Android still has the listener bound.
        boolean ready = prefs.ready();
        boolean on = prefs.enabled();
        boolean connected = NotifService.isConnected();

        if (granted && ready) {
            buildPage(root, on, connected);
        } else {
            buildContents(root, granted, ready, on, connected);
        }
    }

    /** The page. Every state has the same shape; only the status sentence and the button change. */
    private void buildPage(LinearLayout root, boolean on, boolean connected) {
        root.addView(stateLine(statusColour(on, true, connected), statusSentence(on, true, connected)),
                margins(26, 20, 20, 0));

        // One-tap repair, when there is something to repair. The on/off control below it is
        // never conditional: a page that cannot be switched off is the worst possible state.
        // The repair only appears once the silence is long enough to be evidence (see stalled()),
        // so a cold start does not greet the user with an alarm.
        if (stalled(on, true, connected)) {
            addButton(root, secondaryButton("Open notification access", new View.OnClickListener() {
                @Override
                public void onClick(View v) { openNotificationSettings(); }
            }));
        }

        root.addView(gauge(on, connected), margins(16, 20, 20, 0));

        addButton(root, onOffButton(on));

        root.addView(rule(), margins(28, 20, 20, 0));
        root.addView(counters());
        root.addView(meta("\u2014 Removed notifications cannot be brought back. The Removed list "
                + "is the record."), margins(4, 20, 24, 0));
        root.addView(rule());

        String last = lastLine();
        if (last != null) root.addView(meta(last), margins(12, 20, 24, 0));

        root.addView(rule(), margins(28, 20, 20, 0));
        root.addView(kicker("Removed per hour, last 12 hours"), margins(26, 20, 20, 10));
        root.addView(trace());

        root.addView(rule(), margins(30, 20, 20, 0));
        root.addView(kicker("Which apps lost the most"), margins(26, 20, 20, 10));
        senders(root);

        promise(root);
        contents(root);
    }

    /**
     * Not ready, or access revoked. The list contains only what is missing, so finishing an item
     * makes it vanish and leaves the next one.
     */
    private void buildContents(LinearLayout root, boolean granted, boolean ready, boolean on,
                               boolean connected) {
        root.addView(stateLine(statusColour(on, ready, connected),
                statusSentence(on, ready, connected)), margins(26, 20, 20, 0));

        if (!granted) {
            // Android's own dialog is alarming — it lists everything a listener *could* do, not
            // what this app does. Say the difference before sending the user there.
            TextView why = text("Android grants notification access in its own settings screen. Its "
                    + "warning lists everything a notification listener could do; this app only reads "
                    + "titles and text to decide junk, and never replies to messages or changes "
                    + "settings.", 13, c.muted, false);
            why.setLineSpacing(dp(4), 1f);
            root.addView(why, margins(12, 20, 24, 0));
        }

        root.addView(kicker("To finish setup", c.muted), margins(28, 20, 20, 6));

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

        // The control exists in every state, including the one where filtering is on but cannot
        // run: otherwise the screen that says "cannot run" is also the screen that cannot stop it.
        // While a setup action is the page's one filled button, the on/off control is the
        // secondary one — the page still gets exactly one call to action.
        boolean setupAction = !granted || !ready;
        if (on) {
            addButton(root, secondaryButton("Turn off filtering", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setEnabled(false);
                    render();
                }
            }));
        } else if (setupAction) {
            addButton(root, secondaryButton("Turn on filtering", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setEnabled(true);
                    render();
                }
            }));
        } else {
            addButton(root, onOffButton(false));
        }

        root.addView(settingsRow("Removed notifications", removedLabel(), true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { start(RemovedActivity.class); }
                }));
        root.addView(settingsRow("Settings", null, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(SettingsActivity.class); }
        }));
    }

    /** The one on/off control, in every state. */
    private TextView onOffButton(boolean on) {
        if (on) {
            return secondaryButton("Turn off filtering", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setEnabled(false);
                    render();
                }
            });
        }
        return primaryButton("Turn on filtering", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setEnabled(true);
                render();
            }
        });
    }

    // ------------------------------------------------------------------ status

    private String statusSentence(boolean on, boolean ready, boolean connected) {
        if (!on) return "Filtering is off";
        // Access revoked is not "running with a hiccup": the app cannot see a single notification.
        // Saying "on" here put a green dot above a row reading "Notification access — Not allowed".
        if (!notificationAccessGranted()) {
            return "Filtering is paused \u00B7 Android has no notification access";
        }
        if (!ready) return "Filtering is on, but setup is unfinished";
        if (stalled(on, ready, connected)) return "Filtering is on, but Android has stopped it";
        long last = lastHeard();
        if (last <= 0L) return "Filtering is on \u00B7 waiting for the first notification";
        String ago = DateUtils.getRelativeTimeSpanString(last, now,
                DateUtils.MINUTE_IN_MILLIS).toString();
        return "Filtering is on \u00B7 last checked " + ago;
    }

    /**
     * True only when there is real evidence that filtering has stopped: the listener is not bound
     * in this process <em>and</em> nothing has arrived for long enough that the silence is not just
     * a quiet phone.
     *
     * <p>A cold start and the moment after returning from Android's access screen both begin with
     * no binding; the system binds the service when it next needs it. Calling that "Android has
     * stopped it" would be a frightening false alarm on a routine launch, which is the same class
     * of lie as the old "Status · live".
     */
    private boolean stalled(boolean on, boolean ready, boolean connected) {
        if (!on || !ready || connected) return false;
        long last = lastHeard();
        return last > 0L && now - last > STALL_MS;
    }

    /** Last notification received, falling back to the last verdict for upgraded installs. */
    private long lastHeard() {
        long last = prefs.lastNotificationAt();
        return last > 0L ? last : prefs.lastAt();
    }

    private int statusColour(boolean on, boolean ready, boolean connected) {
        if (!on) return c.muted;
        if (!notificationAccessGranted()) return c.bad;
        if (!ready || stalled(on, ready, connected)) return c.bad;
        return c.good;
    }

    /**
     * The one-line status. The dot is decoration; the sentence is the announcement. It is set in
     * sentence case at reading size, not as tracked small caps, because it is a statement and not
     * a label.
     */
    private LinearLayout stateLine(int colour, String sentence) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(colour);
        dot.setBackground(oval);
        decorative(dot);
        line.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        TextView t = text(sentence, 13.5f, colour, true);
        t.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(dp(10));
        line.addView(t, p);
        return line;
    }

    // ------------------------------------------------------------------ the instrument

    /**
     * The gauge, with its reading inside the face: removals in the last hour and whether filtering
     * is running. The frame — not the drawing — is the tap target, so the whole instrument opens the
     * record of what it counted.
     */
    private View gauge(final boolean on, boolean connected) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);

        // A fixed frame has to give way to a large system font scale, or the reading is clipped.
        float fontScale = getResources().getConfiguration().fontScale;
        int base = fontScale > 1.3f ? 190 : 246;
        int size = Math.min(dp(base), getResources().getDisplayMetrics().widthPixels - dp(48));

        FrameLayout frame = new FrameLayout(this);
        DialView dial = new DialView(this);
        dial.palette(c);
        dial.set(on && connected ? DialView.RUNNING : DialView.REST, t.load());
        frame.addView(dial, new FrameLayout.LayoutParams(size, size));

        LinearLayout reading = new LinearLayout(this);
        reading.setOrientation(LinearLayout.VERTICAL);
        reading.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView hour = figure(String.valueOf(thisHour()), 44, c.ink);
        hour.setGravity(Gravity.CENTER);
        hour.setSingleLine(true);
        hour.setMaxLines(1);
        hour.setAutoSizeTextTypeUniformWithConfiguration(20, 44, 2,
                TypedValue.COMPLEX_UNIT_SP);
        reading.addView(hour);

        TextView unit = text("removed in the last hour", 12, c.faint, false);
        unit.setGravity(Gravity.CENTER);
        unit.setMaxLines(2);
        reading.addView(unit, margins(4, 0, 0, 0));

        // "On" is the switch, not the connection: the sentence above the dial explains a missing
        // binding, and a dial that said "Off" while filtering was enabled would contradict it.
        TextView word = kicker(on ? "On" : "Off", on ? c.accent : c.muted);
        word.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        word.setLetterSpacing(0.16f);
        word.setGravity(Gravity.CENTER);
        reading.addView(word, margins(7, 0, 0, 0));

        reading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(reading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        block.addView(frame, new LinearLayout.LayoutParams(size, size));

        String state = !on ? "Filtering is off."
                : stalled(on, true, connected) ? "Filtering is on, but Android has stopped it."
                : "Filtering is on.";
        frame.setContentDescription(state + " " + thisHour()
                + " notifications removed in the last hour.");
        makeButton(frame, "Opens the removed list");
        frame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(RemovedActivity.class); }
        });

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
        if (allTimeRemoved() == 0) return "Nothing removed yet.";
        if (peak == 0) return "Nothing removed in the last 12 hours.";
        if (thisHour == 0) {
            return "None removed in the last hour. Busiest hour in 12: " + peak + ".";
        }
        if (thisHour == 1) {
            return "1 removed in the last hour. Busiest hour in 12: " + peak + ".";
        }
        return thisHour + " removed in the last hour. Busiest hour in the last twelve: " + peak + ".";
    }

    /** The true busiest bucket. Telemetry floors its scale at one; a printed reading must not be. */
    private int peakOfWindow() {
        int peak = 0;
        for (int v : t.buckets) if (v > peak) peak = v;
        return peak;
    }

    /**
     * The counters, in two rows so a large font scale does not crush three columns. The all-time
     * figure is {@link Prefs#removedTotal()}, not the length of the capped log; the Removed row
     * says how much of the record is actually there.
     */
    private View counters() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPaddingRelative(dp(20), dp(14), dp(20), dp(14));

        block.addView(counter(allTimeRemoved(), true, "Removed", "removed", true),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        second.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.setMarginEnd(dp(16));
        second.addView(opensProtectedList(
                counter(t.protectedApps, false, "Apps protected", "protected", false)), left);
        second.addView(opensProtectedList(
                counter(t.watched, false, "Apps seen", "watched", false)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        block.addView(second, margins(14, 0, 0, 0));
        return block;
    }

    /**
     * A summary figure that opens the list behind it. "3 apps protected" with no way to see which
     * three is the kind of dead end a number should never be.
     */
    private View opensProtectedList(View counter) {
        counter.setBackground(ripple());
        counter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(ProtectedAppsActivity.class); }
        });
        makeButton(counter, "Opens the protected apps list");
        return counter;
    }

    private View counter(final int value, boolean lead, String label, String key,
                         boolean opensRemoved) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        final TextView num = figure("0", lead ? 48 : 26, c.ink);
        num.setSingleLine(true);
        num.setMaxLines(1);
        num.setAutoSizeTextTypeUniformWithConfiguration(16, lead ? 48 : 26, 2,
                TypedValue.COMPLEX_UNIT_SP);
        col.addView(num, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView lab = kicker(label);
        lab.setMaxLines(2);
        lab.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(lab, margins(7, 2, 0, 0));
        if (lead) {
            TextView allTime = kicker("all time", c.faint);
            allTime.setMaxLines(1);
            allTime.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(allTime, margins(2, 2, 0, 0));
            allTime.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        String spoken = spokenCounter(value, label);
        col.setContentDescription(spoken);
        num.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        lab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        if (opensRemoved) {
            col.setBackground(ripple());
            col.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { start(RemovedActivity.class); }
            });
            makeButton(col, "Opens the removed list");
        }
        countUp(num, key, value);
        return col;
    }

    private String spokenCounter(int value, String label) {
        if ("Removed".equals(label)) {
            return value + (value == 1 ? " notification removed" : " notifications removed")
                    + ", all time";
        }
        return value + " " + label.toLowerCase(Locale.ROOT);
    }

    /** The drawn twelve-hour trace, with its axis in words. */
    private View trace() {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPaddingRelative(dp(20), 0, dp(20), 0);

        TraceView chart = new TraceView(this);
        chart.palette(c);
        chart.setData(t.buckets, peakOfWindow());
        chart.setContentDescription("Removal chart. " + t.removedInSpan
                + " removed in the last 12 hours.");
        holder.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        if (!traceGrown) {
            traceGrown = true;
            chart.playGrow();
        }

        LinearLayout axis = new LinearLayout(this);
        axis.setOrientation(LinearLayout.HORIZONTAL);
        axis.setPaddingRelative(0, dp(7), 0, 0);
        axis.addView(axisLabel("12 hours ago", Gravity.START),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        axis.addView(axisLabel("6 hours ago", Gravity.CENTER_HORIZONTAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        axis.addView(axisLabel("now", Gravity.END),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        holder.addView(axis);
        return holder;
    }

    private TextView axisLabel(String value, int gravity) {
        TextView t = kicker(value, c.faint);
        t.setLetterSpacing(0.06f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(gravity);
        return t;
    }

    /**
     * The ranking. Rank and tally are the app's only monospaced numerals: they stack in a column
     * and a column that does not line up is not a ranking. Each row opens the same record.
     */
    private void senders(LinearLayout root) {
        List<Telemetry.Sender> ranked = t.top;
        if (ranked == null || ranked.isEmpty()) {
            String line = allTimeRemoved() == 0
                    ? "\u2014 No app has lost a notification yet."
                    : "\u2014 The record has been cleared, so there is nothing to rank.";
            root.addView(meta(line), margins(0, 20, 24, 6));
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
        row.setMinimumHeight(dp(52));
        row.setPaddingRelative(dp(20), dp(8), dp(20), dp(8));

        float fontScale = getResources().getConfiguration().fontScale;
        int figureColumn = dp(32 * Math.max(1f, Math.min(fontScale, 1.8f)));

        TextView index = mono(String.format(Locale.ROOT, "%02d", rank), 12, c.faint, false);
        index.setLetterSpacing(0.14f);
        row.addView(index, new LinearLayout.LayoutParams(figureColumn,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.setMarginEnd(dp(16));
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

        TextView tally = mono(String.valueOf(count), 12, c.ink, true);
        tally.setGravity(Gravity.END);
        row.addView(tally, new LinearLayout.LayoutParams(figureColumn,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setContentDescription("Number " + rank + ", " + app + ", " + count
                + " removed. Opens the removed list.");
        index.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tally.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setBackground(ripple());
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(RemovedActivity.class); }
        });
        makeButton(row, "Opens the removed list");
        return row;
    }

    // ------------------------------------------------------------------ pieces

    /** The destinations, numbered like a contents page but without a heading to decode. */
    private void contents(LinearLayout root) {
        root.addView(rule(), margins(30, 20, 20, 0));
        root.addView(indexedRow("01", "Removed notifications", removedLabel(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { start(RemovedActivity.class); }
                }));
        root.addView(indexedRow("02", "How notifications are judged",
                prefs.usesProvider() ? "Your provider" : "On this phone",
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
        root.addView(kicker("What it never removes", c.muted), margins(20, 20, 20, 4));
        TextView close = italic("\u201CIf the app is unsure, or something goes wrong, it keeps the "
                + "notification.\u201D", 15, c.muted);
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
        r.setContentDescription(label + ", " + value);
        makeButton(r, "Opens " + label);
        return r;
    }

    private TextView meta(String value) {
        TextView t = text(value, 12, c.faint, false);
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    /**
     * The lifetime figure. An install upgraded from a build without the counter has a non-empty
     * log and a zero counter, so the log length is the honest floor rather than "0 removed".
     */
    private int allTimeRemoved() {
        return Math.max(prefs.removedTotal(), t == null ? 0 : t.removed);
    }

    private String removedLabel() {
        int total = allTimeRemoved();
        return total == 0 ? "None" : String.valueOf(total);
    }

    /**
     * "Last checked 4 minutes ago · the app removed an advert." Model verdicts and guard names are
     * internals; the sentence the user gets is the outcome.
     */
    private String lastLine() {
        if (t.lastAt == 0L) return null;
        String ago = DateUtils.getRelativeTimeSpanString(t.lastAt, now,
                DateUtils.MINUTE_IN_MILLIS).toString();
        if (t.failed) {
            String error = prefs.lastError();
            if ("On-device: unsure".equalsIgnoreCase(error)) {
                return "Last check " + ago + " \u00B7 unsure, so it kept the notification.";
            }
            return "Last check " + ago + " \u00B7 the check failed, so it kept the notification.";
        }
        String verdict = t.lastVerdict;
        if (verdict == null || verdict.isEmpty()) return "Last check " + ago + ".";
        return "Last check " + ago + " \u00B7 " + plainOutcome(verdict) + ".";
    }

    private String plainOutcome(String verdict) {
        String v = verdict == null ? "" : verdict.trim();
        if ("JUNK".equalsIgnoreCase(v)) return "the app removed an advert";
        if ("KEEP".equalsIgnoreCase(v)) return "it was kept";
        // The on-device uncertainty band is a success, not a failure, and a different outcome
        // from a notification that changed while it was being checked.
        if ("KEPT (unsure)".equalsIgnoreCase(v)) return "it was kept \u2014 it was unsure";
        if (v.toUpperCase(Locale.ROOT).startsWith("KEPT (CHANGED")) {
            return "it was kept \u2014 it changed while being checked";
        }
        if (v.toUpperCase(Locale.ROOT).startsWith("KEPT")) return "it was kept";
        return "it was kept";
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
