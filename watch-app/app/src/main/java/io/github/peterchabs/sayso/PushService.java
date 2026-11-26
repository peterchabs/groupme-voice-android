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
    static final String CH_MESSAGES = "messages", CH_QUIET = "messages_quiet", CH_RUNNING = "running";
    static final String EXTRA_KEY = "convo_key", EXTRA_ID = "convo_id", EXTRA_NAME = "convo_name", EXTRA_DM = "convo_dm",
            EXTRA_REPLY_TO = "reply_to";
    static final String REPLY = "reply";
    private static final int RUNNING_ID = 1;
    /** Notification groups: one per chat for messages, and one for the "staying connected" notice. */
    private static final String GROUP_RUNNING = "sayso.running", GROUP_CHAT = "sayso.chat.";

    /** The chat on screen right now ("g:123" / "dm:456"): no notification for what you're reading. */
    static volatile String visibleKey;

    /**
     * Say So's own screen, while it's open. A watch shows only a small icon for a notification that
     * arrives while an app is on screen, so the open screen shows its own drawer instead, and no
     * notification is posted for it. Called on the listener thread.
     */
    interface Banner {
        void show(String key, String convoId, boolean dm, String title, String sender, String line, android.graphics.Bitmap pic);
    }
    static volatile Banner banner;

    /** One line in a chat's notification: who, their picture, what, when. */
    static final class Line {
        final String sender, text;
        final long at;
        final android.graphics.Bitmap avatar; // may be null
        String msgId = "";                    // GroupMe's id for an incoming message ("" for yours)
        android.graphics.Bitmap picture;      // the photo it carried, shown in the notification (may be null)

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

    /**
     * The Connection line in settings: whether new messages can reach you, in plain words. Problems first
     * (notifications blocked on the watch, can't reach GroupMe), then when it connected and when the
     * last message came in.
     */
    static String connectionLine(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && !nm.areNotificationsEnabled()) return "Notifications for Say So are blocked in your watch's settings";
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = nm.getNotificationChannel(Store.pushSound(c) ? CH_MESSAGES : CH_QUIET);
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return "\u201C" + ch.getName() + "\u201D notifications are off in your watch's settings";
        }
        long on = Store.pushConnectedAt(c), err = Store.pushErrorAt(c), msg = Store.pushMessageAt(c);
        if (on == 0 && err == 0) return "Connecting to GroupMe...";
        if (err > on) return "Can't reach GroupMe (" + Store.pushError(c) + "), trying again. Last tried " + when(err);
        return "Connected since " + when(on) + (msg > 0 ? ". Last message " + when(msg) : ". No messages yet");
    }

    /** "7:44 AM" today, "Yesterday 7:44 AM", or "11/24 7:44 AM". */
    static String when(long ms) {
        java.util.Calendar t = java.util.Calendar.getInstance(), x = java.util.Calendar.getInstance();
        x.setTimeInMillis(ms);
        String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date(ms));
        if (t.get(java.util.Calendar.YEAR) == x.get(java.util.Calendar.YEAR)
                && t.get(java.util.Calendar.DAY_OF_YEAR) == x.get(java.util.Calendar.DAY_OF_YEAR)) return time;
        t.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (t.get(java.util.Calendar.YEAR) == x.get(java.util.Calendar.YEAR)
                && t.get(java.util.Calendar.DAY_OF_YEAR) == x.get(java.util.Calendar.DAY_OF_YEAR)) return "Yesterday " + time;
        return new java.text.SimpleDateFormat("M/d h:mm a", java.util.Locale.US).format(new java.util.Date(ms));
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
        // Its own group, sorted last: a watch stacks an app's notifications, and without this the
        // stack could lead with this notice instead of the new message ("Getting your GroupMe
        // messages" in place of what was said). Kept on this device, quiet, never the headline.
        Notification on = Ongoing.running(this, CH_RUNNING, RUNNING_ID, R.drawable.ic_stat_connected,
                "Connected to GroupMe. The mic is off.", openChats(this, null, null, null, false), GROUP_RUNNING);
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
                Store.pushConnected(this);
                android.util.Log.i("SaySo", "push: connected to GroupMe's live feed");
                wait = 2000;
                // GroupMe sometimes keeps a connection open that never delivers anything (seen after
                // an app update). A fresh handshake every 10 minutes heals that quietly.
                long fresh = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L;
                while (running && android.os.SystemClock.elapsedRealtime() < fresh) {
                    for (Push.Event e : push.poll()) {
                        android.util.Log.i("SaySo", "push: new message in " + (e.dm ? "a DM" : "a group"));
                        Store.pushMessage(this);
                        show(e);
                    }
                }
            } catch (Api.AuthException e) {
                Store.pushFailed(this, "GroupMe refused the sign-in");
                android.util.Log.w("SaySo", "push: GroupMe refused the sign-in; stopping");
                break; // signed out: nothing to listen for
            } catch (IOException | RuntimeException e) {
                android.util.Log.i("SaySo", "push: connection dropped (" + e.getMessage() + "), retrying in " + wait / 1000 + "s");
                Store.pushFailed(this, e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                        ? "no internet" : "connection dropped");
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
        String mark = e.video ? "🎥 " : "📷 ";
        String line = e.text.isEmpty() ? (e.photo ? mark + (e.video ? "Video" : "Photo") : "Sent something")
                : (e.photo ? mark + e.text : e.text);
        // their GroupMe picture, round, beside their name (fetched here, on the listener thread)
        android.graphics.Bitmap pic = Images.roundNow(this, Feed.sized(e.avatar, "avatar"), 128);
        // the photo itself, so it shows right in the notification (not just "Photo")
        android.graphics.Bitmap photoBitmap = Store.photosOn(this) && !e.imageUrl.isEmpty()
                ? Images.pictureNow(this, e.video ? e.imageUrl : Feed.sized(e.imageUrl, "preview"), Stage.isWatchBuild() ? 400 : 720) : null;
        if (photoBitmap != null && e.video) photoBitmap = withPlayButton(photoBitmap); // a video: its frame, with a play button
        List<Line> lines;
        synchronized (HISTORY) {
            lines = HISTORY.computeIfAbsent(key, k -> new ArrayList<>());
            Line l = new Line(e.sender, line, System.currentTimeMillis(), pic);
            l.msgId = e.msgId == null ? "" : e.msgId;
            if (photoBitmap != null) l.picture = photoBitmap;
            lines.add(l);
            while (lines.size() > 6) lines.remove(0);
            lines = new ArrayList<>(lines);
        }
        Banner b = banner;
        if (b != null) b.show(key, e.convoId, e.dm, title, e.sender, line, pic); // Say So is open: its own drawer only
        else notify(this, key, e.convoId, title, e.dm, lines);
    }

    /**
     * A video's preview frame with a round play button in the middle, so a video notification
     * reads as a video. (Notifications can't play it; tap to open the chat, where it plays.)
     */
    static android.graphics.Bitmap withPlayButton(android.graphics.Bitmap frame) {
        android.graphics.Bitmap b = frame.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        float r = Math.min(b.getWidth(), b.getHeight()) * 0.16f, cx = b.getWidth() / 2f, cy = b.getHeight() / 2f;
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x99000000);
        c.drawCircle(cx, cy, r, p);
        p.setColor(0xFFFFFFFF);
        android.graphics.Path tri = new android.graphics.Path();
        tri.moveTo(cx - r * 0.32f, cy - r * 0.48f);
        tri.lineTo(cx + r * 0.52f, cy);
        tri.lineTo(cx - r * 0.32f, cy + r * 0.48f);
        tri.close();
        c.drawPath(tri, p);
        return b;
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
        // A reply (typed, spoken, or Gemini's "respond to that message") answers the newest
        // message from someone else, as a real GroupMe reply that quotes it.
        String replyTo = "";
        for (Line l : lines) if (!l.msgId.isEmpty()) replyTo = l.msgId;
        Intent ri = new Intent(c, ReplyReceiver.class)
                .putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, title).putExtra(EXTRA_DM, dm)
                .putExtra(EXTRA_REPLY_TO, replyTo);
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
        // a DM request: Accept it right from the notification
        Notification.Action accept = null;
        if (dm && Store.requests(c).contains(id)) {
            PendingIntent ai = PendingIntent.getBroadcast(c, key.hashCode() ^ 0x0acc,
                    new Intent(c, ReplyReceiver.class).setAction(ReplyReceiver.ACTION_ACCEPT)
                            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            accept = new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_sayso), "Accept", ai).build();
        }
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
        // The newest message is a photo: show the picture itself, with who sent it and any words.
        if (last.picture != null) {
            shown = new Notification.BigPictureStyle().bigPicture(last.picture).setBigContentTitle(title)
                    .setSummaryText(summary);
        }
        // Sound is a channel setting on Android, so silent messages get their own channel.
        Notification.Builder nb = new Notification.Builder(c, Store.pushSound(c) ? CH_MESSAGES : CH_QUIET);
        nb.addExtras(invisibleMarkAsRead(markRead)); // hidden: no extra button on screen
        Notification n = nb
                .setSmallIcon(R.drawable.ic_stat_sayso)
                .setStyle(shown)
                .setContentTitle(title)
                .setContentText(summary)
                .setLargeIcon(last.avatar) // who sent it, on the watch too
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setGroup(GROUP_CHAT + key) // its own stack, never folded under the connection notice
                .setSortKey("a")
                .setAutoCancel(true)
                .setColor(Palette.BLUE)
                .addAction(act)
                .setContentIntent(openChats(c, key, id, title, dm))
                .build();
        if (accept != null) n.actions = new Notification.Action[]{accept, act}; // Accept first, then Reply
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

    /** The Chats screen, opened on one chat when key isn't null. */
    static Intent chatIntent(Context c, String key, String id, String name, boolean dm) {
        Intent i = new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (key != null) i.putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name).putExtra(EXTRA_DM, dm);
        return i;
    }

    private static PendingIntent openChats(Context c, String key, String id, String name, boolean dm) {
        Intent i = chatIntent(c, key, id, name, dm);
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
        NotificationChannel quiet = new NotificationChannel(CH_QUIET, "Messages (no sound)", NotificationManager.IMPORTANCE_HIGH);
        quiet.setDescription("New GroupMe messages, with a buzz but no sound");
        quiet.setSound(null, null);
        quiet.enableVibration(true);
        nm.createNotificationChannel(quiet);
        NotificationChannel on = new NotificationChannel(CH_RUNNING, "Staying connected", NotificationManager.IMPORTANCE_MIN);
        on.setDescription("Shown while Say So listens for new messages");
        on.setShowBadge(false);
        nm.createNotificationChannel(msgs);
        nm.createNotificationChannel(on);
    }
}
