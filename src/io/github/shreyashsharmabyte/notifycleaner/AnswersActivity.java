package io.github.shreyashsharmabyte.notifycleaner;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Who answers the question "is this junk?" — the advanced path.
 *
 * <p>The app answers that on the device by default, so nothing here runs until the user asks for a
 * provider from Settings. This screen therefore owns the one moment notification text can start
 * leaving the phone, and it refuses to switch engines without a second, provider-specific
 * disclosure and an explicit agreement. Reading the fields changes nothing; Done validates and, if
 * agreement is still missing, asks before switching.
 *
 * <p>Fields write through as they are typed, so a stray back gesture cannot lose a key. Test sends
 * one sample promotion — and is gated by the same agreement, because a test is a real request.
 */
public class AnswersActivity extends Screen {

    private enum Field { KEY, MODEL, BASE }

    private Providers.Preset preset;
    private LinearLayout body;
    private TextView errorLine;
    private TextView resultLine;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        normaliseEndpoint();

        LinearLayout root = vertical();
        header(root, "Your provider", "\u2039 Back", "Test",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { runTest(); }
                });
        divider(root);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);

        setScreenContent(root);
        render();
    }

    /**
     * Anthropic's endpoint is fixed in the classifier. Older installs stored an empty base for it,
     * which made the provider read as unconfigured everywhere the endpoint is validated. Filling it
     * in once, here, repairs those installs without touching any decision logic.
     */
    private void normaliseEndpoint() {
        Providers.Preset current = prefs.provider();
        if (current.anthropic && prefs.base().isEmpty()) prefs.setBase(Providers.baseFor(current));
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        preset = prefs.provider();

        paragraph(body, "Notifications are classified on this device unless you choose otherwise "
                + "here. A provider is optional: it answers faster on a large model, and it is the "
                + "only setting that sends notification text off the phone.");

        sectionLabel(body, "Provider", "01");
        body.addView(settingsRow("Answers from", preset.name, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickProvider(); }
        }));

        if (!prefs.providerAgreed()) {
            TextView warning = text("Not agreed yet. Until you agree below, notifications stay on "
                    + "this phone and nothing is sent to " + preset.name + ".", 12, c.accent, false);
            warning.setLineSpacing(dp(4), 1f);
            body.addView(warning, margins(6, 12, 20, 6));
        }

        EditText key = field(body, "API key", prefs.apiKey(),
                "Create one in your provider's dashboard.", true, 1);
        watch(key, Field.KEY);

        EditText model = field(body, "Model", prefs.model(), null, false, 1);
        watch(model, Field.MODEL);

        if (preset.anthropic) {
            body.addView(settingsRow("Endpoint", "api.anthropic.com", false, null));
        } else {
            EditText endpoint = field(body, "Endpoint", prefs.base(),
                    "Leave as it is unless your provider says otherwise.", false, 1);
            watch(endpoint, Field.BASE);
        }

        errorLine = text("", 12, c.bad, false);
        errorLine.setVisibility(View.GONE);
        body.addView(errorLine, margins(10, 16, 16, 0));

        resultLine = italic("", 14, c.muted);
        resultLine.setLineSpacing(dp(2), 1f);
        resultLine.setVisibility(View.GONE);
        body.addView(resultLine, margins(8, 16, 16, 0));

        divider(body);
        // One primary action, and it says exactly what it does. The way back to the device is the
        // chooser that brought the user here, not a downgrade button inside the upgrade form.
        addButton(body, primaryButton("Save and use this provider", new View.OnClickListener() {
            @Override
            public void onClick(View v) { save(); }
        }));
    }

    // ------------------------------------------------------------------ fields

    private void watch(EditText input, final Field field) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override
            public void afterTextChanged(Editable s) {
                String value = s.toString();
                if (field == Field.KEY) prefs.setApiKey(value);
                else if (field == Field.MODEL) prefs.setModel(value);
                else prefs.setBase(value);
            }
        });
    }

    private void save() {
        preset = prefs.provider();
        String problem = problem();
        if (problem != null) {
            showError(problem);
            return;
        }
        if (!prefs.providerAgreed()) {
            askConsent(true, new Runnable() {
                @Override
                public void run() { finish(); }
            });
            return;
        }
        prefs.setEngine(Prefs.ENGINE_PROVIDER);
        finish();
    }

    /** @return what is missing or wrong, or null when the provider is usable */
    private String problem() {
        if (prefs.model().isEmpty()) return "Enter the model's name.";
        if (prefs.apiKey().isEmpty()) return "Enter an API key.";
        if (!preset.anthropic && !Urls.allowed(prefs.base())) {
            return "Enter an endpoint that starts with https.";
        }
        return null;
    }

    private void showError(String message) {
        if (errorLine == null) return;
        errorLine.setText(message);
        errorLine.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ pickers

    private void pickProvider() {
        final Providers.Preset[] all = Providers.ALL;
        String[] names = new String[all.length];
        int checked = 0;
        String activeId = prefs.provider().id;
        for (int i = 0; i < all.length; i++) {
            names[i] = all[i].name;
            if (all[i].id.equals(activeId)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Who answers")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Providers.Preset chosen = all[which];
                        boolean changed = !chosen.id.equals(prefs.provider().id);
                        prefs.setProvider(chosen.id);
                        // Consent names one provider. Choosing another voids it, so the second
                        // disclosure is shown again before anything is sent to the new host.
                        if (changed) {
                            prefs.clearProviderConsent();
                            prefs.setBase(Providers.baseFor(chosen));
                            if (!chosen.model.isEmpty()) prefs.setModel(chosen.model);
                        }
                        dialog.dismiss();
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ consent

    /**
     * The second disclosure. Shown before the engine can be switched to a provider and before a
     * test request is made, because both are the same act: notification text leaving the phone.
     */
    private void askConsent(final boolean switchEngine, final Runnable after) {
        preset = prefs.provider();
        new AlertDialog.Builder(this)
                .setTitle("Send notifications to " + preset.name + "?")
                .setMessage(Screen.providerDisclosure(preset.name, hostFor(preset)))
                .setPositiveButton("Agree", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setProviderConsent(preset.id);
                        if (switchEngine) prefs.setEngine(Prefs.ENGINE_PROVIDER);
                        render();
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton("Stay on this device", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (switchEngine) prefs.setEngine(Prefs.ENGINE_OFFLINE);
                        render();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ test

    private void runTest() {
        if (resultLine == null) return;
        if (prefs.model().isEmpty()) {
            resultLine.setVisibility(View.VISIBLE);
            resultLine.setTextColor(c.bad);
            resultLine.setText("Choose a model first.");
            return;
        }
        if (!prefs.providerAgreed()) {
            // A test is a real request carrying real notification-shaped text. Agree first.
            askConsent(false, new Runnable() {
                @Override
                public void run() { sendSample(); }
            });
            return;
        }
        sendSample();
    }

    private void sendSample() {
        if (resultLine == null) return;
        final String key = prefs.apiKey();
        final String model = prefs.model();
        final String base = prefs.base();
        final String providerId = prefs.provider().id;
        final String prompt = prefs.promptText();

        resultLine.setVisibility(View.VISIBLE);
        resultLine.setTextColor(c.muted);
        resultLine.setText("Testing\u2026");

        final CircuitBreaker breaker = new CircuitBreaker();
        final RateLimiter limiter = new RateLimiter();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Classifier.Outcome outcome = new Classifier(key, model, base, prompt,
                        providerId, breaker, limiter)
                        .classify("com.example.shop", "Flash sale: 50% off today only",
                                "Shop now before the offer ends", System.currentTimeMillis());

                final String message;
                final int colour;
                if (outcome.verdict == Decision.Verdict.JUNK) {
                    message = "Working. A sample promotion was removed.";
                    colour = c.good;
                } else if (outcome.verdict == Decision.Verdict.KEEP) {
                    message = "Connected, but the model kept a sample promotion. It may be too "
                            + "cautious to remove anything.";
                    colour = c.bad;
                } else {
                    message = "Failed: " + (outcome.note == null ? "unknown error" : outcome.note);
                    colour = c.bad;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (resultLine == null) return;
                        resultLine.setVisibility(View.VISIBLE);
                        resultLine.setTextColor(colour);
                        resultLine.setText(message);
                    }
                });
            }
        }, "test-connection").start();
    }
}
