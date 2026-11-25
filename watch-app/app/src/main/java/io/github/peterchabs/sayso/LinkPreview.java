package io.github.peterchabs.sayso;

import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link previews: the picture, title and site name a web page offers for sharing (its Open Graph
 * "og:" tags), shown as a card under a message that contains a link. Only the top of the page is
 * read. Parsing is pure so it can be tested without a network.
 */
final class LinkPreview {
    private LinkPreview() { }

    static final class Card {
        final String url, title, site, image;

        Card(String url, String title, String site, String image) {
            this.url = url;
            this.title = title;
            this.site = site;
            this.image = image;
        }

        boolean hasImage() { return !image.isEmpty(); }
    }

    private static final Card NONE = new Card("", "", "", "");
    private static final LruCache<String, Card> CACHE = new LruCache<>(80);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"]+");
    private static final Pattern META = Pattern.compile("(?is)<meta\\s[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(?is)([a-z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /** The first web link in a message, without trailing punctuation, or null. */
    static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_IN_TEXT.matcher(text);
        if (!m.find()) return null;
        String u = m.group();
        while (!u.isEmpty() && ".,;:!?)]}'\u2019\u201D".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1);
        return u.length() > 10 ? u : null;
    }

    /** A link straight to a picture: show the picture itself, no page to read. */
    static boolean isImageUrl(String url) {
        String path = url.toLowerCase().replaceAll("[?#].*$", "");
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".gif") || path.endsWith(".webp");
    }

    /** A link straight to a video file (GroupMe's own uploads arrive this way too). */
    static boolean isVideoUrl(String url) {
        String path = url.toLowerCase().replaceAll("[?#].*$", "");
        return path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".webm") || path.endsWith(".m4v");
    }

    /** Reads og:image / og:title / og:site_name (Twitter's tags and &lt;title&gt; as fallbacks). */
    static Card parse(String pageUrl, String html) {
        String image = "", title = "", site = "", twImage = "", twTitle = "";
        Matcher m = META.matcher(html);
        while (m.find()) {
            String key = "", content = "";
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                String name = a.group(1).toLowerCase();
                String val = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5);
                if (name.equals("property") || name.equals("name")) key = val.toLowerCase().trim();
                else if (name.equals("content")) content = unescape(val).trim();
            }
            if (content.isEmpty()) continue;
            switch (key) {
                case "og:image": case "og:image:url": case "og:image:secure_url":
                    if (image.isEmpty()) image = content;
                    break;
                case "og:title": if (title.isEmpty()) title = content; break;
                case "og:site_name": if (site.isEmpty()) site = content; break;
                case "twitter:image": case "twitter:image:src": if (twImage.isEmpty()) twImage = content; break;
                case "twitter:title": if (twTitle.isEmpty()) twTitle = content; break;
                default: break;
            }
        }
        if (image.isEmpty()) image = twImage;
        if (title.isEmpty()) title = twTitle;
        if (title.isEmpty()) {
            Matcher t = TITLE.matcher(html);
            if (t.find()) title = unescape(t.group(1).replaceAll("\\s+", " ")).trim();
        }
        if (site.isEmpty()) site = host(pageUrl);
        return new Card(pageUrl, title, site, absolute(pageUrl, image));
    }

    /** Calls {@code done} on the UI thread with the page's card (nothing when there isn't one). */
    static void load(String url, Consumer<Card> done) {
        Card hit = CACHE.get(url);
        if (hit != null) {
            if (hit != NONE) done.accept(hit);
            return;
        }
        POOL.execute(() -> {
            Card c;
            try {
                c = isImageUrl(url) ? new Card(url, "", host(url), url) : fetch(url);
            } catch (IOException | RuntimeException e) {
                c = NONE;
            }
            if (c != NONE && c.title.isEmpty() && !c.hasImage()) c = NONE;
            CACHE.put(url, c);
            Card result = c;
            if (result != NONE) UI.post(() -> done.accept(result));
        });
    }

    private static Card fetch(String start) throws IOException {
        String url = start;
        for (int hop = 0; hop < 5; hop++) { // follow redirects, including http to https
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SaySo link preview");
                c.setRequestProperty("Accept", "text/html");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = c.getHeaderField("Location");
                    if (next == null) throw new IOException("redirect");
                    url = absolute(url, next);
                    if (!url.startsWith("http")) throw new IOException("scheme");
                    continue;
                }
                if (code != 200) throw new IOException("page " + code);
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
                if (type.startsWith("image/")) return new Card(start, "", host(url), url);
                if (!type.contains("html")) throw new IOException("not a page");
                return parse(url, head(c.getInputStream()));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /** The page up to &lt;/head&gt; (where the tags live), at most 256 KB. */
    private static String head(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (bo.size() < 256 * 1024 && (n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.toString("ISO-8859-1").toLowerCase().contains("</head>")) break;
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : h.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String absolute(String base, String ref) {
        if (ref == null || ref.isEmpty()) return "";
        try {
            String r = new URI(base).resolve(ref.trim().replace(" ", "%20")).toString();
            return r.startsWith("http") ? r : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
    }
}
