package io.github.peterchabs.voiceme;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * The animated circle at the top of every screen.
 * LISTEN: rings ripple out, and grow with your voice.  BUSY: spinning arc.
 * COUNTDOWN: ring drains over the send delay.  DONE: green pop + drawn check.  ERROR: red shake.
 */
public class OrbView extends View {
    public enum Mode { IDLE, LISTEN, BUSY, COUNTDOWN, DONE, ERROR }

    // GroupMe's brand blue (#00AFF0, from groupme.com) on white.
    static final int ACCENT = 0xFF00AFF0, OK = 0xFF00AFF0, BAD = 0xFFE53935, CORE = 0xFFE6F7FE;

    private Mode mode = Mode.IDLE;
    private float level, phase, progress;
    private long countdownMs = 3000;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path path = new Path(), part = new Path();
    private final ValueAnimator loop = ValueAnimator.ofFloat(0f, 1f);
    private ValueAnimator once;

    public OrbView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        glyph.setColor(0xFFFFFFFF);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        loop.setDuration(1600);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            level *= 0.94f; // let the voice glow settle between mic readings
            invalidate();
        });
    }

    public Mode mode() { return mode; }

    public void setCountdownMs(long ms) { countdownMs = ms; }

    public void setMode(Mode m) {
        if (once != null) once.cancel();
        mode = m;
        progress = 0f;
        if (m == Mode.COUNTDOWN) runOnce(countdownMs, new LinearInterpolator());
        if (m == Mode.DONE) {
            runOnce(520, new DecelerateInterpolator());
            pop();
        }
        if (m == Mode.ERROR) {
            ObjectAnimator s = ObjectAnimator.ofFloat(this, View.TRANSLATION_X, 0, -16, 16, -11, 11, -5, 0);
            s.setDuration(440);
            s.start();
        }
        if (m == Mode.LISTEN) pop();
        invalidate();
    }

    /** Feed SpeechRecognizer's onRmsChanged dB value here (about -2 quiet to 10 loud). */
    public void setLevel(float db) {
        float target = Math.max(0f, Math.min(1f, (db + 2f) / 12f));
        level = Math.max(level, level * 0.5f + target * 0.5f);
    }

    private void runOnce(long ms, android.animation.TimeInterpolator in) {
        once = ValueAnimator.ofFloat(0f, 1f);
        once.setDuration(ms);
        once.setInterpolator(in);
        once.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        once.start();
    }

    private ObjectAnimator popAnim;

    /** Little bounce. Uses its own animator so it never cancels a fade running on this view. */
    private void pop() {
        if (popAnim != null) popAnim.cancel();
        popAnim = ObjectAnimator.ofPropertyValuesHolder(this,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.86f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.86f, 1f));
        popAnim.setDuration(380);
        popAnim.setInterpolator(new OvershootInterpolator(2.2f));
        popAnim.start();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); loop.start(); }

    @Override protected void onDetachedFromWindow() {
        loop.cancel();
        if (once != null) once.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.62f;
        float sw = r * 0.09f;
        stroke.setStrokeWidth(sw);

        switch (mode) {
            case LISTEN: {
                stroke.setColor(ACCENT);
                for (int i = 0; i < 3; i++) {
                    float t = (phase + i / 3f) % 1f;
                    stroke.setAlpha((int) ((1f - t) * (90 + 140 * level)));
                    c.drawCircle(cx, cy, r * (1f + t * (0.25f + 0.35f * level)), stroke);
                }
                fill.setColor(ACCENT);
                c.drawCircle(cx, cy, r * (0.96f + 0.1f * level), fill);
                mic(c, cx, cy, r, 0xFFFFFFFF);
                break;
            }
            case BUSY: {
                core(c, cx, cy, r);
                stroke.setColor(ACCENT);
                stroke.setAlpha(255);
                inset(cx, cy, r);
                c.drawArc(oval, phase * 360f, 110f, false, stroke);
                break;
            }
            case COUNTDOWN: {
                core(c, cx, cy, r);
                inset(cx, cy, r);
                stroke.setColor(0x2200AFF0);
                c.drawArc(oval, 0, 360, false, stroke);
                stroke.setColor(ACCENT);
                c.drawArc(oval, -90f, 360f * (1f - progress), false, stroke);
                arrow(c, cx, cy, r);
                break;
            }
            case DONE: {
                fill.setColor(OK);
                c.drawCircle(cx, cy, r, fill);
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(r * 0.14f);
                path.reset();
                path.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                path.lineTo(cx - r * 0.12f, cy + r * 0.32f);
                path.lineTo(cx + r * 0.45f, cy - r * 0.3f);
                PathMeasure pm = new PathMeasure(path, false);
                part.reset();
                pm.getSegment(0, pm.getLength() * progress, part, true);
                c.drawPath(part, glyph);
                break;
            }
            case ERROR: {
                fill.setColor(BAD);
                c.drawCircle(cx, cy, r, fill);
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(r * 0.15f);
                c.drawLine(cx, cy - r * 0.42f, cx, cy + r * 0.12f, glyph);
                glyph.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy + r * 0.4f, r * 0.09f, glyph);
                break;
            }
            default: { // IDLE: slow breathing
                float b = (float) Math.sin(phase * Math.PI * 2) * 0.03f;
                core(c, cx, cy, r * (1f + b));
                stroke.setColor(ACCENT);
                stroke.setAlpha(150);
                c.drawCircle(cx, cy, r * (1f + b), stroke);
                mic(c, cx, cy, r, ACCENT);
            }
        }
    }

    private void core(Canvas c, float cx, float cy, float r) {
        fill.setColor(CORE);
        c.drawCircle(cx, cy, r, fill);
    }

    private void inset(float cx, float cy, float r) {
        float d = r * 0.82f;
        oval.set(cx - d, cy - d, cx + d, cy + d);
    }

    private void mic(Canvas c, float cx, float cy, float r, int color) {
        glyph.setColor(color);
        glyph.setStyle(Paint.Style.FILL);
        float w = r * 0.19f, top = cy - r * 0.5f, bot = cy + r * 0.08f;
        oval.set(cx - w, top, cx + w, bot);
        c.drawRoundRect(oval, w, w, glyph);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(r * 0.08f);
        float a = r * 0.33f;
        oval.set(cx - a, cy - r * 0.42f, cx + a, cy + r * 0.24f);
        c.drawArc(oval, 20, 140, false, glyph);
        c.drawLine(cx, cy + r * 0.24f, cx, cy + r * 0.45f, glyph);
        glyph.setColor(0xFFFFFFFF);
    }

    private void arrow(Canvas c, float cx, float cy, float r) {
        glyph.setColor(ACCENT);
        glyph.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(cx - r * 0.34f, cy - r * 0.3f);
        path.lineTo(cx + r * 0.4f, cy);
        path.lineTo(cx - r * 0.34f, cy + r * 0.3f);
        path.lineTo(cx - r * 0.2f, cy);
        path.close();
        c.drawPath(path, glyph);
        glyph.setColor(0xFFFFFFFF);
    }
}
