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
        save(a, name, true);
    }

    /** checkFit=false for scrolling lists, which run past the edge by design. */
    private void save(Activity a, String name, boolean checkFit) throws Exception {
        shadowOf(Looper.getMainLooper());
        View root = a.getWindow().getDecorView();
        int size = 450;
        root.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, size, size);
        java.util.List<String> bad = new java.util.ArrayList<>();
        if (checkFit) outside(root, root, size / 2f, bad);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        try (FileOutputStream os = new FileOutputStream(new File(out, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        if (!bad.isEmpty()) throw new AssertionError(name + ": past the round edge: " + bad);
    }


    /** Collects anything drawn outside the round screen (a circle filling the square). */
    private static void outside(View v, View root, float r, java.util.List<String> bad) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() == 0f) return;
        if (v instanceof android.view.ViewGroup && !(v instanceof OrbView)) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) outside(g.getChildAt(i), root, r, bad);
            return;
        }
        float x = 0, y = 0;
        for (View p = v; p != root && p != null; p = (View) p.getParent()) {
            x += p.getLeft() - (p == v ? 0 : p.getScrollX());
            y += p.getTop() - (p == v ? 0 : p.getScrollY());
            if (!(p.getParent() instanceof View)) break;
        }
        float w = v.getWidth(), h = v.getHeight();
        String label = v.getClass().getSimpleName();
        if (v instanceof android.widget.TextView) {
            label += " \"" + ((android.widget.TextView) v).getText() + "\"";
        }
        if (v instanceof android.widget.Button) {
            float k = h / 2f; // pill ends are round
            check(bad, label, r, x + k, y, x + w - k, y, x + k, y + h, x + w - k, y + h, x, y + k, x + w, y + k);
        } else if (v instanceof android.widget.TextView && !(v instanceof android.widget.EditText)) {
            android.widget.TextView t = (android.widget.TextView) v;
            android.text.Layout l = t.getLayout();
            if (l == null) return;
            for (int i = 0; i < l.getLineCount(); i++) {
                float lx = x + t.getTotalPaddingLeft(), ly = y + t.getTotalPaddingTop();
                float a = lx + l.getLineLeft(i), b = lx + l.getLineRight(i);
                float top = ly + l.getLineTop(i) + 4, bot = ly + l.getLineBottom(i) - 4;
                check(bad, label + " line " + (i + 1), r, a, top, b, top, a, bot, b, bot);
            }
        } else if (v instanceof OrbView) {
            float cx = x + w / 2f - r, cy = y + h / 2f - r;
            if (Math.hypot(cx, cy) + w / 2f > r) bad.add(label);
        } else if (w > 0 && h > 0) {
            check(bad, label, r, x, y, x + w, y, x, y + h, x + w, y + h);
        }
    }

    private static void check(java.util.List<String> bad, String label, float r, float... pts) {
        for (int i = 0; i < pts.length; i += 2) {
            if (Math.hypot(pts[i] - r, pts[i + 1] - r) > r - 2) {
                bad.add(label);
                return;
            }
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
        s.show(OrbView.Mode.ERROR, "Which one: Book Club or Book Club Planning?",
                "Say more of the name. Nothing was sent.", "Try again", (Runnable) () -> { }, "Settings", (Runnable) () -> { });
        advance(700);
        save(a, "6-which-one");
    }

    private static final java.util.List<Phrase.Group> GROUPS = java.util.Arrays.asList(
            new Phrase.Group("1", "Weekend Crew"), new Phrase.Group("2", "Family"),
            new Phrase.Group("3", "Book Club"), new Phrase.Group("4", "Soccer Parents"),
            new Phrase.Group("5", "Roommates"));

    @Test public void listeningWithDefault() throws Exception {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        Store.setDefaultGroup(c, GROUPS.get(1));
        Activity a = main();
        Stage s = stageOf(a);
        s.show(OrbView.Mode.LISTEN, "Listening", "Say your message for Family, or [group] that says [message]");
        advance(500);
        s.live("picking up pizza on the way home");
        s.orb.setLevel(8f);
        advance(80);
        save(a, "7-default-listening");
        Store.setDefaultGroup(c, null);
    }

    @Test public void settings() throws Exception {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        signIn(c);
        Store.setDefaultGroup(c, GROUPS.get(1));
        Activity a = Robolectric.buildActivity(SetupActivity.class).setup().get();
        advance(900);
        save(a, "8-settings");
        Store.setDefaultGroup(c, null);
    }

    @Test public void picker() throws Exception {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        signIn(c);
        Store.saveGroups(c, GROUPS);
        Store.setDefaultGroup(c, GROUPS.get(1));
        Activity a = Robolectric.buildActivity(SetupActivity.class).setup().get();
        java.lang.reflect.Method m = SetupActivity.class.getDeclaredMethod("pickDefault");
        m.setAccessible(true);
        m.invoke(a);
        advance(900);
        View root = a.getWindow().getDecorView(); // lay out at watch size so the list scrolls to Family
        root.measure(View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 450, 450);
        m.invoke(a);
        advance(900);
        save(a, "9-default-picker", false);
        Store.setDefaultGroup(c, null);
    }
}
