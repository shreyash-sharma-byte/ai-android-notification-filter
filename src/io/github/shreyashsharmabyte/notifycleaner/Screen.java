package io.github.shreyashsharmabyte.notifycleaner;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared chrome for every screen: palette, type scale, rules, insets and motion.
 *
 * <p><b>Editorial.</b> The app is set like a magazine page rather than a settings list: a serif
 * display face for headlines and numbers, a sans for interface text, small caps for metadata,
 * hairline rules instead of boxes, and one printed vermilion for anything that asks something of
 * the reader. Everything here is a platform typeface ({@code serif} resolves to Noto Serif,
 * {@code sans-serif-medium} to Roboto Medium) and everything visual is drawn — no assets, no
 * dependencies.
 *
 * <p>The rules that survive from earlier directions, because they are the product and not the
 * styling: no control here can read a notification, enable filtering or write consent. This class
 * only draws.
 */
abstract class Screen extends Activity {

    static final class Palette {
        final int bg, surface, line, ink, muted, faint, accent, onAccent, good, bad;

        private Palette(int bg, int surface, int line, int ink, int muted, int faint,
                        int accent, int onAccent, int good, int bad) {
            this.bg = bg;
            this.surface = surface;
            this.line = line;
            this.ink = ink;
            this.muted = muted;
            this.faint = faint;
            this.accent = accent;
            this.onAccent = onAccent;
            this.good = good;
            this.bad = bad;
        }

        /**
         * Warm paper: cream ground, ink brown-black, one deep vermilion.
         *
         * <p>{@code faint} is the app's meaning layer — captions, timestamps, package names — so it
         * is held at 5.29:1 on the ground and 5.51:1 on the dial face rather than the 2.53:1 it used
         * to be. It is a reading colour, not a decorative tint; {@link #line} stays decorative.
         */
        static Palette light() {
            return new Palette(0xFFFBF7EE, 0xFFFFFCF4, 0xFFE3DACA, 0xFF1D1913, 0xFF776D5E,
                    0xFF6F6657, 0xFFC1441F, 0xFFFFF8ED, 0xFF516B3B, 0xFFA63A26);
        }

        /**
         * Warm black: a lamp on paper after dark, the app's natural setting. {@code faint} is
         * 5.63:1 on the ground, and {@code bad} is 5.78:1 — the old 4.44:1 failed AA at the sizes
         * it was used for.
         */
        static Palette dark() {
            return new Palette(0xFF12100D, 0xFF1B1814, 0xFF322C24, 0xFFF2EADD, 0xFFA1968A,
                    0xFF968A7C, 0xFFE0562F, 0xFF14100C, 0xFF8AA96E, 0xFFE06A5C);
        }

        static Palette of(Configuration cfg) {
            boolean night = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            return night ? dark() : light();
        }

        boolean night() {
            return ink == 0xFFF2EADD;
        }
    }

    // Kept as statics: creating a Typeface per row is the one allocation this screen cannot afford.
    private static Typeface SERIF, SERIF_ITALIC, SANS, SANS_MEDIUM, SANS_LIGHT, MONO;

    /** The product name, used where a string constant is cleaner than a per-screen literal. */
    static final String PRODUCT = "NotifyCleaner";

    protected Palette c;
    protected Prefs prefs;
    protected float density;

    /** Staggered reveal bookkeeping so a rebuild does not replay the entrance on every resume. */
    private int revealed;

    /** Scroll position survives a rebuild: a refresh must not read as a new page. */
    private ScrollView lastScroller;
    private int lastScrollY;
    private boolean lastHadFooter;

    /** Last animated value per counter, so an unchanged figure is not re-counted from zero. */
    private final java.util.Map<String, Integer> counterValues =
            new java.util.HashMap<String, Integer>();

    /** Call from onCreate before building any content. */
    protected void initScreen() {
        prefs = new Prefs(this);
        density = getResources().getDisplayMetrics().density;
        c = Palette.of(getResources().getConfiguration());
        ensureFaces();
        Window window = getWindow();
        window.setStatusBarColor(c.bg);
        window.setNavigationBarColor(c.bg);
        applyBarIcons();
    }

    private static void ensureFaces() {
        if (SERIF != null) return;
        SERIF = Typeface.create("serif", Typeface.NORMAL);
        SERIF_ITALIC = Typeface.create("serif", Typeface.ITALIC);
        SANS = Typeface.create("sans-serif", Typeface.NORMAL);
        SANS_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        SANS_LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
        MONO = Typeface.create("monospace", Typeface.NORMAL);
    }

    // ------------------------------------------------------------------ surface

    /**
     * Wraps content in the scrolling surface and applies window insets.
     *
     * <p>The ground is a two-stop gradient a few units apart: enough that a long page reads as a
     * printed sheet with a light source, not enough to notice as a gradient.
     */
    protected void setScreenContent(LinearLayout content) {
        setScreenContent(content, null);
    }

    /**
     * Same surface, with a control pinned to the foot of the page.
     *
     * <p>Used once, on the cover: the disclosure is long by design and the agreement must be
     * available while it is being read, not only after it has been scrolled past. The footer is a
     * separate surface with its own rule, so the button never looks like part of the paragraph.
     */
    protected void setScreenContent(LinearLayout content, View footer) {
        // Capture where the reader was before the old tree is detached. Coming back from Android's
        // settings, or pressing pause, must not throw them to the top of the page.
        if (lastScroller != null) lastScrollY = lastScroller.getScrollY();
        boolean sameStage = (footer == null) == lastHadFooter;
        final int restoreY = sameStage ? lastScrollY : 0;
        lastHadFooter = footer != null;

        GradientDrawable ground = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { c.bg, mix(c.bg, c.night() ? 0xFF000000 : 0xFFE8DFCC, 0.10f) });

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Color.TRANSPARENT);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(ground);

        if (footer == null) {
            outer.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // The fade is drawn over the last 26dp of the scroll area, so the page's final line
            // must stop above it or it reads as a smudge. The floor is applied here rather than in
            // the screen's own padding: whichever screen pins a footer gets the clearance, whether
            // or not it remembered to leave air at the end of its copy.
            content.setPaddingRelative(content.getPaddingStart(), content.getPaddingTop(),
                    content.getPaddingEnd(), Math.max(content.getPaddingBottom(), dp(52)));

            // The page fades into its foot rather than stopping dead against it: a printed page
            // does not end mid-sentence, and on a phone the soft edge is the only "there is more"
            // signal a scrolling text block can give.
            FrameLayout stage = new FrameLayout(this);
            stage.addView(scroller, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View fade = new View(this);
            GradientDrawable veil = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[] { c.bg, alpha(c.bg, 0f) });
            fade.setBackground(veil);
            fade.setClickable(false);
            fade.setFocusable(false);
            stage.addView(fade, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(26), Gravity.BOTTOM));
            outer.addView(stage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            outer.addView(footer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        applyInsets(outer);
        setContentView(outer);
        lastScroller = scroller;
        if (restoreY > 0) {
            scroller.post(new Runnable() {
                @Override
                public void run() { scroller.scrollTo(0, restoreY); }
            });
        }
        // The entrance is a statement about arriving on a page, not a side effect of refreshing
        // one. `revealed` was the intended hook and was never read; it is now the guard.
        if (revealed == 0) {
            revealed = 1;
            if (ValueAnimator.areAnimatorsEnabled()) revealChildren(content);
        }
    }

    /** A staircase of 320ms rises, 40ms apart. Purpose: say "this is a new page", once. */
    private void revealChildren(ViewGroup content) {
        final int n = Math.min(content.getChildCount(), 9);
        for (int i = 0; i < n; i++) {
            final View child = content.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(dp(10));
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(30L + i * 40L)
                    .setDuration(320L)
                    .start();
        }
    }

    protected LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 0, 0, dp(36));
        return l;
    }

    protected int dp(int value) { return (int) (value * density + 0.5f); }

    protected int dp(float value) { return (int) (value * density + 0.5f); }

    // ------------------------------------------------------------------ type

    protected TextView text(String value, float sizeSp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value == null ? "" : value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(colour);
        t.setTypeface(bold ? SANS_MEDIUM : SANS);
        return t;
    }

    /** Display face: headlines, figures, the voice of the page. */
    protected TextView display(String value, float sizeSp, int colour) {
        TextView t = new TextView(this);
        t.setText(value == null ? "" : value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(colour);
        t.setTypeface(SERIF);
        t.setLetterSpacing(-0.018f);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    protected TextView italic(String value, float sizeSp, int colour) {
        TextView t = display(value, sizeSp, colour);
        t.setTypeface(SERIF_ITALIC);
        return t;
    }

    /** A figure set large and quiet: the one number a page is about. */
    protected TextView figure(String value, float sizeSp, int colour) {
        TextView t = display(value, sizeSp, colour);
        t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        t.setLetterSpacing(-0.035f);
        t.setLineSpacing(0f, 0.98f);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Metadata: small caps, wide tracking, muted. The app's connective tissue. */
    protected TextView kicker(String value) {
        return kicker(value, c.muted);
    }

    protected TextView kicker(String value, int colour) {
        TextView t = new TextView(this);
        t.setText(value == null ? "" : value.toUpperCase(java.util.Locale.ROOT));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(colour);
        t.setTypeface(SANS_MEDIUM);
        t.setLetterSpacing(0.17f);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Machine numerals: only where figures stack in a column and must line up. */
    protected TextView mono(String value, float sizeSp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value == null ? "" : value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(colour);
        t.setTypeface(MONO, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Body copy at reading size, with the leading a paragraph needs. */
    protected TextView prose(String value) {
        TextView t = text(value, 14, c.ink, false);
        t.setLineSpacing(dp(5), 1f);
        return t;
    }

    // ------------------------------------------------------------------ structure

    /** The masthead: printer's mark, wordmark in caps, issue line, and the page's top rule. */
    protected void masthead(LinearLayout root, String meta) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(20), dp(20), dp(20), 0);

        Ornaments.Fleuron mark = new Ornaments.Fleuron(this, c.accent, dp(15));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(15), dp(15));
        mp.setMarginEnd(dp(10));
        row.addView(mark, mp);

        TextView word = kicker(PRODUCT, c.ink);
        word.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        word.setLetterSpacing(0.14f);
        // The name is short now; one line, and ellipsised rather than clipped if a large font
        // scale or a narrow screen leaves no room.
        word.setSingleLine(true);
        word.setEllipsize(TextUtils.TruncateAt.END);
        word.setMaxLines(1);
        row.addView(word, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (meta != null && !meta.isEmpty()) {
            TextView m = kicker(meta, c.faint);
            m.setLetterSpacing(0.12f);
            m.setSingleLine(true);
            m.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.setMarginStart(dp(12));
            row.addView(m, mlp);
        }
        root.addView(row);
        root.addView(rule(), margins(20, 20, 20, 0));
    }

    /**
     * Inner-screen header: a back mark, a trailing action, the title in the display face, and a
     * rule. Titles are allowed to run to two lines: an editorial page does not truncate its own
     * name to fit a bar.
     */
    protected void header(LinearLayout root, String title, String backLabel,
                          String actionLabel, View.OnClickListener actionListener) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(dp(16), dp(6), dp(16), 0);

        if (backLabel != null) {
            TextView back = backMark(backLabel);
            bar.addView(back);
        } else {
            Ornaments.Fleuron mark = new Ornaments.Fleuron(this, c.accent, dp(13));
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(13), dp(13));
            mp.setMarginStart(dp(6));
            mp.setMarginEnd(dp(10));
            bar.addView(mark, mp);
        }

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        if (actionLabel != null) bar.addView(action(actionLabel, true, actionListener));
        root.addView(bar);

        TextView heading = display(title, 27, c.ink);
        heading.setPadding(0, 0, 0, 0);
        root.addView(heading, margins(18, 20, 20, 0));
        root.addView(rule(), margins(18, 20, 20, 0));
    }

    /** "Back" as a small-capped rule-and-word, wrong-way round in a right-to-left layout. */
    private TextView backMark(String label) {
        String arrow = rtl() ? "\u2192" : "\u2190";
        String word = label == null ? "Back"
                : label.replace("\u2190", "").replace("\u2192", "")
                        .replace("\u2039", "").replace("\u203A", "").trim();
        if (word.isEmpty()) word = "Back";
        TextView back = kicker(arrow + "  " + word, c.ink);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        back.setPaddingRelative(dp(6), dp(12), dp(14), dp(12));
        back.setBackground(ripple());
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        minTouchTarget(back);
        makeButton(back, null);
        return back;
    }

    /**
     * A hairline. The height is set <em>and</em> made a minimum, because callers pass
     * {@link #margins} to {@code addView}, which replaces the view's own params. Inside a
     * {@link ScrollView} a background-only view measured with an UNSPECIFIED height resolves to
     * its suggested minimum — which was zero until this was added, so every rule in the app was
     * invisible.
     */
    protected View rule() {
        View line = new View(this);
        line.setBackgroundColor(c.line);
        int thickness = Math.max(1, dp(0.7f));
        line.setMinimumHeight(thickness);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, thickness));
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }

    /**
     * A short, thick rule in the accent: the page's one piece of punctuation. Width is set as a
     * minimum too, and {@link #margins(View, int, int, int, int)} is used at the call site so the
     * intended 44dp measure is not replaced with MATCH_PARENT.
     */
    protected View accentRule(int widthDp) {
        View line = new View(this);
        line.setBackgroundColor(c.accent);
        int thickness = Math.max(1, dp(2));
        line.setMinimumWidth(dp(widthDp));
        line.setMinimumHeight(thickness);
        line.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), thickness));
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }

    /** Section opener: a figure, an em rule, then the section in caps. */
    protected void sectionLabel(LinearLayout root, String value) {
        sectionLabel(root, value, null);
    }

    protected void sectionLabel(LinearLayout root, String value, String index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);

        if (index != null) {
            TextView n = kicker(index, c.accent);
            n.setLetterSpacing(0.1f);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.setMarginEnd(dp(10));
            row.addView(n, np);
        }
        row.addView(kicker(value, c.muted));
        root.addView(row, margins(26, 20, 20, 10));
    }

    protected void help(LinearLayout root, String value) {
        TextView t = text(value, 12, c.muted, false);
        t.setLineSpacing(dp(4), 1f);
        root.addView(t, margins(10, 20, 20, 6));
    }

    protected void divider(LinearLayout parent) {
        View line = rule();
        parent.addView(line, margins(line, 0, 20, 20, 0));
    }

    /** A paragraph of body copy, in the secondary voice. */
    protected TextView paragraph(LinearLayout parent, String value) {
        TextView t = text(value, 13, c.muted, false);
        t.setLineSpacing(dp(4), 1f);
        parent.addView(t, margins(12, 20, 20, 12));
        return t;
    }

    // ------------------------------------------------------------------ rows

    protected LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(58));
        r.setPaddingRelative(dp(20), dp(4), dp(20), dp(4));
        return r;
    }

    protected LinearLayout settingsRow(String label, String value, boolean tappable,
                                       View.OnClickListener listener) {
        LinearLayout r = row();
        TextView l = text(label, 15, c.ink, false);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null && !value.isEmpty()) r.addView(valueText(value));
        if (tappable) r.addView(chevron());
        if (tappable) {
            r.setBackground(ripple());
            r.setOnClickListener(listener);
            r.setContentDescription(label + (value == null || value.isEmpty() ? "" : ", " + value));
            makeButton(r, "Opens " + label);
        }
        return r;
    }

    /**
     * A numbered destination: the index is set in the display face at 22sp, so a list of three
     * screens reads as a contents page rather than a menu.
     */
    protected LinearLayout indexedRow(String index, String label, String value,
                                      View.OnClickListener listener) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(54));
        TextView n = figure(index, 20, c.accent);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(30),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        np.setMarginEnd(dp(6));
        r.addView(n, np);
        r.addView(text(label, 15, c.ink, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // A count on a contents page is a figure read as a statement, so it is set in the display
        // face: the rule of numerals, applied to the one place the contents carries a number.
        if (value != null && !value.isEmpty()) {
            TextView v = figure(value, 16, c.ink);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.setMarginEnd(dp(6));
            r.addView(v, vp);
        }
        r.addView(chevron());
        r.setBackground(ripple());
        r.setOnClickListener(listener);
        r.setContentDescription(label + (value == null || value.isEmpty() ? "" : ", " + value));
        makeButton(r, "Opens " + label);
        return r;
    }

    protected TextView settingsRowValue(LinearLayout parent, String label, String value,
                                        View.OnClickListener listener) {
        LinearLayout r = row();
        r.addView(text(label, 15, c.ink, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = valueText(value);
        r.addView(v);
        r.addView(chevron());
        r.setBackground(ripple());
        r.setOnClickListener(listener);
        r.setContentDescription(label + (value == null || value.isEmpty() ? "" : ", " + value));
        makeButton(r, "Opens " + label);
        parent.addView(r);
        return v;
    }

    private TextView valueText(String value) {
        TextView v = text(value, 13, c.muted, false);
        v.setTypeface(SANS_MEDIUM);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setMaxWidth(dp(190));
        return v;
    }

    protected TextView chevron() {
        // A literal arrow has to be mirrored explicitly in a right-to-left layout.
        TextView t = text(rtl() ? "\u2190" : "\u2192", 15, c.faint, false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(dp(12));
        t.setLayoutParams(p);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    // ------------------------------------------------------------------ buttons

    protected void addButton(LinearLayout parent, TextView button) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPaddingRelative(dp(20), dp(14), dp(20), dp(8));
        holder.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(holder);
    }

    /** Trailing header action, set as a small cap rather than a chunky button. */
    protected TextView action(String label, boolean primary, View.OnClickListener listener) {
        TextView t = kicker(label, primary ? c.accent : c.muted);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPaddingRelative(dp(10), dp(12), dp(10), dp(12));
        t.setBackground(ripple());
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(listener);
        minTouchTarget(t);
        makeButton(t, null);
        return t;
    }

    protected TextView primaryButton(String label, View.OnClickListener listener) {
        TextView t = text(label.toUpperCase(java.util.Locale.ROOT), 12, c.onAccent, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(54));
        t.setLetterSpacing(0.12f);
        t.setPaddingRelative(dp(18), dp(16), dp(18), dp(16));
        t.setOnClickListener(listener);
        styleButton(t, true);
        makeButton(t, null);
        return t;
    }

    protected TextView secondaryButton(String label, View.OnClickListener listener) {
        TextView t = text(label.toUpperCase(java.util.Locale.ROOT), 12, c.ink, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(54));
        t.setLetterSpacing(0.12f);
        t.setPaddingRelative(dp(18), dp(16), dp(18), dp(16));
        t.setOnClickListener(listener);
        styleButton(t, false);
        makeButton(t, null);
        return t;
    }

    /**
     * A filled button is a call to action, so it is reserved for one. The corners stay nearly
     * square — 2dp, the radius of a printed rule, not of a card.
     */
    protected void styleButton(TextView button, boolean emphasised) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(emphasised ? c.accent : Color.TRANSPARENT);
        bg.setCornerRadius(dp(2));
        if (!emphasised) bg.setStroke(Math.max(1, dp(0.8f)), c.ink);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(emphasised ? 0x33FFFFFF : 0x22FFFFFF), bg, null));
        button.setTextColor(emphasised ? c.onAccent : c.ink);
        button.setTypeface(SANS_MEDIUM);
    }

    // ------------------------------------------------------------------ accessibility

    /**
     * A TextView with a click listener is announced as text, not as a button. Setting the node's
     * class to Button — and, where it helps, a role description — is the difference between
     * "Pause filtering" and "Pause filtering, button" in TalkBack.
     */
    protected void makeButton(View view, final String role) {
        view.setClickable(true);
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                                                          android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
                info.setClickable(true);
                info.setFocusable(true);
                if (role != null && !role.isEmpty()) {
                    // This SDK has no setRoleDescription; append the role to an existing
                    // description rather than replacing the label the node already carries.
                    CharSequence base = host.getContentDescription();
                    if (base != null && base.length() > 0
                            && !base.toString().contains(role)) {
                        info.setContentDescription(base + ", " + role);
                    }
                }
            }
        });
    }

    /**
     * Enforces the 48dp minimum touch target. {@code setMinWidth}/{@code setMinHeight} grow the
     * view itself; a TouchDelegate on the parent grows the hit rectangle of a view that still lays
     * out smaller than that (a header action in a tight bar) without changing its measured size.
     */
    protected void minTouchTarget(final View view) {
        view.setMinimumWidth(dp(48));
        view.setMinimumHeight(dp(48));
        // One combined delegate per parent: several small controls can share a bar.
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (!(v.getParent() instanceof ViewGroup)) return;
                if (CombinedTouchDelegate.registered(v)) return;
                ViewGroup parent = (ViewGroup) v.getParent();
                int[] loc = new int[2];
                int[] ploc = new int[2];
                v.getLocationInWindow(loc);
                parent.getLocationInWindow(ploc);
                int x = loc[0] - ploc[0];
                int y = loc[1] - ploc[1];
                int growX = Math.max(0, (dp(48) - v.getWidth()) / 2);
                int growY = Math.max(0, (dp(48) - v.getHeight()) / 2);
                android.graphics.Rect rect = new android.graphics.Rect(
                        x - growX, y - growY, x + v.getWidth() + growX, y + v.getHeight() + growY);
                CombinedTouchDelegate group = CombinedTouchDelegate.forParent(parent);
                group.add(rect, v);
            }
        });
    }

    /** Marks a drawn ornament as decoration: TalkBack skips it instead of reading a blank node. */
    protected void decorative(View view) {
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setFocusable(false);
        view.setClickable(false);
    }

    /**
     * Accumulates delegates for several children of one parent, because
     * {@link ViewGroup#setTouchDelegate} holds a single delegate.
     */
    private static final class CombinedTouchDelegate extends android.view.TouchDelegate {
        private static final int PARENT_KEY = 0x6E630001;
        private static final int CHILD_KEY = 0x6E630002;
        private final java.util.List<android.view.TouchDelegate> delegates =
                new java.util.ArrayList<android.view.TouchDelegate>();

        private CombinedTouchDelegate(android.graphics.Rect bounds, View view) {
            super(bounds, view);
        }

        static CombinedTouchDelegate forParent(ViewGroup parent) {
            Object existing = parent.getTag(PARENT_KEY);
            if (existing instanceof CombinedTouchDelegate) return (CombinedTouchDelegate) existing;
            CombinedTouchDelegate group = new CombinedTouchDelegate(new android.graphics.Rect(),
                    parent);
            parent.setTag(PARENT_KEY, group);
            parent.setTouchDelegate(group);
            return group;
        }

        static boolean registered(View child) {
            return Boolean.TRUE.equals(child.getTag(CHILD_KEY));
        }

        void add(android.graphics.Rect bounds, View view) {
            delegates.add(new android.view.TouchDelegate(bounds, view));
            view.setTag(CHILD_KEY, Boolean.TRUE);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            for (int i = 0; i < delegates.size(); i++) {
                if (delegates.get(i).onTouchEvent(event)) return true;
            }
            return false;
        }
    }

    /** True when the current layout direction is right-to-left. */
    protected boolean rtl() {
        return getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
    }

    protected Drawable ripple() {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return getResources().getDrawable(out.resourceId, getTheme());
    }

    protected LinearLayout.LayoutParams margins(int topDp, int leftDp, int rightDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        p.setMarginStart(dp(leftDp));
        p.setMarginEnd(dp(rightDp));
        p.bottomMargin = dp(bottomDp);
        return p;
    }

    /**
     * Margins applied to the params a view already has, rather than replacing them. Used wherever
     * the view's own width or height carries meaning — a 44dp accent rule, a 48dp touch target —
     * because {@link #margins(int, int, int, int)} would otherwise turn it into a full-width
     * WRAP_CONTENT view.
     */
    protected LinearLayout.LayoutParams margins(View view, int topDp, int leftDp, int rightDp,
                                                int bottomDp) {
        LinearLayout.LayoutParams p;
        if (view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            p = (LinearLayout.LayoutParams) view.getLayoutParams();
        } else {
            p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        p.topMargin = dp(topDp);
        p.setMarginStart(dp(leftDp));
        p.setMarginEnd(dp(rightDp));
        p.bottomMargin = dp(bottomDp);
        return p;
    }

    /**
     * The one place the answering engine is chosen. Every entry point opens this, so the user meets
     * the same two options in the same words wherever they arrive from, with the current choice
     * marked rather than implied.
     *
     * <p>Choosing a provider does not switch the engine — the provider screen owns that, after it
     * has shown what will be sent and been agreed to. Choosing the device switches back at once,
     * because moving data onto the network needs ceremony and moving it off does not.
     */
    protected void showAnswersChooser(final Runnable onChanged) {
        final boolean onProvider = prefs.usesProvider();
        new android.app.AlertDialog.Builder(this)
                .setTitle("How notifications are judged")
                .setSingleChoiceItems(new String[] {
                        "On this phone \u2014 nothing leaves it",
                        "A provider you choose \u2014 text is sent to them"
                }, onProvider ? 1 : 0, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            if (onProvider) {
                                prefs.setEngine(Prefs.ENGINE_OFFLINE);
                                onChanged.run();
                            }
                        } else {
                            start(AnswersActivity.class);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    protected void start(Class<?> screen) {
        startActivity(new Intent(this, screen));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ------------------------------------------------------------------ motion

    /**
     * Counts a figure to its value. The first render animates from zero; a refresh with the same
     * value simply prints it, because a number visibly falling to zero and climbing back reads as
     * data loss in an app that deletes things. Honours the system animation setting.
     */
    protected void countUp(final TextView view, String key, final int target) {
        Integer previous = counterValues.get(key);
        counterValues.put(key, target);
        view.setText(String.valueOf(target));
        if (previous != null && previous.intValue() == target) return;
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        ValueAnimator a = ValueAnimator.ofInt(previous == null ? 0 : previous.intValue(), target);
        a.setDuration(620L);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                view.setText(String.valueOf(anim.getAnimatedValue()));
            }
        });
        a.start();
    }

    protected static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    protected static int alpha(int colour, float a) {
        return (colour & 0x00FFFFFF) | (((int) (a * 255)) << 24);
    }

    // ------------------------------------------------------------------ disclosure

    /**
     * The in-app disclosure, shared by every screen that references it. Notification content is
     * personal data, so this is shown before the listener does anything and filtering stays off
     * until it is accepted. Play's User Data policy requires a prominent in-app disclosure followed
     * by affirmative consent, not a line buried in a document.
     *
     * <p>It describes the app that actually ships: the default engine runs on the device and sends
     * nothing. Saying otherwise would be a false statement about a network request that no longer
     * happens, and it would also hide the one thing the user does need to know — that choosing a
     * provider is the only way notification text ever leaves the phone, and that choice has its own
     * disclosure ({@link #providerDisclosure}).
     */
    protected static final String DISCLOSURE_TEXT =
            PRODUCT + " reads your notifications so it can decide which are junk. It decides "
            + "on this phone, with a small classifier that is part of the app.\n\n"
            + "\u2022 Nothing leaves this device. There is no account, no server of ours, and no "
            + "network request at all in the default setup.\n"
            + "\u2022 It reads the notification title and text, and records the posting app's "
            + "name and label.\n"
            + "\u2022 Notifications from apps on your protected list are never classified. Their "
            + "app name is still recorded, but never sent.\n"
            + "\u2022 Promotions, sales and newsletters are removed. Messages, verification codes, "
            + "payments, orders, security alerts and anything uncertain are kept.\n"
            + "\u2022 Apps are recognised too: a notification from a shop, food-delivery or travel "
            + "app is judged more readily as an offer, while chat, mail, calls, codes and banking "
            + "apps are on your protected list from the start.\n"
            + "\u2022 On the optional provider path, long text is shortened before it is sent: "
            + "the beginning and the end are kept.\n"
            + "\u2022 The removed list is stored on this device only.\n\n"
            + "Only if you choose your own provider in Settings does notification text leave the "
            + "phone. That is off by default, it needs a second, provider-specific agreement, and "
            + "nothing is sent until you give it.\n\n"
            + "You can pause filtering at any time.";

    /**
     * The second disclosure, shown at the moment the user opts into a provider and before any
     * request is made. It names the recipient and the data, because "a provider" is not informed
     * consent. {@code host} is the host the request would go to.
     */
    protected static String providerDisclosure(String providerName, String host) {
        String name = providerName == null || providerName.isEmpty() ? "your provider" : providerName;
        String to = host == null || host.isEmpty() ? name : host;
        return "You are choosing " + name + " instead of the on-device classifier.\n\n"
                + "\u2022 With this on, the posting app's name, the notification title and its "
                + "text leave this device and are sent to " + to + ".\n"
                + "\u2022 The request uses your own API key, and " + name + "'s terms and privacy "
                + "policy apply to what it receives.\n"
                + "\u2022 The title and text of apps on your protected list are never read or "
                + "sent, and calls, alarms, media and other functional notifications are never "
                + "sent.\n"
                + "\u2022 Nothing is sent anywhere else. This project runs no server of its own.\n"
                + "\u2022 If the provider fails, is rate limited or is unsure, the notification "
                + "is kept.\n\n"
                + "You can switch back to the on-device classifier at any time. Nothing is sent "
                + "again from that moment.";
    }

    /**
     * Reading the notice. This records nothing and switches nothing on: it is the reference text
     * the user can return to. Consent is collected once, by the first screen, and activation is a
     * separate explicit act.
     */
    protected void showDisclosureDialog() {
        String message = DISCLOSURE_TEXT;
        if (prefs.usesProvider()) {
            Providers.Preset preset = prefs.provider();
            String host = hostFor(preset);
            if (host.isEmpty()) {
                // Custom presets carry no fixed base; name the endpoint actually stored.
                String stored = Urls.hostOf(prefs.base());
                if (stored != null) host = stored;
            }
            message = message + "\n\nRight now you are using " + preset.name + ". Notification "
                    + "text is sent to " + (host.isEmpty() ? "the endpoint you configured" : host)
                    + " only while that setting is on.";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("What leaves your device")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }

    /** The host a preset's requests go to, for the disclosure. Empty when it is not yet set. */
    protected static String hostFor(Providers.Preset preset) {
        if (preset == null) return "";
        String base = Providers.baseFor(preset);
        if (base == null || base.isEmpty()) return "";
        String host = Urls.hostOf(base);
        return host == null ? "" : host;
    }

    /**
     * The disclosure as a pull-quote: a 2dp accent edge, generous measure, no box. It is the most
     * consequential paragraph in the app, so it is set like the pull-quote on a magazine page.
     */
    protected TextView pullQuote(LinearLayout parent, String value, int sizeSp) {
        TextView body = text(value, sizeSp, c.muted, false);
        body.setLineSpacing(dp(6), 1f);
        // The edge is the view's own background, so it is exactly as tall as the text it marks —
        // no sibling to measure, and nothing that can collapse in a wrap_content column.
        body.setBackground(new Ornaments.LeftRule(c.accent, dp(2), rtl()));
        body.setPaddingRelative(dp(16), dp(2), 0, dp(2));
        parent.addView(body, margins(4, 20, 24, 4));
        return body;
    }

    // ------------------------------------------------------------------ fields

    /**
     * Label above, field, rule below, optional help. The rule is the field: a boxed input would be
     * the only rounded rectangle in the app, and the focus state gets to be a colour, not a border
     * width change.
     */
    protected EditText field(LinearLayout parent, String label, String value, String helper,
                             boolean secret, int lines) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(20), 0, dp(20), 0);

        if (label != null) group.addView(kicker(label), margins(18, 0, 0, 0));

        final EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setTypeface(SERIF);
        input.setTextColor(c.ink);
        input.setHintTextColor(c.faint);
        input.setBackground(null);
        input.setPadding(0, dp(9), 0, dp(9));
        if (secret) {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setSingleLine(true);
        } else if (lines > 1) {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(lines);
            input.setGravity(Gravity.TOP | Gravity.START);
        } else {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            input.setSingleLine(true);
        }
        group.addView(input);

        final View underline = new View(this);
        final GradientDrawable line = new GradientDrawable();
        line.setColor(c.line);
        underline.setBackground(line);
        group.addView(underline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f))));

        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                line.setColor(hasFocus ? c.accent : c.line);
                underline.setBackground(line);
            }
        });

        if (helper != null) {
            TextView h = text(helper, 12, c.muted, false);
            h.setLineSpacing(dp(4), 1f);
            group.addView(h, margins(8, 0, 0, 0));
        }
        parent.addView(group, margins(16, 0, 0, 0));
        return input;
    }

    // ------------------------------------------------------------------ window

    private void applyInsets(View content) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            content.setFitsSystemWindows(true);
            return;
        }
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
                return insets;
            }
        });
    }

    private void applyBarIcons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (c.night()) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /**
     * Shared: does the system currently list this app as a notification listener? Compares full
     * component names, not a substring of the settings value: a package whose name merely contains
     * this one must not read as granted.
     */
    protected boolean notificationAccessGranted() {
        try {
            String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName mine = new ComponentName(this, NotifService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                String part = splitter.next();
                ComponentName listed = ComponentName.unflattenFromString(
                        part == null ? "" : part.trim());
                if (mine.equals(listed)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        initScreen();
    }
}
