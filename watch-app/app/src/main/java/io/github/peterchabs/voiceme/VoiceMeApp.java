package io.github.peterchabs.voiceme;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

/**
 * Android 15+ draws every app edge to edge, under the status bar, the navigation bar and the
 * keyboard. On phones, pad each screen's content by those bars so nothing hides behind them, and
 * by the keyboard so text fields stay visible. Round watch screens report no bars, so they're left alone.
 */
public class VoiceMeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < 35 || Stage.isWatchBuild()) return;
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { padForBars(a); }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    static void padForBars(Activity a) {
        View content = a.getWindow().getDecorView().findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            matchScreenColour(a, (android.view.ViewGroup) v);
            return in;
        });
        content.requestApplyInsets();
    }

    /** The strips under the bars take the screen's own colour, with dark icons on a light screen. */
    private static void matchScreenColour(Activity a, android.view.ViewGroup content) {
        if (content.getChildCount() == 0) return;
        android.graphics.drawable.Drawable bg = content.getChildAt(0).getBackground();
        if (!(bg instanceof android.graphics.drawable.ColorDrawable)) return;
        int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
        if (android.graphics.Color.alpha(c) < 255) return; // see-through screens (the voice card) keep the app behind
        content.setBackgroundColor(c);
        boolean light = android.graphics.Color.luminance(c) > 0.5f;
        android.view.WindowInsetsController ic = a.getWindow().getInsetsController();
        if (ic == null) return;
        int bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        ic.setSystemBarsAppearance(light ? bars : 0, bars);
    }
}
