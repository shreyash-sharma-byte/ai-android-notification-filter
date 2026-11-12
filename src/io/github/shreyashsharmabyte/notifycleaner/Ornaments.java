package io.github.shreyashsharmabyte.notifycleaner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The drawn furniture of the Editorial direction: a printer's fleuron, a toggle, a selection ring
 * and the empty-state overture. All four are Canvas-only, so the app ships no images and no fonts.
 */
final class Ornaments {

    private Ornaments() { }

    /**
     * An eight-spoke printer's mark, the app's signature. Drawn as tapered hairlines so it holds at
     * 13dp and at 96dp, and inherits the accent so it can be the only colour on a page.
     */
    static final class Fleuron extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int colour;
        private final int size;

        Fleuron(Context ctx, int colour, int sizePx) {
            super(ctx);
            this.colour = colour;
            this.size = sizePx;
            paint.setColor(colour);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            dot.setColor(colour);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            paint.setStrokeWidth(Math.max(1f, r * 0.085f));
            float inner = r * 0.20f;
            float outer = r * 0.94f;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                canvas.drawLine(cx + (float) Math.cos(a) * inner,
                        cy + (float) Math.sin(a) * inner,
                        cx + (float) Math.cos(a) * outer,
                        cy + (float) Math.sin(a) * outer, paint);
            }
            canvas.drawCircle(cx, cy, r * 0.13f, dot);
        }
    }

    /**
     * A pill toggle drawn by hand: hairline when off, filled accent when on, thumb slides in 180ms.
     * The platform switch is the one widget that would have made this look like a settings app.
     */
    static final class Toggle extends View {
        interface Listener { void onChecked(boolean on); }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accent, onAccent, line, surface, muted;
        private final float density;
        private boolean checked;
        private float fraction;
        private Listener listener;
        private ValueAnimator anim;

        Toggle(Context ctx, int accent, int onAccent, int line, int surface, int muted) {
            super(ctx);
            this.accent = accent;
            this.onAccent = onAccent;
            this.line = line;
            this.surface = surface;
            this.muted = muted;
            this.density = getResources().getDisplayMetrics().density;
            setClickable(true);
            setFocusable(true);
            track.setStyle(Paint.Style.FILL_AND_STROKE);
        }

        void setListener(Listener l) { this.listener = l; }

        void setChecked(boolean on, boolean animate) {
            if (checked == on) { fraction = on ? 1f : 0f; invalidate(); return; }
            checked = on;
            if (anim != null) anim.cancel();
            if (!animate) {
                fraction = on ? 1f : 0f;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(fraction, on ? 1f : 0f);
            anim.setDuration(180L);
            anim.setInterpolator(new DecelerateInterpolator(1.4f));
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    fraction = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        boolean isChecked() { return checked; }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) (44 * density), (int) (26 * density));
        }

        @Override
        public boolean performClick() {
            super.performClick();
            setChecked(!checked, true);
            if (listener != null) listener.onChecked(checked);
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            float inset = density * 0.7f;

            track.setStyle(Paint.Style.FILL);
            track.setColor(surface);
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, track);

            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(Math.max(1f, density * 0.9f));
            track.setColor(Screen.mix(line, accent, fraction));
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, track);

            if (fraction > 0.02f) {
                track.setStyle(Paint.Style.FILL);
                track.setColor(Screen.alpha(accent, fraction));
                canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, track);
            }

            float pad = density * 3.5f;
            float tr = r - pad;
            float left = pad + tr;
            float right = w - pad - tr;
            float cx = left + (right - left) * fraction;
            thumb.setColor(Screen.mix(muted, onAccent, fraction));
            canvas.drawCircle(cx, h / 2f, tr - density * 0.4f, thumb);
        }
    }

    /** Selection mark for the strictness list: a hairline ring, filled with the accent when chosen. */
    static final class Ring extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accent, line;
        private final float density;
        private boolean selected;

        Ring(Context ctx, int accent, int line, boolean selected) {
            super(ctx);
            this.accent = accent;
            this.line = line;
            this.selected = selected;
            this.density = getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) (22 * density), (int) (22 * density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - density;
            ring.setStrokeWidth(Math.max(1f, density * (selected ? 1.4f : 0.9f)));
            ring.setColor(selected ? accent : line);
            canvas.drawCircle(cx, cy, r, ring);
            if (selected) {
                fill.setColor(accent);
                canvas.drawCircle(cx, cy, r * 0.46f, fill);
            }
        }
    }

    /**
     * A left edge that is exactly as tall as the view it backs. Used for the disclosure's
     * pull-quote, where a sibling hairline would have had to be measured against a wrapped
     * paragraph and could collapse to nothing.
     */
    static final class LeftRule extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int width;
        private int alpha = 255;

        LeftRule(int colour, int widthPx) {
            paint.setColor(colour);
            this.width = Math.max(1, widthPx);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            paint.setAlpha(alpha);
            canvas.drawRect(b.left, b.top, b.left + width, b.bottom, paint);
        }

        @Override
        public void setAlpha(int a) { alpha = a; }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /**
     * A large, very quiet ring with the fleuron inside: the empty state of the removed log. Drawn
     * once, 120dp, in the faintest ink the palette has.
     */
    static final class Overture extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int colour;
        private final float density;

        Overture(Context ctx, int colour) {
            super(ctx);
            this.colour = colour;
            this.density = getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            mark.setStyle(Paint.Style.STROKE);
            mark.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) (116 * density), (int) (116 * density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - density;
            ring.setStrokeWidth(Math.max(1f, density * 0.8f));
            ring.setColor(colour);
            canvas.drawCircle(cx, cy, r, ring);
            ring.setStrokeWidth(Math.max(1f, density * 0.5f));
            canvas.drawCircle(cx, cy, r * 0.80f, ring);

            mark.setStrokeWidth(Math.max(1f, density * 1.1f));
            mark.setColor(colour);
            float ir = r * 0.16f, or = r * 0.40f;
            Path path = new Path();
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 4 * i;
                path.moveTo(cx + (float) Math.cos(a) * ir, cy + (float) Math.sin(a) * ir);
                path.lineTo(cx + (float) Math.cos(a) * or, cy + (float) Math.sin(a) * or);
                path.moveTo(cx - (float) Math.cos(a) * ir, cy - (float) Math.sin(a) * ir);
                path.lineTo(cx - (float) Math.cos(a) * or, cy - (float) Math.sin(a) * or);
            }
            canvas.drawPath(path, mark);
        }
    }
}
