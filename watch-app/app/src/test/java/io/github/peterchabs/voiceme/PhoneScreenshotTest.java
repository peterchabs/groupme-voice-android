package io.github.peterchabs.voiceme;

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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

/** Real phone-app screens for the README (phone build only, SCREENSHOTS_DIR set). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w384dp-h832dp-xhdpi") // 768 x 1664, a modern 20:9 phone
public class PhoneScreenshotTest {
    private static final int W = 768, H = 1664;
    private File out;

    @Before public void setUp() {
        String dir = System.getenv("SCREENSHOTS_DIR");
        assumeTrue(dir != null && !dir.isEmpty());
        assumeTrue("phone".equals(BuildConfig.FLAVOR));
        out = new File(dir);
        out.mkdirs();
        // SCREENSHOTS_THEME=dark renders the phone in dark mode (the phone app follows the phone)
        if ("dark".equals(System.getenv("SCREENSHOTS_THEME"))) RuntimeEnvironment.setQualifiers("+night");
        try {
            android.animation.ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 1f);
        } catch (Exception ignored) { }
    }

    private static void advance(long ms) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void save(Activity a, String name) throws Exception {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); // card screens keep a clear background
        root.draw(new Canvas(b));
        try (FileOutputStream os = new FileOutputStream(new File(out, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    private static Stage stageOf(Activity a) throws Exception {
        Field f = a.getClass().getDeclaredField("stage");
        f.setAccessible(true);
        return (Stage) f.get(a);
    }

    private Activity main() {
        Context c = RuntimeEnvironment.getApplication();
        Store.setToken(c, "example");
        Store.setName(c, "Alex");
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        advance(500);
        return a;
    }

    @Test public void signIn() throws Exception {
        Activity a = Robolectric.buildActivity(SetupActivity.class).setup().get();
        advance(800);
        save(a, "p1-sign-in");
    }

    @Test public void account() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Store.setToken(c, "example");
        Store.setName(c, "Alex");
        Activity a = Robolectric.buildActivity(SetupActivity.class).setup().get();
        advance(800);
        save(a, "p5-account");
    }

    @Test public void watchCode() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Store.setToken(c, "example");
        Store.setName(c, "Alex");
        android.content.Intent i = new android.content.Intent(c, SetupActivity.class)
                .putExtra(SetupActivity.EXTRA_OPEN, "watch");
        Activity a = Robolectric.buildActivity(SetupActivity.class, i).setup().get();
        advance(800);
        save(a, "p6-watch-code");
    }

    @Test public void listening() throws Exception {
        Activity a = main();
        Stage s = stageOf(a);
        s.show(OrbView.Mode.LISTEN, "Listening", "Say your message and which group");
        advance(400);
        s.live("Tell Weekend Crew I'm running 10 minutes late");
        s.orb.setLevel(9f);
        advance(80);
        save(a, "p2-listening");
    }

    @Test public void countdown() throws Exception {
        Activity a = main();
        Method m = MainActivity.class.getDeclaredMethod("confirmGroup", Phrase.Group.class, String.class);
        m.setAccessible(true);
        m.invoke(a, new Phrase.Group("1", "Weekend Crew"), "running 10 minutes late");
        Method cancel = MainActivity.class.getDeclaredMethod("cancelPending");
        cancel.setAccessible(true);
        cancel.invoke(a);
        advance(400);
        Stage s = stageOf(a);
        Field once = OrbView.class.getDeclaredField("once");
        once.setAccessible(true);
        ((android.animation.ValueAnimator) once.get(s.orb)).cancel();
        Field pf = OrbView.class.getDeclaredField("progress");
        pf.setAccessible(true);
        pf.setFloat(s.orb, 0.4f);
        s.orb.invalidate();
        save(a, "p3-countdown");
    }

    @Test public void sent() throws Exception {
        Activity a = main();
        stageOf(a).show(OrbView.Mode.DONE, "Sent to Weekend Crew", null);
        advance(900);
        save(a, "p4-sent");
    }
}
