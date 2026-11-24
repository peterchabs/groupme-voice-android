package io.github.peterchabs.groupmevoice;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
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
import android.widget.EditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Hey Google, open GroupMe Voice" lands here. It listens right away, with the orb reacting
 * to your voice and your words appearing as you say them. It reads "[group] that says
 * [message]", shows who it's sending to while a ring counts down 3 seconds (tap Cancel to
 * stop), then sends.
 */
public class MainActivity extends Activity {
    private static final int REQ_SPEECH = 1, REQ_MIC = 2;
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
        stage.orb.setCountdownMs(SEND_DELAY_MS);
        stage.orb.setOnClickListener(v -> {
            if (listening) rec.stopListening();               // tap to finish early
            else if (stage.orb.mode() != OrbView.Mode.BUSY
                    && stage.orb.mode() != OrbView.Mode.COUNTDOWN) begin();
        });
        if (Store.token(this).isEmpty()) {
            openSetup();
            return;
        }
        begin();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); // "Hey Google, open GroupMe Voice" while already open
        cancelPending();
        if (!Store.token(this).isEmpty()) begin();
    }

    @Override
    protected void onStop() {
        if (listening && rec != null) {
            listening = false;
            rec.cancel();
            stage.show(OrbView.Mode.IDLE, "Tap to talk", null, "Settings", (Runnable) this::openSetup);
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

    private void openSetup() {
        startActivity(new Intent(this, SetupActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    // ---------------- listening ----------------

    private void begin() {
        cancelPending();
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
        else systemListen(); // the system voice screen doesn't need our mic permission
    }

    private void listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            systemListen();
            return;
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new Listener());
        }
        stage.show(OrbView.Mode.LISTEN, "Listening", hint());
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        listening = true;
        rec.startListening(i);
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override public void onRmsChanged(float db) {
            if (listening) stage.orb.setLevel(db); // the orb grows with your voice
        }

        @Override public void onPartialResults(Bundle b) {
            if (listening) stage.live(first(b)); // your words, as you say them
        }

        @Override public void onEndOfSpeech() {
            if (listening) stage.orb.setMode(OrbView.Mode.BUSY);
        }

        @Override public void onResults(Bundle b) {
            if (!listening) return;
            listening = false;
            String heard = first(b);
            if (heard == null) showRetry("I didn't catch that.", "Nothing was sent.");
            else handle(heard);
        }

        @Override public void onError(int code) {
            if (!listening) return; // some watches report a late error after results
            listening = false;
            switch (code) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    showRetry("I didn't catch that.", "Nothing was sent.");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    showRetry("No connection", "Check that your watch is connected to your phone or Wi-Fi.");
                    break;
                default:
                    systemListen(); // fall back to the watch's own voice screen
            }
        }
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty() || r.get(0).trim().isEmpty()) ? null : r.get(0);
    }

    /** The watch's built-in voice screen, for watches where in-app listening isn't available. */
    private void systemListen() {
        stage.show(OrbView.Mode.LISTEN, "Listening", hint());
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Which group, and what should I say?");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            showTyping();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_SPEECH) return;
        ArrayList<String> heard = (result == RESULT_OK && data != null)
                ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        if (heard == null || heard.isEmpty()) showRetry("I didn't catch that.", "Nothing was sent.");
        else handle(heard.get(0));
    }

    private void showTyping() {
        EditText e = new EditText(this);
        Press.field(e);
        e.setHint("Family that says hi");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        stage.show(OrbView.Mode.IDLE, "Type or dictate", "Voice isn't available on this watch.",
                "Send", (Runnable) () -> handle(e.getText().toString()),
                "Settings", (Runnable) this::openSetup).extra(e);
    }

    // ---------------- understanding + sending ----------------

    /** What to say, shown while listening. */
    private String hint() {
        Phrase.Group d = Store.defaultGroup(this);
        return d == null ? "Say: [group] that says [message]"
                : "Say your message for " + d.name + ", or [group] that says [message]";
    }

    /** What happens when the countdown ends: send, reply, DM or like. Runs off the UI thread. */
    private interface Action {
        void run(String token) throws IOException;
    }

    private static String quote(String t) { return "\u201C" + t + "\u201D"; }

    private boolean tooLong(String text) {
        if (text.length() <= MAX_TEXT) return false;
        showRetry("That's too long.", "GroupMe allows " + MAX_TEXT + " characters.");
        return true;
    }

    private void handle(String heard) {
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
        if (p == null) {
            // No group named. With a default group set, everything you said is the message.
            Phrase.Group d = Store.defaultGroup(this);
            String text = heard == null ? "" : heard.trim();
            if (d != null && !text.isEmpty()) {
                if (!tooLong(text)) confirmGroup(d, text);
                return;
            }
            showRetry("Say it like this:", "\"Family that says on my way\"\n\nI heard: \"" + heard + "\"");
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

    private void confirmGroup(Phrase.Group g, String text) {
        countdown("To " + g.name, quote(text), "Send now", "Sending", "Sent to " + g.name,
                t -> Api.send(t, g.id, text, null));
    }

    private void confirmDm(Phrase.Group person, String text) {
        countdown("To " + person.name + " (direct)", quote(text), "Send now", "Sending", "Sent to " + person.name,
                t -> Api.sendDm(t, person.id, text));
    }

    private void confirmReply(Api.Incoming in, String text) {
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
        stage.show(OrbView.Mode.COUNTDOWN, title, body,
                "Cancel", (Runnable) () -> {
                    cancelPending();
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
    }

    private void run(String busy, String done, Action action) {
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
                ui.post(() -> showError(e));
            }
        });
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
                "Settings", (Runnable) this::openSetup);
    }

    private void showError(IOException e) {
        if (isFinishing()) return;
        if (e instanceof Api.AuthException) {
            stage.show(OrbView.Mode.ERROR, "Please sign in again", "GroupMe stopped accepting your sign-in.",
                    "Sign in", (Runnable) this::openSetup);
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
