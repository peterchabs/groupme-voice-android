package io.github.peterchabs.sayso;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.io.IOException;

/** "Reply" on a message notification (spoken or typed, on a watch or a phone): sends it to that chat. */
public class ReplyReceiver extends BroadcastReceiver {
    /** "Mark as read" (used by the phone's assistant after reading a chat aloud): just clears it. */
    static final String ACTION_READ = "io.github.peterchabs.sayso.READ";

    @Override
    public void onReceive(Context c, Intent intent) {
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
        final String token = Store.token(c);
        if (key == null || id == null || token.isEmpty()) return;
        final PendingResult done = goAsync(); // the network call outlives onReceive
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Feed.send(token, new Feed.Convo(id, name == null ? "" : name, "", dm, 0, ""), text);
                PushService.addMine(app, key, id, name, dm, text);
            } catch (IOException e) {
                PushService.addMine(app, key, id, name, dm, "Not sent (no connection): " + text);
            } finally {
                done.finish();
            }
        }, "sayso-reply").start();
    }
}
