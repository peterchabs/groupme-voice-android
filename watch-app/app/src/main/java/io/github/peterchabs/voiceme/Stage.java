package io.github.peterchabs.voiceme;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private final FrameLayout hero;
    private final boolean watch, card;
    private final int unit; // width everything is sized from (the card width on a phone)
    private final float textScale;
    private final Activity a;
    private final ScrollView scroll;
    private final ImageView qr;
    private final TextView title, sub, live;
    private final LinearLayout actions, extra;
    private int gen;

    Stage(Activity a) {
        this.a = a;
        Palette.apply(a); // light or dark, from settings
        float dp = a.getResources().getDisplayMetrics().density;
        watch = isWatchBuild();
        // On a phone the voice screen is a card over your current app, so size it like one.
        card = !watch && a instanceof MainActivity;
        // a watch uses its whole round screen; a phone uses a comfortable centered column
        int w = watch ? a.getResources().getDisplayMetrics().widthPixels
                : Math.min(a.getResources().getDisplayMetrics().widthPixels, (int) (330 * dp));
        textScale = watch ? 1.15f : 1.3f; // watch text sized to use the round screen; nothing may cross its edge (ScreenshotTest checks)
        unit = w;
        int pad = (int) (w * 0.11f);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER); // middle of the circle is where a round screen is widest
        if (!watch) col.setPadding((int) (24 * dp), (int) (22 * dp), (int) (24 * dp), (int) (26 * dp));
        else col.setPadding(pad, (int) (w * 0.1f), pad, (int) (w * 0.1f));

        hero = new FrameLayout(a);
        orb = new OrbView(a);
        Press.attach(orb); // the orb is tappable (tap to talk), so it squishes too
        qr = new ImageView(a);
        qr.setVisibility(View.GONE);
        qr.setAlpha(0f);
        int orbSize = (int) (w * (watch ? 0.21f : 0.26f)), qrSize = (int) (w * (watch ? 0.29f : 0.5f));
        hero.addView(orb, new FrameLayout.LayoutParams(orbSize, orbSize, Gravity.CENTER));
        hero.addView(qr, new FrameLayout.LayoutParams(qrSize, qrSize, Gravity.CENTER));
        col.addView(hero, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        title = text(col, 15, Palette.INK, true);
        sub = text(col, 12, Palette.SUB, false);
        live = text(col, 13, Palette.LIVE, false);
        live.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        live.setVisibility(View.GONE);

        extra = new LinearLayout(a);
        extra.setOrientation(LinearLayout.VERTICAL);
        col.addView(extra, full());
        actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(actions, full());

        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        if (!card) {
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(Palette.BG);
            scroll.addView(col, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.MATCH_PARENT));
            a.setContentView(scroll);
            return;
        }
        // Phone: a white card that slides up from the bottom; your app stays visible above it.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.CARD);
        float r = 26 * dp;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        scroll.setBackground(bg);
        scroll.setElevation(16 * dp);
        scroll.setClickable(true); // taps on the card stay on the card
        scroll.addView(col, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        FrameLayout shade = new FrameLayout(a);
        shade.setOnClickListener(v -> a.finish()); // tap outside the card to dismiss
        shade.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        a.setContentView(shade);
        scroll.setTranslationY(600 * dp);
        scroll.animate().translationY(0f).setDuration(340).setInterpolator(EASE).start();
    }

    /** The watch download always acts as a watch app, the phone download as a phone app. */
    static boolean isWatchBuild() { return "watch".equals(BuildConfig.FLAVOR); }

    /** Change the whole screen. Buttons are pairs of (label, Runnable); the first is the main one. */
    Stage show(OrbView.Mode mode, String t, String s, Object... buttons) {
        gen++;
        hero.setVisibility(View.VISIBLE);
        if (orb.mode() != mode) orb.setMode(mode);
        title.setTextSize((t != null && t.length() > 34 ? 13 : 15) * textScale); // long titles shrink to fit the round screen
        swap(title, t);
        swap(sub, s);
        hide(live);
        extra.removeAllViews();
        actions.removeAllViews();
        java.util.List<Object> kept = new java.util.ArrayList<>(); // skip (null, null) slots
        for (int i = 0; i + 1 < buttons.length; i += 2) {
            if (buttons[i] != null && buttons[i + 1] != null) {
                kept.add(buttons[i]);
                kept.add(buttons[i + 1]);
            }
        }
        buttons = kept.toArray();
        int count = buttons.length / 2;
        boolean shortLabels = true; // side by side only when every label is short
        // only the buttons that would share a row matter (with 3, the first one has its own row)
        for (int i = count == 3 ? 2 : 0; i < buttons.length; i += 2) shortLabels &= ((String) buttons[i]).length() <= 10;
        if (!shortLabels && count > 1) count = 99; // stack them all
        // Fit the round screen: 2 buttons sit side by side; 3 = one wide button over a pair.
        actions.setOrientation(count == 2 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout target = actions;
        for (int i = 0; i + 1 < buttons.length; i += 2) {
            int n = i / 2;
            boolean pair = count == 2 || (count == 3 && n > 0);
            if (count == 3 && n == 1) {
                target = new LinearLayout(a);
                target.setOrientation(LinearLayout.HORIZONTAL);
                target.setGravity(Gravity.CENTER_HORIZONTAL);
                actions.addView(target, full());
            }
            addButton(target, (String) buttons[i], (Runnable) buttons[i + 1], i == 0, n, pair);
        }
        scroll.smoothScrollTo(0, 0);
        return this;
    }

    /** A stacked list of choices (always one per row); {@code selected} is drawn as the filled button. */
    Stage showList(OrbView.Mode mode, String t, String s, String[] labels, Runnable[] taps, int selected) {
        show(mode, t, s);
        actions.setOrientation(LinearLayout.VERTICAL);
        Button chosen = null;
        for (int i = 0; i < labels.length; i++) {
            Button b = addButton(actions, labels[i], taps[i], i == selected, i, false);
            if (i == selected) chosen = b;
        }
        if (chosen != null && selected > 0) { // open the list scrolled to the current choice
            final Button c = chosen;
            scroll.post(() -> scroll.smoothScrollTo(0,
                    Math.max(0, actions.getTop() + c.getTop() - scroll.getHeight() / 2 + c.getHeight() / 2)));
        }
        return this;
    }

    /** Hide the orb/QR for this screen only (room for more text on a round watch). */
    Stage hideHero() {
        hero.setVisibility(View.GONE);
        return this;
    }

    /** Put something under the text (a text box, for example), sliding it in. */
    Stage extra(View v) {
        extra.addView(v, full());
        enter(v, 0);
        return this;
    }

    /** Your words, as the recognizer hears them. */
    /** Changes the heading and the line under it in place (a quick fade), e.g. "To Weekend Crew". */
    void retitle(String t, String s) {
        if (t != null && !t.contentEquals(title.getText())) {
            title.setAlpha(0.3f);
            title.setText(t);
            title.animate().alpha(1f).setDuration(220).start();
        }
        if (s != null && !s.contentEquals(sub.getText())) {
            sub.setText(s);
            sub.setVisibility(View.VISIBLE);
        }
    }

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

    private Button addButton(LinearLayout into, String label, Runnable onTap, boolean primary, int index, boolean pair) {
        Button b = new Button(a);
        b.setGravity(android.view.Gravity.CENTER);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13 * textScale);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFF00AFF0);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(Press.button(a, primary));
        b.setStateListAnimator(null);
        int padV = (int) (a.getResources().getDisplayMetrics().density * 7);
        int padH = (int) (a.getResources().getDisplayMetrics().density * 6);
        b.setPadding(padH, padV, padH, padV);
        b.setSingleLine(true); // labels never wrap or break mid-word
        b.setEllipsize(android.text.TextUtils.TruncateAt.END); // long group names end in ...
        Press.attach(b);
        b.setOnClickListener(v -> onTap.run());
        // narrower than the screen so the pill's ends never meet the round edge
        int sw = unit;
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (sw * (pair ? (watch ? 0.35f : 0.42f) : (watch ? 0.62f : 0.84f))), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (dp * 6);
        if (pair) {
            lp.leftMargin = (int) (dp * 2);
            lp.rightMargin = (int) (dp * 2);
        }
        into.addView(b, lp);
        enter(b, index + 1);
        return b;
    }

    private TextView text(LinearLayout col, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setTextSize(sp * textScale);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (a.getResources().getDisplayMetrics().density * 3), 0, 0);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t, full());
        return t;
    }

    private static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
