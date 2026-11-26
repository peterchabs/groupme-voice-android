package io.github.peterchabs.sayso;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GroupMe's live message feed (the same one its web app uses): a Faye "long-polling" connection
 * to push.groupme.com, subscribed to your user channel. Each connect call waits until something
 * happens (or about a minute passes), so a quiet chat costs one small request a minute.
 * Runs on a background thread only.
 */
final class Push {
    private static final String URL_FAYE = "https://push.groupme.com/faye";

    /** A new message for you: in a group, or a direct message. */
    static final class Event {
        final boolean dm;
        final String convoId;    // group id, or the other person's user id for a DM
        final String msgId, senderId, sender, avatar, text;
        final boolean photo;
        /** The picture to show in the notification (a photo, or a video's preview), or "". */
        String imageUrl = "";
        /** True when what it carried is a video (the picture is its preview frame). */
        boolean video;

        Event(boolean dm, String convoId, String msgId, String senderId, String sender, String avatar, String text, boolean photo) {
            this.dm = dm;
            this.convoId = convoId;
            this.msgId = msgId;
            this.senderId = senderId;
            this.sender = sender;
            this.avatar = avatar;
            this.text = text;
            this.photo = photo;
        }
    }

    /**
     * Something live in a chat that isn't a message: someone typing ("typing"), or the other
     * person in a DM reading up to a message ("read", with when).
     */
    static final class Signal {
        final String type, userId, messageId, chatId;
        final long readAt; // seconds since 1970

        Signal(String type, String userId, String messageId, String chatId, long readAt) {
            this.type = type;
            this.userId = userId;
            this.messageId = messageId;
            this.chatId = chatId;
            this.readAt = readAt;
        }
    }

    private final String token, userId;
    /** One chat's own channel ("/group/ID" or "/direct_message/A_B"), for typing, or null. */
    private final String chatChannel;
    private String clientId;
    private int seq;
    /** The signals that came with the last poll. */
    final List<Signal> signals = new ArrayList<>();

    Push(String token, String userId) {
        this(token, userId, null);
    }

    Push(String token, String userId, String chatChannel) {
        this.token = token;
        this.userId = userId;
        this.chatChannel = chatChannel;
    }

    /** The channel GroupMe uses for typing in a chat: groups by id, DMs by "lowerId_higherId". */
    static String chatChannel(boolean dm, String conversationId) {
        return dm ? "/direct_message/" + conversationId.replace('+', '_') : "/group/" + conversationId;
    }

    /** Tells the chat you're writing, the way GroupMe's apps do ("Peter is typing"). */
    void publishTyping() throws IOException {
        if (clientId == null || chatChannel == null) return;
        try {
            post(new JSONArray().put(new JSONObject()
                    .put("channel", chatChannel).put("clientId", clientId).put("id", next())
                    .put("data", new JSONObject().put("type", "typing").put("user_id", userId)
                            .put("started", System.currentTimeMillis()))
                    .put("ext", new JSONObject().put("access_token", token)
                            .put("timestamp", System.currentTimeMillis() / 1000))), 20000);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** A typing or read-receipt signal from one pushed item, or null. Never your own. */
    static Signal signal(JSONObject o, String myId) {
        JSONObject data = o.optJSONObject("data");
        if (data == null) return null;
        String type = data.optString("type"), ch = o.optString("channel");
        if ("typing".equals(type) && (ch.startsWith("/group/") || ch.startsWith("/direct_message/"))) {
            String who = data.optString("user_id", "");
            return who.isEmpty() || who.equals(myId) ? null : new Signal("typing", who, "", ch, 0);
        }
        if ("read_receipt.create".equals(type)) {
            JSONObject s = data.optJSONObject("subject");
            if (s == null) s = data;
            String who = s.optString("user_id", "");
            if (who.isEmpty() || who.equals(myId)) return null;
            return new Signal("read", who, s.optString("message_id", ""), s.optString("chat_id", ""), s.optLong("read_at", 0));
        }
        return null;
    }

    /** Handshake and subscribe to your channel. Throws if GroupMe refuses (bad token). */
    void open() throws IOException {
        try {
            JSONArray r = post(new JSONArray().put(new JSONObject()
                    .put("channel", "/meta/handshake").put("version", "1.0")
                    .put("supportedConnectionTypes", new JSONArray().put("callback-polling"))
                    .put("id", next())), 30000);
            JSONObject hs = r.optJSONObject(0);
            if (hs == null || !hs.optBoolean("successful")) throw new IOException("push handshake refused");
            clientId = hs.optString("clientId");
            JSONArray s = post(new JSONArray().put(new JSONObject()
                    .put("channel", "/meta/subscribe").put("clientId", clientId)
                    .put("subscription", "/user/" + userId).put("id", next())
                    .put("ext", new JSONObject().put("access_token", token)
                            .put("timestamp", System.currentTimeMillis() / 1000))), 30000);
            JSONObject sub = s.optJSONObject(0);
            if (sub == null || !sub.optBoolean("successful")) throw new Api.AuthException();
            if (chatChannel != null) { // the open chat's own channel: who's typing
                post(new JSONArray().put(new JSONObject()
                        .put("channel", "/meta/subscribe").put("clientId", clientId)
                        .put("subscription", chatChannel).put("id", next())
                        .put("ext", new JSONObject().put("access_token", token)
                                .put("timestamp", System.currentTimeMillis() / 1000))), 30000);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Waits for the next batch of events (often none). Throws when the connection needs reopening. */
    List<Event> poll() throws IOException {
        if (clientId == null) throw new IOException("not open");
        List<Event> out = new ArrayList<>();
        signals.clear();
        try {
            // ask for an answer within 30 s: GroupMe's gateway cuts any request off at 60 s
            JSONArray r = post(new JSONArray().put(new JSONObject()
                    .put("channel", "/meta/connect").put("clientId", clientId)
                    .put("connectionType", "callback-polling").put("id", next())
                    .put("advice", new JSONObject().put("timeout", 30000))), 50000);
            for (int i = 0; i < r.length(); i++) {
                JSONObject o = r.optJSONObject(i);
                if (o == null) continue;
                if ("/meta/connect".equals(o.optString("channel")) && !o.optBoolean("successful", true)) {
                    clientId = null; // the server forgot us: handshake again
                    throw new IOException("push session expired");
                }
                Event e = parse(o, userId);
                if (e != null) out.add(e);
                Signal sg = signal(o, userId);
                if (sg != null) signals.add(sg);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return out;
    }

    /** One pushed item to an Event, or null for anything that isn't someone else's new message. */
    static Event parse(JSONObject o, String myId) {
        JSONObject data = o.optJSONObject("data");
        if (data == null) return null;
        String type = data.optString("type");
        JSONObject m = data.optJSONObject("subject");
        if (m == null) return null;
        boolean dm = "direct_message.create".equals(type);
        if (!dm && !"line.create".equals(type)) return null;
        String sender = m.optString("sender_id", m.optString("user_id", ""));
        if (sender.equals(myId) || "system".equals(m.optString("sender_type")) || m.optBoolean("system", false)) return null;
        String convo = dm ? sender : m.optString("group_id", "");
        if (convo.isEmpty()) return null;
        boolean photo = false;
        String picture = "";
        JSONArray atts = m.optJSONArray("attachments");
        if (atts != null) {
            for (int i = 0; i < atts.length(); i++) {
                JSONObject a = atts.optJSONObject(i);
                String t = a == null ? "" : a.optString("type");
                if (t.equals("image") || t.equals("video") || t.equals("linked_image")) photo = true;
                if (picture.isEmpty() && (t.equals("image") || t.equals("linked_image"))) picture = a.optString("url", "");
                if (picture.isEmpty() && t.equals("video")) picture = a.optString("preview_url", "");
            }
        }
        String text = m.isNull("text") ? "" : m.optString("text", "");
        Event ev = new Event(dm, convo, m.optString("id"), sender, m.optString("name"),
                m.isNull("avatar_url") ? "" : m.optString("avatar_url", ""), text, photo);
        ev.imageUrl = picture == null ? "" : picture;
        if (atts != null) {
            for (int i = 0; i < atts.length(); i++) {
                JSONObject a = atts.optJSONObject(i);
                if (a != null && "video".equals(a.optString("type"))) ev.video = true;
            }
        }
        return ev;
    }

    private String next() { return String.valueOf(++seq); }

    /**
     * One Faye exchange. GroupMe's push server only answers GET requests with the message in the
     * URL ("callback-polling"; POSTs hang), and wraps the reply as jsonpcallback([...]).
     */
    private static JSONArray post(JSONArray body, int readMs) throws IOException, JSONException {
        String url = URL_FAYE + "?message=" + java.net.URLEncoder.encode(body.toString(), "UTF-8")
                + "&jsonp=jsonpcallback";
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        // A hard stop: on a watch a request can stall in ways the read timeout never notices
        // (a name lookup, a half-open link through the phone). Cutting it off makes the read
        // throw, and the listener reconnects instead of going quiet for good.
        java.util.concurrent.ScheduledFuture<?> stop = DEADLINE.schedule(c::disconnect,
                readMs + 10000, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(readMs);
            if (c.getResponseCode() != 200) throw new IOException("push " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return unwrap(bo.toString("UTF-8"));
            }
        } finally {
            stop.cancel(false);
            c.disconnect();
        }
    }

    private static final java.util.concurrent.ScheduledExecutorService DEADLINE =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sayso-push-deadline");
                t.setDaemon(true);
                return t;
            });

    /** "/**\/jsonpcallback([...]);" to the array inside. */
    static JSONArray unwrap(String s) throws JSONException {
        int a = s.indexOf('['), b = s.lastIndexOf(']');
        if (a < 0 || b < a) throw new JSONException("not a push reply");
        return new JSONArray(s.substring(a, b + 1));
    }
}
