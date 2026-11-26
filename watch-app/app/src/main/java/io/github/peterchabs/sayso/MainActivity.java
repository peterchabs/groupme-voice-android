package io.github.peterchabs.sayso;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Hey Google, open Say So" lands here. It listens right away, with the orb reacting
 * to your voice and your words appearing as you say them. It finds the group wherever you name it
 * ("tell Family I'm on my way", "I'm on my way, tell Family"), shows who it's sending to while a ring counts down 3 seconds (tap Cancel to
 * stop), then sends.
 */
public class MainActivity extends Activity {
    private static final int REQ_MIC = 2;
    private static final long SEND_DELAY_MS = 3000;
    private static final long GROUP_CACHE_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_TEXT = 1000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Stage stage;
    private SpeechRecognizer rec;
    private boolean listening;
    private Runnable pendingSend;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        stage = new Stage(this);
        SignInCheck.run(this);
        Shortcuts.publish(this); // phone: offer the "Send a GroupMe" shortcut to Google Assistant
        stage.orb.setCountdownMs(SEND_DELAY_MS);
        stage.orb.setOnClickListener(v -> {
            if (listening) { userDone = true; rec.stopListening(); } // tap: that's everything
            else if (stage.orb.mode() != OrbView.Mode.BUSY
                    && stage.orb.mode() != OrbView.Mode.COUNTDOWN) begin();
        });
        if (Store.token(this).isEmpty()) {
            openSetup();
            return;
        }
        if (!resend(getIntent()) && !talkInChat()) begin();
    }

    /**
     * With a default group, talking happens right in that group's chat (Chats screen): you see the
     * conversation and your words type out in a bubble as you speak.
     */
    private boolean talkInChat() {
        if (Store.defaultGroup(this) == null) return false;
        startActivity(new Intent(this, HomeActivity.class)
                .putExtra(HomeActivity.EXTRA_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
        return true;
    }

    /** Back from signing in again: count down the message that didn't go out, same as before. */
    private boolean resend(Intent intent) {
        Unsent u = unsent;
        if (intent == null || !intent.getBooleanExtra(EXTRA_RESEND, false) || u == null) return false;
        unsent = null;
        if (!u.freshFor(Store.myId(this))) return false;
        countdown(u.title, u.body, "Send now", u.busy, u.done, u.action);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); // "Hey Google, open Say So" while already open
        cancelPending();
        if (!Store.token(this).isEmpty() && !resend(intent) && !talkInChat()) begin();
    }

    /** New messages while this screen is up: the watch would only flash a small icon, so show them here. */
    private InAppBanner banner;
    private final PushService.Banner bannerHook = (key, convoId, dm, title, sender, line, pic, msgId) -> ui.post(() -> {
        if (banner == null) banner = new InAppBanner(this, (android.view.ViewGroup) getWindow().getDecorView());
        banner.show(title, sender, line, dm, pic, () -> {
            cancelPending();
            startActivity(PushService.chatIntent(this, key, convoId, title, dm, msgId));
        });
    });

    @Override
    protected void onResume() {
        super.onResume();
        PushService.banner = bannerHook;
    }

    @Override
    protected void onPause() {
        if (PushService.banner == bannerHook) PushService.banner = null;
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (listening && rec != null) {
            listening = false;
            rec.cancel();
            stage.show(OrbView.Mode.IDLE, "Tap to talk", null, "Chats", (Runnable) this::openChats);
        }
        super.onStop(); // a countdown keeps going if the screen dims; Cancel or Back stops it
    }

    @Override
    protected void onDestroy() {
        cancelPending(); // leaving during the countdown means: don't send
        if (rec != null) rec.destroy();
        bg.shutdownNow();
        super.onDestroy();
    }

    /** Every group and DM, with their messages (HomeActivity). */
    private void openChats() {
        cancelPending();
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    /** A watch's extra (programmable) buttons: press to talk, press again to finish. */
    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if ((code == android.view.KeyEvent.KEYCODE_STEM_1 || code == android.view.KeyEvent.KEYCODE_STEM_2
                || code == android.view.KeyEvent.KEYCODE_STEM_3) && e.getRepeatCount() == 0) {
            if (listening && rec != null) { userDone = true; rec.stopListening(); }
            else begin();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void openSetup() {
        startActivity(new Intent(this, SetupActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    // ---------------- listening ----------------

    private void begin() {
        cancelPending();
        autoRetried = false; // each tap of Try again gets its own quiet retry
        spokenSoFar = "";
        userDone = quickRetried = false;
        listenUnder = false;
        showingTo = false;
        awaiting = null; // a fresh start; "Try again" for a named group sets it back right after
        orphan = null;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stage.show(OrbView.Mode.IDLE, "Allow the microphone", "So I can hear your message.");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        listen();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) listen();
        else stage.show(OrbView.Mode.ERROR, "Microphone is off",
                "Allow it to talk, or type your message instead.",
                "Allow", (Runnable) this::begin,
                "Type it", (Runnable) () -> showTyping(null),
                "Chats", (Runnable) this::openChats); // reading chats needs no mic
    }

    private void listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            noVoice();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (rec == null && now - recDroppedAt < 400) { // a new one right after the old gets "disconnected"
            getWindow().getDecorView().postDelayed(this::listen, 400 - (now - recDroppedAt));
            return;
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new Listener());
        } else {
            rec.cancel(); // never two listens at once
        }
        ready = false;
        if (!quietRetry) stage.show(OrbView.Mode.LISTEN, "Listening", hint(), "Chats", (Runnable) this::openChats);
        quietRetry = false;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // a breath after "Yes," isn't the end of the message: wait for a real pause
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                // punctuation and capitals from the speech service itself (Android 13+)
                .putExtra("android.speech.extra.ENABLE_FORMATTING", "quality")
                // say it how you said it: no "f***" masking
                .putExtra("android.speech.extra.PROFANITY_FILTER", false)
                .putExtra("android.speech.extra.MASK_OFFENSIVE_WORDS", false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        listening = true;
        heardSoFar = "";
        pauses.reset();
        listenAt = android.os.SystemClock.elapsedRealtime();
        rec.startListening(i);
    }

    /** Sentences already finished (you can pause between them), and whether you tapped to finish. */
    private String spokenSoFar = "";
    private boolean userDone, quickRetried;
    private long listenAt;

    private static String joinSpoken(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " " + Tidy.PAUSE + " " + b; // the speech service stopped on a pause: a new sentence
    }

    /** Listen again after a pause, keeping what's been said, with the screen as it is. */
    /**
     * You paused, so the countdown that follows keeps listening under it: speak again before the
     * ring runs out and it stops and adds your words. Set at the pause; the countdown starts the
     * listen itself, because naming a group finds it first and the countdown comes a moment later.
     */
    private boolean listenUnder;
    /** Quick retries of a listen under the countdown that failed at once (the mic hadn't let go). */
    private int underRetries;

    private boolean listenQueued;

    private void keepListening() {
        if (listenQueued) return; // already on its way
        listenQueued = true;
        quietRetry = true;
        getWindow().getDecorView().postDelayed(() -> {
            listenQueued = false;
            // not if it already sent, or you cancelled, in the moment between
            if (!listening && !isFinishing() && (pendingSend != null || awaiting != null || orphan != null)) listen();
        }, 120);
    }

    /** The words heard so far in this listen, kept in case the speech service ends in an error. */
    private String heardSoFar = "";
    /** Where you paused while talking: commas and periods. */
    private final Pauses pauses = new Pauses();


    /** Set once the speech service has the mic open; a refusal before that gets one quiet retry. */
    private boolean ready, autoRetried, quietRetry;

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) { ready = true; }
        @Override public void onBeginningOfSpeech() { ready = true; }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override public void onRmsChanged(float db) {
            if (listening) stage.orb.setLevel(db); // the orb grows with your voice
        }

        @Override public void onPartialResults(Bundle b) {
            if (!listening) return;
            String t = first(b);
            if (t != null) {
                pauses.heard(t, android.os.SystemClock.elapsedRealtime());
                t = pauses.mark(t);
            }
            if (t != null && !t.isEmpty() && pendingSend != null) {
                // talking again before it sent: stop the countdown and keep adding
                cancelPending();
                stage.show(OrbView.Mode.LISTEN, "Listening", hint(), "Chats", (Runnable) MainActivity.this::openChats);
            }
            if (t != null) heardSoFar = t;
            // your words as you say them (earlier sentences kept), tidied as they go
            String all = joinSpoken(spokenSoFar, t);
            stage.live(all.isEmpty() ? null : Tidy.live(all));
            // the moment a group is recognized, the screen says where it's going, before you finish
            if (orphan != null && !all.isEmpty()) { // naming the group for the message you said
                Phrase a = Phrase.whichGroupAnswer(all, Store.groups(MainActivity.this));
                Phrase.Match m = a == null ? null : Phrase.match(Store.groups(MainActivity.this), a.group);
                if (m != null && m.group != null) {
                    stage.retitle("To " + m.group.name, quote(out(a.text.isEmpty() ? orphan : a.text)));
                }
                return;
            }
            if (awaiting == null && pendingSend == null && !all.isEmpty()) {
                Phrase named = Phrase.parse(all);
                if (named == null) named = Phrase.parseNamed(all, Store.groups(MainActivity.this));
                if (named != null) {
                    Phrase.Match m = Phrase.match(Store.groups(MainActivity.this), named.group);
                    if (m.group != null) {
                        stage.retitle("To " + m.group.name, named.text.isEmpty() ? "What's the message?" : "Your message");
                        showingTo = true;
                        return;
                    }
                }
                if (showingTo) { // the words changed and no longer name a group
                    stage.retitle("Listening", hint());
                    showingTo = false;
                }
            }
        }

        @Override public void onEndOfSpeech() {
            if (listening && userDone) stage.orb.setMode(OrbView.Mode.BUSY); // a pause isn't the end
        }

        @Override public void onResults(Bundle b) {
            if (!listening) return;
            listening = false;
            String heard = pauses.mark(first(b));
            if (heard == null && !heardSoFar.isEmpty()) heard = heardSoFar;
            String full = joinSpoken(spokenSoFar, heard);
            if (full.isEmpty()) {
                didntCatch();
            } else if (userDone || heard == null) { // tapped to finish, or stopped talking: that's it
                spokenSoFar = "";
                spoken = true;
                userDone = false;
                listenUnder = false;
                handle(full);
                if (awaiting != null || orphan != null) keepListening(); // half of it: listen for the rest
            } else { // a pause: count down as usual, but keep listening under it
                spokenSoFar = full;
                heardSoFar = "";
                spoken = true;
                listenUnder = true; // the countdown (now, or once the group is found) listens on
                underRetries = 0;
                handle(full);
                if (awaiting != null || orphan != null) { // the next words are the other half
                    spokenSoFar = "";
                    keepListening();
                }
            }
        }

        @Override public void onError(int code) {
            android.util.Log.w("SaySo", "speech error " + code + " ready=" + ready);
            if (!listening) return; // some watches report a late error after results
            listening = false;
            if (pendingSend != null) {
                resetRecognizer();
                boolean atOnce = android.os.SystemClock.elapsedRealtime() - listenAt < 1500
                        && code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
                if (atOnce && listenUnder && underRetries++ < 2) keepListening(); // the mic hadn't let go yet
                // otherwise: counting down and no more words came, so it simply sends
                return;
            }
            if (orphan != null && heardSoFar.isEmpty() && spokenSoFar.isEmpty()) {
                final String msg = orphan;
                resetRecognizer();
                stage.show(OrbView.Mode.ERROR, "Which group is it for?", quote(out(msg)) + "\nNothing was sent yet.",
                        "Try again", (Runnable) () -> askWhichGroup(msg),
                        "Chats", (Runnable) MainActivity.this::openChats);
                return;
            }
            if (awaiting != null && heardSoFar.isEmpty() && spokenSoFar.isEmpty()) {
                final Phrase.Group g = awaiting;
                resetRecognizer();
                stage.show(OrbView.Mode.ERROR, "What's the message for " + g.name + "?", "Nothing was sent.",
                        "Try again", (Runnable) () -> {
                            begin();
                            awaiting = g;
                            stage.retitle("To " + g.name, "What's the message?");
                        },
                        "Chats", (Runnable) MainActivity.this::openChats);
                return;
            }
            if (!ready && !autoRetried && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    && code != SpeechRecognizer.ERROR_NETWORK && code != SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                // refused before the mic even opened (the last listen hadn't let go): start once
                // more with a fresh recognizer, so one tap of Try again is enough
                autoRetried = true;
                resetRecognizer();
                quietRetry = true;
                getWindow().getDecorView().postDelayed(MainActivity.this::listen, 350);
                return;
            }
            boolean nothing = heardSoFar.isEmpty() && spokenSoFar.isEmpty();
            if (nothing && !quickRetried && android.os.SystemClock.elapsedRealtime() - listenAt < 2500
                    && (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    || code == SpeechRecognizer.ERROR_CLIENT)) {
                // gave up before you could have said anything: once more, quietly, like Try again
                quickRetried = true;
                resetRecognizer();
                quietRetry = true;
                getWindow().getDecorView().postDelayed(MainActivity.this::listen, 300);
                return;
            }
            if (!nothing && code != SpeechRecognizer.ERROR_NETWORK && code != SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                // you've stopped talking (or the speech service gave up after hearing you): use it all
                String heard = joinSpoken(spokenSoFar, heardSoFar);
                heardSoFar = "";
                spokenSoFar = "";
                resetRecognizer();
                spoken = true;
                handle(heard);
                return;
            }
            switch (code) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    didntCatch();
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    showRetry("No connection", "Check that your watch is connected to your phone or Wi-Fi.");
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    resetRecognizer();
                    stage.show(OrbView.Mode.ERROR, "Can't hear the mic",
                            Stage.isWatchBuild() ? "Another app may be using it. Try again, or type it."
                                    : "Another app may be using the microphone. Try again, or type it.",
                            "Try again", (Runnable) MainActivity.this::begin,
                            "Type it", (Runnable) () -> showTyping(null));
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    resetRecognizer(); // a stuck recognizer: start fresh next time
                    didntCatch();
                    break;
                default:
                    resetRecognizer();
                    noVoice(); // stay in Say So; never hand off to the system voice screen
            }
        }
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty() || r.get(0).trim().isEmpty()) ? null : r.get(0);
    }

    /**
     * No speech recognizer on this device (or it failed to start). Say So stays on its own screen
     * instead of opening the system voice screen: try again, or type it.
     */
    private void noVoice() {
        stage.show(OrbView.Mode.ERROR, "Voice isn't set up",
                Stage.isWatchBuild()
                        ? "Update Speech Recognition & Synthesis in the watch's Play Store, or type it."
                        : "Update Speech Services by Google in the Play Store, or type it.",
                "Type it", (Runnable) () -> showTyping(null),
                "Try again", (Runnable) this::begin);
        if (Stage.isWatchBuild()) stage.hideHero(); // room for the explanation on a round screen
    }

    /** When the recognizer was last thrown away: the next one waits a moment (see listen()). */
    private long recDroppedAt;

    private void resetRecognizer() {
        if (rec != null) {
            rec.destroy();
            rec = null;
            recDroppedAt = android.os.SystemClock.elapsedRealtime();
        }
    }


    /** Speech came back empty (noise, too quiet, or the speech service failed): retry or type it. */
    private void didntCatch() {
        stage.show(OrbView.Mode.ERROR, "I didn't catch that.", "Nothing was sent.",
                "Try again", (Runnable) this::begin,
                "Type it", (Runnable) () -> showTyping(null),
                "Chats", (Runnable) this::openChats);
    }

    /** Type the same thing you'd say. Goes through exactly the same steps as speech. */
    private void showTyping(String why) {
        cancelPending();
        listenUnder = false; // typed: nothing to listen for under the countdown
        EditText e = new EditText(this);
        Press.field(e);
        Phrase.Group d = Store.defaultGroup(this);
        e.setHint(d != null ? "Your message for " + d.name : "Tell Family hi");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        Runnable send = () -> {
            String t = e.getText().toString().trim();
            if (t.isEmpty()) return;
            hideKeyboard(e);
            spoken = false; // typed: sent exactly as typed
            handle(t);
        };
        e.setOnEditorActionListener((v, action, ev) -> {
            send.run();
            return true;
        });
        stage.show(OrbView.Mode.IDLE, "Type it", why != null ? why
                        : d != null ? "Your message for " + d.name + ", or name a group anywhere in it." : hint(),
                "Send", send,
                "Talk", (Runnable) this::begin).extra(e);
        if (Stage.isWatchBuild()) stage.hideHero(); // room for the field and buttons on a round screen
        e.requestFocus();
    }

    private void hideKeyboard(android.view.View v) {
        android.view.inputmethod.InputMethodManager m = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (m != null) m.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---------------- understanding + sending ----------------

    /** What to say, shown while listening. */
    private String hint() {
        Phrase.Group d = Store.defaultGroup(this);
        return d == null ? "Say your message and which group"
                : "Say your message for " + d.name + ", or name a group";
    }

    /** What happens when the countdown ends: send, reply, DM or like. Runs off the UI thread. */
    interface Action {
        void run(String token) throws IOException;
    }

    /**
     * A message that didn't go out because GroupMe signed you out. Kept in memory only (never
     * saved to storage), so after you sign back in as the same person you can send it with one tap.
     */
    static final class Unsent {
        static final long MAX_AGE_MS = 15 * 60 * 1000L;
        final String title, body, busy, done, userId;
        final Action action;
        final long at = System.currentTimeMillis();

        Unsent(String title, String body, String busy, String done, String userId, Action action) {
            this.title = title;
            this.body = body;
            this.busy = busy;
            this.done = done;
            this.userId = userId;
            this.action = action;
        }

        boolean freshFor(String user) {
            return System.currentTimeMillis() - at < MAX_AGE_MS && !userId.isEmpty() && userId.equals(user);
        }
    }

    static Unsent unsent;
    static final String EXTRA_RESEND = "resend";
    private String shownTitle, shownBody;

    private static String quote(String t) { return "\u201C" + Tidy.breaks(t) + "\u201D"; }

    private boolean tooLong(String text) {
        if (text.length() <= MAX_TEXT) return false;
        showRetry("That's too long.", "GroupMe allows " + MAX_TEXT + " characters.");
        return true;
    }

    /** Whether the words came from the mic (tidied: capital, ending mark) or were typed (left as is). */
    private boolean spoken = true;

    private String out(String text) { return spoken ? Tidy.spoken(text) : text; }

    /** You named only a group: it's waiting for the message (the mic stays on). */
    private Phrase.Group awaiting;
    /** The title says "To <group>" from what you have said so far. */
    private boolean showingTo;

    /** You said a message but no group (and there's no default): it's kept while you name one. */
    private String orphan;

    /** Keeps {@code msg} and listens for which group it goes to. */
    private void askWhichGroup(String msg) {
        begin();
        orphan = msg;
        stage.retitle("Which group?", quote(out(msg)));
    }

    private void handle(String heard) {
        if (orphan != null) { // the group for the message you already said
            String msg = orphan;
            orphan = null;
            String said = heard == null ? "" : heard.trim();
            Phrase a = Phrase.whichGroupAnswer(said, Store.groups(this));
            Phrase.Match m = a == null ? null : Phrase.match(Store.groups(this), a.group);
            if (m != null && m.group != null) {
                String text = a.text.isEmpty() ? msg : a.text; // the group alone keeps what you said before
                if (!tooLong(text)) confirmGroup(m.group, text);
            } else if (said.isEmpty()) {
                stopListeningQuietly();
                stage.show(OrbView.Mode.ERROR, "Which group is it for?", quote(out(msg)) + "\nNothing was sent yet.",
                        "Try again", (Runnable) () -> askWhichGroup(msg),
                        "Chats", (Runnable) this::openChats);
            } else if (!tooLong(said)) {
                // no group in it: you said the message again, so that's the message now, still asking where
                orphan = said;
                spokenSoFar = "";
                stage.show(OrbView.Mode.LISTEN, "Which group?", quote(out(said)), "Chats", (Runnable) this::openChats);
            }
            return;
        }
        if (awaiting != null) { // the message for the group you just named
            Phrase.Group g = awaiting;
            awaiting = null;
            String text = heard == null ? "" : heard.trim();
            if (!text.isEmpty() && !tooLong(text)) confirmGroup(g, text);
            return;
        }
        if (Phrase.parseLike(heard)) {           // "like that", "heart that message"
            withLatest(null);
            return;
        }
        String reply = Phrase.parseReply(heard); // "reply saying on my way"
        if (reply != null) {
            if (!tooLong(reply)) withLatest(reply);
            return;
        }
        Phrase dm = Phrase.parseDm(heard);       // "DM Sam saying hi"
        if (dm != null) {
            if (!tooLong(dm.text)) find(dm, true);
            return;
        }
        Phrase p = Phrase.parse(heard);          // "Weekend Crew that says hi"
        if (p == null) {                         // "Weekend Crew I'm late", "tell Weekend Crew I'm late"
            Phrase named = Phrase.parseNamed(heard, Store.groups(this));
            if (named != null) {
                if (named.text.isEmpty()) { // just the name: ask for the message, still listening
                    Phrase.Match m = Phrase.match(Store.groups(this), named.group);
                    if (m.group != null) {
                        awaiting = m.group;
                        spokenSoFar = "";
                        stage.show(OrbView.Mode.LISTEN, "To " + m.group.name, "What's the message?", "Chats", (Runnable) this::openChats);
                        return;
                    }
                } else {
                    p = named;
                }
            }
        }
        if (p == null) {
            // No group named. With a default group set, everything you said is the message.
            Phrase.Group d = Store.defaultGroup(this);
            String text = heard == null ? "" : heard.trim();
            if (d != null && !text.isEmpty()) {
                if (!tooLong(text)) confirmGroup(d, text);
                return;
            }
            if (spoken && !text.isEmpty() && !tooLong(text)) { // keep it and ask where, still listening
                orphan = text;
                spokenSoFar = "";
                stage.show(OrbView.Mode.LISTEN, "Which group?", quote(out(text)), "Chats", (Runnable) this::openChats);
                return;
            }
            showRetry("Name a group in it:", "\"Tell Family I'm on my way\"\n\nI heard: \"" + heard + "\"");
            return;
        }
        if (!tooLong(p.text)) find(p, false);
    }

    /**
     * Finds who you named: your groups first, then people you have direct messages with.
     * A name that matches nothing never falls back to the default group.
     */
    private void find(Phrase p, boolean dmOnly) {
        stage.show(OrbView.Mode.BUSY, "Finding " + p.group, quote(p.text));
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                Phrase.Match g = null;
                if (!dmOnly) {
                    g = Phrase.match(Store.groups(this), p.group);
                    if (g.group == null || Store.groupsAgeMs(this) > GROUP_CACHE_MS) {
                        List<Phrase.Group> fresh = Api.listGroups(token);
                        Store.saveGroups(this, fresh);
                        g = Phrase.match(fresh, p.group);
                    }
                    final Phrase.Match found = g;
                    if (found.group != null) {
                        ui.post(() -> confirmGroup(found.group, p.text));
                        return;
                    }
                    if (found.problem.startsWith("Which one") || found.problem.startsWith("You have")) {
                        ui.post(() -> showRetry(found.problem, "Say more of the name. Nothing was sent."));
                        return;
                    }
                }
                Phrase.Match c = Phrase.match(Store.contacts(this), p.group);
                if (c.group == null || Store.contactsAgeMs(this) > GROUP_CACHE_MS) {
                    List<Phrase.Group> fresh = Api.listContacts(token);
                    Store.saveContacts(this, fresh);
                    c = Phrase.match(fresh, p.group);
                }
                final Phrase.Match person = c;
                final String problem = dmOnly || g == null ? person.problem
                        .replace("a group called", "anyone called").replace("groups named", "people named") : g.problem;
                ui.post(() -> {
                    if (person.group != null) confirmDm(person.group, p.text);
                    else showRetry(problem, "Nothing was sent.");
                });
            } catch (IOException e) {
                ui.post(() -> showError(e));
            }
        });
    }

    /** Reply to (replyText != null) or like (replyText == null) the newest message someone sent you. */
    private void withLatest(String replyText) {
        stage.show(OrbView.Mode.BUSY, "Finding the last message", replyText == null ? null : quote(replyText));
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                String myId = Store.myId(this);
                if (myId.isEmpty()) { // signed in before this existed
                    myId = Api.me(token)[0];
                    Store.setMyId(this, myId);
                }
                Api.Incoming in = Api.latestIncoming(token, myId);
                ui.post(() -> {
                    if (in == null) {
                        showRetry(replyText == null ? "No message to like" : "No message to reply to", "Nothing was sent.");
                    } else if (replyText == null) {
                        confirmLike(in);
                    } else {
                        confirmReply(in, replyText);
                    }
                });
            } catch (IOException e) {
                ui.post(() -> showError(e));
            }
        });
    }

    private static String where(Api.Incoming in) { return in.isDm ? "Direct" : in.groupName; }

    private static String clip(String t) { return t.length() > 60 ? t.substring(0, 57) + "..." : t; }

    private void confirmGroup(Phrase.Group g, String raw) {
        final String text = out(raw);
        countdown("To " + g.name, quote(text), "Send now", "Sending", "Sent to " + g.name,
                t -> Api.send(t, g.id, text, null));
    }

    private void confirmDm(Phrase.Group person, String raw) {
        final String text = out(raw);
        countdown("To " + person.name + " (direct)", quote(text), "Send now", "Sending", "Sent to " + person.name,
                t -> Api.sendDm(t, person.id, text));
    }

    private void confirmReply(Api.Incoming in, String raw) {
        final String text = out(raw);
        countdown("Reply to " + in.sender, where(in) + ": " + quote(clip(in.text)) + "\nYou: " + quote(text),
                "Send now", "Sending", "Replied to " + in.sender,
                t -> {
                    if (in.isDm) Api.sendDm(t, in.otherUserId, text);
                    else Api.send(t, in.groupId, text, in.id);
                });
    }

    private void confirmLike(Api.Incoming in) {
        countdown("Like " + in.sender + "'s message", where(in) + ": " + quote(clip(in.text)),
                "Like now", "Liking", "Liked",
                t -> Api.like(t, in.conversationId, in.id));
    }

    /** Shows what's about to happen while the ring counts down. Cancel (or leaving) stops it. */
    private void countdown(String title, String body, String nowLabel, String busy, String done, Action action) {
        if (isFinishing()) return;
        shownTitle = title;
        shownBody = body;
        stage.show(OrbView.Mode.COUNTDOWN, title, body,
                "Cancel", (Runnable) () -> {
                    cancelPending();
                    stopListeningQuietly();
                    showRetry("Cancelled", "Nothing was sent.");
                },
                nowLabel, (Runnable) () -> {
                    cancelPending();
                    run(busy, done, action);
                });
        pendingSend = () -> {
            pendingSend = null;
            run(busy, done, action);
        };
        ui.postDelayed(pendingSend, SEND_DELAY_MS);
        // came after a pause (maybe a moment later, once the group was found): listen under it
        if (listenUnder && !listening) keepListening();
    }

    private void run(String busy, String done, Action action) {
        stopListeningQuietly(); // anything still listening under the countdown
        stage.show(OrbView.Mode.BUSY, busy, null);
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                action.run(token);
                ui.post(() -> {
                    buzz();
                    stage.show(OrbView.Mode.DONE, done, null);
                    ui.postDelayed(() -> {
                        finish();
                        overridePendingTransition(0, android.R.anim.fade_out);
                    }, 2200);
                });
            } catch (IOException e) {
                if (e instanceof Api.AuthException) {
                    unsent = new Unsent(shownTitle, shownBody, busy, done, Store.myId(this), action);
                }
                ui.post(() -> showError(e));
            }
        });
    }

    private void stopListeningQuietly() {
        if (listening && rec != null) rec.cancel();
        listening = false;
        listenUnder = false;
        spokenSoFar = "";
        heardSoFar = "";
    }

    private void cancelPending() {
        if (pendingSend != null) {
            ui.removeCallbacks(pendingSend);
            pendingSend = null;
        }
    }

    // ---------------- result screens ----------------

    private void showRetry(String title, String body) {
        stage.show(OrbView.Mode.ERROR, title, body,
                "Try again", (Runnable) this::begin,
                "Chats", (Runnable) this::openChats);
    }

    private void showError(IOException e) {
        if (isFinishing()) return;
        if (e instanceof Api.AuthException) {
            // Expired, reset on dev.groupme.com, or a changed password. Forget the old token so
            // "Sign in again" really goes to sign-in, not to the "Signed in as" screen.
            Store.forgetSignIn(this);
            stage.show(OrbView.Mode.ERROR, "GroupMe signed you out",
                    unsent != null
                            ? "Your sign-in stopped working, so nothing was sent. Sign in again and I'll keep your message ready."
                            : "Your sign-in stopped working, so nothing was sent. Sign in again, then say it once more.",
                    "Sign in again", (Runnable) this::openSetup);
            return;
        }
        String msg = e.getMessage();
        if (msg == null || !msg.startsWith("GroupMe")) {
            msg = "Couldn't reach GroupMe. Check that your watch is connected to your phone or Wi-Fi.";
        }
        showRetry("Not sent", msg);
    }

    private void buzz() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
