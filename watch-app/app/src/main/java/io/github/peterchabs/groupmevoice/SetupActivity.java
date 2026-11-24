package io.github.peterchabs.groupmevoice;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Sign in. The watch shows a QR code and a short code; you open the page on your phone,
 * paste your GroupMe token (or sign in with GroupMe), and the watch picks it up, checks it
 * with GroupMe, and says who you're signed in as. Typing the token on the watch is the backup.
 */
public class SetupActivity extends Activity {
    private static final long POLL_MS = 3000;
    private static final long CODE_LIFE_MS = 10 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Stage stage;
    private Future<?> polling;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        stage = new Stage(this);
        if (fromBrowser(getIntent())) return;
        if (Store.token(this).isEmpty()) startLink();
        else showAccount();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        fromBrowser(intent);
    }

    /** groupmevoice://signin?p=... from the sign-in page: unlock it with our pending code. */
    private boolean fromBrowser(Intent intent) {
        android.net.Uri u = intent == null ? null : intent.getData();
        if (u == null || !"groupmevoice".equals(u.getScheme())) return false;
        String code = Store.pendingCode(this);
        String payload = u.getQueryParameter("p");
        String token = code.isEmpty() || payload == null ? null : new Link(code).open(payload, System.currentTimeMillis());
        if (token == null) {
            stage.show(OrbView.Mode.ERROR, "That sign-in didn't work", "Please try again.",
                    "Try again", (Runnable) this::startLink);
        } else {
            verify(token);
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        bg.shutdownNow();
        super.onDestroy();
    }

    private void showAccount() {
        stopPolling();
        stage.showOrb();
        String who = Store.name(this);
        Phrase.Group d = Store.defaultGroup(this);
        stage.show(OrbView.Mode.DONE, who.isEmpty() ? "Signed in" : "Signed in as " + who,
                "Default group: " + (d == null ? "none" : d.name),
                // no Back button: swiping right goes back on Wear OS, and four buttons don't fit
                "Default group", (Runnable) this::pickDefault,
                "Switch", (Runnable) this::startLink,
                "Sign out", (Runnable) () -> {
                    Store.clear(this);
                    startLink();
                });
    }

    // ---------------- default group ----------------

    /** Pick the group messages go to when you don't say one. */
    private void pickDefault() {
        List<Phrase.Group> cached = Store.groups(this);
        if (!cached.isEmpty()) {
            showPicker(cached);
            return;
        }
        stage.show(OrbView.Mode.BUSY, "Getting your groups", null);
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                List<Phrase.Group> fresh = Api.listGroups(token);
                Store.saveGroups(this, fresh);
                ui.post(() -> showPicker(fresh));
            } catch (IOException e) {
                ui.post(() -> stage.show(OrbView.Mode.ERROR, "Couldn't reach GroupMe",
                        "Check that your watch is connected.", "Back", (Runnable) this::showAccount));
            }
        });
    }

    private void showPicker(List<Phrase.Group> groups) {
        Phrase.Group current = Store.defaultGroup(this);
        List<Phrase.Group> sorted = new java.util.ArrayList<>(groups);
        sorted.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        String[] labels = new String[sorted.size() + 1];
        Runnable[] taps = new Runnable[sorted.size() + 1];
        int selected = 0;
        labels[0] = "No default";
        taps[0] = () -> choose(null);
        for (int i = 0; i < sorted.size(); i++) {
            Phrase.Group g = sorted.get(i);
            labels[i + 1] = g.name;
            taps[i + 1] = () -> choose(g);
            if (current != null && current.id.equals(g.id)) selected = i + 1;
        }
        stage.showList(OrbView.Mode.IDLE, "Default group",
                "Where messages go when you don't say a group", labels, taps, selected);
    }

    private void choose(Phrase.Group g) {
        Store.setDefaultGroup(this, g);
        stage.show(OrbView.Mode.DONE, g == null ? "No default group" : "Default: " + g.name,
                g == null ? "Say a group before each message." : "Now just say your message.",
                "Done", (Runnable) this::showAccount);
    }

    // ---------------- sign in from your phone ----------------

    private void startLink() {
        stopPolling();
        Link link = Link.create();
        Store.setPendingCode(this, link.code);
        if (!isWatch()) {
            // Phone: no QR or code. One tap to GroupMe's login, and the browser comes back here.
            stage.showOrb();
            stage.show(OrbView.Mode.IDLE, "Sign in to GroupMe",
                    "Sign in with GroupMe is easiest. It returns through a page hosted on GitHub,"
                            + " which receives your sign-in in its logs. Pasting your token avoids that.",
                    "Sign in with GroupMe", (Runnable) () -> {
                        openPage(link);
                        final long started = System.currentTimeMillis();
                        stopPolling();
                        polling = bg.submit(() -> poll(link, started)); // backup if the browser doesn't return
                    },
                    "Paste token instead", (Runnable) () -> showTyping(null));
            return;
        }
        Bitmap code = qr(link.pageUrl());
        if (code != null) stage.showQr(code);
        stage.show(OrbView.Mode.BUSY, "Code: " + link.pretty(),
                "Scan, or enter it at\npeterchabs.github.io/groupme-voice-android",
                "Type token instead", (Runnable) () -> showTyping(null));
        final long started = System.currentTimeMillis();
        polling = bg.submit(() -> poll(link, started));
    }

    private void poll(Link link, long started) {
        long sinceSec = started / 1000 - 5;
        while (!Thread.currentThread().isInterrupted()) {
            if (System.currentTimeMillis() - started > CODE_LIFE_MS) {
                ui.post(() -> {
                    stage.showOrb();
                    stage.show(OrbView.Mode.ERROR, "That code expired", "Codes last 10 minutes.",
                            "Get a new code", (Runnable) this::startLink);
                });
                return;
            }
            try {
                List<String> msgs = Api.relayMessages(link.topic(), sinceSec);
                for (int i = msgs.size() - 1; i >= 0; i--) { // newest first
                    String token = link.open(msgs.get(i), System.currentTimeMillis());
                    if (token != null) {
                        ui.post(() -> verify(token));
                        return;
                    }
                }
            } catch (IOException ignored) {
                // relay hiccup or no connection yet: keep waiting
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean isWatch() {
        return Stage.isWatchBuild();
    }

    private void openPage(Link link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link.phoneUrl())));
        } catch (android.content.ActivityNotFoundException e) {
            showTyping("No web browser found. Type your token instead.");
        }
    }

    private void stopPolling() {
        if (polling != null) {
            polling.cancel(true);
            polling = null;
        }
    }

    // ---------------- typing the token on the watch (backup) ----------------

    private void showTyping(String error) {
        stopPolling();
        stage.showOrb();
        EditText e = new EditText(this);
        Press.field(e);
        e.setHint("Token");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); // no autocorrect: typed exactly
        stage.show(error == null ? OrbView.Mode.IDLE : OrbView.Mode.ERROR, isWatch() ? "Type your token" : "Paste your token",
                error != null ? error : isWatch() ? "From dev.groupme.com > Access Token. Every letter matters."
                        : "Copy it from dev.groupme.com > Access Token, then press and hold the box to paste.",
                "Save", (Runnable) () -> verify(e.getText().toString().replaceAll("\\s+", "")),
                isWatch() ? "Use my phone instead" : "Back", (Runnable) this::startLink).extra(e);
    }

    // ---------------- check with GroupMe, then save ----------------

    private void verify(String token) {
        if (token.isEmpty()) {
            showTyping("Please type your token first.");
            return;
        }
        stopPolling();
        stage.showOrb();
        stage.show(OrbView.Mode.BUSY, "Checking with GroupMe", null);
        bg.execute(() -> {
            try {
                String[] me = Api.me(token);
                String name = me[1];
                List<Phrase.Group> groups = Api.listGroups(token);
                Store.setToken(this, token);
                Store.setName(this, name);
                Store.setMyId(this, me[0]);
                Store.setPendingCode(this, ""); // one-time code is used up
                Store.saveGroups(this, groups);
                ui.post(() -> stage.show(OrbView.Mode.DONE,
                        name.isEmpty() ? "You're signed in" : "Hi, " + name + "!",
                        "Found " + groups.size() + " groups. Now say \"Hey Google, open GroupMe Voice\".",
                        "Try it now", (Runnable) this::goMain));
            } catch (Api.AuthException e) {
                ui.post(() -> showTyping("GroupMe didn't accept that token. Check it and try again."));
            } catch (IOException e) {
                ui.post(() -> {
                    stage.show(OrbView.Mode.ERROR, "Couldn't reach GroupMe",
                            "Check that your watch is connected to your phone or Wi-Fi.",
                            "Try again", (Runnable) () -> verify(token));
                });
            }
        });
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private Bitmap qr(String text) {
        try {
            int size = Stage.isWatchBuild() ? (int) (getResources().getDisplayMetrics().widthPixels * 0.29f) : (int) (Math.min(getResources().getDisplayMetrics().widthPixels, 330 * getResources().getDisplayMetrics().density) * 0.5f);
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2); // white border so phone cameras lock on
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException | IllegalArgumentException e) {
            return null; // the typed code still works
        }
    }
}
