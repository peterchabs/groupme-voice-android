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
 * Phone sign-in. Opens GroupMe's developer site (dev.groupme.com) inside the app. You log in
 * there like you normally would, and once you're in, the app reads your access token from
 * GroupMe's Access Token page. Your password goes only to GroupMe. When we have the token, the
 * site's login is wiped from the app so nothing else lingers.
 */
public class DevSignInActivity extends Activity {
    static final String EXTRA_TOKEN = "token";
    static final String HOST = "dev.groupme.com";
    static final String TOKEN_PAGE = "https://" + HOST + "/account/access-token";

    /** Reads the token from GroupMe's Access Token page, and says whether a sign-in form is showing. */
    private static final String READ_JS = "(function(){"
            + "var e=document.getElementById('access-token-value');"
            + "var t=e&&e.getAttribute('data-full-value');"
            + "if(!t){var b=document.querySelector('.copy-token-button');t=b&&b.getAttribute('data-copy-value');}"
            + "var f=!!document.querySelector('input[type=password],input[type=tel],input[name*=pin],input[name*=code],form#login-form');"
            + "return JSON.stringify({t:t||'',f:f});})()";

    /**
     * GroupMe's PIN boxes are fixed-width and run off both sides of a phone screen. Let them
     * share the row instead. Only touches the PIN row's layout on dev.groupme.com.
     */
    private static final String FIT_JS = "(function(){if(document.getElementById('gmv-fit'))return;"
            + "var s=document.createElement('style');s.id='gmv-fit';"
            + "s.textContent='.otp-input{gap:6px!important;max-width:100%!important;box-sizing:border-box}"
            + ".otp-input .otp-digit{flex:1 1 0!important;min-width:0!important;width:auto!important;"
            + "max-width:52px!important;padding-left:0!important;padding-right:0!important;box-sizing:border-box}';"
            + "(document.head||document.documentElement).appendChild(s);})()";

    private static final Pattern TOKEN =Pattern.compile("^[A-Za-z0-9]{20,100}$");
    private static final Pattern FIELD_T = Pattern.compile("\\\\?\"t\\\\?\":\\\\?\"([A-Za-z0-9]*)\\\\?\"");

    private WebView web;
    private TextView note;
    private ProgressBar bar;

    /** The line under the title: what this is, or (failed) what went wrong and how to retry. */
    private void showNote(boolean failed) {
        if (note == null) return;
        note.setText(failed
                ? "Couldn't open GroupMe's site. Check your connection, then tap here to try again."
                : "This is GroupMe's own developer site. Log in like normal and the app picks up"
                        + " your token for you. Your password goes only to GroupMe.");
        note.setTextColor(failed ? 0xFFB71C1C : 0xFF4A5560);
        note.setBackgroundColor(failed ? 0xFFFDECEA : 0xFFE6F7FE);
        note.setOnClickListener(failed ? v -> web.loadUrl(TOKEN_PAGE) : null);
    }
    private int redirects;
    private boolean done;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
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
                // GroupMe's own redirects say http://dev.groupme.com/...; always go over https instead
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
            public void onPageCommitVisible(WebView v, String url) {
                fit(v, url); // as soon as the page shows, so the boxes never appear cut off
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                fit(v, url);
                check(url, true);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame() && !done) showNote(true);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl(TOKEN_PAGE); // not signed in: GroupMe sends you to its login, then we come back
    }

    private static void fit(WebView v, String url) {
        if (url != null && HOST.equals(Uri.parse(url).getHost())) v.evaluateJavascript(FIT_JS, null);
    }

    private void check(String url, boolean retry) {
        if (done || url == null) return;
        Uri u = Uri.parse(url);
        if (!HOST.equals(u.getHost())) return; // another site in the login (e.g. the human check)
        web.evaluateJavascript(READ_JS, json -> {
            if (done) return;
            String token = tokenFrom(json);
            if (token != null) {
                finishWith(token);
                return;
            }
            boolean form = json != null && json.contains("\\\"f\\\":true") || json != null && json.contains("\"f\":true");
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.startsWith("/account/access-token")) {
                if (retry) web.postDelayed(() -> check(web.getUrl(), false), 900); // page still drawing
            } else if (!form && !path.startsWith("/session") && redirects < 4) {
                redirects++; // logged in and landed elsewhere: go get the token
                web.loadUrl(TOKEN_PAGE);
            }
        });
    }

    /** Pulls the token out of what evaluateJavascript hands back (a JSON string inside a string). */
    static String tokenFrom(String json) {
        if (json == null) return null;
        Matcher m = FIELD_T.matcher(json);
        if (!m.find()) return null;
        String t = m.group(1);
        return TOKEN.matcher(t).matches() ? t : null;
    }

    private void finishWith(String token) {
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
