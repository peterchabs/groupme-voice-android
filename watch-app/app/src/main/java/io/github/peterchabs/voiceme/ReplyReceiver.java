package io.github.peterchabs.voiceme;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.io.IOException;

/** "Reply" on a message notification (spoken or typed, on a watch or a phone): sends it to that chat. */
public class ReplyReceiver extends BroadcastReceiver {
    /** "Mark as read" (used by the phone's assistant after reading a chat aloud): just clears it. */
    static final String ACTION_READ = "io.github.peterchabs.voiceme.READ";
    /** "Accept" on a DM request's notification: lets them message you. */
    static final String ACTION_ACCEPT = "io.github.peterchabs.voiceme.ACCEPT";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION_ACCEPT.equals(intent.getAction())) {
            final String who = intent.getStringExtra(PushService.EXTRA_ID), k = intent.getStringExtra(PushService.EXTRA_KEY);
            final String tok = Store.token(c);
            if (who == null || tok.isEmpty()) return;
            final PendingResult wait = goAsync();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    Feed.answerRequest(tok, who, true);
                    java.util.Set<String> r = new java.util.HashSet<>(Store.requests(app));
                    r.remove(who);
                    Store.setRequests(app, r);
                    if (k != null) PushService.clear(app, k);
                } catch (java.io.IOException ignored) {
                    // still a request; Accept again later
                } finally {
                    wait.finish();
                }
            }, "voiceme-accept").start();
            return;
        }
        if (ACTION_READ.equals(intent.getAction())) {
            String key = intent.getStringExtra(PushService.EXTRA_KEY);
            if (key != null) PushService.clear(c, key);
            return;
        }
        Bundle in = RemoteInput.getResultsFromIntent(intent);
        CharSequence said = in == null ? null : in.getCharSequence(PushService.REPLY);
        if (said == null || said.toString().trim().isEmpty()) return;
        final String text = Tidy.spoken(said.toString());
        final String key = intent.getStringExtra(PushService.EXTRA_KEY);
        final String id = intent.getStringExtra(PushService.EXTRA_ID);
        final String name = intent.getStringExtra(PushService.EXTRA_NAME);
        final boolean dm = intent.getBooleanExtra(PushService.EXTRA_DM, false);
        final String replyTo = intent.getStringExtra(PushService.EXTRA_REPLY_TO);
        final String token = Store.token(c);
        if (key == null || id == null || token.isEmpty()) return;
        final PendingResult done = goAsync(); // the network call outlives onReceive
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                // a real GroupMe reply to the message it answers, quoting it (unless that's turned off)
                String quote = replyTo == null || !Store.quoteReplies(app) ? "" : replyTo;
                Feed.sendTagged(token, new Feed.Convo(id, name == null ? "" : name, "", dm, 0, ""),
                        new java.util.ArrayList<>(), text, quote);
                PushService.addMine(app, key, id, name, dm, text);
            } catch (Api.RefusedException e) {
                PushService.addMine(app, key, id, name, dm, "Not sent (" + e.getMessage() + "): " + text);
            } catch (IOException e) {
                PushService.addMine(app, key, id, name, dm, "Not sent (no connection): " + text);
            } finally {
                done.finish();
            }
        }, "voiceme-reply").start();
    }
}
