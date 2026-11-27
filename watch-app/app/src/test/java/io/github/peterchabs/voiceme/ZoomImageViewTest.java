package io.github.peterchabs.voiceme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Pinch, double-tap and drag on the full-screen picture, with real Android gesture detectors. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ZoomImageViewTest {
    private static final int W = 720, H = 1520;
    private ZoomImageView z;
    private long t;

    @Before public void setUp() {
        z = new ZoomImageView(RuntimeEnvironment.getApplication());
        z.image.setImageBitmap(Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)); // square: fills the width
        z.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        z.layout(0, 0, W, H);
        t = 1000;
    }

    /** One touch event with one or two fingers at the given x positions (all at the same height). */
    private void touch(int action, float y, float... xs) {
        int n = xs.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = y;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent e = MotionEvent.obtain(1000, t, action, n, props, coords, 0, 0, 1, 1, 0, 0, 0, 0);
        z.dispatchTouchEvent(e);
        e.recycle();
        t += 16;
    }

    private void pinchOut() {
        touch(MotionEvent.ACTION_DOWN, 760, 310);
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 760, 310, 410);
        for (int d = 60; d <= 300; d += 20) touch(MotionEvent.ACTION_MOVE, 760, 360 - d, 360 + d);
        touch(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 760, 60, 660);
        touch(MotionEvent.ACTION_UP, 760, 60);
    }

    @Test public void pinchingOutZoomsIn() {
        assertFalse(z.zoomed());
        pinchOut();
        assertTrue("zoomed after a pinch", z.zoomed());
        assertTrue("scale " + z.image.getScaleX(), z.image.getScaleX() > 3f);
        assertEquals(z.image.getScaleX(), z.image.getScaleY(), 0.001f);
    }

    @Test public void neverZoomsPastTheMaximum() {
        pinchOut();
        pinchOut();
        assertEquals(ZoomImageView.MAX, z.image.getScaleX(), 0.001f);
    }

    @Test public void dragPansOnlyWhileZoomedAndStopsAtTheEdge() {
        touch(MotionEvent.ACTION_DOWN, 760, 360); // not zoomed: a drag moves nothing
        touch(MotionEvent.ACTION_MOVE, 760, 500);
        touch(MotionEvent.ACTION_UP, 760, 500);
        assertEquals(0f, z.image.getTranslationX(), 0.001f);

        pinchOut();
        float s = z.image.getScaleX();
        touch(MotionEvent.ACTION_DOWN, 760, 360);
        for (int x = 380; x <= 6000; x += 200) touch(MotionEvent.ACTION_MOVE, 760, x); // far past the edge
        touch(MotionEvent.ACTION_UP, 760, 6000);
        float edge = (W * s - W) / 2f; // a square picture shown whole on a tall screen is exactly W wide
        assertTrue("panned right", z.image.getTranslationX() > 0);
        assertTrue("kept the picture on screen: " + z.image.getTranslationX() + " <= " + edge,
                z.image.getTranslationX() <= edge + 0.5f);
    }

    @Test public void singleTapStillClicks() {
        final int[] clicks = {0};
        z.setOnClickListener(v -> clicks[0]++);
        touch(MotionEvent.ACTION_DOWN, 760, 360);
        touch(MotionEvent.ACTION_UP, 760, 360);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, clicks[0]);
    }

    @Test public void showsTheWholePictureAndPinchingInSpringsBack() {
        assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, z.image.getScaleType());
        touch(MotionEvent.ACTION_DOWN, 760, 60);
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 760, 60, 660);
        for (int d = 280; d >= 20; d -= 20) touch(MotionEvent.ACTION_MOVE, 760, 360 - d, 360 + d);
        touch(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 760, 340, 380);
        touch(MotionEvent.ACTION_UP, 760, 340);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1f, z.image.getScaleX(), 0.01f); // already whole: it settles back to fit
        assertFalse(z.zoomed()); // so sideways swipes still change pages
    }

    @Test public void doubleTapZoomsInThenBackOut() {
        for (int round = 0; round < 2; round++) {
            touch(MotionEvent.ACTION_DOWN, 760, 360);
            touch(MotionEvent.ACTION_UP, 760, 360);
            t += 60;
            touch(MotionEvent.ACTION_DOWN, 760, 360);
            touch(MotionEvent.ACTION_UP, 760, 360);
            org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            t += 1000;
            if (round == 0) {
                assertTrue(z.zoomed());
                assertEquals(ZoomImageView.DOUBLE_TAP, z.image.getScaleX(), 0.01f);
            } else {
                assertFalse(z.zoomed());
                assertEquals(1f, z.image.getScaleX(), 0.01f);
            }
        }
    }

    @Test public void zoomListenerHearsInAndOut() {
        final StringBuilder heard = new StringBuilder();
        z.setListener(zoomed -> heard.append(zoomed ? "in " : "out "));
        pinchOut();
        z.reset(false);
        assertEquals("in out ", heard.toString());
        assertFalse(z.zoomed());
        assertEquals(1f, z.image.getScaleX(), 0.001f);
    }
}
