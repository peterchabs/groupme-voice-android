package io.github.peterchabs.sayso;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A new message, shown over Say So's own screen. While an app is on screen the watch only flashes a
 * small icon for a notification, so the open screen draws this card instead: the sender's picture,
 * the chat, and what was said. Tap it to open that chat; otherwise it slides away after a few
 * seconds. A newer one replaces it. No notification is posted for it, so nothing peeks at the
 * bottom of the screen.
 */
final class InAppBanner {
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private final Activity a;
    private final ViewGroup root;
    private final boolean watch = Stage.isWatchBuild();
    private final float d;
    private View shown;

    InAppBanner(Activity a, ViewGroup root) {
        this.a = a;
        this.root = root;
        d = a.getResources().getDisplayMetrics().density;
    }

    void show(String title, String sender, String line, boolean dm, Bitmap pic, Runnable onTap) {
        if (a.isFinishing()) return;
        if (shown != null) root.removeView(shown);
        int W = a.getResources().getDisplayMetrics().widthPixels;
        int H = a.getResources().getDisplayMetrics().heightPixels;
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        // a drawer pulled down from the top edge: see-through, rounded only along its bottom
        GradientDrawable g = new GradientDrawable();
        g.setColor(Palette.dark ? 0xD91C2127 : 0xE0FFFFFF);
        float r = px(watch ? 34 : 24);
        g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        card.setBackground(g);
        card.setElevation(px(10));
        int side = watch ? (int) (W * 0.17f) : px(16);
        card.setPadding(side, watch ? (int) (H * 0.12f) : px(36), side, px(watch ? 14 : 14));
        if (pic != null) {
            ImageView face = new ImageView(a);
            face.setImageBitmap(pic);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(px(watch ? 28 : 34), px(watch ? 28 : 34));
            fp.rightMargin = px(8);
            card.addView(face, fp);
        }
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView head = label(title, sp(watch ? 11 : 13), Palette.BLUE, true);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(head);
        TextView body = label(dm || sender.isEmpty() ? line : sender + ": " + line, sp(watch ? 12 : 14), Palette.INK, false);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(body);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(card, lp);
        shown = card;
        card.setOnClickListener(v -> {
            hide(card);
            onTap.run();
        });
        card.setTranslationY(-H * 0.5f); // above the screen until it's measured, then slides down
        card.post(() -> {
            card.setTranslationY(-card.getHeight());
            card.animate().translationY(0).setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        });
        buzz();
        card.postDelayed(() -> hide(card), 4500);
    }

    /** No notification is posted while Say So is open, so the drawer gives the tap on the wrist itself. */
    private void buzz() {
        try {
            android.os.Vibrator v = a.getSystemService(android.os.Vibrator.class);
            if (v != null && v.hasVibrator())
                v.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) {
        }
    }

    private void hide(View v) {
        if (v == null || v.getParent() == null) return;
        v.animate().cancel();
        v.animate().translationY(-v.getHeight()).setDuration(240).withEndAction(() -> {
            root.removeView(v);
            if (shown == v) shown = null;
        }).start();
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(bold ? BOLD : REGULAR);
        return t;
    }

    private int px(float dp) { return (int) (dp * d + 0.5f); }

    private float sp(float s) { return watch ? s * 1.18f : s * 1.15f; }
}
