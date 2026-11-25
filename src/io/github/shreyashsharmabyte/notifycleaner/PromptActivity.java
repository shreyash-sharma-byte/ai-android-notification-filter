package io.github.shreyashsharmabyte.notifycleaner;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The raw instructions, for someone who wants them. Deliberately out of the way: the settings screen
 * offers a strictness level, because choosing an intent is a fair thing to ask of a user and writing
 * a correct classification prompt is not.
 *
 * <p>Editing anything here switches the policy to Custom. The safety rules are shown read-only beneath,
 * because they are appended to whatever is typed and knowing that matters.
 */
public class PromptActivity extends Screen {

    private EditText input;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = vertical();
        header(root, "Advanced prompt", "\u2039 Back", "Reset", new View.OnClickListener() {
            @Override
            public void onClick(View v) { reset(); }
        });

        sectionLabel(root, "Instruction", "01");
        help(root, "Editing this makes the policy Custom.");

        input = new EditText(this);
        input.setText(prefs.promptText());
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        input.setTextColor(c.ink);
        input.setTypeface(android.graphics.Typeface.create("monospace",
                android.graphics.Typeface.NORMAL));
        input.setBackground(null);
        input.setLineSpacing(dp(4), 1f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(20), dp(10), dp(20), dp(10));
        root.addView(input);

        loading = false;
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override
            public void afterTextChanged(Editable s) {
                if (loading) return;
                prefs.setCustomPrompt(s.toString());
                prefs.setStrictness(Strictness.Level.CUSTOM);
            }
        });

        divider(root);
        sectionLabel(root, "Always appended", "02");
        TextView rules = text(Prompt.safetyRules(), 12, c.muted, false);
        rules.setLineSpacing(dp(3), 1f);
        root.addView(rules, margins(4, 16, 16, 12));

        setScreenContent(root);
    }

    private void reset() {
        loading = true;
        prefs.setCustomPrompt("");
        prefs.setStrictness(Strictness.Level.BALANCED);
        input.setText(Strictness.prompt(Strictness.Level.BALANCED));
        loading = false;
    }
}
