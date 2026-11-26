package io.github.peterchabs.sayso;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone sign-in, on GroupMe's own web login (web.groupme.com) inside the app. You log in there
 * exactly as on the web (phone number or email, password, the code GroupMe texts), and once
 * you're in, the app takes GroupMe web's own sign-in (kept in the page's storage as
 * "access_token"; older versions used a "token" cookie). That's the kind of
 * sign-in GroupMe lets send direct messages; tokens from its developer site can't anymore.
 * Your password goes only to GroupMe. When we have the token, the site's login is wiped from
 * the app so nothing else lingers.
 */
public class GroupMeSignInActivity extends Activity {
    static final String EXTRA_TOKEN = "token";
    static final String HOST = "web.groupme.com";
    static final String START = "https://" + HOST + "/signin";

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9]{20,100}$");
    private static final Pattern COOKIE = Pattern.compile("(?:^|;\\s*)token=([^;]*)");

    private WebView web;
    private TextView note;
    private ProgressBar bar;

    /** The line under the title: what this is, or (failed) what went wrong and how to retry. */
    private void showNote(boolean failed) {
        if (note == null) return;
        note.setText(failed
                ? "Couldn't open GroupMe's site. Check your connection, then tap here to try again."
                : "This is GroupMe's own sign-in. Sign in however you normally do."
                        + " Your password goes only to GroupMe.");
        // the app's own colors, light or dark
        note.setTextColor(failed ? (Palette.dark ? 0xFFFF8A80 : 0xFFB71C1C) : Palette.SUB);
        note.setBackgroundColor(failed ? (Palette.dark ? 0xFF3A1D1F : 0xFFFDECEA) : Palette.ROW);
        note.setOnClickListener(failed ? v -> web.loadUrl(START) : null);
    }
    private boolean done;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Palette.apply(this);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(OrbView.ACCENT);
        top.setPadding((int) (8 * d), (int) (6 * d), (int) (16 * d), (int) (6 * d));

        TextView cancel = new TextView(this);
        cancel.setText("Cancel");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(16);
        cancel.setPadding((int) (10 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        cancel.setOnClickListener(v -> giveUp());
        top.addView(cancel);

        TextView title = new TextView(this);
        title.setText("Sign in to GroupMe");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        note = new TextView(this);
        note.setTextSize(13);
        showNote(false);
        note.setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (10 * d));
        root.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(OrbView.ACCENT));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (3 * d)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);   // GroupMe's login needs it (human check)
        s.setDomStorageEnabled(true);
        s.setSaveFormData(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.animate().alpha(p >= 100 ? 0f : 1f).setDuration(200).start();
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme())) return false;
                // any plain-http link in GroupMe's login: go over https instead
                if ("http".equals(u.getScheme()) && r.isForMainFrame()) {
                    v.loadUrl(u.buildUpon().scheme("https").build().toString());
                }
                return true; // nothing else (no http, no other apps' links)
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                showNote(false);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                check();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame() && !done) showNote(true);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        // (a sign-in that finished, or Cancel, wipes GroupMe's site from the app, so whatever is here
        // is a sign-in you just made and didn't finish, e.g. stuck on "Use the GroupMe app")
        CookieManager.getInstance().setAcceptCookie(true);
        web.loadUrl(START);
        web.postDelayed(watcher, 1000);
    }

    /** GroupMe web signs in without changing pages, so look for its sign-in every second. */
    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            if (done || web == null) return;
            check();
            web.postDelayed(this, 1000);
        }
    };

    /** Takes GroupMe web's sign-in the moment it exists: its page storage first, then the older cookie. */
    private void check() {
        if (done || web == null) return;
        String url = web.getUrl();
        if (url != null && HOST.equals(Uri.parse(url).getHost())) {
            web.evaluateJavascript("(function(){try{return localStorage.getItem('access_token')||''}catch(e){return ''}})()", v -> {
                if (done) return;
                String t = fromStorage(v);
                if (t != null) finishWith(t);
            });
        }
        String token = tokenFrom(CookieManager.getInstance().getCookie("https://" + HOST));
        if (token != null) finishWith(token);
    }

    /** What evaluateJavascript hands back for the stored sign-in ("\"XYZ\"", "\"\"" or "null"). */
    static String fromStorage(String js) {
        if (js == null) return null;
        String t = js.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return TOKEN.matcher(t).matches() ? t : null;
    }

    /** GroupMe web's sign-in from its cookies ("a=1; token=XYZ; b=2"), or null if not signed in yet. */
    static String tokenFrom(String cookies) {
        if (cookies == null) return null;
        Matcher m = COOKIE.matcher(cookies);
        if (!m.find()) return null;
        String t = m.group(1).trim();
        return TOKEN.matcher(t).matches() ? t : null;
    }

    private void finishWith(String token) {
        if (done) return;
        done = true;
        wipe();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_TOKEN, token));
        finish();
    }

    private void giveUp() {
        done = true;
        wipe();
        setResult(RESULT_CANCELED);
        finish();
    }

    /** Signs the in-app browser out of GroupMe's site and forgets everything it stored. */
    private void wipe() {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        WebStorage.getInstance().deleteAllData();
        if (web != null) {
            web.stopLoading();
            web.clearCache(true);
            web.clearHistory();
            web.clearFormData();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else giveUp();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
