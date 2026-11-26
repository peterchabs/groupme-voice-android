package io.github.peterchabs.sayso;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small image loader: downloads once to the app's cache folder, keeps recent bitmaps in memory,
 * scales each image down to the size it's shown at (a watch has little memory), crops avatars
 * to circles, and plays animated GIFs. Images come straight from GroupMe's servers.
 */
final class Images {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, named("sayso-img"));
    private static final ExecutorService FRAMES = Executors.newFixedThreadPool(2, named("sayso-frame"));
    private static final java.util.concurrent.ScheduledExecutorService KILL =
            Executors.newSingleThreadScheduledExecutor(named("sayso-frame-stop"));

    private static java.util.concurrent.ThreadFactory named(String name) {
        final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 24L * 1024 * 1024)) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private Images() { }

    /** Shows {@code url} in {@code v}, scaled to about {@code px} wide. {@code circle} crops it round. */
    static void load(ImageView v, String url, int px, boolean circle) {
        load(v, url, px, circle, false);
    }

    /** Like load(), but plays GIFs (Android 9+) instead of showing their first frame. */
    static void load(ImageView v, String url, int px, boolean circle, boolean animate) {
        if (url == null || url.isEmpty()) {
            v.setTag(R.id.img_key, null);
            return;
        }
        String key = url + "|" + px + "|" + circle;
        v.setTag(R.id.img_key, key);
        Bitmap hit = animate ? null : MEM.get(key);
        if (hit != null) {
            v.setImageBitmap(hit);
            v.setAlpha(1f);
            return;
        }
        v.setImageDrawable(null);
        v.setAlpha(0f);
        Context app = v.getContext().getApplicationContext();
        POOL.execute(() -> {
            try {
                File f = cached(app, url);
                if (animate && Build.VERSION.SDK_INT >= 28) {
                    Drawable d = android.graphics.ImageDecoder.decodeDrawable(
                            android.graphics.ImageDecoder.createSource(f), (dec, info, src) -> {
                                int w = info.getSize().getWidth();
                                if (w > px) dec.setTargetSampleSize(Math.max(1, w / px));
                            });
                    UI.post(() -> {
                        if (!key.equals(v.getTag(R.id.img_key))) return;
                        v.setImageDrawable(d);
                        if (d instanceof Animatable) ((Animatable) d).start();
                        fadeIn(v);
                    });
                    return;
                }
                Bitmap b = decode(f, px);
                if (b == null) {
                    android.util.Log.w("SaySo", "picture unreadable: " + url + " (" + f.length() + " bytes)");
                    return;
                }
                if (circle) b = circle(b);
                MEM.put(key, b);
                Bitmap done = b;
                UI.post(() -> {
                    if (!key.equals(v.getTag(R.id.img_key))) return; // the row was reused
                    v.setImageBitmap(done);
                    fadeIn(v);
                });
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("SaySo", "picture didn't load: " + url + " (" + e + ")"); // no picture: the placeholder stays
            }
        });
    }

    /** A frame from about a second into a video, as its still. Cached in memory like any picture. */
    static void loadVideoFrame(ImageView v, String url, int px) {
        String key = "frame|" + url + "|" + px;
        v.setTag(R.id.img_key, key);
        Bitmap hit = MEM.get(key);
        if (hit != null) {
            v.setImageBitmap(hit);
            v.setAlpha(1f);
            return;
        }
        v.setImageDrawable(null);
        v.setAlpha(0f);
        // Its own queue: grabbing a frame over the network has no timeout of its own and can hang,
        // and a stuck grab must never hold up everyone's pictures.
        FRAMES.execute(() -> {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            java.util.concurrent.ScheduledFuture<?> stop = KILL.schedule(() -> {
                try {
                    r.release(); // past 15 s: give up, which unblocks the grab
                } catch (Exception ignored) {
                    // already done
                }
            }, 15, java.util.concurrent.TimeUnit.SECONDS);
            try {
                r.setDataSource(finalUrl(url), new java.util.HashMap<>()); // the frame grabber won't follow redirects
                Bitmap f = r.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f == null) return;
                if (f.getWidth() > px) {
                    f = Bitmap.createScaledBitmap(f, px, Math.max(1, f.getHeight() * px / f.getWidth()), true);
                }
                MEM.put(key, f);
                Bitmap done = f;
                UI.post(() -> {
                    if (!key.equals(v.getTag(R.id.img_key))) return;
                    v.setImageBitmap(done);
                    fadeIn(v);
                });
            } catch (RuntimeException e) {
                android.util.Log.w("SaySo", "video frame didn't load: " + url + " (" + e + ")");
            } finally {
                stop.cancel(false);
                try {
                    r.release();
                } catch (Exception ignored) {
                    // already released
                }
            }
        });
    }

    /** Where a link ends up after redirects (GroupMe's uploads redirect to a signed address). */
    static String finalUrl(String url) {
        String u = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                int code = c.getResponseCode();
                String next = c.getHeaderField("Location");
                if (code < 300 || code >= 400 || next == null) return u;
                u = new java.net.URI(u).resolve(next).toString();
            } catch (Exception e) {
                return u;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return u;
    }

    /**
     * A round picture for a notification, fetched right now (call from a background thread).
     * Uses the same caches as the screens. Null if there's no picture or it can't be fetched.
     */
    static Bitmap roundNow(Context c, String url, int px) {
        if (url == null || url.isEmpty()) return null;
        String key = url + "|" + px + "|true";
        Bitmap hit = MEM.get(key);
        if (hit != null) return hit;
        try {
            Bitmap b = decode(cached(c.getApplicationContext(), url), px);
            if (b == null) return null;
            b = circle(b);
            MEM.put(key, b);
            return b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A whole picture (not cropped round) for a notification, fetched now, on a background thread. */
    static Bitmap pictureNow(Context c, String url, int px) {
        if (url == null || url.isEmpty()) return null;
        String key = url + "|" + px + "|false";
        Bitmap hit = MEM.get(key);
        if (hit != null) return hit;
        try {
            Bitmap b = decode(cached(c.getApplicationContext(), url), px);
            if (b != null) MEM.put(key, b);
            return b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void fadeIn(ImageView v) {
        v.animate().alpha(1f).setDuration(180).start();
    }

    private static File cached(Context c, String url) throws IOException {
        File dir = new File(c.getCacheDir(), "img");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("no cache");
        File f = new File(dir, hash(url));
        // A new video's preview is a small grey "processing" picture until GroupMe finishes; don't
        // keep that one forever: fetch again once it's a minute old.
        boolean placeholder = url.contains("/uploads/") && f.length() > 0 && f.length() < 12000
                && System.currentTimeMillis() - f.lastModified() > 60_000;
        if (f.length() > 0 && !placeholder) return f;
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            if (con.getResponseCode() >= 300) throw new IOException("image " + con.getResponseCode());
            File tmp = new File(dir, f.getName() + ".part");
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(f)) throw new IOException("cache write");
            trim(dir);
            return f;
        } finally {
            con.disconnect();
        }
    }

    /** Keep the disk cache under ~40 MB, oldest files first. */
    private static void trim(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return;
        long total = 0;
        for (File x : all) total += x.length();
        if (total < 40L * 1024 * 1024) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File x : all) {
            if (total < 30L * 1024 * 1024) break;
            total -= x.length();
            //noinspection ResultOfMethodCallIgnored
            x.delete();
        }
    }

    private static Bitmap decode(File f, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= px) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    private static Bitmap circle(Bitmap src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(-(src.getWidth() - s) / 2f, -(src.getHeight() - s) / 2f);
        sh.setLocalMatrix(m);
        p.setShader(sh);
        new Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, p);
        return out;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
