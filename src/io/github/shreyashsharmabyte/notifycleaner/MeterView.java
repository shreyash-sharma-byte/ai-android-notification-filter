package io.github.shreyashsharmabyte.notifycleaner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The share of one sender's removals, drawn as a hairline track and a vermilion measure.
 *
 * <p>The figure at the end of the row is the reading; the bar is the comparison, and this is the
 * only place in the app where a length stands for a count. Track and fill share a baseline, so the
 * bar grows out of the rule rather than floating on it — a printed rule, not a progress bar.
 */
final class MeterView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float fraction;
    private int accent, line;

    MeterView(Context context) {
        super(context);
        // A length standing for a count is comparison, not content: the row's figure is the
        // reading, so TalkBack should not stop on the bar.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void palette(Screen.Palette c) {
        accent = c.accent;
        line = c.line;
    }

    void setFraction(float value) {
        fraction = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float base = getHeight() / 2f + dp(1.4f);
        paint.setColor(line);
        canvas.drawRect(0, base - dp(0.6f), w, base, paint);
        if (fraction <= 0f) return;
        paint.setColor(accent);
        float measure = Math.max(dp(2), w * fraction);
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
            canvas.drawRect(w - measure, base - dp(1.7f), w, base, paint);
        } else {
            canvas.drawRect(0, base - dp(1.7f), measure, base, paint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
