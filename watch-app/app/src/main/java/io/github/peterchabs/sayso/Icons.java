package io.github.peterchabs.sayso;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small icons drawn as shapes, so the app never depends on emoji fonts. */
final class Icons extends Drawable {
    static final int PLAY = 1, HEART = 2, MIC = 3, BACK = 4;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean disc;

    /** {@code color} draws the shape; {@code discColor} (0 for none) draws a circle behind it. */
    Icons(int kind, int color, int discColor) {
        this.kind = kind;
        p.setColor(color);
        bg.setColor(discColor);
        disc = discColor != 0;
    }

    @Override
    public void draw(Canvas c) {
        RectF b = new RectF(getBounds());
        float s = Math.min(b.width(), b.height());
        float cx = b.centerX(), cy = b.centerY();
        if (disc) c.drawCircle(cx, cy, s / 2f, bg);
        Path path = new Path();
        switch (kind) {
            case PLAY: {
                float r = s * 0.22f;
                path.moveTo(cx - r * 0.8f, cy - r);
                path.lineTo(cx + r * 1.1f, cy);
                path.lineTo(cx - r * 0.8f, cy + r);
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            }
            case HEART: {
                float w = s * (disc ? 0.55f : 0.9f);
                float x = cx - w / 2f, y = cy - w * 0.38f;
                path.moveTo(cx, y + w * 0.92f);
                path.cubicTo(x - w * 0.15f, y + w * 0.45f, x + w * 0.1f, y - w * 0.15f, cx, y + w * 0.18f);
                path.cubicTo(x + w * 0.9f, y - w * 0.15f, x + w * 1.15f, y + w * 0.45f, cx, y + w * 0.92f);
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            }
            case MIC: {
                float u = s * (disc ? 0.075f : 0.12f);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(cx - 1.4f * u, cy - 3.2f * u, cx + 1.4f * u, cy + 0.9f * u), 1.4f * u, 1.4f * u, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(0.7f * u);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(new RectF(cx - 2.4f * u, cy - 2.2f * u, cx + 2.4f * u, cy + 2.2f * u), 0, 180, false, p);
                c.drawLine(cx, cy + 2.2f * u, cx, cy + 3.4f * u, p);
                break;
            }
            case BACK: {
                float r = s * 0.18f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.09f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                path.moveTo(cx + r * 0.5f, cy - r);
                path.lineTo(cx - r * 0.5f, cy);
                path.lineTo(cx + r * 0.5f, cy + r);
                c.drawPath(path, p);
                break;
            }
            default:
                break;
        }
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); bg.setAlpha(a); }

    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
