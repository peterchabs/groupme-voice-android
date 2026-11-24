package io.github.peterchabs.groupmevoice;

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
     * A key-cap button: a darker lip underneath and a shiny face on top. Pressed, the face
     * sinks down onto the lip; the change fades in and out so it feels soft.
     */
    static Drawable button(Context c, boolean primary) {
        float d = c.getResources().getDisplayMetrics().density;
        int lip = Math.round(4 * d);
        int faceTop = primary ? 0xFF45C8F7 : 0xFFFFFFFF;
        int faceBottom = primary ? OrbView.ACCENT : 0xFFEAF7FD;
        int lipColor = primary ? 0xFF0088BC : 0xFFAEE3F8;
        int stroke = primary ? 0 : Math.round(1.5f * d);

        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(70);
        s.setExitFadeDuration(160);
        s.addState(new int[]{android.R.attr.state_pressed},
                cap(lipColor, faceTop, faceBottom, stroke, lip, Math.round(lip * 0.8f)));
        s.addState(new int[]{}, cap(lipColor, faceTop, faceBottom, stroke, lip, 0));
        return s;
    }

    private static Drawable cap(int lipColor, int top, int bottom, int stroke, int lip, int sink) {
        GradientDrawable under = new GradientDrawable();
        under.setCornerRadius(999f);
        under.setColor(lipColor);
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        face.setCornerRadius(999f);
        if (stroke > 0) face.setStroke(stroke, OrbView.ACCENT);
        LayerDrawable l = new LayerDrawable(new Drawable[]{under, face});
        l.setLayerInset(0, 0, lip, 0, 0);          // lip peeks out below
        l.setLayerInset(1, 0, sink, 0, lip - sink); // face rides on top, sinks when pressed
        return l;
    }

    /** A sunken text box: slightly shaded at the top, blue outline when you tap into it. */
    static void field(EditText e) {
        Context c = e.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        s.addState(new int[]{android.R.attr.state_focused}, well(d, OrbView.ACCENT, 2f));
        s.addState(new int[]{}, well(d, 0xFFC5D3DC, 1.5f));
        e.setBackground(s);
        int px = Math.round(12 * d), py = Math.round(10 * d);
        e.setPadding(px, py, px, py);
        e.setTextColor(0xFF262626);
        e.setHintTextColor(0xFF9AA7B0);
        attach(e);
    }

    private static Drawable well(float d, int border, float width) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFE3EBF0, 0xFFF7FAFC, Color.WHITE});
        g.setCornerRadius(14 * d);
        g.setStroke(Math.round(width * d), border);
        return g;
    }
}
