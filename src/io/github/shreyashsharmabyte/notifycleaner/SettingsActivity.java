package io.github.shreyashsharmabyte.notifycleaner;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
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
 * <p>The default install shows one judging row reading "On this phone" and no policy section at
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
    private TextView removedValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = vertical();
        header(root, "Settings", "Back", null, null);

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
        if (answersValue != null) setRowValue(answersValue, "Judged by", answersLabel());
        if (accessValue != null) {
            boolean granted = notificationAccessGranted();
            setRowValue(accessValue, "Notification access", granted ? "Allowed" : "Not allowed");
            accessValue.setTextColor(granted ? c.muted : c.accent);
        }
        if (protectedValue != null) {
            int count = Filters.protectedCount(prefs.allowList());
            setRowValue(protectedValue, "Protected apps", count == 0 ? "None"
                    : count + (count == 1 ? " app" : " apps"));
        }
        if (promptValue != null) {
            setRowValue(promptValue, "Advanced prompt",
                    prefs.strictness() == Strictness.Level.CUSTOM ? "Custom" : "");
        }
        if (removedValue != null) {
            setRowValue(removedValue, "Removed notifications", removedLabel());
        }
    }

    /**
     * Updates a row's value and its accessibility description together: the description was built
     * from the old value when the row was created, and a stale "Not allowed" after access is granted
     * is exactly the kind of lie this screen must not tell.
     */
    private void setRowValue(TextView view, String label, String value) {
        if (view == null) return;
        view.setText(value);
        if (view.getParent() instanceof View) {
            ((View) view.getParent()).setContentDescription(
                    label + (value == null || value.isEmpty() ? "" : ", " + value));
        }
    }

    /** The one place the engine is named in the interface. */
    private String answersLabel() {
        return prefs.usesProvider() ? prefs.provider().name : "On this phone";
    }

    private static String number(int section) {
        return String.format(Locale.ROOT, "%02d", section);
    }

    private String removedLabel() {
        // An upgraded install can have a log but no lifetime counter; the log length is the floor.
        String log = prefs.log();
        int logged = log == null || log.isEmpty() ? 0 : log.split("\n").length;
        int total = Math.max(prefs.removedTotal(), logged);
        return total == 0 ? "None" : total + " all time";
    }

    // ------------------------------------------------------------------ answers

    private void buildAnswers(LinearLayout root, int section) {
        sectionLabel(root, "Judging", number(section));
        answersValue = settingsRowValue(root, "Judged by", answersLabel(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { pickEngine(); }
                });
        help(root, prefs.usesProvider()
                ? "Notification text is sent to this provider. You can switch back to the "
                  + "on-device classifier at any time."
                : "Classification runs on this device. No key, no account, and nothing leaves "
                  + "the phone.");
        if (!prefs.usesProvider()) {
            root.addView(settingsRow("Test the on-device classifier", null, true,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { testOnDevice(); }
                    }));
        }
        root.addView(rule(), margins(10, 20, 20, 0));
    }

    /**
     * A visible self-test. Reports whether the on-device model loaded and what it answers for a
     * sample promotion and a sample OTP, so "it does nothing" is one tap from an answer.
     */
    private void testOnDevice() {
        LocalClassifier.Model m = NotifService.localModel(this);
        String msg;
        if (m == null) {
            msg = "Notification access: "
                    + (notificationAccessGranted() ? "allowed" : "not allowed")
                    + "\nFiltering: " + (prefs.enabled() ? "on" : "off")
                    + "\n\nThe on-device classifier could not be loaded, so every notification "
                    + "is being kept.\n\nReason: "
                    + (NotifService.localModelError == null ? "unknown" : NotifService.localModelError);
        } else {
            LocalClassifier.Result promo = LocalClassifier.classify(m, "Zomato", "50% OFF for you",
                    "Get 50% off up to Rs 120 on your next 3 orders. Use code CRAVE50.");
            LocalClassifier.Result otp = LocalClassifier.classify(m, "ICICI Bank",
                    "OTP for your transaction",
                    "482913 is your OTP for a transaction of Rs 1,299. Do not share this with anyone.");
            msg = "Notification access: "
                    + (notificationAccessGranted() ? "allowed" : "not allowed")
                    + "\nFiltering: " + (prefs.enabled() ? "on" : "off")
                    + "\n\nThe on-device classifier is loaded.\n\n"
                    + "Sample advert \u2192 " + plainOutcome(promo) + "\n"
                    + "Sample verification code \u2192 " + plainOutcome(otp);
        }
        new AlertDialog.Builder(this)
                .setTitle("On-device classifier")
                .setMessage(msg)
                .setPositiveButton("Close", null)
                .show();
    }

    /** Model internals translated into the outcome the user cares about. */
    private String plainOutcome(LocalClassifier.Result result) {
        if (result.verdict == Decision.Verdict.JUNK) return "removed as an advert";
        if (result.verdict == Decision.Verdict.KEEP) {
            return result.guard == null ? "kept" : "kept because it " + guardReason(result.guard);
        }
        return "kept because the classifier was unsure";
    }

    private String guardReason(String guard) {
        if ("otp".equals(guard)) return "looks like a verification code";
        if ("transaction".equals(guard)) return "looks like a payment message";
        if ("order".equals(guard)) return "looks like an order or delivery message";
        if ("security".equals(guard)) return "looks like a security alert";
        if ("deadline".equals(guard)) return "has an account deadline";
        return "matched a safety rule";
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
            rp.setMarginStart(dp(14));
            r.addView(ring, rp);

            r.setBackground(ripple());
            r.setContentDescription(Strictness.label(level) + ", "
                    + (selected ? "selected" : "not selected"));
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setStrictness(level);
                    recreate();
                }
            });
            makeButton(r, "Selects this strictness level");
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
        removedValue = settingsRowValue(root, "Removed notifications", removedLabel(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { start(RemovedActivity.class); }
                });
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
        help(root, PRODUCT + " " + BuildConfig.VERSION_NAME + " \u00B7 MIT. No analytics, no "
                + "account, no server of ours.");
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
