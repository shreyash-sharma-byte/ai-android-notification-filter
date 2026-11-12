package io.github.shreyashsharmabyte.notifycleaner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Removals per hour across the last twelve hours, lifted from the Instrument direction and set in
 * Editorial's vermilion on paper.
 *
 * <p>Height is the count in that bucket, so a quiet hour is a stub on the baseline rather than a
 * gap: the floor is visibly a floor and a missing reading cannot masquerade as a quiet one. The
 * current hour is drawn at full strength with a two-pixel cap; earlier hours are the same ink at
 * half opacity, which is the only place in the app where the accent is diluted. The bars rise once
 * on arrival and then hold: nothing here loops.
 */
final class TraceView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] values = new int[Telemetry.BUCKETS];
    private int peak = 1;
    private int accent, line, muted;
    private float grow = 1f;

    TraceView(Context context) {
        super(context);
    }

    void palette(Screen.Palette c) {
        accent = c.accent;
        line = c.line;
        muted = c.muted;
    }

    void setData(int[] buckets, int peak) {
        this.values = buckets == null ? new int[Telemetry.BUCKETS] : buckets;
        this.peak = Math.max(1, peak);
        invalidate();
    }

    /** One rise, on first attach, to the stored values. Never repeats. */
    void playGrow() {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(560L);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                grow = (Float) anim.getAnimatedValue();
                invalidate();
            }
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float baseline = h - dp(0.8f);
        float usable = h - dp(6);

        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(withAlpha(line, 150));
        for (int i = 1; i < 4; i++) {
            float x = w * i / 4f;
            canvas.drawLine(x, 0, x, baseline, paint);
        }

        int slots = values.length;
        float slot = w / slots;
        float barW = Math.max(dp(3), slot * 0.4f);

        for (int i = 0; i < slots; i++) {
            float left = slot * i + (slot - barW) / 2f;
            int v = values[i];
            boolean current = i == slots - 1;
            if (v <= 0) {
                paint.setColor(withAlpha(muted, 110));
                canvas.drawRect(left, baseline - dp(2), left + barW, baseline, paint);
                continue;
            }
            float height = Math.max(dp(2), usable * (v / (float) peak) * grow);
            paint.setColor(current ? accent : withAlpha(accent, 130));
            canvas.drawRect(left, baseline - height, left + barW, baseline, paint);
            if (current) {
                canvas.drawRect(left, baseline - height - dp(2), left + barW,
                        baseline - height, paint);
            }
        }

        paint.setColor(line);
        canvas.drawRect(0, baseline, w, baseline + dp(0.8f), paint);
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | (alpha << 24);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
