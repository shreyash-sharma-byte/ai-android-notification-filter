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
    private TextView trainingValue;
    private TextView trainingExportValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = vertical();
        header(root, "Settings", "Back", null, null);

        int section = 0;
        buildAnswers(root, ++section);
        if (prefs.usesProvider()) buildPolicy(root, ++section);
        buildSystem(root, ++section);
        buildTraining(root, ++section);
        buildClassifier(root, ++section);
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
            int count = ProtectedCount.of(this, prefs.allowList(), prefs.seenApps());
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
        if (trainingValue != null) {
            setRowValue(trainingValue, "Save samples on this device",
                    prefs.capturesTraining() ? "On" : "Off");
        }
        if (trainingExportValue != null) {
            int rows = sampleCount();
            setRowValue(trainingExportValue, "Export saved samples",
                    rows == 0 ? "None" : rows + (rows == 1 ? " sample" : " samples"));
        }
    }

    /** Number of captured sample rows, without splitting the whole log twice. */
    private int sampleCount() {
        String log = prefs.trainingLog();
        return log == null || log.isEmpty() ? 0 : log.split("\n").length;
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

    // ------------------------------------------------------------------ samples

    /**
     * The opt-in capture used to improve the classifier. It is off unless switched on, it says
     * exactly what is kept, and the samples leave the phone only when the owner exports them.
     */
    private void buildClassifier(LinearLayout root, int section) {
        sectionLabel(root, "Classifier", number(section));
        long v = prefs.modelVersion();
        settingsRowValue(root, "Model", v > 0 ? "v" + v + " (downloaded)" : "Built-in", null);
        settingsRowValue(root, "Check for update", "Now", new View.OnClickListener() {
            @Override
            public void onClick(View view) { checkClassifier(); }
        });
        settingsRowValue(root, "Automatic updates", prefs.autoUpdate() ? "Weekly" : "Off",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        prefs.setAutoUpdate(!prefs.autoUpdate());
                        refresh();
                    }
                });
        if (v > 0) {
            settingsRowValue(root, "Revert to built-in", null, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    prefs.setModelMeta("", 0);
                    ModelUpdater.revert(SettingsActivity.this, prefs);
                    refresh();
                }
            });
        }
        help(root, "The classifier ships inside the app, so filtering works offline from the first "
                + "launch. When a newer one is published the app fetches it, checks its checksum and "
                + "runs three test notifications before using it; if any check fails it keeps the "
                + "version it already has. The check is one request for a small public file and "
                + "carries no account, device or usage information.");
    }

    private void checkClassifier() {
        final android.widget.Toast probe = android.widget.Toast.makeText(this, "Checking\u2026",
                android.widget.Toast.LENGTH_SHORT);
        probe.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = ModelUpdater.check(getApplicationContext(), prefs);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(SettingsActivity.this, result,
                                android.widget.Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void buildTraining(LinearLayout root, int section) {
        sectionLabel(root, "Improving the filter", number(section));

        trainingValue = settingsRowValue(root, "Save samples on this device", "",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setCapturesTraining(!prefs.capturesTraining());
                        refresh();
                    }
                });
        help(root, "When on, the app saves the app name, title and text of the notifications it "
                + "judges, with its verdict. Card numbers, phone numbers, emails and amounts are "
                + "replaced before saving. The samples stay on this phone; nothing is uploaded, "
                + "and nothing is sent anywhere until you export them yourself.");

        trainingExportValue = settingsRowValue(root, "Export saved samples", "",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { exportTraining(); }
                });
        settingsRowValue(root, "Clear saved samples", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { confirmClearTraining(); }
                });
        root.addView(rule(), margins(10, 20, 20, 0));
    }

    private void confirmClearTraining() {
        if (sampleCount() == 0) {
            Toast.makeText(this, "Nothing saved yet", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear saved samples?")
                .setMessage("This deletes every sample stored on this phone. It does not change "
                        + "any setting.")
                .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.clearTrainingLog();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    /**
     * Writes the samples out and, where the platform allows it, offers a share sheet.
     *
     * <p>Android 10 and later can write into Downloads through MediaStore with no permission and
     * share a {@code content://} URI. Below that there is no FileProvider in this dependency-free
     * build, and handing a {@code file://} URI to another app throws {@code FileUriExposedException},
     * so the file is written to the app's own folder and the path is reported instead of shared.
     */
    private void exportTraining() {
        String log = prefs.trainingLog();
        if (log == null || log.trim().isEmpty()) {
            Toast.makeText(this, "Nothing saved yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String body = "ts\tapp\ttitle\ttext\tpkg\tverdict\tscore\n" + log;
        if (android.os.Build.VERSION.SDK_INT >= 29) shareFromDownloads(body);
        else saveBesideTheApp(body);
    }

    private void shareFromDownloads(String body) {
        android.net.Uri uri;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "notifycleaner-samples.tsv");
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            uri = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                Toast.makeText(this, "Could not create the file", Toast.LENGTH_LONG).show();
                return;
            }
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                Toast.makeText(this, "Could not create the file", Toast.LENGTH_LONG).show();
                return;
            }
            writeUtf8(os, body);
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Notification samples");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Send saved samples"));
        } catch (Exception e) {
            Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_LONG).show();
        }
    }

    private void saveBesideTheApp(String body) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "Could not create the folder", Toast.LENGTH_LONG).show();
                return;
            }
            java.io.File f = new java.io.File(dir, "notifycleaner-samples.tsv");
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            writeUtf8(os, body);
            Toast.makeText(this, "Saved to " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void writeUtf8(java.io.OutputStream os, String body) throws java.io.IOException {
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(os, "UTF-8"));
            w.print(body);
            w.flush();
        } finally {
            try { os.close(); } catch (Exception ignored) { }
        }
    }
}
