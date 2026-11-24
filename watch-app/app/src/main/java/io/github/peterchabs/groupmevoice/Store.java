package io.github.peterchabs.groupmevoice;

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

    static void setName(Context c, String n) { p(c).edit().putString("name", n == null ? "" : n).apply(); }

    /** The group your message goes to when you don't name one. Null when none is set. */
    static Phrase.Group defaultGroup(Context c) {
        String id = p(c).getString("default_id", "");
        return id.isEmpty() ? null : new Phrase.Group(id, p(c).getString("default_name", ""));
    }

    static void setDefaultGroup(Context c, Phrase.Group g) {
        if (g == null) p(c).edit().remove("default_id").remove("default_name").apply();
        else p(c).edit().putString("default_id", g.id).putString("default_name", g.name).apply();
    }

    static void clear(Context c) { p(c).edit().clear().apply(); }

    static long groupsAgeMs(Context c) {
        return System.currentTimeMillis() - p(c).getLong("groups_at", 0);
    }

    static List<Phrase.Group> groups(Context c) {
        List<Phrase.Group> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("groups", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Phrase.Group(o.getString("id"), o.getString("name")));
            }
        } catch (Exception ignored) {
            // a damaged cache just means we fetch fresh from GroupMe
        }
        return out;
    }

    static void saveGroups(Context c, List<Phrase.Group> groups) {
        JSONArray a = new JSONArray();
        try {
            for (Phrase.Group g : groups) a.put(new JSONObject().put("id", g.id).put("name", g.name));
        } catch (Exception ignored) {
            return;
        }
        p(c).edit().putString("groups", a.toString())
                .putLong("groups_at", System.currentTimeMillis()).apply();
    }
}
