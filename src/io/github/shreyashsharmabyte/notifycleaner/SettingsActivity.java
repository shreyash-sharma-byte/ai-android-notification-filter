package io.github.shreyashsharmabyte.notifycleaner;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Everything that is not the home state, grouped by what the user is trying to do and numbered like
 * the sections of a magazine: answers, policy, system, about.
 *
 * <p>The default install shows one answers row reading "On this device" and no policy section at
 * all: strictness and the custom prompt only exist for a provider, and showing controls that do
 * nothing would be worse than hiding them. Provider, key and model are edited on one screen, which
 * is reached only from that row and only after the user asks for a provider.
 *
 * <p>No Save button: every control writes through. Back means done.
 */
public class SettingsActivity extends Screen {

    private TextView answersValue;
    private TextView accessValue;
    private TextView protectedValue;
    private TextView promptValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = vertical();
        header(root, "Settings", "\u2190 Back", null, null);

        int section = 0;
        buildAnswers(root, ++section);
        if (prefs.usesProvider()) buildPolicy(root, ++section);
        buildSystem(root, ++section);
        buildAbout(root, ++section);

        setScreenContent(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** Values can change on other screens, so they are re-read rather than rebuilt into place. */
    private void refresh() {
        if (answersValue != null) answersValue.setText(answersLabel());
        if (accessValue != null) {
            boolean granted = notificationAccessGranted();
            accessValue.setText(granted ? "Allowed" : "Not allowed");
            accessValue.setTextColor(granted ? c.muted : c.accent);
        }
        if (protectedValue != null) {
            int count = Filters.protectedCount(prefs.allowList());
            protectedValue.setText(count == 0 ? "None"
                    : count + (count == 1 ? " app" : " apps"));
        }
        if (promptValue != null) {
            promptValue.setText(prefs.strictness() == Strictness.Level.CUSTOM ? "Custom" : "");
        }
    }

    /** The one place the engine is named in the interface. */
    private String answersLabel() {
        return prefs.usesProvider() ? prefs.provider().name : "On this device";
    }

    private static String number(int section) {
        return String.format(Locale.ROOT, "%02d", section);
    }

    // ------------------------------------------------------------------ answers

    private void buildAnswers(LinearLayout root, int section) {
        sectionLabel(root, "Answers", number(section));
        answersValue = settingsRowValue(root, "Answers from", answersLabel(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { pickEngine(); }
                });
        help(root, prefs.usesProvider()
                ? "Notification text is sent to this provider. You can switch back to the "
                  + "on-device classifier at any time."
                : "Classification runs on this device. No key, no account, and nothing leaves "
                  + "the phone.");
        root.addView(rule(), margins(10, 20, 20, 0));
    }

    /**
     * Advanced door to the provider form. The engine itself is chosen by the shared chooser, so
     * there is exactly one place that decision is made and one place it is configured.
     */
    private void pickEngine() {
        showAnswersChooser(new Runnable() {
            @Override
            public void run() { recreate(); }
        });
    }

    // ------------------------------------------------------------------ policy

    /** Only built when a provider is in use; the on-device classifier has fixed rules. */
    private void buildPolicy(LinearLayout root, int section) {
        sectionLabel(root, "How strict", number(section));

        final Strictness.Level current = prefs.strictness();
        for (final Strictness.Level level : Strictness.selectable()) {
            final boolean selected = level == current;
            LinearLayout r = row();
            r.setMinimumHeight(dp(70));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView label = text(Strictness.label(level), 17, c.ink, false);
            label.setTypeface(selected ? android.graphics.Typeface.create("serif",
                    android.graphics.Typeface.NORMAL) : android.graphics.Typeface.create("sans-serif",
                    android.graphics.Typeface.NORMAL));
            texts.addView(label);
            TextView description = text(Strictness.description(level), 12, c.muted, false);
            description.setLineSpacing(dp(4), 1f);
            texts.addView(description, margins(4, 0, 0, 0));
            r.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Ornaments.Ring ring = new Ornaments.Ring(this, c.accent, c.line, selected);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(22), dp(22));
            rp.leftMargin = dp(14);
            r.addView(ring, rp);

            r.setBackground(ripple());
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setStrictness(level);
                    recreate();
                }
            });
            root.addView(r);
        }

        promptValue = settingsRowValue(root, "Advanced prompt", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(PromptActivity.class); }
        });
        root.addView(rule(), margins(10, 20, 20, 0));
    }

    // ------------------------------------------------------------------ system

    private void buildSystem(LinearLayout root, int section) {
        sectionLabel(root, "System", number(section));
        accessValue = settingsRowValue(root, "Notification access",
                notificationAccessGranted() ? "Allowed" : "Not allowed",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { openNotificationSettings(); }
                });

        protectedValue = settingsRowValue(root, "Protected apps", "",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { start(ProtectedAppsActivity.class); }
                });
        root.addView(rule(), margins(10, 20, 20, 0));
    }

    // ------------------------------------------------------------------ about

    private void buildAbout(LinearLayout root, int section) {
        sectionLabel(root, "About", number(section));
        root.addView(settingsRow("What leaves your device", null, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { showDisclosureDialog(); }
        }));
        help(root, "AI Android Notification Filter " + BuildConfig.VERSION_NAME + " \u00B7 MIT. No analytics, no "
                + "account, no server of ours.");

        LinearLayout colophon = new LinearLayout(this);
        colophon.setOrientation(LinearLayout.HORIZONTAL);
        colophon.setGravity(Gravity.CENTER_VERTICAL);
        colophon.addView(new Ornaments.Fleuron(this, c.faint, dp(11)),
                new LinearLayout.LayoutParams(dp(11), dp(11)));
        TextView mark = kicker("Set in Noto Serif and Roboto", c.faint);
        mark.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = dp(10);
        colophon.addView(mark, mp);
        root.addView(colophon, margins(30, 20, 20, 0));
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
