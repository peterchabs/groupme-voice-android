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

    private final String token, userId;
    private String clientId;
    private int seq;

    Push(String token, String userId) {
        this.token = token;
        this.userId = userId;
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
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Waits for the next batch of events (often none). Throws when the connection needs reopening. */
    List<Event> poll() throws IOException {
        if (clientId == null) throw new IOException("not open");
        List<Event> out = new ArrayList<>();
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
        JSONArray atts = m.optJSONArray("attachments");
        if (atts != null) {
            for (int i = 0; i < atts.length(); i++) {
                JSONObject a = atts.optJSONObject(i);
                String t = a == null ? "" : a.optString("type");
                if (t.equals("image") || t.equals("video") || t.equals("linked_image")) photo = true;
            }
        }
        String text = m.isNull("text") ? "" : m.optString("text", "");
        return new Event(dm, convo, m.optString("id"), sender, m.optString("name"),
                m.isNull("avatar_url") ? "" : m.optString("avatar_url", ""), text, photo);
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
