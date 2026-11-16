package io.github.shreyashsharmabyte.notifycleaner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The gauge: the page's one instrument, and the only moving thing on it.
 *
 * <p>Sixty bezel ticks are a scale, and the number of lit ticks is this hour's removals against the
 * busiest hour of the twelve-hour window — the same numbers the load sentence beneath it prints. The
 * face is left clear because the reading is set inside it: the hour's figure, its unit and the
 * instrument's own state word are real text laid over this view, so they inherit the app's type and
 * scale with the dial instead of being painted at a fixed size.
 *
 * <p>The pointer rides the ring outside the face, never across it, and turns only while filtering is
 * actually running. A still pointer at twelve is therefore a true reading of "nothing is being
 * judged", and it is never used to report setup progress: the checklist screen carries no dial.
 *
 * <p>Drawn, never shipped: no asset, no gradient, no glow. Brightness on the ring is information.
 */
final class DialView extends View {

    static final int REST = 0;
    static final int RUNNING = 1;

    private static final int TICKS = 60;
    private static final float SWEEP_MS = 9000f;

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path blade = new Path();

    private int state = REST;
    private float load;
    private int accent, line, muted, surface;

    private ValueAnimator sweep;
    private float sweepDeg;

    DialView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        // The reading is laid over this view as real text, and the frame around it carries the
        // accessible description. The drawing itself is decoration.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        tick.setStrokeCap(Paint.Cap.BUTT);
        pointer.setStyle(Paint.Style.FILL);
        pointer.setStrokeJoin(Paint.Join.MITER);
    }

    /** Colours come from the Editorial palette; nothing here carries its own. */
    void palette(Screen.Palette c) {
        accent = c.accent;
        line = c.line;
        muted = c.muted;
        surface = c.surface;
    }

    /**
     * @param state {@link #REST} or {@link #RUNNING}
     * @param load  0..1, this hour against the peak hour of the window
     */
    void set(int state, float load) {
        this.state = state;
        this.load = Math.max(0f, Math.min(1f, load));
        boolean live = state == RUNNING;
        if (live && sweep == null) startSweep();
        if (!live) stopSweep();
        invalidate();
    }

    private void startSweep() {
        if (sweep != null) return;
        // An infinite spinner is motion, not information. A reader who has asked the system to
        // remove animations gets a still pointer, which is still true: it is not judging silently.
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        sweep = ValueAnimator.ofFloat(0f, 360f);
        sweep.setDuration((long) SWEEP_MS);
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                sweepDeg = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        sweep.start();
    }

    private void stopSweep() {
        if (sweep != null) {
            sweep.cancel();
            sweep = null;
        }
        sweepDeg = 0f;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Do not keep a looping animator alive behind another activity.
        if (visibility != VISIBLE) {
            stopSweep();
        } else if (state == RUNNING) {
            startSweep();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float outer = Math.min(cx, cy) - dp(2);
        boolean live = state == RUNNING;
        int lit = Math.round(load * TICKS);

        // Bezel: a scale, read clockwise from twelve.
        for (int i = 0; i < TICKS; i++) {
            double a = Math.toRadians(-90 + i * (360.0 / TICKS));
            boolean major = i % 5 == 0;
            float inner = outer - outer * (major ? 0.10f : 0.055f);
            tick.setColor(i < lit ? accent : line);
            tick.setStrokeWidth(major ? Math.max(dp(1.2f), outer * 0.014f)
                    : Math.max(dp(0.8f), outer * 0.008f));
            canvas.drawLine(cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer,
                    cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner, tick);
        }

        // Face: left empty on purpose. The reading is laid over it as text.
        float discR = outer * 0.76f;
        face.setStyle(Paint.Style.FILL);
        face.setColor(surface);
        canvas.drawCircle(cx, cy, discR, face);
        face.setStyle(Paint.Style.STROKE);
        face.setStrokeWidth(Math.max(dp(0.7f), outer * 0.008f));
        face.setColor(line);
        canvas.drawCircle(cx, cy, discR, face);
        face.setStyle(Paint.Style.FILL);

        // Pointer: a blade in the ring between the face and the scale. Still at twelve at rest.
        double a = Math.toRadians((live ? sweepDeg : 0f) - 90);
        float dirX = (float) Math.cos(a);
        float dirY = (float) Math.sin(a);
        float r1 = outer * 0.80f;
        float r2 = outer * 0.885f;
        float halfWidth = outer * 0.022f;
        blade.reset();
        blade.moveTo(cx + dirX * r2, cy + dirY * r2);
        blade.lineTo(cx + dirX * r1 - dirY * halfWidth, cy + dirY * r1 + dirX * halfWidth);
        blade.lineTo(cx + dirX * r1 + dirY * halfWidth, cy + dirY * r1 - dirX * halfWidth);
        blade.close();
        pointer.setColor(live ? accent : muted);
        canvas.drawPath(blade, pointer);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
