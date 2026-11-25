package io.github.peterchabs.sayso;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps GroupMe's live feed open (see Push) while notifications are on, and turns each new
 * message into a notification you can reply to. Android requires a small ongoing notification
 * while it runs. Off unless you switch it on in Chats settings.
 */
public class PushService extends Service {
    static final String CH_MESSAGES = "messages", CH_RUNNING = "running";
    static final String EXTRA_KEY = "convo_key", EXTRA_ID = "convo_id", EXTRA_NAME = "convo_name", EXTRA_DM = "convo_dm";
    static final String REPLY = "reply";
    private static final int RUNNING_ID = 1;

    /** The chat on screen right now ("g:123" / "dm:456"): no notification for what you're reading. */
    static volatile String visibleKey;

    /** One line in a chat's notification: who, their picture, what, when. */
    static final class Line {
        final String sender, text;
        final long at;
        final android.graphics.Bitmap avatar; // may be null

        Line(String sender, String text, long at, android.graphics.Bitmap avatar) {
            this.sender = sender;
            this.text = text;
            this.at = at;
            this.avatar = avatar;
        }
    }

    /** The last few lines per chat, so a notification shows the conversation, not just one line. */
    private static final Map<String, List<Line>> HISTORY = new HashMap<>();

    private volatile boolean running;
    private Thread loop;

    /** Starts or stops the service to match the setting (and whether you're signed in). */
    static void sync(Context c) {
        Bridge.update(c); // watch: its own notifications replace the phone's copies, never both
        Intent i = new Intent(c, PushService.class);
        if (Store.pushOn(c) && !Store.token(c).isEmpty() && !Store.myId(c).isEmpty()) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } else {
            c.stopService(i);
        }
    }

    /** Opening a chat clears its notification. */
    static void clear(Context c, String key) {
        synchronized (HISTORY) { HISTORY.remove(key); }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(key.hashCode());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        channels(this);
        // No title: the app name already heads the card, and a watch tints titles in its own accent.
        Notification on = new Notification.Builder(this, CH_RUNNING)
                .setSmallIcon(R.drawable.ic_stat_sayso)
                .setContentText("Getting your GroupMe messages")
                .setOngoing(true)
                .setContentIntent(openChats(this, null, null, null, false))
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(RUNNING_ID, on, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        else startForeground(RUNNING_ID, on);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (loop == null || !loop.isAlive()) {
            running = true;
            loop = new Thread(this::listen, "sayso-push");
            loop.start();
        }
        return START_STICKY; // Android restarts it if it has to stop it
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void listen() {
        long wait = 2000;
        while (running) {
            String token = Store.token(this), me = Store.myId(this);
            if (token.isEmpty() || me.isEmpty() || !Store.pushOn(this)) break;
            try {
                Push push = new Push(token, me);
                push.open();
                android.util.Log.i("SaySo", "push: connected to GroupMe's live feed");
                wait = 2000;
                // GroupMe sometimes keeps a connection open that never delivers anything (seen after
                // an app update). A fresh handshake every 10 minutes heals that quietly.
                long fresh = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L;
                while (running && android.os.SystemClock.elapsedRealtime() < fresh) {
                    for (Push.Event e : push.poll()) {
                        android.util.Log.i("SaySo", "push: new message in " + (e.dm ? "a DM" : "a group"));
                        show(e);
                    }
                }
            } catch (Api.AuthException e) {
                android.util.Log.w("SaySo", "push: GroupMe refused the sign-in; stopping");
                break; // signed out: nothing to listen for
            } catch (IOException | RuntimeException e) {
                android.util.Log.i("SaySo", "push: connection dropped (" + e.getMessage() + "), retrying in " + wait / 1000 + "s");
                // dropped connection or no network: try again, waiting longer each time (max 1 min)
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    break;
                }
                wait = Math.min(60000, wait * 2);
            }
        }
        stopSelf();
    }

    private void show(Push.Event e) {
        String key = (e.dm ? "dm:" : "g:") + e.convoId;
        if (key.equals(visibleKey)) return;
        String title = e.dm ? e.sender : groupName(e.convoId);
        String line = e.text.isEmpty() ? (e.photo ? "📷 Photo" : "Sent something") : (e.photo ? "📷 " + e.text : e.text);
        // their GroupMe picture, round, beside their name (fetched here, on the listener thread)
        android.graphics.Bitmap pic = Images.roundNow(this, Feed.sized(e.avatar, "avatar"), 128);
        List<Line> lines;
        synchronized (HISTORY) {
            lines = HISTORY.computeIfAbsent(key, k -> new ArrayList<>());
            lines.add(new Line(e.sender, line, System.currentTimeMillis(), pic));
            while (lines.size() > 6) lines.remove(0);
            lines = new ArrayList<>(lines);
        }
        notify(this, key, e.convoId, title, e.dm, lines);
    }

    /** A person in the conversation: name plus picture (Android 9+ shows the picture). */
    private static android.app.Person person(String name, android.graphics.Bitmap pic, String key) {
        android.app.Person.Builder b = new android.app.Person.Builder().setName(name).setKey(key);
        if (pic != null) b.setIcon(android.graphics.drawable.Icon.createWithBitmap(pic));
        return b.build();
    }

    /** Builds (or rebuilds) one chat's notification: the conversation so far, Reply, and tap to open. */
    static void notify(Context c, String key, String id, String title, boolean dm, List<Line> lines) {
        channels(c);
        Notification.MessagingStyle style;
        if (Build.VERSION.SDK_INT >= 28) {
            android.app.Person me = person("You", Images.roundNow(c, Feed.sized(Store.avatar(c), "avatar"), 128), "me");
            style = new Notification.MessagingStyle(me);
            for (Line l : lines) {
                android.app.Person who = l.sender.equals("You") ? me : person(l.sender, l.avatar, l.sender);
                style.addMessage(new Notification.MessagingStyle.Message(l.text, l.at, who));
            }
            style.setGroupConversation(!dm);
        } else {
            style = new Notification.MessagingStyle("You");
            for (Line l : lines) style.addMessage(l.text, l.at, l.sender);
        }
        if (!dm) style.setConversationTitle(title);
        Line last = lines.get(lines.size() - 1);
        RemoteInput input = new RemoteInput.Builder(REPLY).setLabel("Reply to " + title).build();
        Intent ri = new Intent(c, ReplyReceiver.class)
                .putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, title).putExtra(EXTRA_DM, dm);
        PendingIntent reply = PendingIntent.getBroadcast(c, key.hashCode(), ri,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        Notification.Action.Builder ab = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_sayso), "Reply", reply)
                .addRemoteInput(input).setAllowGeneratedReplies(true);
        // Marked as a reply that needs no screen, so the phone's assistant can send one by voice
        // ("Hey Google, reply to Test ...") the way it does for other messaging apps.
        if (Build.VERSION.SDK_INT >= 28) ab.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY);
        android.os.Bundle noUi = new android.os.Bundle();
        noUi.putBoolean("android.support.action.showsUserInterface", false); // what AndroidX writes
        ab.addExtras(noUi);
        Notification.Action act = ab.build();
        // A hidden "mark as read" the assistant uses after reading the chat aloud.
        PendingIntent markRead = PendingIntent.getBroadcast(c, key.hashCode() ^ 0x5a5a,
                new Intent(c, ReplyReceiver.class).setAction(ReplyReceiver.ACTION_READ).putExtra(EXTRA_KEY, key),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Style shown = style;
        String summary = last.text;
        if (Stage.isWatchBuild()) {
            // Wear draws chat-style notifications as left-hugging bubbles the round edge cuts off;
            // centered "Name: message" lines read cleanly on a circle.
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(dm && !l.sender.equals("You") ? l.text : l.sender + ": " + l.text);
            }
            shown = new Notification.BigTextStyle().bigText(sb).setBigContentTitle(title);
            summary = dm || last.sender.equals("You") ? last.text : last.sender + ": " + last.text;
        }
        Notification.Builder nb = new Notification.Builder(c, CH_MESSAGES);
        nb.addExtras(invisibleMarkAsRead(markRead)); // hidden: no extra button on screen
        Notification n = nb
                .setSmallIcon(R.drawable.ic_stat_sayso)
                .setStyle(shown)
                .setContentTitle(title)
                .setContentText(summary)
                .setLargeIcon(last.avatar) // who sent it, on the watch too
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setColor(Palette.BLUE)
                .addAction(act)
                .setContentIntent(openChats(c, key, id, title, dm))
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(key.hashCode(), n);
    }

    /**
     * A "mark as read" action that never shows as a button, stored the way AndroidX's
     * NotificationCompat.addInvisibleAction stores it, which is where assistants look for it.
     */
    static android.os.Bundle invisibleMarkAsRead(PendingIntent pi) {
        android.os.Bundle a = new android.os.Bundle();
        a.putInt("icon", R.drawable.ic_stat_sayso);
        a.putCharSequence("title", "Mark as read");
        a.putParcelable("actionIntent", pi);
        android.os.Bundle ax = new android.os.Bundle();
        ax.putBoolean("android.support.allowGeneratedReplies", false);
        a.putBundle("extras", ax);
        a.putBoolean("showsUserInterface", false);
        a.putInt("semanticAction", 2); // SEMANTIC_ACTION_MARK_AS_READ
        android.os.Bundle list = new android.os.Bundle();
        list.putBundle("0", a);
        android.os.Bundle car = new android.os.Bundle();
        car.putBundle("invisible_actions", list);
        android.os.Bundle out = new android.os.Bundle();
        out.putBundle("android.car.EXTENSIONS", car);
        return out;
    }

    /** After a reply from the notification: show it in the conversation as yours. */
    static void addMine(Context c, String key, String id, String title, boolean dm, String text) {
        List<Line> lines;
        synchronized (HISTORY) {
            lines = HISTORY.computeIfAbsent(key, k -> new ArrayList<>());
            lines.add(new Line("You", text, System.currentTimeMillis(), null));
            while (lines.size() > 6) lines.remove(0);
            lines = new ArrayList<>(lines);
        }
        notify(c, key, id, title, dm, lines);
    }

    private static PendingIntent openChats(Context c, String key, String id, String name, boolean dm) {
        Intent i = new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (key != null) i.putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name).putExtra(EXTRA_DM, dm);
        return PendingIntent.getActivity(c, key == null ? 0 : key.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Group names as they are now, looked up at most every 10 minutes per group. */
    private static final java.util.Map<String, String> NAMES = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> NAMED_AT = new java.util.HashMap<>();

    /** The group's current name (a rename in GroupMe shows on the next message), else the saved one. */
    private String groupName(String id) {
        Long at = NAMED_AT.get(id);
        if (at == null || System.currentTimeMillis() - at > 10 * 60_000L) {
            try {
                org.json.JSONObject g = Api.call("GET", "/groups/" + java.net.URLEncoder.encode(id, "UTF-8"),
                        Store.token(this), null).optJSONObject("response");
                String n = g == null ? "" : g.optString("name", "");
                if (!n.isEmpty()) {
                    NAMES.put(id, n);
                    NAMED_AT.put(id, System.currentTimeMillis());
                }
            } catch (Exception e) {
                // offline or refused: fall back to what we know
            }
        }
        String n = NAMES.get(id);
        if (n != null) return n;
        for (Phrase.Group g : Store.groups(this)) if (g.id.equals(id)) return g.name;
        return "GroupMe";
    }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel msgs = new NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH);
        msgs.setDescription("New GroupMe messages");
        NotificationChannel on = new NotificationChannel(CH_RUNNING, "Staying connected", NotificationManager.IMPORTANCE_MIN);
        on.setDescription("Shown while Say So listens for new messages");
        on.setShowBadge(false);
        nm.createNotificationChannel(msgs);
        nm.createNotificationChannel(on);
    }
}
