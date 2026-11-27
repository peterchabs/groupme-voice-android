package io.github.peterchabs.voiceme;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;

/** Raised 3D buttons and sunken text boxes that react to your finger. */
final class Press {
    private Press() { }

    /** Squish on touch, spring back on release. Doesn't block taps. */
    static void attach(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scale(view, 0.94f, 90, new DecelerateInterpolator());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scale(view, 1f, 320, new OvershootInterpolator(3f));
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static void scale(View v, float to, long ms, android.animation.TimeInterpolator in) {
        Object old = v.getTag(R.id.press_anim);
        if (old instanceof ObjectAnimator) ((ObjectAnimator) old).cancel();
        // its own animator, so it never cancels the fade a button slides in with
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, to),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, to));
        a.setDuration(ms);
        a.setInterpolator(in);
        v.setTag(R.id.press_anim, a);
        a.start();
    }

    /**
     * A button, drawn like the chat list's cards so every button in the app is one family: the
     * main action is a solid blue card with white words, everything else a plain card with a
     * hairline edge. Pressed, it tints; the change fades in and out so it feels soft.
     */
    static Drawable button(Context c, boolean primary) {
        float d = c.getResources().getDisplayMetrics().density;
        boolean watch = Stage.isWatchBuild();
        float radius = (watch ? 22 : 18) * d;
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(70);
        s.setExitFadeDuration(160);
        if (primary) {
            s.addState(new int[]{android.R.attr.state_pressed}, card(0xFF0398D8, 0, radius, d));
            s.addState(new int[]{}, card(OrbView.ACCENT, 0, radius, d));
        } else {
            s.addState(new int[]{android.R.attr.state_pressed}, card(Palette.BADGE, OrbView.ACCENT, radius, d));
            s.addState(new int[]{}, card(Palette.ROW, Palette.ROW_EDGE, radius, d));
        }
        return s;
    }

    private static Drawable card(int fill, int edge, float radius, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (edge != 0) g.setStroke(Math.max(1, Math.round(0.75f * d)), edge);
        return g;
    }

    /** A sunken text box: slightly shaded at the top, blue outline when you tap into it. */
    static void field(EditText e) {
        Context c = e.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        s.addState(new int[]{android.R.attr.state_focused}, well(d, OrbView.ACCENT, 2f));
        s.addState(new int[]{}, well(d, Palette.FIELD_EDGE, 1.5f));
        e.setBackground(s);
        int px = Math.round(12 * d), py = Math.round(10 * d);
        e.setPadding(px, py, px, py);
        e.setTextColor(Palette.FIELD_TEXT);
        e.setHintTextColor(Palette.FIELD_HINT);
        attach(e);
    }

    private static Drawable well(float d, int border, float width) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Palette.FIELD_FILL_TOP, Palette.FIELD_FILL_MID, Palette.dark ? Palette.CARD : Color.WHITE});
        g.setCornerRadius(14 * d);
        g.setStroke(Math.round(width * d), border);
        return g;
    }
}
