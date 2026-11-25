package io.github.peterchabs.sayso;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Groups, direct messages and their messages, for the chat screens. Parsing is separate from
 * the network calls so it can be tested on the JVM.
 */
final class Feed {
    private Feed() { }

    /** A group or a direct-message conversation, as one row in the list. */
    static final class Convo {
        final String id;          // group id, or the other person's user id for a DM
        final String name;
        final String avatarUrl;   // may be empty
        final boolean isDm;
        final long lastAt;        // seconds since 1970 of the last message, 0 if none
        final String preview;     // "Sam: on my way", "Photo", ...

        Convo(String id, String name, String avatarUrl, boolean isDm, long lastAt, String preview) {
            this.id = id;
            this.name = name;
            this.avatarUrl = avatarUrl;
            this.isDm = isDm;
            this.lastAt = lastAt;
            this.preview = preview;
        }

        String key() { return (isDm ? "dm:" : "g:") + id; }
    }

    /** Something attached to a message. */
    static final class Media {
        static final int IMAGE = 1, GIF = 2, VIDEO = 3;
        final int kind;
        final String url;       // full image, GIF or video
        final String preview;   // still image to show inline (for video: its preview frame)
        // who posted it, when, and what they wrote with it (for the full-screen overlay)
        String from = "", fromAvatar = "", caption = "", msgId = "";
        long at;

        Media(int kind, String url, String preview) {
            this.kind = kind;
            this.url = url;
            this.preview = preview;
        }
    }

    static final class Msg {
        final String id, senderId, sender, avatarUrl, text;
        final long at;
        final int likes;
        final boolean system;
        final List<Media> media;
        /** Set when the message announces a calendar event: tap it for the event. */
        String eventId = "";

        Msg(String id, String senderId, String sender, String avatarUrl, String text, long at, int likes,
            boolean system, List<Media> media) {
            this.id = id;
            this.senderId = senderId;
            this.sender = sender;
            this.avatarUrl = avatarUrl;
            this.text = text;
            this.at = at;
            this.likes = likes;
            this.system = system;
            this.media = media;
        }
    }

    // ---------------- network ----------------

    /** Every group and DM, most recent first. */
    static List<Convo> conversations(String token) throws IOException {
        List<Convo> out = new ArrayList<>();
        for (int page = 1; page < 20; page++) {
            JSONArray a = Api.call("GET", "/groups?per_page=100&omit=memberships&page=" + page, token, null)
                    .optJSONArray("response");
            if (a == null) break;
            for (int i = 0; i < a.length(); i++) {
                Convo c = parseGroup(a.optJSONObject(i));
                if (c != null) out.add(c);
            }
            if (a.length() < 100) break;
        }
        for (int page = 1; page < 10; page++) {
            JSONArray a = Api.call("GET", "/chats?per_page=100&page=" + page, token, null).optJSONArray("response");
            if (a == null) break;
            for (int i = 0; i < a.length(); i++) {
                Convo c = parseChat(a.optJSONObject(i));
                if (c != null) out.add(c);
            }
            if (a.length() < 100) break;
        }
        sortNewestFirst(out);
        return out;
    }

    /** The newest messages in a conversation, oldest first (the way a chat reads). */
    static List<Msg> messages(String token, Convo c, int limit) throws IOException {
        JSONArray a;
        if (c.isDm) {
            JSONObject r = Api.call("GET", "/direct_messages?other_user_id=" + enc(c.id), token, null)
                    .optJSONObject("response");
            a = r == null ? null : r.optJSONArray("direct_messages");
        } else {
            JSONObject r = Api.call("GET", "/groups/" + enc(c.id) + "/messages?limit=" + limit, token, null)
                    .optJSONObject("response");
            a = r == null ? null : r.optJSONArray("messages");
        }
        List<Msg> out = new ArrayList<>();
        if (a == null) return out; // GroupMe answers 304 (empty) for a group with no messages
        for (int i = 0; i < a.length() && out.size() < limit; i++) {
            Msg m = parseMessage(a.optJSONObject(i));
            if (m != null) out.add(m);
        }
        Collections.reverse(out); // GroupMe sends newest first
        return out;
    }

    /**
     * The messages around one message (some before, the message itself, some after), oldest
     * first: for jumping to where a photo was posted. Groups only.
     */
    static List<Msg> around(String token, Convo c, String msgId) throws IOException {
        List<Msg> older = page(token, c, "before_id=" + enc(msgId) + "&limit=15");
        // "after" the newest of the older ones includes the message itself and what followed it
        String from = older.isEmpty() ? null : older.get(older.size() - 1).id;
        List<Msg> newer = from == null ? new ArrayList<>() : page(token, c, "after_id=" + enc(from) + "&limit=20");
        java.util.LinkedHashMap<String, Msg> all = new java.util.LinkedHashMap<>();
        for (Msg m : older) all.put(m.id, m);
        for (Msg m : newer) all.put(m.id, m);
        List<Msg> out = new ArrayList<>(all.values());
        out.sort((a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    private static List<Msg> page(String token, Convo c, String query) throws IOException {
        JSONObject r = Api.call("GET", "/groups/" + enc(c.id) + "/messages?" + query, token, null).optJSONObject("response");
        JSONArray a = r == null ? null : r.optJSONArray("messages");
        List<Msg> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            Msg m = parseMessage(a.optJSONObject(i));
            if (m != null) out.add(m);
        }
        out.sort((x, y) -> Long.compare(x.at, y.at));
        return out;
    }

    static void send(String token, Convo c, String text) throws IOException {
        if (c.isDm) Api.sendDm(token, c.id, text);
        else Api.send(token, c.id, text);
    }

    /** The group's photos and videos, newest first (GroupMe's gallery). */
    static List<Media> gallery(String token, String groupId, int limit) throws IOException {
        JSONObject r = Api.call("GET", "/conversations/" + enc(groupId) + "/gallery?limit=" + limit, token, null)
                .optJSONObject("response");
        return parseGallery(r);
    }

    static List<Media> parseGallery(JSONObject response) {
        List<Media> out = new ArrayList<>();
        JSONArray a = response == null ? null : response.optJSONArray("messages");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            Msg m = parseMessage(a.optJSONObject(i));
            if (m != null) out.addAll(m.media);
        }
        return out;
    }

    /** A group calendar event. Times are milliseconds since 1970 (0 when missing). */
    static final class Event {
        final String id, name, description, place;
        final long startMs, endMs;
        final boolean allDay;
        final int going;

        Event(String id, String name, String description, String place, long startMs, long endMs, boolean allDay, int going) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.place = place;
            this.startMs = startMs;
            this.endMs = endMs;
            this.allDay = allDay;
            this.going = going;
        }
    }

    /** The group's calendar: upcoming events soonest first, then recent ones. */
    static List<Event> events(String token, String groupId, long nowMs) throws IOException {
        String since = java.time.Instant.ofEpochMilli(nowMs - 30L * 86400000L).toString();
        JSONObject r = Api.call("GET", "/conversations/" + enc(groupId) + "/events/list?limit=50&end_at="
                + enc(since), token, null).optJSONObject("response");
        return parseEvents(r, nowMs);
    }

    /** One event by its id (from an event message in the chat), or null if it's gone. */
    static Event event(String token, String groupId, String eventId) throws IOException {
        try { // the event's own page first
            JSONObject r = Api.call("GET", "/conversations/" + enc(groupId) + "/events/show?event_id=" + enc(eventId),
                    token, null).optJSONObject("response");
            JSONObject e = r == null ? null : r.optJSONObject("event");
            if (e != null) {
                List<Event> one = parseEvents(new JSONObject().put("events", new JSONArray().put(e)), 0);
                if (!one.isEmpty()) return one.get(0);
            }
        } catch (IOException | org.json.JSONException ignored) {
            // not every account can open that: look through the group's list instead
        }
        String since = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 400L * 86400000L).toString();
        JSONObject r = Api.call("GET", "/conversations/" + enc(groupId) + "/events/list?limit=100&end_at="
                + enc(since), token, null).optJSONObject("response");
        for (Event e : parseEvents(r, 0)) if (e.id.equals(eventId)) return e;
        return null;
    }

    /** The event a message is about ("created event 'Team breakfast'"), or "" for an ordinary message. */
    static String eventIdOf(JSONObject m) {
        JSONArray atts = m == null ? null : m.optJSONArray("attachments");
        if (atts == null) return "";
        for (int i = 0; i < atts.length(); i++) {
            JSONObject a = atts.optJSONObject(i);
            if (a != null && "event".equals(a.optString("type")) && !a.optString("event_id").isEmpty()) return a.optString("event_id");
        }
        return "";
    }

    static List<Event> parseEvents(JSONObject response, long nowMs) {
        List<Event> out = new ArrayList<>();
        JSONArray a = response == null ? null : response.optJSONArray("events");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || e.optBoolean("canceled", false) || !e.isNull("deleted_at") && e.has("deleted_at")) continue;
            JSONObject loc = e.optJSONObject("location");
            String place = loc == null ? "" : str(loc, "name");
            if (place.isEmpty() && loc != null) place = str(loc, "address");
            JSONArray going = e.optJSONArray("going");
            out.add(new Event(str(e, "event_id"), str(e, "name"), str(e, "description"), place,
                    when(str(e, "start_at")), when(str(e, "end_at")), e.optBoolean("is_all_day", false),
                    going == null ? 0 : going.length()));
        }
        out.sort((x, y) -> {
            boolean xu = Math.max(x.startMs, x.endMs) >= nowMs, yu = Math.max(y.startMs, y.endMs) >= nowMs;
            if (xu != yu) return xu ? -1 : 1;                                  // upcoming before past
            return xu ? Long.compare(x.startMs, y.startMs) : Long.compare(y.startMs, x.startMs);
        });
        return out;
    }

    /** "2026-11-30T18:00:00Z" (or with an offset) to milliseconds; 0 if unreadable. */
    static long when(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            try {
                return java.time.Instant.parse(iso).toEpochMilli();
            } catch (RuntimeException e2) {
                return 0;
            }
        }
    }

    /** Someone in a group. */
    static final class Member {
        final String userId, name, avatarUrl, role; // role: "Owner", "Admin" or ""

        Member(String userId, String name, String avatarUrl, String role) {
            this.userId = userId;
            this.name = name;
            this.avatarUrl = avatarUrl;
            this.role = role;
        }
    }

    /** Everyone in a group, A to Z by the name they use there. */
    static List<Member> members(String token, String groupId) throws IOException {
        JSONObject g = Api.call("GET", "/groups/" + enc(groupId), token, null).optJSONObject("response");
        return parseMembers(g);
    }

    static List<Member> parseMembers(JSONObject group) {
        List<Member> out = new ArrayList<>();
        JSONArray a = group == null ? null : group.optJSONArray("members");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null) continue;
            String role = "";
            JSONArray roles = m.optJSONArray("roles");
            if (roles != null) {
                for (int r = 0; r < roles.length(); r++) {
                    String x = roles.optString(r);
                    if (x.equals("owner")) role = "Owner";
                    else if (x.equals("admin") && role.isEmpty()) role = "Admin";
                }
            }
            String name = str(m, "nickname");
            if (name.isEmpty()) name = str(m, "name");
            out.add(new Member(str(m, "user_id"), name, str(m, "image_url"), role));
        }
        // owner first, then admins, then everyone else; A to Z within each
        out.sort((x, y) -> {
            int rx = x.role.equals("Owner") ? 0 : x.role.equals("Admin") ? 1 : 2;
            int ry = y.role.equals("Owner") ? 0 : y.role.equals("Admin") ? 1 : 2;
            return rx != ry ? Integer.compare(rx, ry) : x.name.compareToIgnoreCase(y.name);
        });
        return out;
    }

    /**
     * "@Sam @Jo " in front of the message, plus where each name sits, which is what makes GroupMe
     * notify those people. Returns the full text; {@code loci} gets one [start, length] per person.
     */
    static String withMentions(List<Member> tags, String text, List<int[]> loci) {
        StringBuilder b = new StringBuilder();
        for (Member m : tags) {
            loci.add(new int[]{b.length(), m.name.length() + 1});
            b.append('@').append(m.name).append(' ');
        }
        return b.append(text).toString();
    }

    /** Sends to a group with people tagged (see withMentions). */
    static void sendTagged(String token, Convo c, List<Member> tags, String text) throws IOException {
        if (tags.isEmpty() || c.isDm) {
            send(token, c, text);
            return;
        }
        List<int[]> loci = new ArrayList<>();
        String full = withMentions(tags, text, loci);
        try {
            JSONArray ids = new JSONArray(), where = new JSONArray();
            for (int i = 0; i < tags.size(); i++) {
                ids.put(tags.get(i).userId);
                where.put(new JSONArray().put(loci.get(i)[0]).put(loci.get(i)[1]));
            }
            JSONObject msg = new JSONObject()
                    .put("source_guid", java.util.UUID.randomUUID().toString())
                    .put("text", full)
                    .put("attachments", new JSONArray().put(new JSONObject()
                            .put("type", "mentions").put("user_ids", ids).put("loci", where)));
            Api.call("POST", "/groups/" + enc(c.id) + "/messages", token, new JSONObject().put("message", msg).toString());
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    // ---------------- parsing (pure) ----------------

    static Convo parseGroup(JSONObject g) {
        if (g == null) return null;
        JSONObject msgs = g.optJSONObject("messages");
        JSONObject prev = msgs == null ? null : msgs.optJSONObject("preview");
        long at = msgs == null ? 0 : msgs.optLong("last_message_created_at", 0);
        String preview = "";
        if (prev != null) {
            String who = str(prev, "nickname");
            String body = describe(str(prev, "text"), prev.optJSONArray("attachments"));
            preview = who.isEmpty() ? body : who + ": " + body;
        }
        return new Convo(str(g, "id"), str(g, "name"), str(g, "image_url"), false, at, preview);
    }

    static Convo parseChat(JSONObject ch) {
        if (ch == null) return null;
        JSONObject other = ch.optJSONObject("other_user");
        if (other == null) return null;
        JSONObject last = ch.optJSONObject("last_message");
        long at = last == null ? ch.optLong("updated_at", 0) : last.optLong("created_at", 0);
        String preview = last == null ? "" : describe(str(last, "text"), last.optJSONArray("attachments"));
        return new Convo(str(other, "id"), str(other, "name"), str(other, "avatar_url"), true, at, preview);
    }

    static Msg parseMessage(JSONObject m) {
        if (m == null) return null;
        List<Media> media = new ArrayList<>();
        JSONArray atts = m.optJSONArray("attachments");
        if (atts != null) {
            for (int i = 0; i < atts.length(); i++) {
                Media x = parseMedia(atts.optJSONObject(i));
                if (x != null) media.add(x);
            }
        }
        String text = str(m, "text");
        if (media.isEmpty()) { // a video sent as a link in the text: show the video
            String link = LinkPreview.firstUrl(text);
            if (link != null && LinkPreview.isVideoUrl(link)) media.add(new Media(Media.VIDEO, link, videoThumb(link)));
        }
        text = withoutMediaLinks(text, media); // the media shows itself: no long address in the text
        JSONArray fav = m.optJSONArray("favorited_by");
        boolean system = m.optBoolean("system", false) || "system".equals(str(m, "sender_type"));
        String sender = str(m, "sender_id");
        if (sender.isEmpty()) sender = str(m, "user_id");
        for (Media x : media) {
            x.from = str(m, "name");
            x.fromAvatar = str(m, "avatar_url");
            x.caption = text;
            x.at = m.optLong("created_at", 0);
            x.msgId = str(m, "id");
        }
        Msg out = new Msg(str(m, "id"), sender, str(m, "name"), str(m, "avatar_url"), text,
                m.optLong("created_at", 0), fav == null ? 0 : fav.length(), system, media);
        out.eventId = eventIdOf(m);
        return out;
    }

    static Media parseMedia(JSONObject a) {
        if (a == null) return null;
        String type = str(a, "type");
        String url = str(a, "url");
        switch (type) {
            case "image":
            case "linked_image":
                if (url.isEmpty()) return null;
                boolean gif = url.toLowerCase().contains(".gif") || url.contains("giphy");
                return new Media(gif ? Media.GIF : Media.IMAGE, url, url);
            case "video":
                if (url.isEmpty()) return null;
                String still = str(a, "preview_url");
                return new Media(Media.VIDEO, url, still.isEmpty() ? videoThumb(url) : still);
            default:
                return null; // emoji, mentions, location, polls: shown as text only
        }
    }

    private static final java.util.regex.Pattern UPLOAD =
            java.util.regex.Pattern.compile("^(https://m\\.groupme\\.com/uploads/[0-9a-f]+)/.*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * GroupMe keeps a still for every uploaded video at .../uploads/ID/thumbnail.jpg. (Other
     * names there return the whole video, so only that one is used.) Empty for anything else.
     */
    static String videoThumb(String url) {
        java.util.regex.Matcher m = UPLOAD.matcher(url == null ? "" : url);
        return m.matches() ? m.group(1) + "/thumbnail.jpg" : "";
    }

    /** Removes from the text any link to a photo or video the message already shows. */
    static String withoutMediaLinks(String text, List<Media> media) {
        if (text == null || text.isEmpty() || media.isEmpty()) return text == null ? "" : text;
        String t = text;
        for (Media m : media) {
            if (!m.url.isEmpty()) t = t.replace(m.url, " ");
        }
        // GroupMe upload links for the same file can differ by size name (1920x1080.original.mp4 ...)
        t = t.replaceAll("https?://(m|v|i)\\.groupme\\.com/\\S+", " ");
        return t.replaceAll("[ \\t]{2,}", " ").trim();
    }

    /** "Photo", "GIF", "Video", or the text itself. */
    static String describe(String text, JSONArray atts) {
        String kind = "";
        if (atts != null) {
            for (int i = 0; i < atts.length() && kind.isEmpty(); i++) {
                Media m = parseMedia(atts.optJSONObject(i));
                if (m != null) kind = m.kind == Media.VIDEO ? "Video" : m.kind == Media.GIF ? "GIF" : "Photo";
            }
        }
        if (text != null && !text.trim().isEmpty()) {
            String link = LinkPreview.firstUrl(text);
            if (link != null && text.trim().equals(link) && LinkPreview.isVideoUrl(link)) return "Video";
            return text.trim();
        }
        return kind.isEmpty() ? "" : kind;
    }

    static void sortNewestFirst(List<Convo> list) {
        list.sort((a, b) -> Long.compare(b.lastAt, a.lastAt));
    }

    /** GroupMe image sizes: ".avatar" (60px), ".preview" (200px), ".large" (960px). Only for i.groupme.com. */
    static String sized(String url, String size) {
        if (url == null || url.isEmpty()) return "";
        return url.contains("i.groupme.com/") && !url.matches(".*\\.(avatar|preview|large)$") ? url + "." + size : url;
    }

    /** "now", "3m", "2h", "Mon", "Nov 3": how long since a message, short enough for a watch. */
    static String ago(long atSec, long nowMs) {
        if (atSec <= 0) return "";
        long s = Math.max(0, nowMs / 1000 - atSec);
        if (s < 60) return "now";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        if (s < 7 * 86400) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE", java.util.Locale.US);
            return f.format(new java.util.Date(atSec * 1000));
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMM d", java.util.Locale.US);
        return f.format(new java.util.Date(atSec * 1000));
    }

    private static String str(JSONObject o, String k) {
        return o == null || o.isNull(k) ? "" : o.optString(k, "");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
