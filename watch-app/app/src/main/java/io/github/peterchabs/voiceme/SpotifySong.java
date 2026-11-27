package io.github.peterchabs.voiceme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A profile song, read from Spotify's public embed page (the same player GroupMe's web app
 * embeds): its title, artist, cover and the 30 second preview Spotify lets anyone play.
 * Parsing is pure so it can be tested without a network.
 */
final class SpotifySong {
    final String kind, id, title, artist, cover, preview;
    final int tint; // the background colour Spotify picks for this cover (0 when it gave none)

    SpotifySong(String kind, String id, String title, String artist, String cover, String preview, int tint) {
        this.kind = kind;
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.cover = cover;
        this.preview = preview;
        this.tint = tint;
    }

    private static final Pattern LINK = Pattern.compile(
            "(?i)(?:open\\.spotify\\.com/(?:intl-[a-z-]+/)?(?:embed/)?|spotify:)(track|album|playlist|episode|show|artist)[/:]([A-Za-z0-9]{10,})");
    private static final Pattern DATA = Pattern.compile(
            "(?s)<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>");

    /** {"track", "4cOd..."} from any Spotify link or URI, or null when it isn't one. */
    static String[] parseLink(String url) {
        if (url == null) return null;
        Matcher m = LINK.matcher(url);
        return m.find() ? new String[]{m.group(1).toLowerCase(), m.group(2)} : null;
    }

    static String embedUrl(String[] link) { return "https://open.spotify.com/embed/" + link[0] + "/" + link[1]; }

    /** The song in an embed page, or null when the page has none. */
    static SpotifySong parse(String[] link, String html) {
        Matcher m = DATA.matcher(html);
        if (!m.find()) return null;
        try {
            JSONObject e = new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps")
                    .getJSONObject("state").getJSONObject("data").getJSONObject("entity");
            String title = e.optString("title", e.optString("name", ""));
            StringBuilder by = new StringBuilder();
            JSONArray artists = e.optJSONArray("artists");
            if (artists != null) for (int i = 0; i < artists.length(); i++) {
                String n = artists.getJSONObject(i).optString("name", "");
                if (n.isEmpty()) continue;
                if (by.length() > 0) by.append(", ");
                by.append(n);
            }
            if (by.length() == 0) by.append(e.optString("subtitle", ""));
            JSONObject vis = e.optJSONObject("visualIdentity");
            String cover = "";
            int tint = 0;
            if (vis != null) {
                JSONArray imgs = vis.optJSONArray("image");
                int best = 0;
                if (imgs != null) for (int i = 0; i < imgs.length(); i++) { // the 300 px one suits a watch
                    JSONObject im = imgs.getJSONObject(i);
                    int w = im.optInt("maxWidth", 0);
                    if (cover.isEmpty() || Math.abs(w - 300) < Math.abs(best - 300)) {
                        cover = im.optString("url", "");
                        best = w;
                    }
                }
                JSONObject b = vis.optJSONObject("backgroundBase");
                if (b != null) tint = 0xFF000000 | (b.optInt("red") << 16) | (b.optInt("green") << 8) | b.optInt("blue");
            }
            JSONObject ap = e.optJSONObject("audioPreview");
            String preview = ap == null ? "" : ap.optString("url", "");
            if (title.isEmpty()) return null;
            return new SpotifySong(link[0], link[1], title, by.toString(), cover, preview, tint);
        } catch (Exception bad) {
            return null;
        }
    }

    /** Reads the embed page (blocking: call it off the UI thread). Null when there's nothing to show. */
    static SpotifySong load(String url) throws IOException {
        String[] link = parseLink(url);
        if (link == null) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(embedUrl(link)).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "text/html");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while (bo.size() < 1024 * 1024 && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return parse(link, new String(bo.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }
}
