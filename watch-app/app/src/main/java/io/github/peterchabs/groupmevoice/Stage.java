package io.github.peterchabs.groupmevoice;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One screen that never gets torn down: the orb (or a QR code) on top, a title, a line of
 * detail, your words as you speak them, and buttons. Every change animates with fades and
 * small slides (opacity + transform only), so state changes feel smooth instead of jumping.
 */
final class Stage {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    final OrbView orb;
    private final Activity a;
    private final ScrollView scroll;
    private final ImageView qr;
    private final TextView title, sub, live;
    private final LinearLayout actions, extra;
    private int gen;

    Stage(Activity a) {
        this.a = a;
        int w = a.getResources().getDisplayMetrics().widthPixels;
        int pad = (int) (w * 0.12f);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(pad, (int) (w * 0.07f), pad, (int) (w * 0.2f));

        FrameLayout hero = new FrameLayout(a);
        orb = new OrbView(a);
        Press.attach(orb); // the orb is tappable (tap to talk), so it squishes too
        qr = new ImageView(a);
        qr.setVisibility(View.GONE);
        qr.setAlpha(0f);
        int orbSize = (int) (w * 0.3f), qrSize = (int) (w * 0.42f);
        hero.addView(orb, new FrameLayout.LayoutParams(orbSize, orbSize, Gravity.CENTER));
        hero.addView(qr, new FrameLayout.LayoutParams(qrSize, qrSize, Gravity.CENTER));
        col.addView(hero, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        title = text(col, 17, 0xFF262626, true);
        sub = text(col, 13, 0xFF6B7680, false);
        live = text(col, 15, 0xFF0A8FC4, false);
        live.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        live.setVisibility(View.GONE);

        extra = new LinearLayout(a);
        extra.setOrientation(LinearLayout.VERTICAL);
        col.addView(extra, full());
        actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.VERTICAL);
        col.addView(actions, full());

        scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFFFFFFFF);
        scroll.addView(col);
        a.setContentView(scroll);
    }

    /** Change the whole screen. Buttons are pairs of (label, Runnable); the first is the main one. */
    Stage show(OrbView.Mode mode, String t, String s, Object... buttons) {
        gen++;
        if (orb.mode() != mode) orb.setMode(mode);
        title.setTextSize(t != null && t.length() > 34 ? 14 : 17); // long titles shrink to fit the round screen
        swap(title, t);
        swap(sub, s);
        hide(live);
        extra.removeAllViews();
        actions.removeAllViews();
        for (int i = 0; i + 1 < buttons.length; i += 2) {
            addButton((String) buttons[i], (Runnable) buttons[i + 1], i == 0, i / 2);
        }
        scroll.smoothScrollTo(0, 0);
        return this;
    }

    /** Put something under the text (a text box, for example), sliding it in. */
    Stage extra(View v) {
        extra.addView(v, full());
        enter(v, 0);
        return this;
    }

    /** Your words, as the recognizer hears them. */
    void live(String text) {
        if (text == null || text.isEmpty()) return;
        live.setText("“" + text + "”");
        if (live.getVisibility() != View.VISIBLE) {
            live.setVisibility(View.VISIBLE);
            enter(live, 0);
        }
    }

    void showQr(Bitmap bmp) {
        qr.setImageBitmap(bmp);
        crossfade(orb, qr);
    }

    void showOrb() {
        if (qr.getVisibility() == View.VISIBLE) crossfade(qr, orb);
    }

    // ---------------- animation helpers ----------------

    private void swap(TextView v, String text) {
        String now = v.getVisibility() == View.VISIBLE ? v.getText().toString() : null;
        if (text == null ? now == null : text.equals(now)) return;
        v.animate().cancel(); // a cancelled animation skips its end action, so stale text never lands
        if (now == null) {
            set(v, text);
            return;
        }
        v.animate().alpha(0f).translationY(-8f).setDuration(110).setInterpolator(EASE)
                .withEndAction(() -> set(v, text)).start();
    }

    private void set(TextView v, String text) {
        if (text == null) {
            v.setVisibility(View.GONE);
            return;
        }
        v.setText(text);
        v.setVisibility(View.VISIBLE);
        enter(v, 0);
    }

    private void hide(View v) {
        if (v.getVisibility() != View.VISIBLE) return;
        v.animate().cancel();
        final int g = gen;
        v.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            if (g == gen) v.setVisibility(View.GONE);
        }).start();
    }

    private void enter(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(14f);
        v.animate().alpha(1f).translationY(0f).setStartDelay(60L * index)
                .setDuration(240).setInterpolator(EASE).start();
    }

    private void crossfade(View out, View in) {
        out.animate().cancel();
        in.animate().cancel();
        out.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(160)
                .withEndAction(() -> out.setVisibility(View.GONE)).start();
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setScaleX(0.9f);
        in.setScaleY(0.9f);
        in.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(260)
                .setInterpolator(EASE).start();
    }

    private void addButton(String label, Runnable onTap, boolean primary, int index) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFF00AFF0);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(Press.button(a, primary));
        b.setStateListAnimator(null);
        int padV = (int) (a.getResources().getDisplayMetrics().density * 10);
        b.setPadding(b.getPaddingLeft(), padV, b.getPaddingRight(), padV + (int) (a.getResources().getDisplayMetrics().density * 4));
        Press.attach(b);
        b.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = (int) (a.getResources().getDisplayMetrics().density * 6);
        actions.addView(b, lp);
        enter(b, index + 1);
    }

    private TextView text(LinearLayout col, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (a.getResources().getDisplayMetrics().density * 4), 0, 0);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t, full());
        return t;
    }

    private static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
