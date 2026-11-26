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
import java.util.Map;
import java.util.UUID;

/** Minimal GroupMe v3 client. Runs on a background thread only. */
final class Api {
    private static final String BASE = "https://api.groupme.com/v3";

    static final class AuthException extends IOException {
        AuthException() { super("GroupMe rejected your token."); }
    }

    /** GroupMe understood the request and said no (403): trying again won't change that. */
    static final class RefusedException extends IOException {
        RefusedException(String why) { super(why); }
    }

    /** Words for a 403. GroupMe keeps direct messages for its own apps (11/26/2026: "not available via the Developer API"). */
    static String refusal(String path, String groupMeSaid) {
        if (path.startsWith("/direct_messages") && groupMeSaid.contains("Developer API"))
            return "To send direct messages, sign in again with the Say So phone app (Settings, Sign out).";
        return "GroupMe didn't allow that" + (groupMeSaid.isEmpty() ? "." : ": " + groupMeSaid);
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

    /**
     * Which app made this sign-in, from GroupMe's list of your sign-ins: "groupme-web" for GroupMe's
     * own sign-in, "gm-dev" for its developer site. Null if GroupMe didn't say.
     */
    static String tokenApp(String token) throws IOException {
        JSONObject r = call("GET", "https://v2.groupme.com/access_tokens", token, null);
        Object resp = r.opt("response");
        JSONArray list = resp instanceof JSONArray ? (JSONArray) resp
                : resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("access_tokens") : null;
        return currentApp(list);
    }

    /** The app_id of the entry marked "current" (the sign-in that asked). */
    static String currentApp(JSONArray list) {
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject t = list.optJSONObject(i);
            if (t != null && t.optBoolean("current", false)) {
                String app = t.optString("app_id", "");
                return app.isEmpty() ? null : app;
            }
        }
        return null;
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

    // ---------------- who's online (GroupMe presence) ----------------

    /** One person's presence: "online", "away" or "offline", and when they were last active (ms, 0 unknown). */
    static final class Presence {
        final String status;
        final long lastActiveMs;

        Presence(String status, long lastActiveMs) {
            this.status = status;
            this.lastActiveMs = lastActiveMs;
        }
    }

    /**
     * GroupMe answers presence only for a browser, so it goes with the User-Agent of the browser the
     * phone signed in with. Null when that's missing or GroupMe says no: then nothing is shown. A
     * refusal here never signs anyone out (it isn't about the sign-in).
     */
    static Presence presence(String token, String browser, String userId) {
        JSONObject o = presenceGet(token, browser, "presence/users/" + userId);
        return o == null ? null : parsePresence(o);
    }

    /** Group members who are online or away (user id to "online"/"away"); null when unavailable. */
    static Map<String, String> groupPresence(String token, String browser, String groupId) {
        JSONObject o = presenceGet(token, browser, "presence/groups/" + groupId + "/members");
        return o == null ? null : parseGroupPresence(o);
    }

    static Presence parsePresence(JSONObject o) {
        return new Presence(o.optString("status", "offline"), o.optLong("last_active", 0));
    }

    static Map<String, String> parseGroupPresence(JSONObject o) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String kind : new String[]{"away", "online"}) { // online wins if someone is in both
            JSONArray a = o.optJSONArray(kind);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject u = a.optJSONObject(i);
                String id = u == null ? "" : u.optString("user_id", "");
                if (!id.isEmpty()) out.put(id, kind);
            }
        }
        return out;
    }

    /** "Active now", "Active 5 min ago", "Active 2 hr ago"; "" when it's been longer than GroupMe shows (6 hours). */
    static String activeLine(Presence p, long nowMs) {
        if (p == null) return "";
        if ("online".equals(p.status)) return "Active now";
        if (p.lastActiveMs <= 0) return "";
        long min = (nowMs - p.lastActiveMs) / 60000;
        if (min < 0 || min > 360) return "";
        if (min < 1) return "Active just now";
        if (min < 60) return "Active " + min + " min ago";
        return "Active " + (min / 60) + " hr ago";
    }

    private static JSONObject presenceGet(String token, String browser, String path) {
        if (token == null || token.isEmpty() || browser == null || browser.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.groupme.com/v1/" + path).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("X-Access-Token", token);
            c.setRequestProperty("User-Agent", browser);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            return new JSONObject(read(c.getInputStream()));
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static JSONObject call(String method, String path, String token, String body) throws IOException {
        // a full address (GroupMe's v4 read receipts) goes as is; anything else is under v3
        HttpURLConnection c = (HttpURLConnection) new URL(path.startsWith("https://") ? path : BASE + path).openConnection();
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
            if (code == 403) {
                String said = "";
                try {
                    java.io.InputStream es = c.getErrorStream();
                    JSONArray errs = es == null ? null : new JSONObject(read(es)).optJSONObject("meta").optJSONArray("errors");
                    if (errs != null && errs.length() > 0) said = errs.optString(0, "");
                } catch (JSONException | IOException | RuntimeException ignored) {
                    // no reason given
                }
                throw new RefusedException(refusal(path, said));
            }
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
