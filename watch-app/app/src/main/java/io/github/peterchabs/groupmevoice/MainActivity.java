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
        stage.show(OrbView.Mode.LISTEN, "Listening", "Say: [group] that says [message]");
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
        stage.show(OrbView.Mode.LISTEN, "Listening", "Say: [group] that says [message]");
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
        e.setHint("Family that says hi");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        stage.show(OrbView.Mode.IDLE, "Type or dictate", "Voice isn't available on this watch.",
                "Send", (Runnable) () -> handle(e.getText().toString()),
                "Settings", (Runnable) this::openSetup).extra(e);
    }

    // ---------------- understanding + sending ----------------

    private void handle(String heard) {
        Phrase p = Phrase.parse(heard);
        if (p == null) {
            showRetry("Say it like this:", "\"Family that says on my way\"\n\nI heard: \"" + heard + "\"");
            return;
        }
        if (p.text.length() > MAX_TEXT) {
            showRetry("That's too long.", "GroupMe allows " + MAX_TEXT + " characters.");
            return;
        }
        stage.show(OrbView.Mode.BUSY, "Finding " + p.group, "“" + p.text + "”");
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                Phrase.Match m = Phrase.match(Store.groups(this), p.group);
                if (m.group == null || Store.groupsAgeMs(this) > GROUP_CACHE_MS) {
                    List<Phrase.Group> fresh = Api.listGroups(token);
                    Store.saveGroups(this, fresh);
                    m = Phrase.match(fresh, p.group);
                }
                final Phrase.Match match = m;
                ui.post(() -> confirm(p, match));
            } catch (IOException e) {
                ui.post(() -> showError(e));
            }
        });
    }

    private void confirm(Phrase p, Phrase.Match m) {
        if (isFinishing()) return;
        if (m.group == null) {
            showRetry(m.problem, "Nothing was sent.");
            return;
        }
        stage.show(OrbView.Mode.COUNTDOWN, "To " + m.group.name, "“" + p.text + "”",
                "Cancel", (Runnable) () -> {
                    cancelPending();
                    showRetry("Cancelled", "Nothing was sent.");
                },
                "Send now", (Runnable) () -> {
                    cancelPending();
                    send(m.group, p.text);
                });
        pendingSend = () -> {
            pendingSend = null;
            send(m.group, p.text);
        };
        ui.postDelayed(pendingSend, SEND_DELAY_MS);
    }

    private void send(Phrase.Group g, String text) {
        stage.show(OrbView.Mode.BUSY, "Sending", "“" + text + "”");
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                Api.send(token, g.id, text);
                ui.post(() -> {
                    buzz();
                    stage.show(OrbView.Mode.DONE, "Sent to " + g.name, null);
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
