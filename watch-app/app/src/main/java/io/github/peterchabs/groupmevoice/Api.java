package io.github.peterchabs.groupmevoice;

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

    /** Your GroupMe display name. Also proves the token works. */
    static String myName(String token) throws IOException {
        JSONObject r = call("GET", "/users/me", token, null);
        JSONObject me = r.optJSONObject("response");
        return me == null ? "" : me.optString("name", "");
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

    static void send(String token, String groupId, String text) throws IOException {
        try {
            JSONObject msg = new JSONObject()
                    .put("source_guid", UUID.randomUUID().toString())
                    .put("text", text);
            String path = "/groups/" + URLEncoder.encode(groupId, "UTF-8") + "/messages";
            call("POST", path, token, new JSONObject().put("message", msg).toString());
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static JSONObject call(String method, String path, String token, String body) throws IOException {
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
