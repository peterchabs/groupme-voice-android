package io.github.peterchabs.sayso;

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

    /** sayso://signin?p=... from the sign-in page: unlock it with our pending code. */
    private boolean fromBrowser(Intent intent) {
        android.net.Uri u = intent == null ? null : intent.getData();
        if (u == null || !"sayso".equals(u.getScheme())) return false;
        if ("watch".equals(u.getHost())) { // the page, opened from a watch's QR code: sign that watch in
            String c = u.getQueryParameter("c");
            if (Link.valid(c)) startWatch(c);
            else enterWatchCode("That watch code didn't come through. Type the code on your watch.");
            return true;
        }
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
        String title = who.isEmpty() ? "Signed in" : "Signed in as " + who;
        String body = "Default group: " + (d == null ? "none" : d.name) + "\nNot affiliated with GroupMe or Microsoft";
        Runnable signOut = () -> {
            MainActivity.unsent = null;
            Store.clear(this);
            startLink();
        };
        if (isWatch()) {
            // three buttons fit a round screen; swiping right also goes back to talking
            stage.show(OrbView.Mode.DONE, title, body,
                    "Talk", (Runnable) () -> goMain(false),
                    "Default", (Runnable) this::pickDefault,
                    "Sign out", signOut);
        } else {
            stage.show(OrbView.Mode.DONE, title, body,
                    "Talk", (Runnable) () -> goMain(false),
                    "Default group", (Runnable) this::pickDefault,
                    "Voice shortcuts", (Runnable) this::showShortcuts,
                    "Sign in a watch", (Runnable) () -> enterWatchCode(null),
                    "Switch account", (Runnable) this::startLink,
                    "Sign out", signOut);
        }
        if (isWatch()) stage.hideHero(); // room for the not-affiliated line on a round screen
    }

    // ---------------- voice shortcuts (phone) ----------------

    /**
     * Ways to start Say So that don't depend on Google Assistant finding the app by name
     * (which it often can't for apps installed outside the Play Store).
     */
    private boolean onShortcuts;

    @Override
    protected void onResume() {
        super.onResume();
        if (onShortcuts) showShortcuts(); // back from Android's settings: show the new state
    }

    private void showShortcuts() {
        onShortcuts = true;
        stage.showOrb();
        boolean isAssistant = isAssistantApp();
        stage.show(OrbView.Mode.IDLE, "Voice shortcuts",
                (isAssistant
                        ? "Say So is your assistant app. Hold the power or home button (or swipe up"
                        + " from a bottom corner) and it starts listening."
                        : "Make Say So your assistant app, and holding the power or home button (or"
                        + " swiping up from a bottom corner) opens it listening. This replaces Google"
                        + " Assistant for that gesture.")
                        + "\n\nOr keep Google Assistant and add the Quick Settings tile: swipe down, tap"
                        + " Say So, talk.",
                isAssistant ? "Assistant settings" : "Set as assistant", (Runnable) this::openAssistantSettings,
                "Add the tile", (Runnable) this::addTile,
                "Back", (Runnable) () -> {
                    onShortcuts = false;
                    showAccount();
                });
    }

    private boolean isAssistantApp() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
            return rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
        }
        String s = android.provider.Settings.Secure.getString(getContentResolver(), "assistant");
        return s != null && s.startsWith(getPackageName() + "/");
    }

    private void openAssistantSettings() {
        // The "Digital assistant app" picker. Not every phone has the direct screen, so fall back.
        String[] actions = {"android.settings.VOICE_INPUT_SETTINGS",
                android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                android.provider.Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
                // try the next one
            }
        }
    }

    private void addTile() {
        android.content.ComponentName tile = new android.content.ComponentName(this, getPackageName() + ".VoiceTile");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.app.StatusBarManager sb = getSystemService(android.app.StatusBarManager.class);
            if (sb != null) {
                sb.requestAddTileService(tile, getString(R.string.app_name),
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_fg),
                        getMainExecutor(), result -> ui.post(() -> tileAdded(result)));
                return;
            }
        }
        tileHowTo();
    }

    private void tileAdded(int result) {
        if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                || result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
            stage.show(OrbView.Mode.DONE, "Tile added",
                    "Swipe down from the top of the screen and tap Say So to talk.",
                    "Done", (Runnable) this::showShortcuts);
        } else {
            tileHowTo();
        }
    }

    private void tileHowTo() {
        stage.show(OrbView.Mode.IDLE, "Add the tile yourself",
                "Swipe down twice from the top of the screen, tap the pencil (Edit), and drag"
                        + " Say So into your tiles.",
                "Back", (Runnable) this::showShortcuts);
    }

    // ---------------- sign in a watch from the phone app ----------------

    /** A watch waiting for this phone to sign it in (its 10-character code), or null. */
    private String watchCode;

    /** Type the code your watch shows (when you didn't come here from its QR code). */
    private void enterWatchCode(String error) {
        stage.showOrb();
        EditText e = new EditText(this);
        Press.field(e);
        e.setHint("XXXXX-XXXXX");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        stage.show(error == null ? OrbView.Mode.IDLE : OrbView.Mode.ERROR, "Sign in a watch",
                error != null ? error : "Open Say So on your watch and type the code it shows.",
                "Next", (Runnable) () -> {
                    String c = e.getText().toString();
                    if (Link.valid(c)) startWatch(c);
                    else enterWatchCode("Check the code. It's the 10 letters and numbers on your watch.");
                },
                "Back", (Runnable) this::backFromWatch).extra(e);
    }

    private void startWatch(String code) {
        stopPolling();
        watchCode = Link.normalize(code);
        String pretty = new Link(watchCode).pretty();
        stage.showOrb();
        if (!Store.token(this).isEmpty()) {
            String who = Store.name(this);
            stage.show(OrbView.Mode.IDLE, "Sign in your watch?",
                    "Sends " + (who.isEmpty() ? "your" : who + "'s") + " GroupMe sign-in to the watch showing "
                            + pretty + ". It's locked so only that watch can open it.",
                    "Send to watch", (Runnable) () -> sendToWatch(Store.token(this)),
                    "Use another account", (Runnable) this::openDevSignIn,
                    "Cancel", (Runnable) this::backFromWatch);
        } else {
            stage.show(OrbView.Mode.IDLE, "Sign in your watch",
                    "Log in to GroupMe here on your phone. Your watch (" + pretty + ") finishes"
                            + " signing in by itself, and this phone is signed in too."
                            + "\n\nNot affiliated with GroupMe or Microsoft.",
                    "Sign in with GroupMe", (Runnable) this::openDevSignIn,
                    "Paste token instead", (Runnable) () -> showTyping(null),
                    "Cancel", (Runnable) this::backFromWatch);
        }
    }

    private void backFromWatch() {
        watchCode = null;
        if (Store.token(this).isEmpty()) startLink();
        else showAccount();
    }

    /** Locks the token with the watch's code and drops it on the relay; the watch is polling for it. */
    private void sendToWatch(String token) {
        final String code = watchCode;
        if (code == null) {
            showAccount();
            return;
        }
        stage.showOrb();
        stage.show(OrbView.Mode.BUSY, "Sending to your watch", null);
        bg.execute(() -> {
            try {
                Link link = new Link(code);
                Api.relayPost(link.topic(), link.seal(token, System.currentTimeMillis()));
                ui.post(() -> {
                    watchCode = null;
                    stage.show(OrbView.Mode.DONE, "Sent to your watch",
                            "Your watch finishes signing in within a few seconds. If its code ran out,"
                                    + " get a new one on the watch and try again.",
                            "Done", (Runnable) this::showAccount);
                });
            } catch (IOException e) {
                ui.post(() -> stage.show(OrbView.Mode.ERROR, "Couldn't send it",
                        "Check your phone's internet connection.",
                        "Try again", (Runnable) () -> sendToWatch(token),
                        "Cancel", (Runnable) this::backFromWatch));
            }
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
                "Talk now", (Runnable) () -> goMain(false),
                "Settings", (Runnable) this::showAccount);
    }

    /** Back (or a swipe on the watch) from Settings returns to talking instead of closing the app. */
    @Override
    public void onBackPressed() {
        if (!Store.token(this).isEmpty()) goMain(false);
        else super.onBackPressed();
    }

    // ---------------- sign in from your phone ----------------

    private void startLink() {
        stopPolling();
        Link link = Link.create();
        Store.setPendingCode(this, link.code);
        if (!isWatch()) {
            // Phone: log in on GroupMe's developer site inside the app; it picks up the token.
            stage.showOrb();
            boolean again = Store.token(this).isEmpty() && !Store.name(this).isEmpty(); // signed out by GroupMe, not "Switch"
            stage.show(OrbView.Mode.IDLE, again ? "Sign in again" : "Sign in to GroupMe",
                    (again ? "GroupMe needs you to sign in again. " : "")
                            + "You'll log in on GroupMe's own site, right here in the app. Your token"
                            + " stays on this phone."
                            + "\n\nNot affiliated with GroupMe or Microsoft.",
                    "Sign in with GroupMe", (Runnable) this::openDevSignIn,
                    "Paste token instead", (Runnable) () -> showTyping(null),
                    "Web page sign-in", (Runnable) () -> showWebPageNote(link));
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

    private static final int REQ_DEV = 41;

    private void openDevSignIn() {
        stopPolling();
        startActivityForResult(new Intent(this, DevSignInActivity.class), REQ_DEV);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_DEV) return;
        String token = data == null ? null : data.getStringExtra(DevSignInActivity.EXTRA_TOKEN);
        if (result == RESULT_OK && token != null && !token.isEmpty()) verify(token);
        // cancelled: the sign-in choices are still on screen
    }

    /** The older way, kept as a last choice: GroupMe's sign-in page, returning through GitHub. */
    private void showWebPageNote(Link link) {
        stage.show(OrbView.Mode.IDLE, "Web page sign-in",
                "Uses GroupMe's older sign-in page, which rejects some accounts with \"invalid"
                        + " credentials\". It returns through a page hosted on GitHub, which receives"
                        + " your sign-in in its logs.",
                "Open it", (Runnable) () -> {
                    openPage(link);
                    final long started = System.currentTimeMillis();
                    stopPolling();
                    polling = bg.submit(() -> poll(link, started)); // backup if the browser doesn't return
                },
                "Back", (Runnable) this::startLink);
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
                isWatch() ? "Use my phone instead" : "Back",
                (Runnable) () -> { if (watchCode != null) startWatch(watchCode); else startLink(); }).extra(e);
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
                Phrase.Group d = Store.defaultGroup(this);
                if (d != null && groups.stream().noneMatch(g -> g.id.equals(d.id))) {
                    Store.setDefaultGroup(this, null); // a different account, or you left that group
                }
                if (watchCode != null) { // signed in on the phone to sign a watch in: send it on
                    ui.post(() -> sendToWatch(token));
                    return;
                }
                MainActivity.Unsent u = MainActivity.unsent;
                if (u != null && !u.freshFor(me[0])) MainActivity.unsent = null; // someone else, or too old
                final boolean resend = MainActivity.unsent != null;
                final String hi = name.isEmpty() ? "You're signed in" : "Hi, " + name + "!";
                ui.post(() -> {
                    if (resend) {
                        stage.show(OrbView.Mode.DONE, hi,
                                "Your message didn't go out before:\n" + u.title + "\n" + u.body,
                                "Send it now", (Runnable) () -> goMain(true),
                                "Not now", (Runnable) () -> {
                                    MainActivity.unsent = null;
                                    goMain(false);
                                });
                    } else {
                        stage.show(OrbView.Mode.DONE, hi,
                                "Found " + groups.size() + " groups. Now say \"Hey Google, open Say So\".",
                                "Try it now", (Runnable) () -> goMain(false));
                    }
                });
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

    private void goMain(boolean resend) {
        startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_RESEND, resend));
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
