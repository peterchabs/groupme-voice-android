package io.github.peterchabs.sayso;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A full-screen picture you can pinch to zoom, drag around once zoomed, and double-tap to zoom
 * in on a spot (or back out). The frame stays put and reads the fingers; only the picture inside
 * moves (scale and translation, so the GPU does the work). A single tap still counts as a click.
 */
final class ZoomImageView extends FrameLayout {
    static final float MAX = 5f, DOUBLE_TAP = 2.5f;

    /** Told when the picture goes from fitting the screen to zoomed in, and back. */
    interface Listener { void onZoomed(boolean zoomed); }

    final ImageView image;
    private final ScaleGestureDetector pinch;
    private final GestureDetector taps;
    private float s = 1f, tx, ty;
    private boolean wasZoomed;
    private Listener listener;

    ZoomImageView(Context c) {
        super(c);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); // the whole picture shows; zoom in for detail
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(s * d.getScaleFactor(), d.getFocusX(), d.getFocusY(), false);
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false); // double-tap is handled below, as a jump
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoomed()) reset(true);
                else zoomAround(DOUBLE_TAP, e.getX(), e.getY(), true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (!zoomed() || pinch.isInProgress()) return false;
                tx -= dx;
                ty -= dy;
                apply(false);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (!zoomed() || pinch.isInProgress()) return false;
                tx += vx * 0.18f; // a short glide in the direction of the flick
                ty += vy * 0.18f;
                apply(true);
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    boolean zoomed() { return s > 1.02f; }

    /** Back to fitting the screen. */
    void reset(boolean animate) {
        s = 1f;
        tx = ty = 0;
        apply(animate);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) { return true; } // the frame handles every touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        taps.onTouchEvent(e);
        int a = e.getActionMasked();
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && s < fitScale() - 0.001f) {
            s = fitScale(); // pinched past the whole picture: settle on showing all of it
            tx = ty = 0;
            apply(true);
        }
        return true;
    }

    /** The picture's size on screen at scale 1 (as the image view draws it), or the screen if unknown. */
    private float[] coverSize() {
        Drawable d = image.getDrawable();
        float w = getWidth(), h = getHeight();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0 || w <= 0 || h <= 0) return new float[]{w, h};
        float kw = w / d.getIntrinsicWidth(), kh = h / d.getIntrinsicHeight();
        float k = image.getScaleType() == ImageView.ScaleType.CENTER_CROP ? Math.max(kw, kh) : Math.min(kw, kh);
        return new float[]{d.getIntrinsicWidth() * k, d.getIntrinsicHeight() * k};
    }

    /** The scale that shows the whole picture (at most 1: it already fills the screen at 1). */
    float fitScale() {
        float[] c = coverSize();
        if (c[0] <= 0 || c[1] <= 0) return 1f;
        return Math.min(1f, Math.min(getWidth() / c[0], getHeight() / c[1]));
    }

    /** Scale to {@code to}, keeping the spot under the fingers (fx, fy) where it is. */
    private void zoomAround(float to, float fx, float fy, boolean animate) {
        to = Math.max(fitScale() * 0.85f, Math.min(MAX, to));
        float cx = getWidth() / 2f, cy = getHeight() / 2f, k = to / s;
        // screen = center + t + s * (point - center); solve for the t that keeps (fx, fy) fixed
        tx = fx - cx - k * (fx - cx - tx);
        ty = fy - cy - k * (fy - cy - ty);
        s = to;
        apply(animate);
    }

    /** Keeps the picture covering the screen where it can: no panning off into empty black. */
    private void clamp() {
        float w = getWidth(), h = getHeight();
        float[] c = coverSize(); // the picture's size on screen before zooming
        float mx = Math.max(0, (c[0] * s - w) / 2f);
        float my = Math.max(0, (c[1] * s - h) / 2f);
        tx = Math.max(-mx, Math.min(mx, tx));
        ty = Math.max(-my, Math.min(my, ty));
    }

    private void apply(boolean animate) {
        clamp();
        image.animate().cancel();
        if (animate) {
            image.animate().scaleX(s).scaleY(s).translationX(tx).translationY(ty).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        } else {
            image.setScaleX(s);
            image.setScaleY(s);
            image.setTranslationX(tx);
            image.setTranslationY(ty);
        }
        boolean z = zoomed();
        if (z != wasZoomed) {
            wasZoomed = z;
            if (listener != null) listener.onZoomed(z);
        }
    }
}
