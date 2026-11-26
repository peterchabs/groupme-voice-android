package io.github.peterchabs.sayso;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** App-private storage (other apps can't read it; backups are off in the manifest). */
final class Store {
    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("groupme_voice", Context.MODE_PRIVATE);
    }

    static String token(Context c) { return p(c).getString("token", ""); }

    static void setToken(Context c, String t) { p(c).edit().putString("token", t).apply(); }

    static String name(Context c) { return p(c).getString("name", ""); }

    static String myId(Context c) { return p(c).getString("my_id", ""); }

    static void setMyId(Context c, String id) { p(c).edit().putString("my_id", id == null ? "" : id).apply(); }

    static void setName(Context c, String n) { p(c).edit().putString("name", n == null ? "" : n).apply(); }

    /**
     * Where you left off in a chat, as Say So saw it: how many messages it had and its newest
     * message id. GroupMe has no way for an app to mark a chat read, so Say So keeps its own.
     */
    static int seenCount(Context c, String key) { return p(c).getInt("seen_n_" + key, -1); }

    static String seenId(Context c, String key) { return p(c).getString("seen_id_" + key, ""); }

    static void setSeen(Context c, String key, int count, String lastId) {
        SharedPreferences.Editor e = p(c).edit();
        if (count >= 0) e.putInt("seen_n_" + key, count);
        if (lastId != null && !lastId.isEmpty()) e.putString("seen_id_" + key, lastId);
        e.apply();
    }

    /** Where you were reading a chat: the message at the top of the screen, and how far down it sat (px). */
    static String viewId(Context c, String key) { return p(c).getString("view_id_" + key, ""); }

    static int viewOffset(Context c, String key) { return p(c).getInt("view_y_" + key, 0); }

    static void setView(Context c, String key, String id, int offset) {
        p(c).edit().putString("view_id_" + key, id).putInt("view_y_" + key, offset).apply();
    }

    /** New-message notifications (PushService). Off until you turn them on. */
    static boolean pushOn(Context c) { return p(c).getBoolean("push", false); }

    static void setPushOn(Context c, boolean on) { p(c).edit().putBoolean("push", on).apply(); }

    /** Whether new-message notifications make a sound (on) or arrive silently (off). Buzzes either way. */
    static boolean pushSound(Context c) { return p(c).getBoolean("push_sound", true); }

    static void setPushSound(Context c, boolean on) { p(c).edit().putBoolean("push_sound", on).apply(); }

    /** "light" (default), "dark" or "system" (see Palette). */
    static String theme(Context c) { return p(c).getString("theme", "light"); }

    static void setTheme(Context c, String t) { p(c).edit().putString("theme", t).apply(); }

    /** Your GroupMe profile picture (may be empty). */
    static String avatar(Context c) { return p(c).getString("avatar", ""); }

    static void setAvatar(Context c, String url) { p(c).edit().putString("avatar", url == null ? "" : url).apply(); }

    /** Pictures in chats on or off (off saves data on a watch). On unless turned off. */
    static boolean photosOn(Context c) { return p(c).getBoolean("photos", true); }

    static void setPhotosOn(Context c, boolean on) { p(c).edit().putBoolean("photos", on).apply(); }

    /** The group your message goes to when you don't name one. Null when none is set. */
    static Phrase.Group defaultGroup(Context c) {
        String id = p(c).getString("default_id", "");
        return id.isEmpty() ? null : new Phrase.Group(id, p(c).getString("default_name", ""));
    }

    static void setDefaultGroup(Context c, Phrase.Group g) {
        if (g == null) p(c).edit().remove("default_id").remove("default_name").apply();
        else p(c).edit().putString("default_id", g.id).putString("default_name", g.name).apply();
    }

    /** The one-time sign-in code while you're signing in (survives the trip to the browser). */
    static String pendingCode(Context c) { return p(c).getString("pending_code", ""); }

    static void setPendingCode(Context c, String code) { p(c).edit().putString("pending_code", code).apply(); }

    static void clear(Context c) { p(c).edit().clear().apply(); }

    /**
     * GroupMe stopped accepting the token (expired, reset, or signed out elsewhere). Forget it so
     * the next screen is sign-in, but keep who you were and your default group so signing back
     * in picks up where you left off.
     */
    static void forgetSignIn(Context c) {
        p(c).edit().remove("token").remove("groups").remove("groups_at")
                .remove("contacts").remove("contacts_at").apply();
    }

    /** People you have direct messages with (id = their user id). */
    static List<Phrase.Group> contacts(Context c) { return read(c, "contacts"); }

    static void saveContacts(Context c, List<Phrase.Group> people) { write(c, "contacts", people); }

    static long contactsAgeMs(Context c) {
        return System.currentTimeMillis() - p(c).getLong("contacts_at", 0);
    }

    static long groupsAgeMs(Context c) {
        return System.currentTimeMillis() - p(c).getLong("groups_at", 0);
    }

    static List<Phrase.Group> groups(Context c) { return read(c, "groups"); }

    static void saveGroups(Context c, List<Phrase.Group> groups) { write(c, "groups", groups); }

    private static List<Phrase.Group> read(Context c, String key) {
        List<Phrase.Group> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Phrase.Group(o.getString("id"), o.getString("name")));
            }
        } catch (Exception ignored) {
            // a damaged cache just means we fetch fresh from GroupMe
        }
        return out;
    }

    private static void write(Context c, String key, List<Phrase.Group> list) {
        JSONArray a = new JSONArray();
        try {
            for (Phrase.Group g : list) a.put(new JSONObject().put("id", g.id).put("name", g.name));
        } catch (Exception ignored) {
            return;
        }
        p(c).edit().putString(key, a.toString()).putLong(key + "_at", System.currentTimeMillis()).apply();
    }
}
