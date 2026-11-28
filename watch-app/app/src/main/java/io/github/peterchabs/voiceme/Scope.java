package io.github.peterchabs.voiceme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Google Play review account is a real GroupMe account that sits in other groups too. Signed in
 * as that account, VoiceMe only ever sees and uses its "Test" group: other groups and direct
 * messages are hidden from every list, refused if asked for directly, and dropped from the live feed.
 * Every other account is unaffected.
 */
final class Scope {
    static final String REVIEW_USER = "138844059";
    static final String REVIEW_GROUP = "109842457";

    /** The one group allowed, or null when the signed-in account isn't limited. */
    private static volatile String only;

    private Scope() { }

    static void forUser(String myId) { only = REVIEW_USER.equals(myId) ? REVIEW_GROUP : null; }

    static boolean limited() { return only != null; }

    /** Whether a chat may be shown or used: a group's id, or a direct message (dm = true). */
    static boolean allows(boolean dm, String id) { return only == null || (!dm && only.equals(id)); }

    /** Before a request goes out: refuses anything outside the allowed group. */
    static void check(String method, String path) throws Api.RefusedException {
        if (only == null) return;
        String p = path.startsWith("https://") ? path.replaceFirst("^https://[^/]+/v\\d+", "") : path;
        if (p.startsWith("/direct_messages")) throw new Api.RefusedException(NOT_HERE);
        String gid = null;
        if (p.startsWith("/groups/")) gid = segment(p, 8);
        else if (p.startsWith("/messages/")) gid = segment(p, 10); // likes: /messages/GROUP/MESSAGE/like
        else if (p.startsWith("/conversations/")) gid = segment(p, 15);
        if (gid != null && !gid.isEmpty() && !only.equals(gid)) throw new Api.RefusedException(NOT_HERE);
        if (p.startsWith("/chats") && !"GET".equals(method)) throw new Api.RefusedException(NOT_HERE);
    }

    /** After a response comes back: lists keep only the allowed group, and no direct messages. */
    static JSONObject filter(String method, String path, JSONObject r) throws JSONException {
        if (only == null || !"GET".equals(method)) return r;
        String p = path.startsWith("https://") ? path.replaceFirst("^https://[^/]+/v\\d+", "") : path;
        if (p.startsWith("/chats")) return r.put("response", new JSONArray());
        if (p.equals("/groups") || p.startsWith("/groups?")) {
            JSONArray a = r.optJSONArray("response"), keep = new JSONArray();
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject g = a.optJSONObject(i);
                    if (g != null && only.equals(g.optString("id"))) keep.put(g);
                }
            }
            return r.put("response", keep);
        }
        return r;
    }

    private static String segment(String p, int from) {
        int end = from;
        while (end < p.length() && p.charAt(end) != '/' && p.charAt(end) != '?') end++;
        return p.substring(from, end);
    }

    private static final String NOT_HERE = "This account can only use its Test group in VoiceMe.";
}
