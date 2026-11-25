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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Minimal GroupMe v3 client. Runs on a background thread only. */
final class Api {
    private static final String BASE = "https://api.groupme.com/v3";

    static final class AuthException extends IOException {
        AuthException() { super("GroupMe rejected your token."); }
    }

    static List<Phrase.Group> listGroups(String token) throws IOException {
        List<Phrase.Group> out = new ArrayList<>();
        for (int page = 1; page < 50; page++) {
            JSONObject r = call("GET", "/groups?per_page=100&omit=memberships&page=" + page, token, null);
            JSONArray a = r.optJSONArray("response");
            if (a == null) break;
            for (int i = 0; i < a.length(); i++) {
                JSONObject g = a.optJSONObject(i);
                if (g != null) out.add(new Phrase.Group(g.optString("id"), g.optString("name")));
            }
            if (a.length() < 100) break;
        }
        return out;
    }

    /** {id, name, picture url} of the signed-in GroupMe user. Also proves the token works. */
    static String[] me(String token) throws IOException {
        JSONObject r = call("GET", "/users/me", token, null);
        JSONObject me = r.optJSONObject("response");
        if (me == null) return new String[]{"", "", ""};
        return new String[]{me.optString("id", me.optString("user_id", "")), me.optString("name", ""),
                me.isNull("image_url") ? "" : me.optString("image_url", "")};
    }

    /** The newest message someone else sent in one of your groups. */
    static final class Incoming {
        final String id, groupId, groupName, sender, text;
        final boolean isDm;
        final String otherUserId;    // for a DM: who to answer
        final String conversationId; // group id, or "lowerId+higherId" for a DM (used for likes)

        /** A message in a group. */
        Incoming(String id, String groupId, String groupName, String sender, String text) {
            this(id, groupId, groupName, sender, text, false, "", groupId);
        }

        Incoming(String id, String groupId, String groupName, String sender, String text,
                 boolean isDm, String otherUserId, String conversationId) {
            this.id = id;
            this.groupId = groupId;
            this.groupName = groupName;
            this.sender = sender;
            this.text = text;
            this.isDm = isDm;
            this.otherUserId = otherUserId;
            this.conversationId = conversationId;
        }
    }

    /** GroupMe names a DM conversation by both user ids, smaller first, joined with "+". */
    static String dmConversationId(String a, String b) {
        try {
            return Long.parseLong(a) < Long.parseLong(b) ? a + "+" + b : b + "+" + a;
        } catch (NumberFormatException e) {
            return a.compareTo(b) < 0 ? a + "+" + b : b + "+" + a;
        }
    }

    /** People you have direct messages with. Group.id holds their user id. */
    static List<Phrase.Group> listContacts(String token) throws IOException {
        List<Phrase.Group> out = new ArrayList<>();
        for (int page = 1; page < 20; page++) {
            JSONObject r = call("GET", "/chats?per_page=100&page=" + page, token, null);
            JSONArray a = r.optJSONArray("response");
            if (a == null) break;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                JSONObject u = o == null ? null : o.optJSONObject("other_user");
                if (u != null) out.add(new Phrase.Group(u.optString("id"), u.optString("name")));
            }
            if (a.length() < 100) break;
        }
        return out;
    }

    static void sendDm(String token, String userId, String text) throws IOException {
        try {
            JSONObject dm = new JSONObject()
                    .put("source_guid", UUID.randomUUID().toString())
                    .put("recipient_id", userId)
                    .put("text", text);
            call("POST", "/direct_messages", token, new JSONObject().put("direct_message", dm).toString());
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Hearts a message (GroupMe's "like"). */
    static void like(String token, String conversationId, String messageId) throws IOException {
        call("POST", "/messages/" + URLEncoder.encode(conversationId, "UTF-8") + "/"
                + URLEncoder.encode(messageId, "UTF-8") + "/like", token, "{}");
    }

    /**
     * Looks at your most recently active groups (GroupMe lists them newest first) and returns
     * the newest message that wasn't sent by you, or null if there isn't one.
     */
    static Incoming latestIncoming(String token, String myId) throws IOException {
        JSONObject r = call("GET", "/groups?per_page=4&omit=memberships", token, null);
        JSONArray groups = r.optJSONArray("response");
        if (groups == null) return null;
        Incoming best = null;
        long bestAt = -1;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            String gid = g.optString("id");
            JSONObject m = call("GET", "/groups/" + URLEncoder.encode(gid, "UTF-8") + "/messages?limit=8", token, null);
            JSONObject resp = m.optJSONObject("response");
            JSONArray msgs = resp == null ? null : resp.optJSONArray("messages");
            if (msgs == null) continue;
            for (int j = 0; j < msgs.length(); j++) { // newest first
                JSONObject x = msgs.optJSONObject(j);
                if (x == null || x.optBoolean("system", false)) continue;
                String sender = x.optString("sender_id", x.optString("user_id", ""));
                if (sender.equals(myId) || "system".equals(x.optString("sender_type"))) continue;
                long at = x.optLong("created_at", 0);
                if (at > bestAt) {
                    bestAt = at;
                    String text = x.isNull("text") ? "" : x.optString("text", "");
                    if (text.isEmpty()) text = "(photo or attachment)";
                    best = new Incoming(x.optString("id"), gid, g.optString("name"), x.optString("name"), text);
                }
                break;
            }
        }
        // direct messages too: the newest DM conversations, newest first
        JSONObject cr = call("GET", "/chats?per_page=3", token, null);
        JSONArray chats = cr.optJSONArray("response");
        if (chats != null) {
            for (int i = 0; i < chats.length(); i++) {
                JSONObject ch = chats.optJSONObject(i);
                JSONObject last = ch == null ? null : ch.optJSONObject("last_message");
                JSONObject other = ch == null ? null : ch.optJSONObject("other_user");
                if (last == null || other == null) continue;
                String sender = last.optString("sender_id", last.optString("user_id", ""));
                if (sender.equals(myId)) continue;
                long at = last.optLong("created_at", 0);
                if (at > bestAt) {
                    bestAt = at;
                    String otherId = other.optString("id");
                    String text = last.isNull("text") ? "" : last.optString("text", "");
                    if (text.isEmpty()) text = "(photo or attachment)";
                    String conv = last.optString("conversation_id", "");
                    if (conv.isEmpty()) conv = dmConversationId(myId, otherId);
                    best = new Incoming(last.optString("id"), "", "", other.optString("name"), text,
                            true, otherId, conv);
                }
            }
        }
        return best;
    }

    /** Encrypted sign-in messages waiting on the relay topic (see Link). Newest last. */
    static List<String> relayMessages(String topic, long sinceSec) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(
                Link.RELAY + topic + "/json?poll=1&since=" + sinceSec).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) throw new IOException("relay " + c.getResponseCode());
            List<String> out = new ArrayList<>();
            for (String line : read(c.getInputStream()).split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    if ("message".equals(o.optString("event"))) out.add(o.optString("message"));
                } catch (JSONException ignored) {
                    // skip anything that isn't a message line
                }
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** The relay is limiting requests from this internet address for a moment. */
    static final class BusyException extends IOException {
        BusyException() { super("relay busy"); }
    }

    /**
     * Waits on the relay topic with one open connection (the relay streams each message as it
     * arrives, and a keepalive every ~45 s), returning messages as soon as any come, or an empty
     * list after about {@code maxMs}. One request a minute instead of one every few seconds,
     * which keeps a waiting watch far under the relay's per-address limit.
     */
    private static volatile HttpURLConnection relayLive;

    /** Ends a relayWait() in progress right away (from any thread). */
    static void abortRelayWait() {
        HttpURLConnection c = relayLive;
        relayLive = null;
        if (c != null) new Thread(c::disconnect).start(); // closing a socket is network work: not on the UI thread
    }

    static List<String> relayWait(String topic, long sinceSec, long maxMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(
                Link.RELAY + topic + "/json?since=" + sinceSec).openConnection();
        relayLive = c;
        long end = System.currentTimeMillis() + maxMs;
        List<String> out = new ArrayList<>();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000); // longer than the relay's keepalive
            int code = c.getResponseCode();
            if (code == 429) throw new BusyException();
            if (code != 200) throw new IOException("relay " + code);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    try {
                        JSONObject o = new JSONObject(line);
                        if ("message".equals(o.optString("event"))) out.add(o.optString("message"));
                    } catch (JSONException ignored) {
                        // not a message line
                    }
                }
                if (!out.isEmpty() || System.currentTimeMillis() > end) break;
            }
            return out;
        } catch (java.net.SocketTimeoutException e) {
            return out; // quiet topic: just ask again
        } finally {
            if (relayLive == c) relayLive = null;
            c.disconnect();
        }
    }

    /** Drops a locked sign-in on the relay topic for a watch to pick up (see Link.seal). */
    static void relayPost(String topic, String payload) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                relayPostOnce(topic, payload);
                return;
            } catch (BusyException e) {
                if (attempt >= 3) throw e;
                try {
                    Thread.sleep(4000L * (attempt + 1)); // the relay refills about one request per 5 s
                } catch (InterruptedException ie) {
                    throw e;
                }
            }
        }
    }

    private static void relayPostOnce(String topic, String payload) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Link.RELAY + topic).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 429) throw new BusyException();
            if (code >= 300) throw new IOException("relay " + code);
        } finally {
            c.disconnect();
        }
    }

    static void send(String token, String groupId, String text) throws IOException {
        send(token, groupId, text, null);
    }

    /** replyTo = a message id to quote (GroupMe's "reply"), or null for a normal message. */
    static void send(String token, String groupId, String text, String replyTo) throws IOException {
        try {
            JSONObject msg = new JSONObject()
                    .put("source_guid", UUID.randomUUID().toString())
                    .put("text", text);
            if (replyTo != null) {
                msg.put("attachments", new JSONArray().put(new JSONObject()
                        .put("type", "reply").put("reply_id", replyTo).put("base_reply_id", replyTo)));
            }
            String path = "/groups/" + URLEncoder.encode(groupId, "UTF-8") + "/messages";
            call("POST", path, token, new JSONObject().put("message", msg).toString());
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static JSONObject call(String method, String path, String token, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("X-Access-Token", token); // header, never in the URL
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 401) throw new AuthException();
            if (code == 304) return new JSONObject(); // GroupMe: "no messages" for an empty group
            if (code >= 300) throw new IOException("GroupMe said error " + code + ". Nothing was sent.");
            String s = read(c.getInputStream());
            return s.trim().isEmpty() ? new JSONObject() : new JSONObject(s);
        } catch (JSONException e) {
            throw new IOException("GroupMe sent back something unexpected.");
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
