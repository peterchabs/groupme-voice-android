package io.github.peterchabs.groupmevoice;

import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;

/**
 * Renders the real app screens to PNGs for the README. Only runs when SCREENSHOTS_DIR is set:
 *   SCREENSHOTS_DIR=/some/folder gradle testReleaseUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w225dp-h225dp-round-xhdpi") // 450 x 450, like a 44 mm Galaxy Watch
public class ScreenshotTest {
    private File out;

    @Before public void setUp() {
        String dir = System.getenv("SCREENSHOTS_DIR");
        assumeTrue(dir != null && !dir.isEmpty());
        out = new File(dir);
        out.mkdirs();
        try { // the renderer defaults animations to zero length; play them at normal speed
            java.lang.reflect.Method m = android.animation.ValueAnimator.class.getMethod("setDurationScale", float.class);
            m.invoke(null, 1f);
        } catch (Exception ignored) { }
    }

    private static void signIn(Context c) {
        Store.setToken(c, "example");
        Store.setName(c, "Alex");
    }

    private static void advance(long ms) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static Stage stageOf(Activity a) throws Exception {
        Field f = a.getClass().getDeclaredField("stage");
        f.setAccessible(true);
        return (Stage) f.get(a);
    }

    private void save(Activity a, String name) throws Exception {
        shadowOf(Looper.getMainLooper());
        View root = a.getWindow().getDecorView();
        int size = 450;
        root.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, size, size);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        try (FileOutputStream os = new FileOutputStream(new File(out, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    private static void shadowOf(Looper l) {
        org.robolectric.Shadows.shadowOf(l).idle();
    }

    private Activity main() {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        signIn(c);
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        advance(400);
        return a;
    }

    @Test public void listening() throws Exception {
        Activity a = main();
        Stage s = stageOf(a);
        s.show(OrbView.Mode.LISTEN, "Listening", "Say: [group] that says [message]");
        advance(500);
        s.live("Weekend Crew that says running 10 minutes late");
        s.orb.setLevel(9f);
        advance(380);
        s.orb.setLevel(9f);
        advance(60);
        save(a, "1-listening");
    }

    @Test public void countdown() throws Exception {
        Activity a = main();
        Stage s = stageOf(a);
        s.show(OrbView.Mode.COUNTDOWN, "To Weekend Crew", "“running 10 minutes late”",
                "Cancel", (Runnable) () -> { }, "Send now", (Runnable) () -> { });
        advance(400);
        Field pf = OrbView.class.getDeclaredField("progress"); // freeze the ring partway down
        pf.setAccessible(true);
        Field once = OrbView.class.getDeclaredField("once");
        once.setAccessible(true);
        ((android.animation.ValueAnimator) once.get(s.orb)).cancel();
        pf.setFloat(s.orb, 0.4f);
        s.orb.invalidate();
        save(a, "2-countdown");
    }

    @Test public void sent() throws Exception {
        Activity a = main();
        Stage s = stageOf(a);
        s.show(OrbView.Mode.DONE, "Sent to Weekend Crew", null);
        advance(900);
        save(a, "3-sent");
    }

    @Test public void signInCode() throws Exception {
        Activity a = Robolectric.buildActivity(SetupActivity.class).setup().get();
        advance(700);
        save(a, "4-sign-in");
    }

    @Test public void signedIn() throws Exception {
        Activity a = Robolectric.buildActivity(SetupActivity.class).setup().get();
        Stage s = stageOf(a);
        s.showOrb();
        s.show(OrbView.Mode.DONE, "Hi, Alex!",
                "Found 12 groups. Now say \"Hey Google, open GroupMe Voice\".",
                "Try it now", (Runnable) () -> { });
        advance(900);
        save(a, "5-signed-in");
    }

    @Test public void notSure() throws Exception {
        Activity a = main();
        Stage s = stageOf(a);
        s.show(OrbView.Mode.ERROR, "Which one? Book Club or Book Club Planning. Say more of the name.",
                "Nothing was sent.", "Try again", (Runnable) () -> { }, "Settings", (Runnable) () -> { });
        advance(700);
        save(a, "6-which-one");
    }
}
