package io.github.peterchabs.sayso;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chats: every group and direct message (newest first, with how long ago), a settings panel that
 * slides down from above the list, a conversation with inline photos, GIFs and videos, and
 * full-screen media. Swipe left to go deeper (list, then chat), swipe right to come back.
 * The mic in a chat sends your spoken message straight to that group or person.
 */
public class HomeActivity extends Activity {
    private static final int BLUE = Palette.BLUE;
    private int INK, GREY, BG; // from Palette (light or dark), set first thing in onCreate
    private static final int REQ_MIC = 7, REQ_NOTIF = 8;
    private static final long SEND_DELAY_MS = 3000;
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(2);

    private boolean watch;
    private float d;
    private int W, H;

    private FrameLayout root, homeLayer, listWrap, chatLayer, mediaLayer;
    private ScrollView settings;
    private ListView list, chatList;
    private TextView listStatus, chatTitle, chatStatus;
    private ImageView chatAvatar;
    private View micButton;
    private LinearLayout voicePanel;

    private final List<Feed.Convo> convos = new ArrayList<>();
    private final List<Feed.Msg> msgs = new ArrayList<>();
    private ConvoAdapter convoAdapter;
    private MsgAdapter msgAdapter;
    private Feed.Convo current;
    private int level; // 0 list/settings, 1 chat, 2 members, 3 a profile or event, 4 full-screen media, 5 gallery, 6 events
    private FrameLayout galleryLayer, eventsLayer;
    private android.widget.GridView galleryGrid;
    private TextView galleryStatus, eventsStatus;
    private ListView eventList;
    private final List<Feed.Media> photos = new ArrayList<>();
    private final List<Feed.Event> events = new ArrayList<>();
    private BaseAdapter photoAdapter, eventAdapter;
    private String galleryFor = "", eventsFor = "";
    private int mediaFrom = 1, profileFrom = 2;
    private FrameLayout membersLayer, profileLayer;
    private ListView memberList;
    private TextView membersStatus;
    private Button tagButton;
    private final List<Feed.Member> members = new ArrayList<>();
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>(); // user ids, in tap order
    private boolean picking;
    private MemberAdapter memberAdapter;
    private String membersFor = "";
    /** People to @mention in the next message from the mic. */
    private final List<Feed.Member> tagging = new ArrayList<>();
    private boolean settingsOpen;
    private VideoView playing;

    /** Opened by voice ("Hey Google, open Say So") with a default group: straight into its chat. */
    static final String EXTRA_VOICE = "voice";
    private boolean voiceEntry;   // the first thing said may still name another chat
    private boolean instant;      // next go() switches without the slide
    private LinearLayout draft, draftButtons;
    private TextView draftText, draftNote;
    private View draftBar;
    private int listBottom;       // the chat list's bottom padding (room for the mic)
    private LinearLayout tagRow;  // the faces of everyone being tagged, under the chat name
    private float micScale = 1f;  // the mic's resting size while it waits beside the chat
    private Feed.Msg replying;    // long-pressed: the next message answers this one
    private TextView draftQuote;  // "Replying to ..." above the draft
    private LinearLayout reactCard; // the emoji row a double-tap brings up
    private boolean reactTouch;   // this touch began on the emoji row
    /** The quick reactions a double-tap offers. */
    private static final String[] REACTIONS = {"\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDD25"};
    private String typeTarget = "";
    private int typeShown;

    private SpeechRecognizer rec;
    private boolean listening;
    private Runnable pendingSend;

    // ---------------- setup ----------------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Palette.apply(this);
        INK = Palette.INK;
        GREY = Palette.SUB;
        BG = Palette.BG;
        watch = Stage.isWatchBuild();
        if (!watch) { // phone status bar follows the theme
            getWindow().setStatusBarColor(BG);
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            d.setSystemUiVisibility(Palette.dark ? f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        getWindow().getDecorView().setBackgroundColor(Palette.BG);
        d = getResources().getDisplayMetrics().density;
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        if (Store.token(this).isEmpty()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        homeLayer = new FrameLayout(this);
        buildList();
        buildSettings();
        root.addView(homeLayer, match());
        buildChat();
        buildMembers();
        buildProfile();
        buildGallery();
        buildEvents();
        buildMedia();
        root.addView(settings, match()); // above every page: pulls down from any of them
        setContentView(root);
        settings.setTranslationY(-H);
        chatLayer.setVisibility(View.GONE);
        membersLayer.setVisibility(View.GONE);
        profileLayer.setVisibility(View.GONE);
        galleryLayer.setVisibility(View.GONE);
        eventsLayer.setVisibility(View.GONE);
        mediaLayer.setVisibility(View.GONE);
        refreshList();
        PushService.sync(this);
        voiceIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        voiceIntent(intent);
    }

    /** Started to talk with a default group set: show that chat at once and listen in it. */
    private void voiceIntent(Intent intent) {
        if (intent != null && intent.hasExtra(PushService.EXTRA_KEY)) { // from a message notification
            String id = intent.getStringExtra(PushService.EXTRA_ID), name = intent.getStringExtra(PushService.EXTRA_NAME);
            boolean dm = intent.getBooleanExtra(PushService.EXTRA_DM, false);
            intent.removeExtra(PushService.EXTRA_KEY);
            if (id != null) {
                if (settingsOpen) closeSettings();
                String pic = "";
                for (Feed.Convo c : convos) if (c.isDm == dm && c.id.equals(id)) pic = c.avatarUrl;
                instant = level == 0;
                fromNotification = true;
                openChat(new Feed.Convo(id, name == null ? "" : name, pic, dm, 0, ""));
                fromNotification = false;
            }
            return;
        }
        if (intent == null || !intent.getBooleanExtra(EXTRA_VOICE, false)) return;
        intent.removeExtra(EXTRA_VOICE);
        Phrase.Group dg = Store.defaultGroup(this);
        if (dg == null) return;
        if (settingsOpen) closeSettings();
        String pic = "";
        for (Feed.Convo c : convos) if (!c.isDm && c.id.equals(dg.id)) pic = c.avatarUrl;
        voiceEntry = true;
        instant = level == 0;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ui.postDelayed(() -> toast("Listening \u00B7 just talk"), 700); // no tap needed: say it so
        }
        tagging.clear();
        refreshTagRow();
        openChat(new Feed.Convo(dg.id, dg.name, pic, false, 0, ""));
        ui.postDelayed(this::startVoice, 120);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PushService.visibleKey = level >= 1 && current != null ? current.key() : null;
        if (PushService.visibleKey != null) PushService.clear(this, PushService.visibleKey);
        if (level >= 1 && current != null && !liveOn) startLive(); // back in a chat: live again
        if (level == 0 && !liveOn) startListLive(); // the chat list: pins and new messages as they happen
        ui.post(ticker);
        ui.removeCallbacks(stampFlip);
        ui.postDelayed(stampFlip, 8000);
        if (settingsOpen) fillSettings(); // back from the default-group picker
        fillButtonNote();
        if (current != null && level >= 1) {
            loadMessages(false);
            ui.removeCallbacks(chatPoll);
            ui.postDelayed(chatPoll, 8000);
        }
    }

    @Override
    protected void onPause() {
        saveChatPosition();
        stopLive();
        PushService.visibleKey = null;
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(stampFlip);
        ui.removeCallbacks(chatPoll);
        cancelVoice();
        if (playing != null) playing.pause(); // leaving the app: the video stops too
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (rec != null) rec.destroy();
        bg.shutdownNow();
        super.onDestroy();
    }

    /** Every 30 s: refresh "3m ago"-style times; every 60 s, fetch the list again. */
    private int ticks;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (convoAdapter != null) convoAdapter.notifyDataSetChanged();
            if (++ticks % 2 == 0 && level == 0) refreshList();
            ui.postDelayed(this, 30000);
        }
    };

    /** Every message time on screen, and when its message was sent (seconds). */
    private final java.util.WeakHashMap<TextView, Long> times = new java.util.WeakHashMap<>();
    /** Showing the actual time ("5:34 PM") instead of how long ago ("13m"). */
    private boolean showStamp;

    private String whenText(long atSec) {
        long now = System.currentTimeMillis();
        return showStamp ? Feed.stamp(atSec, now) : Feed.ago(atSec, now);
    }

    /** Every few seconds message times cross-fade between "13m" and the actual time. */
    private final Runnable stampFlip = new Runnable() {
        @Override
        public void run() {
            ui.postDelayed(this, 8000);
            if (level != 1 || settingsOpen || dragging || pulling) return;
            showStamp = !showStamp;
            for (java.util.Map.Entry<TextView, Long> e : new ArrayList<>(times.entrySet())) {
                TextView t = e.getKey();
                if (t == null || !t.isAttachedToWindow()) continue;
                long at = e.getValue();
                // a soft cross-fade with a slight drift: out and up, then the other in from below
                t.animate().cancel();
                t.animate().alpha(0f).translationY(-px(2)).setDuration(320)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).withEndAction(() -> {
                    t.setText(whenText(at));
                    t.setTranslationY(px(2));
                    t.animate().alpha(0.85f).translationY(0).setDuration(420)
                            .setInterpolator(new DecelerateInterpolator(1.4f)).start();
                }).start();
            }
        }
    };

    private final Runnable chatPoll = new Runnable() {
        @Override
        public void run() {
            if (level >= 1 && current != null && !listening && pendingSend == null && !history) loadMessages(false);
            ui.postDelayed(this, 8000);
        }
    };

    private int px(float dp) { return (int) (dp * d + 0.5f); }

    /** Text size: a watch is read at arm's length, so its text runs larger than the dp math suggests. */
    private float sp(float s) { return watch ? s * 1.18f : s * 1.15f; }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---------------- list of groups and DMs ----------------

    private void buildList() {
        listWrap = new FrameLayout(this);
        list = new ListView(this);
        cardList(list);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER); // the top stays put; pulling further opens settings
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        int side = watch ? (int) (W * 0.09f) : px(12);
        list.setPadding(side, watch ? (int) (H * 0.07f) : px(6), side, watch ? (int) (H * 0.22f) : px(24));
        list.setClipToPadding(false);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, 0, 0, px(3));
        TextView title = label("Chats", sp(watch ? 14 : 20), INK, true);
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        listStatus = label("Pull down for settings", sp(watch ? 9.5f : 11), GREY, false);
        listStatus.setGravity(Gravity.CENTER);
        header.addView(listStatus);
        list.addHeaderView(header, null, false);

        convoAdapter = new ConvoAdapter();
        list.setAdapter(convoAdapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            int i = pos - list.getHeaderViewsCount();
            if (i >= 0 && i < convos.size()) openChat(convos.get(i));
        });
        list.setOnItemLongClickListener((parent, v, pos, id) -> { // hold a chat: mute or unmute it
            int i = pos - list.getHeaderViewsCount();
            if (i < 0 || i >= convos.size()) return false;
            Feed.Convo c = convos.get(i);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (c.request) {
                showChoices(c.name, new String[]{"Accept request", "Decline"},
                        new Runnable[]{() -> answerRequest(c, true), () -> answerRequest(c, false)});
            } else if (c.isDm) {
                showChoices(c.name, new String[]{c.pinned ? "Unpin" : "Pin to top", "Cancel"},
                        new Runnable[]{() -> setPinned(c, !c.pinned), () -> { }});
            } else {
                showChoices(c.name, new String[]{c.pinned ? "Unpin" : "Pin to top", c.muted ? "Unmute" : "Mute", "Cancel"},
                        new Runnable[]{() -> setPinned(c, !c.pinned), () -> setMuted(c, !c.muted), () -> { }});
            }
            return true;
        });
        if (watch) curve(list);
        listWrap.addView(list, match());
        homeLayer.addView(listWrap, match());
    }

    private volatile boolean avatarAsked;

    /**
     * Round watch screens: each row shrinks a little as it nears the top or bottom edge, so its
     * ends (the time, a bubble's edge) stay inside the circle. Scale only, nothing re-lays out.
     */
    /**
     * The same round-screen fisheye for a scrolling page of buttons (Settings): each one
     * shrinks a little near the top or bottom edge so its ends stay inside the circle.
     */
    private void curveScroll(ScrollView sv) {
        sv.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (sv.getChildCount() == 0 || !(sv.getChildAt(0) instanceof ViewGroup)) return true;
            ViewGroup col = (ViewGroup) sv.getChildAt(0);
            float r = W / 2f, edge = px(6);
            for (int i = 0; i < col.getChildCount(); i++) {
                View c = col.getChildAt(i);
                if (c.getWidth() == 0) continue;
                float top = col.getTop() + c.getTop() - sv.getScrollY(), bot = top + c.getHeight();
                float worst = Math.min(Math.abs((top + bot) / 2f - r) + (bot - top) * 0.25f, r * 0.92f);
                float chord = 2f * (float) Math.sqrt(r * r - worst * worst) - 2 * edge;
                float s = Math.max(0.8f, Math.min(1f, chord / c.getWidth()));
                if (Math.abs(c.getScaleX() - s) > 0.004f) {
                    c.setScaleX(s);
                    c.setScaleY(s);
                }
            }
            return true;
        });
    }

    private void curve(android.widget.AbsListView lv) {
        lv.getViewTreeObserver().addOnPreDrawListener(() -> {
            float r = W / 2f, edge = px(6);
            for (int i = 0; i < lv.getChildCount(); i++) {
                View c = lv.getChildAt(i);
                if (c.getWidth() == 0) continue;
                float top = c.getTop(), bot = c.getBottom();
                float mid = (top + bot) / 2f;
                float worst = bot - top > H * 0.3f
                        ? Math.abs(mid - r)                                      // tall (a photo, a long message): its middle
                        : Math.abs(mid - r) + (bot - top) * 0.25f;               // where its text and time sit
                worst = Math.min(worst, r * 0.92f);
                float chord = 2f * (float) Math.sqrt(r * r - worst * worst) - 2 * edge;
                float s = Math.max(0.8f, Math.min(1f, chord / c.getWidth()));
                if (Math.abs(c.getScaleX() - s) > 0.004f) {
                    c.setScaleX(s);
                    c.setScaleY(s);
                }
            }
            return true;
        });
    }

    private void refreshList() {
        if (convos.isEmpty()) listStatus.setText("Loading your chats...");
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                if (Store.avatar(this).isEmpty() && !avatarAsked) { // signed in before pictures existed
                    avatarAsked = true;
                    Store.setAvatar(this, Api.me(token)[2]);
                }
                List<Feed.Convo> fresh = Feed.conversations(token);
                syncReadState(token, fresh);
                try { // your pinned chats first, in your GroupMe order
                    List<String> pins = Feed.pinnedChats(token);
                    java.util.Map<String, Integer> order = new java.util.HashMap<>();
                    for (int k = 0; k < pins.size(); k++) order.put(pins.get(k), k);
                    for (Feed.Convo c : fresh) c.pinned = order.containsKey(conversationIdOf(c)) || order.containsKey(c.id);
                    List<Feed.Convo> pinned = new ArrayList<>(), rest = new ArrayList<>();
                    for (Feed.Convo c : fresh) (c.pinned ? pinned : rest).add(c);
                    pinned.sort((x, y) -> Integer.compare(
                            order.getOrDefault(conversationIdOf(x), order.getOrDefault(x.id, 0)),
                            order.getOrDefault(conversationIdOf(y), order.getOrDefault(y.id, 0))));
                    fresh.clear();
                    fresh.addAll(pinned);
                    fresh.addAll(rest);
                } catch (IOException ignored) {
                    // no pins this time: newest first, as before
                }
                ui.post(() -> {
                    boolean first = convos.isEmpty();
                    java.util.HashMap<String, Long> had = new java.util.HashMap<>();
                    for (Feed.Convo x : convos) had.put(x.key(), x.lastAt);
                    if (!first) {
                        for (Feed.Convo x : fresh) {
                            Long was = had.get(x.key());
                            if (was == null || was != x.lastAt) movedUp.add(x.key()); // new message: it rises to the top
                        }
                    } else if (!fresh.isEmpty()) {
                        fadeList(list);
                    }
                    convos.clear();
                    convos.addAll(fresh);
                    java.util.Set<String> reqs = new java.util.HashSet<>(); // for Accept on their notifications
                    for (Feed.Convo c : fresh) if (c.isDm && c.request) reqs.add(c.id);
                    Store.setRequests(HomeActivity.this, reqs);
                    convoAdapter.notifyDataSetChanged();
                    int pins = 0;
                    for (Feed.Convo c : fresh) if (c.pinned) pins++;
                    if (first && pins > 0) { // opens on your pinned chats, then glides to the newest
                        final int to = list.getHeaderViewsCount() + pins;
                        list.postDelayed(() -> list.smoothScrollToPositionFromTop(to, list.getPaddingTop(), 600), 700);
                    }
                    listStatus.setText(fresh.isEmpty() ? "No chats yet" : "Pull down for settings");
                });
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> listStatus.setText(convos.isEmpty() ? "Couldn't reach GroupMe. Pull down to retry."
                        : "Pull down for settings"));
            }
        });
    }

    /**
     * How many messages in a chat you haven't seen: since where you left off in Say So, or
     * GroupMe's own count (from its apps) if that's lower. 0 when neither knows.
     */
    /** The open chat's pinned messages, newest first. Tap one to jump to it in the chat. */
    private void openPinned() {
        final Feed.Convo c = current;
        if (c == null) return;
        profileFrom = 1;
        profileLayer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = watch ? (int) (W * 0.12f) : px(20);
        col.setPadding(side, watch ? (int) (H * 0.12f) : px(32), side, watch ? (int) (H * 0.2f) : px(32));
        TextView t = label("Pinned", sp(watch ? 15 : 20), INK, true);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        TextView st = label("Loading...", sp(watch ? 10.5f : 12), GREY, false);
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, 0, 0, px(8));
        col.addView(st);
        sv.addView(col);
        profileLayer.addView(sv, match());
        if (watch) curveScroll(sv);
        go(3, true);
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                List<Feed.Msg> pins = Feed.pinnedMessages(token, c);
                ui.post(() -> {
                    st.setText(pins.isEmpty() ? "No pinned messages" : pins.size() + (pins.size() == 1 ? " message" : " messages"));
                    for (Feed.Msg m : pins) {
                        LinearLayout card = new LinearLayout(this);
                        card.setOrientation(LinearLayout.VERTICAL);
                        card.setBackground(pressable());
                        card.setPadding(px(12), px(8), px(12), px(9));
                        TextView who = label(m.sender + "  \u00B7  " + Feed.stamp(m.at, System.currentTimeMillis()), sp(watch ? 10 : 11.5f), BLUE, true);
                        who.setSingleLine(true);
                        who.setEllipsize(TextUtils.TruncateAt.END);
                        card.addView(who);
                        TextView body = label(m.text.isEmpty() ? (m.media.isEmpty() ? "Message" : "Photo") : m.text, sp(watch ? 12.5f : 14.5f), INK, false);
                        body.setMaxLines(4);
                        body.setEllipsize(TextUtils.TruncateAt.END);
                        card.addView(body);
                        final String id = m.id;
                        card.setOnClickListener(v -> jumpTo(id));
                        Press.attach(card);
                        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        cl.topMargin = px(5);
                        col.addView(card, cl);
                    }
                });
            } catch (IOException e) {
                ui.post(() -> st.setText("Couldn't reach GroupMe"));
            }
        });
    }

    /** A DM request: who wants to message you, with Accept and Decline. */
    private void showRequestBar(Feed.Convo c) {
        showPanel("Message request", c.name + " wants to message you.",
                new Object[]{"Accept", (Runnable) () -> answerRequest(c, true), "Decline", (Runnable) () -> answerRequest(c, false)});
    }

    private void hideRequestBar() {
        hidePanel();
    }

    /** The group's details: its picture, name, description, when it started, members and its link. */
    private void openGroupInfo() {
        final Feed.Convo c = current;
        if (c == null || c.isDm) return;
        profileFrom = 2;
        profileLayer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = watch ? (int) (W * 0.15f) : px(28);
        col.setPadding(side, watch ? (int) (H * 0.1f) : px(40), side, watch ? (int) (H * 0.2f) : px(32));
        Avatar pic = new Avatar(watch ? 70 : 110);
        pic.setLive(c.name, c.avatarUrl, false);
        if (!c.avatarUrl.isEmpty()) {
            pic.view.setOnClickListener(v -> openPicture(c.name, c.avatarUrl));
            Press.attach(pic.view);
        }
        col.addView(pic.view);
        TextView name = label(c.name, sp(watch ? 15 : 20), INK, true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, px(8), 0, 0);
        col.addView(name);
        TextView facts = label("", sp(watch ? 11 : 13), GREY, false);
        facts.setGravity(Gravity.CENTER);
        facts.setPadding(0, px(4), 0, 0);
        col.addView(facts);
        TextView desc = label("", sp(watch ? 12 : 14.5f), INK, false);
        desc.setGravity(watch ? Gravity.CENTER : Gravity.START);
        desc.setPadding(0, px(10), 0, 0);
        desc.setVisibility(View.GONE);
        col.addView(desc);
        TextView link = label("", sp(watch ? 10.5f : 12.5f), BLUE, true);
        link.setGravity(Gravity.CENTER);
        link.setPadding(0, px(10), 0, 0);
        link.setVisibility(View.GONE);
        col.addView(link);
        addProfileButton(col, c.muted ? "Unmute" : "Mute", false, () -> {
            setMuted(c, !c.muted);
            openGroupInfo();
        });
        sv.addView(col);
        profileLayer.addView(sv, match());
        if (watch) curveScroll(sv);
        go(3, true);
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                Feed.Convo g = Feed.groupInfo(token, c.id);
                ui.post(() -> {
                    if (g == null) return;
                    String since = g.createdAt > 0 ? "Since " + new java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                            .format(new java.util.Date(g.createdAt * 1000)) : "";
                    String count = g.memberCount > 0 ? g.memberCount + (g.memberCount == 1 ? " member" : " members") : "";
                    facts.setText(count + (count.isEmpty() || since.isEmpty() ? "" : "  \u00B7  ") + since);
                    if (!g.description.isEmpty()) {
                        desc.setText(g.description);
                        desc.setVisibility(View.VISIBLE);
                    }
                    if (!g.shareUrl.isEmpty()) {
                        link.setText(g.shareUrl.replace("https://", ""));
                        link.setVisibility(View.VISIBLE);
                    }
                });
            } catch (IOException ignored) {
                // just the name and picture this time
            }
        });
    }

    /**
     * Pins a chat to the top of your list, or unpins it, in GroupMe itself: its phone and web apps
     * show the same pins. A new pin goes last among the pinned, as GroupMe's apps do.
     */
    private void setPinned(Feed.Convo c, boolean on) {
        c.pinned = on;
        sortPinned();
        final String token = Store.token(this);
        final String id = conversationIdOf(c);
        bg.execute(() -> {
            try {
                List<String> pins = Feed.pinnedChats(token); // the current list, pins from other apps included
                pins.remove(id);
                pins.remove(c.id);
                if (on) pins.add(id);
                Feed.setPinnedChats(token, pins);
            } catch (IOException e) {
                ui.post(() -> {
                    c.pinned = !on;
                    sortPinned();
                    listStatus.setText("Couldn't reach GroupMe");
                });
            }
        });
    }

    /** Pinned chats first (in the order they are), then the rest newest first. */
    private void sortPinned() {
        List<Feed.Convo> pinned = new ArrayList<>(), rest = new ArrayList<>();
        for (Feed.Convo x : convos) (x.pinned ? pinned : rest).add(x);
        rest.sort((x, y) -> Long.compare(y.lastAt, x.lastAt));
        convos.clear();
        convos.addAll(pinned);
        convos.addAll(rest);
        convoAdapter.notifyDataSetChanged();
    }

    /** Mutes or unmutes a group's notifications in GroupMe (its apps follow it too). */
    private void setMuted(Feed.Convo c, boolean on) {
        c.muted = on;
        convoAdapter.notifyDataSetChanged();
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                Feed.mute(token, c.id, on);
            } catch (IOException e) {
                ui.post(() -> {
                    c.muted = !on;
                    convoAdapter.notifyDataSetChanged();
                    listStatus.setText("Couldn't reach GroupMe");
                });
            }
        });
    }

    /** Accepts or declines a DM request. */
    private void answerRequest(Feed.Convo c, boolean accept) {
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                Feed.answerRequest(token, c.id, accept);
                ui.post(() -> {
                    c.request = false;
                    if (!accept) convos.remove(c);
                    convoAdapter.notifyDataSetChanged();
                    if (accept && current == c) { hideRequestBar(); loadMessages(false); }
                    if (!accept && current == c) go(0, false);
                    refreshList();
                });
            } catch (IOException e) {
                ui.post(() -> listStatus.setText("Couldn't reach GroupMe"));
            }
        });
    }

    /** Unread, but how many isn't known: shown as a red dot without a number. */
    private static final int UNKNOWN = -2;

    private int unreadOf(Feed.Convo c) {
        if (current != null && level >= 1 && current.key().equals(c.key())) return 0; // reading it now
        return c.unread == UNKNOWN ? UNKNOWN : Math.max(0, c.unread);
    }

    /** Where you've read to in each chat (GroupMe's own record, shared with its apps), by chat key. */
    private final java.util.Map<String, String> readTo = new java.util.concurrent.ConcurrentHashMap<>();
    /** Counts already worked out, so an unchanged chat isn't asked again: key to "readTo|newest=count". */
    private final java.util.Map<String, String> countedAt = new java.util.concurrent.ConcurrentHashMap<>();

    private String conversationIdOf(Feed.Convo c) {
        return c.isDm ? Feed.dmConversationId(Store.myId(this), c.id) : c.id;
    }

    /**
     * Fills in each chat's read position and unread count from GroupMe, the same record its
     * phone and web apps use, so the counts match wherever you read. (Background thread.)
     */
    private void syncReadState(String token, List<Feed.Convo> list) {
        java.util.Map<String, String> receipts;
        try {
            receipts = Feed.readReceipts(token);
        } catch (IOException e) {
            return; // no counts this time; the chats still show
        }
        int asked = 0;
        for (Feed.Convo c : list) {
            String at = receipts.get(conversationIdOf(c));
            if (at == null || at.isEmpty()) at = c.lastReadId;
            String mine = readTo.get(c.key()); // what Say So just told GroupMe (it can take a moment to show)
            if (mine != null && Feed.newer(mine, at)) at = mine;
            if (at == null || at.isEmpty()) { c.unread = 0; continue; } // GroupMe has no record: nothing to count from
            c.lastReadId = at;
            readTo.put(c.key(), at);
            if (!Feed.newer(c.lastMsgId, at)) { c.unread = 0; continue; }
            String stamp = at + "|" + c.lastMsgId;
            String done = countedAt.get(c.key());
            if (done != null && done.startsWith(stamp + "=")) {
                c.unread = Integer.parseInt(done.substring(stamp.length() + 1));
                continue;
            }
            if (asked >= 15) { c.unread = UNKNOWN; continue; } // counted next time; for now a dot says there's news
            asked++;
            try {
                c.unread = Math.max(1, Feed.countAfter(token, c, at, 100));
                countedAt.put(c.key(), stamp + "=" + c.unread);
            } catch (IOException e) {
                c.unread = UNKNOWN; // there's news, number unknown: a dot
            }
        }
    }

    /** The chat being opened came from tapping a notification: open at the newest, not where you were. */
    private boolean fromNotification;

    /** In the open chat: the "New messages" line goes above the first message newer than this. */
    private String newSince = "";
    /** Open the chat at that line (the first new message), not at the newest. Once per opening. */
    private boolean jumpToNew;
    /** Open the chat where you were last reading it. Once per opening (not from a notification). */
    private boolean restoreView;

    private final Runnable savePosition = this::saveChatPosition;

    /**
     * Remembers where you are in the open chat: the message at the top of the screen (to come
     * back to it) and the last one you've had on screen (everything up to it counts as seen).
     */
    private void saveChatPosition() {
        Feed.Convo c = current;
        if (c == null || level < 1 || history || msgs.isEmpty() || chatList.getChildCount() == 0) return;
        int first = chatList.getFirstVisiblePosition();
        if (first < msgs.size()) {
            Store.setView(this, c.key(), msgs.get(first).id, chatList.getChildAt(0).getTop());
        }
        int last = Math.min(chatList.getLastVisiblePosition(), msgs.size() - 1);
        if (last < 0) return;
        String lastId = msgs.get(last).id;
        String was = readTo.get(c.key());
        if (was == null || Feed.newer(lastId, was)) { // read further: tell GroupMe, so its apps agree
            readTo.put(c.key(), lastId);
            final String token = Store.token(this), conv = conversationIdOf(c);
            bg.execute(() -> {
                try {
                    Feed.markRead(token, conv, lastId);
                } catch (IOException ignored) {
                    // offline: it's marked next time you look
                }
            });
        }
    }

    /** Chats with something new since the last refresh: they glide into their new place. */
    private final java.util.HashSet<String> movedUp = new java.util.HashSet<>();

    private final class ConvoAdapter extends BaseAdapter {
        @Override public int getCount() { return convos.size(); }

        @Override public Object getItem(int i) { return convos.get(i); }

        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Row r;
            if (v == null) {
                r = new Row();
                v = r.build();
            } else {
                r = (Row) v.getTag();
            }
            Feed.Convo c = convos.get(i);
            splitName(r, c.name);
            // groups: the sender's picture, then just what they said; DMs: the message as is
            // DMs: only when the last message was yours (theirs is already the chat's picture)
            boolean mineLast = c.isDm && c.previewSenderId.equals(Store.myId(HomeActivity.this));
            boolean pic = photosOn() && (mineLast || !c.isDm && !c.previewPic.isEmpty());
            r.who.view.setVisibility(pic ? View.VISIBLE : View.GONE);
            if (pic) {
                if (mineLast) r.who.set(Store.name(HomeActivity.this), Store.avatar(HomeActivity.this), true);
                else r.who.set(c.previewWho, c.previewPic, true);
                r.preview.setText(c.previewText.isEmpty() ? " " : c.previewText);
            } else {
                r.preview.setText(c.preview.isEmpty() ? (c.isDm ? "Direct message" : " ") : c.preview);
            }
            // a pin for pinned chats, a muted bell for muted ones (drawn, not emoji), "Request" for a DM request
            r.time.setText(c.request ? "Request" : Feed.ago(c.lastAt, System.currentTimeMillis()));
            int is = px(watch ? 13 : 15);
            Icons mark = c.request ? null : c.muted ? new Icons(Icons.MUTED, GREY, 0) : c.pinned ? new Icons(Icons.PIN, BLUE, 0) : null;
            if (mark != null) mark.setBounds(0, 0, is, is);
            r.time.setCompoundDrawables(mark, null, null, null);
            r.time.setCompoundDrawablePadding(px(3));
            r.avatar.set(c.name, c.avatarUrl, c.isDm);
            if (!c.avatarUrl.isEmpty()) { // the picture itself: full screen; the rest of the card opens the chat
                r.avatar.view.setOnClickListener(x -> openPicture(c.name, c.avatarUrl));
            } else {
                r.avatar.view.setOnClickListener(null);
                r.avatar.view.setClickable(false);
            }
            int n = unreadOf(c);
            boolean dot = n == UNKNOWN; // new messages, number not known: a plain red dot
            r.badge.setVisibility(n > 0 || dot ? View.VISIBLE : View.GONE);
            r.badge.setText(dot ? "" : n > 99 ? "99+" : String.valueOf(n));
            r.badge.setMinWidth(dot ? px(watch ? 12 : 13) : px(watch ? 18 : 20));
            ViewGroup.LayoutParams blp = r.badge.getLayoutParams();
            blp.height = dot ? px(watch ? 12 : 13) : px(watch ? 18 : 20);
            r.badge.setLayoutParams(blp);
            r.badge.setContentDescription(dot ? "New messages" : n + " unread");
            r.preview.setTextColor(n > 0 || dot ? INK : GREY); // unread: the preview reads darker
            if (movedUp.remove(c.key())) glideIn(v, 0);
            return v;
        }
    }

    /**
     * Fits a chat name in two lines: as many whole words as fit beside the time on the first,
     * the rest on the second, which runs the full width (cut with "..." if it's still too long).
     */
    private void splitName(Row r, String full) {
        r.name.setText(full);
        r.name2.setVisibility(View.GONE);
        r.name.setTag(R.id.img_key, full);
        r.name.post(() -> {
            if (!full.equals(r.name.getTag(R.id.img_key))) return; // the row now shows another chat
            int room = r.name.getWidth() - r.name.getPaddingLeft() - r.name.getPaddingRight();
            if (room <= 0) return;
            android.text.TextPaint paint = r.name.getPaint();
            if (paint.measureText(full) <= room) return; // fits on one line
            int n = paint.breakText(full, true, room, null);
            int cut = full.lastIndexOf(' ', n);       // break between words when there's a space
            if (cut <= 0) cut = n;
            String first = full.substring(0, cut).trim(), rest = full.substring(cut).trim();
            r.name.setText(first);
            r.name2.setText(rest);
            r.name2.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private final class Row {
        TextView name, name2, preview, time, badge;
        Avatar avatar, who;

        View build() {
            // Two parts: the picture beside the name and time, then the last message on its own
            // line underneath, the full width of the card.
            LinearLayout card = new LinearLayout(HomeActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = new LinearLayout(HomeActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setPadding(px(watch ? 6 : 10), px(watch ? 6 : 10), px(watch ? 6 : 10), px(watch ? 7 : 10));
            card.setBackground(pressable());
            liftRow(card);
            avatar = new Avatar(watch ? 34 : 48);
            // the picture, with the unread count over its top-left corner
            FrameLayout pic = new FrameLayout(HomeActivity.this);
            pic.setClipChildren(false);
            FrameLayout.LayoutParams avl = new FrameLayout.LayoutParams(avatar.size, avatar.size);
            avl.leftMargin = avl.topMargin = px(4);
            pic.addView(avatar.view, avl);
            badge = label("", sp(watch ? 9.5f : 11), Color.WHITE, true);
            badge.setGravity(Gravity.CENTER);
            badge.setIncludeFontPadding(false);
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(0xFFFF3B30); // red: new messages stand out
            bb.setCornerRadius(px(10));
            bb.setStroke(px(1.75f), Palette.ROW); // a ring in the card's color, so it stands off the picture
            badge.setBackground(bb);
            badge.setMinWidth(px(watch ? 18 : 20));
            badge.setPadding(px(5), 0, px(5), 0);
            badge.setVisibility(View.GONE);
            badge.setElevation(px(4));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(watch ? 18 : 20));
            bl.gravity = Gravity.TOP | Gravity.START;
            pic.addView(badge, bl);
            row.addView(pic, new LinearLayout.LayoutParams(avatar.size + px(4), avatar.size + px(4)));
            LinearLayout text = new LinearLayout(HomeActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(px(watch ? 8 : 12), 0, px(4), 0);
            // The name: its first line beside the time, its second line the full width (under the time too)
            name = label("", sp(watch ? 13.5f : 16), INK, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name2 = label("", sp(watch ? 13.5f : 16), INK, true);
            name2.setSingleLine(true);
            name2.setEllipsize(TextUtils.TruncateAt.END);
            name2.setVisibility(View.GONE);
            preview = label("", sp(watch ? 11.5f : 13.5f), GREY, false);
            preview.setSingleLine(true);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            // who sent the last message: their small picture before it, instead of their name
            who = new Avatar(watch ? 15 : 18);
            LinearLayout bottom = new LinearLayout(HomeActivity.this);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(who.size, who.size);
            wl.rightMargin = px(4);
            bottom.addView(who.view, wl);
            bottom.addView(preview, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // top line: the name, and the time at the top right; the preview gets the full width below
            LinearLayout top = new LinearLayout(HomeActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            time = label("", sp(watch ? 10.5f : 12), BLUE, true);
            time.setPadding(px(6), 0, 0, 0);
            top.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            top.setGravity(Gravity.TOP);
            text.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams n2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            n2.topMargin = -px(2);
            text.addView(name2, n2);
            LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl2.topMargin = px(4);
            bl2.leftMargin = px(watch ? 4 : 6);
            card.addView(bottom, bl2); // the last message: its own line, under the picture too
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.setTag(this);
            Press.attach(card);
            return card;
        }
    }

    /** A round picture with the person's or group's initials underneath until it loads. */
    private final class Avatar {
        final FrameLayout view;
        final TextView initials;
        final ImageView img;
        final int size;

        Avatar(int dp) {
            size = px(dp);
            view = new FrameLayout(HomeActivity.this);
            view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            initials = new TextView(HomeActivity.this);
            initials.setGravity(Gravity.CENTER);
            initials.setTextColor(Color.WHITE);
            initials.setTypeface(BOLD);
            initials.setTextSize(dp * 0.36f);
            GradientDrawable o = new GradientDrawable();
            o.setShape(GradientDrawable.OVAL);
            o.setColor(BLUE);
            initials.setBackground(o);
            img = new ImageView(HomeActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.addView(initials, new FrameLayout.LayoutParams(size, size));
            view.addView(img, new FrameLayout.LayoutParams(size, size));
            view.setOutlineProvider(new android.view.ViewOutlineProvider() { // round, with a soft shadow
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            if (!(Palette.dark && watch)) view.setElevation(px(1.5f));
        }

        void set(String name, String url, boolean person) {
            initials.setText(initialsOf(name));
            ((GradientDrawable) initials.getBackground()).setColor(person ? 0xFF5B6CFF : BLUE);
            img.setImageDrawable(null);
            if (photosOn()) Images.load(img, Feed.sized(url, "avatar"), size, true);
        }

        /** Like set(), but an animated GIF picture plays (used where you look at one person). */
        void setLive(String name, String url, boolean person) {
            if (url == null || !url.toLowerCase(java.util.Locale.US).contains(".gif") || !photosOn()) {
                set(name, url, person);
                return;
            }
            initials.setText(initialsOf(name));
            ((GradientDrawable) initials.getBackground()).setColor(person ? 0xFF5B6CFF : BLUE);
            img.setImageDrawable(null);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setOutlineProvider(new android.view.ViewOutlineProvider() { // round, while it plays
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            img.setClipToOutline(true);
            Images.load(img, url, size * 2, false, true); // the original: the small version may be a still
        }
    }

    private static String initialsOf(String name) {
        if (name == null) return "";
        String[] parts = name.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            int cp = p.codePointAt(0);
            if (Character.isLetterOrDigit(cp)) b.appendCodePoint(Character.toUpperCase(cp));
            if (b.length() >= 2) break;
        }
        return b.toString();
    }

    private boolean photosOn() { return Store.photosOn(this); }

    // ---------------- settings (slides down from above the list) ----------------

    private void buildSettings() {
        settings = new ScrollView(this);
        settings.setBackgroundColor(Palette.SURFACE);
        settings.setVerticalScrollBarEnabled(false);
        settings.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (watch) curveScroll(settings); // buttons ease smaller near the round edge, like the chats
        fillSettings();
    }

    private void fillSettings() {
        settings.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = watch ? (int) (W * 0.14f) : px(24);
        col.setPadding(side, watch ? (int) (H * 0.14f) : px(32), side, watch ? (int) (H * 0.2f) : px(32));

        Avatar me = new Avatar(watch ? 46 : 64);
        col.addView(me.view);
        me.set(Store.name(this), Store.avatar(this), true);
        if (!Store.avatar(this).isEmpty()) { // tap your picture: full screen, your name at the bottom
            me.view.setOnClickListener(v -> {
                closeSettings();
                openPicture(Store.name(this), Store.avatar(this));
            });
            Press.attach(me.view);
        }
        TextView who = label(Store.name(this).isEmpty() ? "Signed in" : Store.name(this), sp(watch ? 15 : 19), INK, true);
        who.setGravity(Gravity.CENTER);
        who.setPadding(0, px(6), 0, px(10));
        col.addView(who);

        Phrase.Group dflt = Store.defaultGroup(this);
        addSettingButton(col, "Default group: " + (dflt == null ? "none" : dflt.name), true, () -> openSetup("default"));
        if (!watch) {
            addSettingButton(col, "Voice shortcuts", false, () -> openSetup("shortcuts"));
            addSettingButton(col, "Sign in a watch", false, () -> openSetup("watch"));
        }
        addSettingButton(col, "Photos: " + (photosOn() ? "on" : "off"), false, () -> {
            Store.setPhotosOn(this, !photosOn());
            fillSettings();
            convoAdapter.notifyDataSetChanged();
        });
        if (watch) addSettingButton(col, "Watch button", false, this::openButtonHelp);
        addSettingButton(col, "Notifications: " + (Store.pushOn(this) ? "on" : "off"), false, () -> {
            boolean on = !Store.pushOn(this);
            if (on && android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return; // turned on once Android's question is answered yes
            }
            Store.setPushOn(this, on);
            PushService.sync(this);
            fillSettings();
        });
        // One switch for every sound: notifications (they still buzz without it) and the chat tones
        addSettingButton(col, "Sounds: " + (Store.pushSound(this) ? "on" : "off"), false, () -> {
            Store.setPushSound(this, !Store.pushSound(this));
            fillSettings();
            if (Store.pushSound(this)) chime(true); // a sample of how it sounds
        });
        addSettingButton(col, "Theme: " + Palette.label(Store.theme(this)), false, () -> {
            Store.setTheme(this, Palette.next(Store.theme(this)));
            recreate(); // every color rebuilt in the new theme
        });
        addSettingButton(col, "Refresh", false, () -> {
            closeSettings();
            refreshList();
        });
        final TextView[] out = new TextView[1];
        final boolean[] armed = new boolean[1];
        out[0] = addSettingButton(col, "Sign out", false, () -> {
            if (!armed[0]) { // a stray tap on a small screen shouldn't sign you out
                armed[0] = true;
                out[0].setText(watch ? "Tap again" : "Tap again to sign out");
                ui.postDelayed(() -> {
                    armed[0] = false;
                    out[0].setText("Sign out");
                }, 3000);
                return;
            }
            MainActivity.unsent = null;
            Store.clear(this);
            PushService.sync(this); // signed out: stop listening
            startActivity(new Intent(this, SetupActivity.class));
            finish();
        });

        String ver = "Say So " + BuildConfig.VERSION_NAME;
        TextView meta = label(ver + "\nNot affiliated with GroupMe or Microsoft\nSwipe up to close", sp(10.5f), GREY, false);
        meta.setGravity(Gravity.CENTER);
        meta.setPadding(0, px(10), 0, 0);
        col.addView(meta);
        settings.addView(col);
    }

    /**
     * A settings row, as a card like the chat list: the setting on the left and, for "Name: value"
     * settings, its current value in blue on the right. (Raised blue buttons are for actions.)
     * Returns the left label, so a row can change its own words ("Tap again").
     */
    private TextView addSettingButton(LinearLayout col, String text, boolean primary, Runnable tap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(pressable());
        liftRow(row);
        row.setPadding(px(watch ? 14 : 16), 0, px(watch ? 14 : 16), 0);
        int split = text.indexOf(": ");
        String name = split > 0 ? text.substring(0, split) : text, value = split > 0 ? text.substring(split + 2) : "";
        TextView left = label(name, sp(watch ? 13 : 15), text.equals("Sign out") ? 0xFFFF3B30 : INK, true);
        left.setSingleLine(true);
        left.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!value.isEmpty()) {
            TextView right = label(value.substring(0, 1).toUpperCase(java.util.Locale.US) + value.substring(1),
                    sp(watch ? 12.5f : 14.5f), BLUE, true);
            right.setSingleLine(true);
            right.setEllipsize(TextUtils.TruncateAt.END);
            right.setMaxWidth((int) (W * (watch ? 0.3f : 0.4f)));
            right.setPadding(px(8), 0, 0, 0);
            row.addView(right);
        }
        row.setContentDescription(text);
        row.setOnClickListener(v -> tap.run());
        Press.attach(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(watch ? 42 : 50));
        lp.topMargin = px(5);
        col.addView(row, lp);
        return left;
    }

    private void openSetup(String screen) {
        startActivity(new Intent(this, SetupActivity.class)
                .putExtra(SetupActivity.EXTRA_OPEN, screen)
                .putExtra(SetupActivity.EXTRA_FROM_HOME, true));
    }

    /** The page that settings push down: the list at home, otherwise whichever page is showing. */
    private View under;

    private View pullTarget() {
        if (under == null) under = level == 0 ? listWrap : layer(level);
        return under;
    }

    private void setPull(float p) {
        p = Math.max(0f, Math.min(1f, p));
        settings.bringToFront(); // a sideways move may have raised a page over it
        settings.setTranslationY(-H * (1 - p));
        View u = pullTarget();
        u.setTranslationY(H * 0.3f * p);
        u.setAlpha(1 - 0.7f * p);
    }

    private void openSettings() {
        settingsOpen = true;
        fillSettings();
        settings.bringToFront();
        settings.scrollTo(0, 0);
        settings.animate().translationY(0).setDuration(300).setInterpolator(new DecelerateInterpolator()).withLayer().start();
        pullTarget().animate().translationY(H * 0.3f).alpha(0.3f).setDuration(300).withLayer().start();
    }

    private void closeSettings() {
        settingsOpen = false;
        settings.animate().translationY(-H).setDuration(280).setInterpolator(new DecelerateInterpolator()).withLayer().start();
        View u = under != null ? under : listWrap;
        under = null;
        u.animate().translationY(0).alpha(1f).setDuration(280).withLayer().start();
    }

    // ---------------- chat ----------------

    private void buildChat() {
        chatLayer = new FrameLayout(this);
        chatLayer.setBackgroundColor(BG);

        chatList = new ListView(this);
        chatList.setDivider(null);
        chatList.setVerticalScrollBarEnabled(false);
        chatList.setStackFromBottom(true);
        chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        chatList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        int side = watch ? (int) (W * 0.125f) : px(10);
        chatList.setPadding(side, watch ? (int) (H * 0.15f) : px(64), side, watch ? (int) (H * 0.24f) : px(96));
        chatList.setClipToPadding(false);
        msgAdapter = new MsgAdapter();
        chatList.setAdapter(msgAdapter);
        // where you are in the chat is remembered each time scrolling stops
        chatList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {
                if (state == SCROLL_STATE_IDLE) saveChatPosition();
            }
            @Override public void onScroll(android.widget.AbsListView v, int first, int count, int total) {
                updateLatestPill(); // the way down appears whenever you're above the newest
            }
        });
        if (watch) curve(chatList);
        chatLayer.addView(chatList, match());

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(watch ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER);
        head.setPadding(px(10), watch ? (int) (H * 0.04f) : px(6), px(10), px(watch ? 6 : 8)); // room for the line under it
        head.setBackgroundColor(BG);
        head.setElevation(px(2));
        chatAvatar = new ImageView(this);
        chatAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        chatAvatar.setContentDescription("Chat picture");
        chatAvatar.setOnClickListener(v -> { // the chat's picture, full screen, its name at the bottom
            if (current != null) openPicture(current.name, current.avatarUrl);
        });
        int a = px(watch ? 26 : 36);
        if (!watch) head.addView(chatAvatar, new LinearLayout.LayoutParams(a, a));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(watch ? Gravity.CENTER : Gravity.START);
        titles.setPadding(watch ? 0 : px(10), 0, 0, 0);
        chatTitle = label("", sp(watch ? 12 : 16), INK, true);
        chatTitle.setSingleLine(true);
        chatTitle.setEllipsize(TextUtils.TruncateAt.END);
        chatTitle.setMaxWidth((int) (W * (watch ? 0.6f : 0.7f)));
        chatStatus = label("", sp(10), GREY, false);
        if (watch) { // a round screen: everything in the header sits on the centre line
            chatTitle.setGravity(Gravity.CENTER);
            chatStatus.setGravity(Gravity.CENTER);
            // Long names get a second line rather than running off the curved top edge.
            chatTitle.setSingleLine(false);
            chatTitle.setMaxLines(2);
            chatTitle.setEllipsize(TextUtils.TruncateAt.END);
            chatTitle.setLineSpacing(0, 0.92f);
            chatTitle.setMaxWidth((int) (W * 0.5f)); // room for the pin; long names lose their end, never their start
        }
        // the chat's name, with a pin at its right edge for the chat's pinned messages
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER);
        nameRow.addView(chatTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView pin = new ImageView(this);
        chatPinIcon = pin;
        pin.setVisibility(View.GONE); // only for a chat that has pinned messages (see prepareChat)
        pin.setImageDrawable(new Icons(Icons.PIN, BLUE, 0));
        pin.setContentDescription("Pinned messages");
        int pp = px(watch ? 18 : 22);
        pin.setPadding(px(2), px(2), px(2), px(2));
        pin.setOnClickListener(v -> openPinned());
        Press.attach(pin);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(pp, pp);
        pl.leftMargin = px(4);
        nameRow.addView(pin, pl);
        titles.addView(nameRow, new LinearLayout.LayoutParams(watch ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Who this message will tag: their faces side by side in one row, no names.
        tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(watch ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        tagRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trl.topMargin = px(3);
        titles.addView(tagRow, trl);
        titles.addView(chatStatus);
        if (watch) chatStatus.setVisibility(View.GONE);
        if (watch) head.addView(titles, new LinearLayout.LayoutParams((int) (W * 0.58f), ViewGroup.LayoutParams.WRAP_CONTENT));
        else head.addView(titles);
        head.setElevation(0);
        head.setBackground(new LayerDrawableCompat(BG, new TaperLine(Palette.dark ? 0x5500AFF0 : 0x4000AFF0, px(2.2f))));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.TOP;
        chatLayer.addView(head, hl);
        // The list starts just under the header, whatever its height (one line or two).
        head.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or, ob) -> {
            int top = v.getHeight() + px(4);
            if (chatList.getPaddingTop() != top) {
                chatList.setPadding(chatList.getPaddingLeft(), top, chatList.getPaddingRight(), chatList.getPaddingBottom());
            }
        });

        if (watch) {
            // The picture rides at the left edge, halfway down: the one strip of a round screen
            // that messages never use, so the top stays free for conversation.
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(Palette.AVATAR);
            chatAvatar.setBackground(ring);
            chatAvatar.setElevation(px(2));
            int pa = px(22);
            FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(pa, pa);
            al.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            al.leftMargin = px(3);
            chatLayer.addView(chatAvatar, al);
        }

        // the mic: speak, and it goes to this conversation
        ImageView mic = new ImageView(this);
        mic.setImageDrawable(new Icons(Icons.MIC, Color.WHITE, 0));
        mic.setBackground(domed());
        mic.getBackground().mutate().setAlpha(218); // a touch see-through: the newest messages show faintly behind
        // raised above the chat: a soft round shadow under it
        mic.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
                o.setAlpha(0.9f);
            }
        });
        mic.setElevation(px(8));
        mic.setOutlineAmbientShadowColor(0xFF000000);
        mic.setOutlineSpotShadowColor(Palette.dark ? 0xFF000000 : 0xFF0077B6);

        mic.setPadding(px(10), px(10), px(10), px(10));
        mic.setContentDescription("Speak a message");
        mic.setOnClickListener(v -> {
            if (listening && rec != null) { // tap again: that's everything, send it
                userDone = true;
                rec.stopListening();
            }
            else startVoice();
        });
        Press.attach(mic);
        micButton = mic;
        int m = px(watch ? 46 : 60);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(m, m);
        ml.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        ml.bottomMargin = watch ? (int) (H * 0.02f) : px(20); // the circle is still wide enough down here
        chatLayer.addView(mic, ml);
        buildDraft(m, ml.bottomMargin);

        voicePanel = new LinearLayout(this);
        voicePanel.setOrientation(LinearLayout.VERTICAL);
        voicePanel.setGravity(Gravity.CENTER);
        GradientDrawable card = new GradientDrawable();
        card.setColor(glass()); // translucent: the chat stays faintly visible behind it
        card.setCornerRadius(px(20));
        card.setStroke(px(1), 0x2200AFF0);
        voicePanel.setElevation(px(8));
        voicePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams vl;
        if (watch) {
            // the whole round screen, dimmed: the chat shows faintly through, the words stay clear
            voicePanel.setBackground(new android.graphics.drawable.ColorDrawable(scrim()));
            voicePanel.setPadding((int) (W * 0.15f), (int) (H * 0.1f), (int) (W * 0.15f), (int) (H * 0.1f));
            vl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            vl.gravity = Gravity.CENTER;
        } else {
            voicePanel.setBackground(card);
            voicePanel.setPadding(px(14), px(12), px(14), px(12));
            vl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            vl.bottomMargin = px(16);
            vl.leftMargin = vl.rightMargin = px(12);
        }
        chatLayer.addView(voicePanel, vl);

        root.addView(chatLayer, match());
    }

    /**
     * What you're saying, typed into a blue bubble at the bottom of the chat as you speak, like
     * a message being written. It stays small (three lines at most) so the chat stays readable.
     */
    private void buildDraft(int micSize, int micBottom) {
        listBottom = chatList.getPaddingBottom();
        draft = new LinearLayout(this);
        draft.setOrientation(LinearLayout.VERTICAL);
        draft.setGravity(Gravity.END);
        LinearLayout who = new LinearLayout(this); // like the line above any message you've sent
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView dnow = label("now", sp(watch ? 10 : 11.5f), BLUE, true);
        dnow.setAlpha(0.85f);
        who.addView(dnow);
        String myName = Store.name(this);
        TextView you = label(myName.isEmpty() ? "You" : myName, sp(watch ? 10 : 11.5f), BLUE, true);
        you.setPadding(px(5), 0, px(5), 0);
        you.setSingleLine(true);
        you.setEllipsize(TextUtils.TruncateAt.END);
        you.setMaxWidth((int) (W * (watch ? 0.36f : 0.45f)));
        who.addView(you);
        ImageView me = new ImageView(this);
        int as = px(watch ? 16 : 22);
        GradientDrawable o = new GradientDrawable();
        o.setShape(GradientDrawable.OVAL);
        o.setColor(Palette.AVATAR);
        me.setBackground(o);
        who.addView(me, new LinearLayout.LayoutParams(as, as));
        if (photosOn()) Images.load(me, Feed.sized(Store.avatar(this), "avatar"), as, true);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.bottomMargin = px(2);
        draft.addView(who, wl);
        draftQuote = quoteLabel("", true);
        draftQuote.setVisibility(View.GONE);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ql.bottomMargin = px(3);
        draft.addView(draftQuote, ql);
        draftText = label("", sp(watch ? 13 : 15), Color.WHITE, false);
        draftText.setBackground(bubbleBg(true));
        draftText.setPadding(px(12), px(7), px(12), px(8));
        lift(draftText, true);
        draftText.setMinWidth(px(44)); // grows a line at a time; the chat slides up to make room
        draft.addView(draftText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        draftBar = new View(this);
        draftBar.setBackgroundColor(BLUE);
        draftBar.setPivotX(0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(2));
        bl.topMargin = px(4);
        draft.addView(draftBar, bl);
        draftNote = label("", sp(watch ? 10 : 11.5f), GREY, false);
        draftNote.setSingleLine(true);
        draftNote.setEllipsize(TextUtils.TruncateAt.END);
        draftNote.setGravity(Gravity.END);
        draftNote.setPadding(0, px(2), px(4), px(4)); // room under the line, so the "g" in Listening isn't cut
        draft.addView(draftNote);
        draft.setVisibility(View.GONE);
        draft.setPadding(0, px(4), 0, px(2));
        // The draft is the chat's last row, never laid over it: as it grows, every message above
        // slides up with it, and nothing is ever covered.
        // (wrapped: a hidden footer row still takes up its full height in a ListView)
        LinearLayout draftRow = new LinearLayout(this);
        draftRow.setOrientation(LinearLayout.VERTICAL);
        // "Sam is typing…": who's writing right now, just above where your own words go
        typingLabel = label("", sp(watch ? 10.5f : 12), GREY, false);
        typingLabel.setTypeface(Typeface.create(REGULAR, Typeface.ITALIC));
        typingLabel.setPadding(px(watch ? 6 : 8), px(4), px(6), px(5));
        typingLabel.setSingleLine(true);
        typingLabel.setEllipsize(TextUtils.TruncateAt.END);
        typingLabel.setVisibility(View.GONE);
        draftRow.addView(typingLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        draftRow.addView(draft, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatList.addFooterView(draftRow, null, false);
        draftRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) followDraft();
        });

        draftButtons = new LinearLayout(this);
        draftButtons.setOrientation(LinearLayout.HORIZONTAL);
        draftButtons.setGravity(Gravity.CENTER);
        draftButtons.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(watch ? (int) (W * 0.64f) : (int) (W * 0.8f), micSize);
        bp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bp.bottomMargin = micBottom;
        chatLayer.addView(draftButtons, bp);
    }

    /**
     * While you talk the mic moves to the right edge as the mirror image of the group's picture
     * on the left: the same size, the same height, the same distance from the edge. That leaves
     * the whole bottom of the screen to the words you're saying. Afterwards it comes back.
     */
    private void micAside(boolean aside) {
        micButton.animate().cancel();
        float tx = 0f, ty = 0f, sc = 1f;
        if (aside) {
            int m = micButton.getLayoutParams().width;
            float[] pic = groupPictureSpot();          // centre x, centre y, size
            float micCx = micButton.getLeft() + m / 2f, micCy = micButton.getTop() + m / 2f;
            if (micButton.getWidth() == 0) {           // not laid out yet: where its layout puts it
                FrameLayout.LayoutParams ml = (FrameLayout.LayoutParams) micButton.getLayoutParams();
                micCx = W / 2f;
                micCy = chatLayer.getHeight() > 0 ? chatLayer.getHeight() - ml.bottomMargin - m / 2f : H - ml.bottomMargin - m / 2f;
            }
            float w = chatLayer.getWidth() > 0 ? chatLayer.getWidth() : W;
            tx = (w - pic[0]) - micCx;                 // mirrored across the middle
            ty = pic[1] - micCy;
            sc = pic[2] / m;
        }
        micScale = sc;
        micButton.animate().translationX(tx).translationY(ty).scaleX(sc).scaleY(sc).alpha(1f)
                .setDuration(320).setInterpolator(new DecelerateInterpolator(1.4f)).start();
        fitListRoom();
    }

    /** Where the group's picture sits in the chat page: {centre x, centre y, size}. */
    private float[] groupPictureSpot() {
        if (chatAvatar.getWidth() > 0 && chatAvatar.isShown()) {
            int[] a = new int[2], l = new int[2];
            chatAvatar.getLocationInWindow(a);
            chatLayer.getLocationInWindow(l);
            float s = chatAvatar.getWidth();
            return new float[]{a[0] - l[0] + s / 2f, a[1] - l[1] + s / 2f, s};
        }
        if (watch) { // hidden (a group with no picture): where it would be
            FrameLayout.LayoutParams al = (FrameLayout.LayoutParams) chatAvatar.getLayoutParams();
            float h = chatLayer.getHeight() > 0 ? chatLayer.getHeight() : H;
            return new float[]{al.leftMargin + al.width / 2f, h / 2f, al.width};
        }
        float s = px(36);
        return new float[]{px(10) + s / 2f, px(10) + s / 2f, s};
    }

    /**
     * The room under the chat. While you're talking the mic is off to the side, so the draft
     * row drops down into the space it left; with the mic or the Send buttons back at the
     * bottom, the chat leaves room for them again. Either way the chat glides, never jumps.
     */
    private void fitListRoom() {
        boolean talking = listening && draftButtons.getVisibility() != View.VISIBLE;
        int want = talking ? (watch ? (int) (H * 0.12f) : px(16)) : listBottom;
        if (chatList.getPaddingBottom() != want) {
            chatList.setPadding(chatList.getPaddingLeft(), chatList.getPaddingTop(), chatList.getPaddingRight(), want);
            followDraft();
        }
    }

    /**
     * Keeps the newest row (the draft while you talk) resting on the bottom of the chat. When it
     * grows a line, or the room under it changes, everything above slides smoothly to make room.
     */
    private void followDraft() {
        chatList.post(() -> {
            if (history || chatList.getChildCount() == 0) return;
            int last = chatList.getLastVisiblePosition();
            if (last < chatList.getCount() - 1) return;         // reading older messages: leave them be
            View end = chatList.getChildAt(chatList.getChildCount() - 1);
            int gap = end.getBottom() - (chatList.getHeight() - chatList.getPaddingBottom());
            if (gap != 0) chatList.smoothScrollBy(gap, 260);
        });
    }

    /** All the way down, after the list has laid out its new padding. */
    private void scrollChatToEnd() {
        chatList.post(() -> {
            chatList.setSelection(chatList.getCount() - 1);
            chatList.post(() -> chatList.scrollListBy(100000));
        });
    }

    private void showDraft(String note) {
        draftNames.clear();
        draft.animate().cancel();
        typeTarget = "";
        typeShown = 0;
        ui.removeCallbacks(typer);
        draftText.setText("\u2026");
        draftNote.setText(note);
        showReplyQuote();
        draftBar.animate().cancel();
        draftBar.setScaleX(1f);
        draftBar.setVisibility(View.INVISIBLE);
        if (draft.getVisibility() != View.VISIBLE) {
            draft.setVisibility(View.VISIBLE);
            draft.setAlpha(0f);
        }
        draft.animate().alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hideDraft() {
        ui.removeCallbacks(typer);
        if (draft.getVisibility() == View.VISIBLE) {
            draft.animate().cancel();
            draft.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                draft.setVisibility(View.GONE);
                followDraft();
            }).start();
        }
        showDraftButtons(null);
    }

    /**
     * A platform Button pads its text generously and insists on a minimum width, which cuts
     * words like "Cancel" off in a half-width watch button. Small padding, one line, and the
     * text shrinks a little rather than ever being cut.
     */
    private void fitButtonText(Button b) {
        b.setPadding(px(4), 0, px(4), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setSingleLine(true);
        b.setEllipsize(null);
        float max = b.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
        b.setAutoSizeTextTypeUniformWithConfiguration(Math.max(8, (int) (max * 0.7f)), (int) Math.ceil(max), 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
    }

    /** Swaps the mic for small Cancel / Send buttons (or back, with null). */
    private void showDraftButtons(Runnable[] cancelSend) {
        draftButtons.removeAllViews();
        if (cancelSend == null) {
            draftButtons.setVisibility(View.GONE);
            micAside(listening);
            return;
        }
        String[] names = {"Cancel", "Send"};
        for (int i = 0; i < 2; i++) {
            Button b = new Button(this);
            b.setGravity(Gravity.CENTER);
            b.setAllCaps(false);
            b.setText(names[i]);
            b.setTextSize(sp(watch ? 12 : 14));
            b.setTypeface(BOLD);
            b.setTextColor(i == 1 ? Color.WHITE : BLUE);
            b.setBackground(Press.button(this, i == 1));

            b.setPadding(px(8), 0, px(8), 0);
            b.setStateListAnimator(null);
            fitButtonText(b);
            Runnable r = cancelSend[i];
            b.setOnClickListener(v -> r.run());
            Press.attach(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.leftMargin = lp.rightMargin = px(3);
            draftButtons.addView(b, lp);
        }
        micButton.animate().cancel();
        micButton.animate().alpha(0f).translationX(0).translationY(0).scaleX(0.6f).scaleY(0.6f).setDuration(150).start();
        draftButtons.setVisibility(View.VISIBLE);
        draftButtons.setAlpha(0f);
        draftButtons.animate().alpha(1f).setDuration(180).start();
        fitListRoom(); // the buttons sit where the mic was: the chat rises above them
    }

    /**
     * Types toward {@code t} a few letters at a time, the way a message gets written. Words
     * already on screen stay put: when the new text only changes their capitals or punctuation
     * ("yes see" to "Yes, see"), they change in place instead of being typed again.
     */
    private void typeTo(String t) {
        String shown = typeTarget.substring(0, Math.min(typeShown, typeTarget.length()));
        int keep = sameWords(shown, t);
        typeTarget = t;
        typeShown = keep;
        if (keep > 0) draftText.setText(boldNames(t.substring(0, keep), draftNames, Color.WHITE));
        ui.removeCallbacks(typer);
        ui.post(typer);
    }

    /**
     * How much of {@code t} says the same as {@code shown}, ignoring capitals and punctuation:
     * the length of {@code t} that covers the letters and digits the two have in common.
     */
    static int sameWords(String shown, String t) {
        int i = 0, j = 0, keep = 0;
        while (i < shown.length() && j < t.length()) {
            char a = shown.charAt(i), b = t.charAt(j);
            boolean wa = Character.isLetterOrDigit(a), wb = Character.isLetterOrDigit(b);
            if (!wa && !Character.isWhitespace(a)) { i++; continue; } // punctuation: skip it
            if (!wb && !Character.isWhitespace(b)) { j++; continue; }
            if (Character.toLowerCase(a) != Character.toLowerCase(b)) break;
            i++;
            j++;
            keep = j;
        }
        if (i < shown.length()) return keep; // they part ways here
        while (keep < t.length() && !Character.isLetterOrDigit(t.charAt(keep)) && !Character.isWhitespace(t.charAt(keep))) keep++;
        return keep;                          // and the new punctuation right after comes too
    }

    private final Runnable typer = new Runnable() {
        @Override
        public void run() {
            int left = typeTarget.length() - typeShown;
            if (left <= 0) return;
            typeShown += Math.max(1, left / 10); // catches up quickly if the words run ahead
            draftText.setText(boldNames(typeTarget.substring(0, typeShown), draftNames, Color.WHITE));
            ui.postDelayed(this, 30);
        }
    };

    /** The pin beside a chat's name: shown only when it has pinned messages. */
    private ImageView chatPinIcon;
    /** Which chats have pinned messages, as last checked (so the pin doesn't blink on reopening). */
    private final java.util.Map<String, Boolean> hasPins = new java.util.HashMap<>();

    /** Shows the header pin if {@code c} has pinned messages; checks GroupMe in the background. */
    private void checkPins(Feed.Convo c) {
        Boolean known = hasPins.get(c.key());
        chatPinIcon.setVisibility(Boolean.TRUE.equals(known) ? View.VISIBLE : View.GONE);
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                boolean any = !Feed.pinnedMessages(token, c).isEmpty();
                ui.post(() -> {
                    hasPins.put(c.key(), any);
                    if (current == c) chatPinIcon.setVisibility(any ? View.VISIBLE : View.GONE);
                });
            } catch (IOException ignored) {
                // as last known
            }
        });
    }

    private void openChat(Feed.Convo c) {
        prepareChat(c);
        go(1, true);
    }

    /** Fills the chat page for {@code c} without moving to it (a drag may bring it in). */
    private void prepareChat(Feed.Convo c) {
        if (history) {
            history = false;
            showLatestPill(false);
            chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
            msgs.clear();
        }
        boolean same = current != null && current.key().equals(c.key());
        if (!same || newSince.isEmpty()) { // where you left off: Say So's place or GroupMe's, whichever is further along
            String r = readTo.get(c.key());
            newSince = r != null && Feed.newer(r, c.lastReadId) ? r : c.lastReadId;
            if (!Feed.newer(c.lastMsgId, newSince)) newSince = ""; // nothing new
            jumpToNew = !newSince.isEmpty();
            restoreView = !fromNotification;
        }
        current = c;
        PushService.visibleKey = c.key();
        PushService.clear(this, c.key());
        chatTitle.setText(c.name);
        checkPins(c);
        status(c.isDm ? "Direct message" : "Group");
        chatAvatar.setImageDrawable(null);
        if (watch) chatAvatar.setVisibility(c.avatarUrl.isEmpty() || !photosOn() ? View.GONE : View.VISIBLE);
        if (photosOn()) Images.load(chatAvatar, Feed.sized(c.avatarUrl, "avatar"), px(watch ? 22 : 36), true);
        if (!same) {
            msgs.clear();
            msgAdapter.notifyDataSetChanged();
        }
        closeReactions();
        replying = null;
        hideVoice();
        if (c.request) ui.postDelayed(() -> { if (current == c && c.request) showRequestBar(c); }, 500);
        if (!same || !liveOn) startLive();
        loadMessages(true);
        ui.removeCallbacks(chatPoll);
        ui.postDelayed(chatPoll, 8000);
    }

    /**
     * The line under the chat name. On a watch it shows only news (loading, no connection, empty
     * chat), never the obvious "Group", so the header stays one line.
     */
    private void status(String text) {
        boolean plain = text.equals("Group") || text.equals("Direct message");
        chatStatus.setText(text);
        if (watch) chatStatus.setVisibility(plain ? View.GONE : View.VISIBLE);
    }

    private void loadMessages(boolean showLoading) {
        final Feed.Convo c = current;
        if (c == null) return;
        if (showLoading && msgs.isEmpty()) status("Loading...");
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                List<Feed.Msg> fresh = Feed.messages(token, c, watch ? 25 : 40);
                ui.post(() -> {
                    if (current == null || !current.key().equals(c.key())) return;
                    ui.removeCallbacks(savePosition);
                    ui.postDelayed(savePosition, 400); // once laid out: what's on screen counts as seen
                    String oldLast = msgs.isEmpty() ? "" : msgs.get(msgs.size() - 1).id;
                    String newLast = fresh.isEmpty() ? "" : fresh.get(fresh.size() - 1).id;
                    status(c.isDm ? "Direct message" : "Group");
                    if (!oldLast.equals(newLast) || msgs.size() != fresh.size()) {
                        boolean first = msgs.isEmpty();
                        java.util.HashSet<String> had = new java.util.HashSet<>();
                        for (Feed.Msg x : msgs) had.add(x.id);
                        String me = Store.myId(HomeActivity.this);
                        boolean mineArrived = false;
                        boolean theirs = false;
                        for (Feed.Msg x : fresh) {
                            if (had.contains(x.id) || first) continue;
                            if (handoff > 0 && x.senderId.equals(me)) mineArrived = true; // takes the draft's place, no slide
                            else appearing.add(x.id);
                            if (!x.senderId.equals(me) && !x.system) theirs = true;
                        }
                        if (theirs && level == 1) { // someone wrote in the chat you're looking at
                            chime(false);
                            buzz(25);
                        }
                        if (mineArrived) {
                            handoff = 0;
                            dropDraftNow();
                        }
                        if (first && !fresh.isEmpty()) fadeList(chatList);
                        // At the bottom already (or sending): follow new messages down. Reading
                        // further up: stay put, they're waiting below.
                        boolean atEnd = chatList.getChildCount() == 0
                                || chatList.getLastVisiblePosition() >= chatList.getCount() - 2;
                        msgs.clear();
                        msgs.addAll(fresh);
                        // Opening the chat: back where you were reading; failing that, the first new
                        // message; failing that, the newest.
                        int jump = -1, jumpY = chatList.getPaddingTop();
                        if (restoreView) {
                            restoreView = false;
                            int at = indexOf(Store.viewId(HomeActivity.this, c.key()));
                            if (at >= 0) {
                                jump = at;
                                jumpY = Store.viewOffset(HomeActivity.this, c.key());
                            }
                        }
                        if (jump < 0 && jumpToNew) jump = firstNewIndex();
                        jumpToNew = false;
                        if (jump >= 0) {
                            final int to = jump, y = jumpY;
                            chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
                            msgAdapter.notifyDataSetChanged();
                            chatList.post(() -> {
                                chatList.setSelectionFromTop(to, y);
                                chatList.post(() -> chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL));
                            });
                        } else if (atEnd || mineArrived || first) {
                            chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
                            msgAdapter.notifyDataSetChanged();
                            chatList.post(() -> chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL));
                        } else {
                            chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
                            msgAdapter.notifyDataSetChanged();
                            chatList.post(() -> chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL));
                        }
                    } else if (!reactionsOf(msgs).equals(reactionsOf(fresh)) || !c.peerReadId.equals(shownReadId)) {
                        shownReadId = c.peerReadId;
                        msgs.clear();          // only reactions changed: redraw in place, no scrolling
                        msgs.addAll(fresh);
                        msgAdapter.notifyDataSetChanged();
                    }
                    if (fresh.isEmpty()) status("No messages yet. Tap the mic to start.");
                    if (handoff > 0) {
                        if (--handoff > 0) ui.postDelayed(() -> loadMessages(false), 700);
                        else hideDraft();
                    }
                });
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> status("Couldn't reach GroupMe"));
            }
        });
    }

    /** Every message's reactions as one string, to notice when only those changed. */
    /** The DM read receipt the chat last drew, to notice when it moves. */
    private String shownReadId = "";

    private static String reactionsOf(List<Feed.Msg> list) {
        StringBuilder b = new StringBuilder();
        for (Feed.Msg m : list) {
            b.append(m.id).append(':');
            for (Feed.Reaction r : m.reactions) b.append(r.code).append(r.userIds).append(',');
        }
        return b.toString();
    }

    /** The first message from someone else that's newer than where you left off, or -1. */
    private int firstNewIndex() {
        if (newSince.isEmpty() || history) return -1;
        String me = Store.myId(this);
        for (int i = 0; i < msgs.size(); i++) {
            Feed.Msg m = msgs.get(i);
            if (!m.system && !m.senderId.equals(me) && Feed.newer(m.id, newSince)) return i;
        }
        return -1;
    }

    /** "New messages", a thin blue line either side, across the whole row. */
    private View newLine() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, px(4), 0, px(8));
        for (int k = 0; k < 3; k++) {
            if (k == 1) {
                TextView t = label("New messages", sp(watch ? 10 : 11.5f), BLUE, true);
                t.setPadding(px(8), 0, px(8), 0);
                line.addView(t);
            } else {
                View v = new View(this);
                v.setBackgroundColor(0x6600AFF0);
                line.addView(v, new LinearLayout.LayoutParams(0, Math.max(1, px(1)), 1f));
            }
        }
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    private final class MsgAdapter extends BaseAdapter {
        @Override public int getCount() { return msgs.size(); }

        @Override public Object getItem(int i) { return msgs.get(i); }

        @Override public long getItemId(int i) { return i; }

        @Override public boolean isEnabled(int i) { return false; }

        @Override
        public View getView(int i, View reuse, ViewGroup parent) {
            Feed.Msg m = msgs.get(i);
            boolean mine = m.senderId.equals(Store.myId(HomeActivity.this));
            LinearLayout row = new LinearLayout(HomeActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, px(watch ? 4 : 6), 0, px(watch ? 4 : 6));
            if (m.system) {
                TextView t = label(m.text, sp(10.5f), GREY, false);
                t.setGravity(Gravity.CENTER);
                row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (appearing.remove(m.id)) glideIn(row, 0);
                return row;
            }
            row.setGravity(mine ? Gravity.END : Gravity.START);
            if (i == firstNewIndex()) row.addView(newLine()); // where you left off

            LinearLayout who = new LinearLayout(HomeActivity.this);
            who.setOrientation(LinearLayout.HORIZONTAL);
            who.setGravity((mine ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
            // Everyone's picture beside their name, yours included: theirs on the left, yours on
            // the right, the same side as the bubbles.
            ImageView av = new ImageView(HomeActivity.this);
            int as = px(watch ? 16 : 22);
            GradientDrawable o = new GradientDrawable();
            o.setShape(GradientDrawable.OVAL);
            o.setColor(Palette.AVATAR);
            av.setBackground(o);
            String pic = mine && m.avatarUrl.isEmpty() ? Store.avatar(HomeActivity.this) : m.avatarUrl;
            if (photosOn()) loadFace(av, pic, as); // an animated GIF picture plays here
            String myName = Store.name(HomeActivity.this);
            TextView name = label(mine ? (myName.isEmpty() ? "You" : myName) : m.sender, sp(watch ? 10 : 11.5f), mine ? BLUE : GREY, true);
            name.setPadding(mine ? px(5) : px(5), 0, mine ? px(5) : 0, 0);
            name.setMaxWidth((int) (W * (watch ? 0.36f : 0.45f))); // long names shorten; the time stays
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            // the time: "13m" and the actual time ("5:34 PM") take turns (see stampFlip)
            TextView when = label(whenText(m.at), sp(watch ? 10 : 11.5f), mine ? BLUE : GREY, true);
            when.setPadding(mine ? 0 : px(5), 0, 0, 0);
            when.setSingleLine(true);
            when.setAlpha(0.85f);
            times.put(when, m.at);
            if (mine) { // yours mirror everyone else's: time, then your name, then your picture
                who.addView(when);
                who.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                who.addView(av, new LinearLayout.LayoutParams(as, as));
            } else {
                who.addView(av, new LinearLayout.LayoutParams(as, as));
                who.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                who.addView(when);
            }
            // tap someone's picture or name: their profile
            final Feed.Msg msg = m;
            who.setOnClickListener(v -> openProfile(memberFor(msg), 1));
            Press.attach(who);
            row.addView(who);

            int maxW = (int) (W * (watch ? 0.74f : 0.72f));
            if (!m.replyId.isEmpty()) { // the message this answers, quoted small above it
                int at = indexOf(m.replyId);
                Feed.Msg orig = at >= 0 ? msgs.get(at) : null;
                View q = quoteView(orig, maxW);
                LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                qp.topMargin = px(3);
                row.addView(q, qp);
                final String oid = m.replyId; // tap: the original, glowing (fetched if it's older)
                q.setOnClickListener(v -> jumpTo(oid));
                Press.attach(q);
            }
            if (!m.text.isEmpty()) {
                TextView t = label("", sp(watch ? 13 : 15), mine ? Color.WHITE : INK, false);
                t.setText(namesBold(m.text, m.mentions, mine ? Color.WHITE : BLUE));
                t.setBackground(bubbleBg(mine));
                t.setPadding(px(12), px(7), px(12), px(8));
                t.setMaxWidth(maxW);
                lift(t, mine);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tl.topMargin = px(3);
                row.addView(t, tl);
                messageGestures(t, m);
                if (!m.eventId.isEmpty()) { // "created event 'Team breakfast'": tap it for the event itself
                    final String eid = m.eventId;
                    t.setOnClickListener(v -> openEventById(eid));
                    TextView more = label("See event ›", sp(watch ? 10.5f : 12), BLUE, true);
                    more.setPadding(px(4), px(3), px(4), 0);
                    more.setOnClickListener(v -> openEventById(eid));
                    row.addView(more);
                }
            }
            for (Feed.Media media : m.media) {
                View mv = mediaView(media, maxW);
                messageGestures(mv, m);
                row.addView(mv);
            }
            String link = m.media.isEmpty() && photosOn() ? LinkPreview.firstUrl(m.text) : null;
            if (link != null) row.addView(linkCard(link, maxW));
            if (!m.reactions.isEmpty()) row.addView(reactionChips(m, mine));
            if (mine && m.id.equals(readMarkId())) { // DM: they've read up to here
                TextView read = label("Read " + Feed.stamp(current.peerReadAt, System.currentTimeMillis()),
                        sp(watch ? 9.5f : 11), GREY, false);
                read.setPadding(0, px(m.reactions.isEmpty() ? 2 : 4), px(4), 0);
                read.setGravity(Gravity.END);
                row.addView(read, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (appearing.remove(m.id)) glideIn(row, 0);
            if (m.id.equals(flashId)) {
                flashId = null;
                GradientDrawable glow = new GradientDrawable();
                glow.setColor(0x4400AFF0);
                glow.setCornerRadius(px(14));
                row.setBackground(glow);
                android.animation.ObjectAnimator.ofInt(glow, "alpha", 255, 0).setDuration(1800).start();
            }
            return row;
        }
    }

    /** The color for pop-up cards: the card color, a little see-through. */
    private static int glass() {
        return (Palette.CARD & 0x00FFFFFF) | 0xF0000000;
    }

    /** A watch pop-up's backdrop over the whole screen: the page color, mostly opaque. */
    private static int scrim() {
        return (Palette.BG & 0x00FFFFFF) | (Palette.dark ? 0xE0000000 : 0xE8000000);
    }

    /** A quick note over the screen that fades away by itself (nothing to tap). */
    private void toast(String text) {
        TextView t = label(text, sp(watch ? 12 : 14), Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xE6000000 | (BLUE & 0x00FFFFFF));
        g.setCornerRadius(px(20));
        t.setBackground(g);
        t.setPadding(px(14), px(8), px(14), px(9));
        t.setElevation(px(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        lp.topMargin = watch ? (int) (H * 0.22f) : px(80);
        root.addView(t, lp);
        t.setAlpha(0f);
        t.setTranslationY(-px(8));
        t.animate().alpha(1f).translationY(0).setDuration(220).withEndAction(() ->
                t.animate().alpha(0f).setStartDelay(1800).setDuration(300).withEndAction(() -> root.removeView(t)).start()).start();
    }

    // ---------------- a menu of choices (long-presses) ----------------

    private FrameLayout menu;

    /**
     * A short list of choices over the whole (dimmed) screen, like the pop-ups: a title, then a
     * card for each choice. Tap one, or tap outside or press Back to close.
     */
    private void showChoices(String title, String[] labels, Runnable[] actions) {
        closeChoices();
        menu = new FrameLayout(this);
        menu.setBackgroundColor(scrim());
        menu.setClickable(true);
        menu.setOnClickListener(v -> closeChoices());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = label(title, sp(watch ? 12.5f : 15), GREY, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, 0, 0, px(6));
        col.addView(t);
        for (int i = 0; i < labels.length; i++) {
            final Runnable r = actions[i];
            boolean danger = labels[i].startsWith("Unsend") || labels[i].startsWith("Decline");
            Button b = new Button(this);
            b.setGravity(Gravity.CENTER);
            b.setAllCaps(false);
            b.setText(labels[i]);
            b.setTextSize(sp(watch ? 13 : 15));
            b.setTypeface(BOLD);
            b.setTextColor(i == 0 && !danger ? Color.WHITE : danger ? 0xFFFF3B30 : BLUE);
            b.setBackground(Press.button(this, i == 0 && !danger));
            b.setStateListAnimator(null);
            fitButtonText(b);
            b.setOnClickListener(v -> {
                closeChoices();
                r.run();
            });
            Press.attach(b);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(watch ? 40 : 48));
            bl.topMargin = px(5);
            col.addView(b, bl);
        }
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(watch ? (int) (W * 0.72f) : (int) (W * 0.8f), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.CENTER;
        menu.addView(col, cl);
        root.addView(menu, match());
        menu.setAlpha(0f);
        menu.animate().alpha(1f).setDuration(200).start();
    }

    private boolean closeChoices() {
        if (menu == null) return false;
        View m = menu;
        menu = null;
        m.animate().alpha(0f).setDuration(160).withEndAction(() -> root.removeView(m)).start();
        return true;
    }

    // ---------------- chat sounds ----------------

    private android.media.SoundPool sounds;
    private int soundSent, soundGot;

    /**
     * A short soft tone: rising when your message goes out, falling when one arrives in the chat
     * you have open. Quiet when the watch is on silent or vibrate, or Sounds is off.
     */
    private void chime(boolean sent) {
        if (!Store.pushSound(this)) return; // the Sounds switch covers these too
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am != null && am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return;
        if (sounds == null) {
            sounds = new android.media.SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
                    new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            soundSent = sounds.load(this, R.raw.sent, 1);
            soundGot = sounds.load(this, R.raw.received, 1);
            final boolean s = sent;
            sounds.setOnLoadCompleteListener((pool, id, status) -> {
                if (status == 0 && id == (s ? soundSent : soundGot)) pool.play(id, 0.6f, 0.6f, 1, 0, 1f);
            });
            return;
        }
        sounds.play(sent ? soundSent : soundGot, 0.6f, 0.6f, 1, 0, 1f);
    }

    // ---------------- live chat: who's typing, and DM read times ----------------

    private TextView typingLabel;
    /** People typing in the open chat, and when we last heard (ms). */
    private final java.util.Map<String, Long> typers = new java.util.LinkedHashMap<>();
    private volatile boolean liveOn;
    private volatile Push livePush;
    private Thread liveThread;
    private long typingSentAt;

    /**
     * While a chat is open: a live connection to it, the way GroupMe's apps keep one, for
     * "is typing", read receipts in a DM, and new messages the moment they're sent.
     */
    private void startLive() {
        stopLive();
        final Feed.Convo c = current;
        if (c == null) return;
        final String token = Store.token(this), me = Store.myId(this);
        final String channel = Push.chatChannel(c.isDm, conversationIdOf(c));
        liveOn = true;
        liveThread = new Thread(() -> {
            while (liveOn && current == c) {
                try {
                    Push p = new Push(token, me, channel);
                    p.open();
                    livePush = p;
                    long until = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L;
                    while (liveOn && current == c && android.os.SystemClock.elapsedRealtime() < until) {
                        List<Push.Event> events = p.poll();
                        boolean here = false;
                        for (Push.Event e : events) if (e.dm == c.isDm && e.convoId.equals(c.id)) here = true;
                        for (Push.Signal sg : p.signals) {
                            if ("typing".equals(sg.type) && sg.chatId.equals(channel)) {
                                ui.post(() -> { if (current == c) heardTyping(sg.userId); });
                            } else if ("read".equals(sg.type) && c.isDm && sg.userId.equals(c.id)) {
                                ui.post(() -> { if (current == c) loadMessages(false); }); // "Read" moves down
                            } else if ("pinned".equals(sg.type) && !c.isDm && sg.chatId.equals(c.id)) {
                                // pinned in any GroupMe app: the header pin and GroupMe's "pinned a message" line
                                ui.post(() -> { if (current == c && !history) loadMessages(false); });
                            }
                        }
                        // new messages may come with pin changes (an unpin sends nothing of its own)
                        if (here || p.signals.stream().anyMatch(s -> "pinned".equals(s.type)))
                            ui.post(() -> { if (current == c) checkPins(c); });
                        if (here) ui.post(() -> { if (current == c && !history) loadMessages(false); });
                    }
                } catch (Api.AuthException e) {
                    return;
                } catch (IOException | RuntimeException e) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "sayso-live");
        liveThread.setDaemon(true);
        liveThread.start();
    }

    /**
     * While the chat list is showing: a live connection to your own GroupMe channel, the way its
     * web app keeps one. A chat pinned or unpinned in any GroupMe app moves at once, and the list
     * refreshes the moment a message comes in or you read a chat somewhere else.
     */
    private void startListLive() {
        stopLive();
        final String token = Store.token(this), me = Store.myId(this);
        if (token.isEmpty() || me.isEmpty()) return;
        liveOn = true;
        liveThread = new Thread(() -> {
            while (liveOn && level == 0) {
                try {
                    Push p = new Push(token, me);
                    p.open();
                    livePush = p;
                    long until = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L;
                    while (liveOn && level == 0 && android.os.SystemClock.elapsedRealtime() < until) {
                        List<Push.Event> events = p.poll();
                        for (Push.Signal sg : p.signals) {
                            if ("pins".equals(sg.type)) {
                                final List<String> ids = new ArrayList<>(sg.ids);
                                ui.post(() -> { if (level == 0) applyPins(ids); });
                            } else if ("sync".equals(sg.type)) {
                                ui.post(this::refreshListSoon);
                            }
                        }
                        if (!events.isEmpty()) ui.post(this::refreshListSoon);
                    }
                } catch (Api.AuthException e) {
                    return;
                } catch (IOException | RuntimeException e) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "sayso-list-live");
        liveThread.setDaemon(true);
        liveThread.start();
    }

    /** Your pinned chats as GroupMe just sent them: pinned first in that order, the rest newest first. */
    private void applyPins(List<String> ids) {
        java.util.Map<String, Integer> order = new java.util.HashMap<>();
        for (int k = 0; k < ids.size(); k++) order.put(ids.get(k), k);
        for (Feed.Convo c : convos) c.pinned = order.containsKey(conversationIdOf(c)) || order.containsKey(c.id);
        List<Feed.Convo> pinned = new ArrayList<>(), rest = new ArrayList<>();
        for (Feed.Convo x : convos) (x.pinned ? pinned : rest).add(x);
        pinned.sort((x, y) -> Integer.compare(
                order.getOrDefault(conversationIdOf(x), order.getOrDefault(x.id, 0)),
                order.getOrDefault(conversationIdOf(y), order.getOrDefault(y.id, 0))));
        rest.sort((x, y) -> Long.compare(y.lastAt, x.lastAt));
        convos.clear();
        convos.addAll(pinned);
        convos.addAll(rest);
        convoAdapter.notifyDataSetChanged();
    }

    private final Runnable listRefresh = this::refreshList;

    /** Refreshes the list in a moment (several events in a row make one refresh). */
    private void refreshListSoon() {
        ui.removeCallbacks(listRefresh);
        ui.postDelayed(listRefresh, 800);
    }

    private void stopLive() {
        liveOn = false;
        livePush = null;
        if (liveThread != null) liveThread.interrupt();
        liveThread = null;
        typers.clear();
        showTypers();
    }

    private EditText typingBox;
    private final Runnable typingWhileFocused = new Runnable() {
        @Override public void run() {
            if (typingBox == null || !typingBox.hasFocus() || voicePanel.getVisibility() != View.VISIBLE) return;
            sendTyping();
            ui.postDelayed(this, 5000);
        }
    };

    /** Lets the chat know you're writing (by voice or typing), at most every 5 seconds. */
    private void sendTyping() {
        long now = android.os.SystemClock.elapsedRealtime();
        final Push p = livePush;
        if (p == null || now - typingSentAt < 5000) return; // GroupMe's apps: at most every 5 s
        typingSentAt = now;
        bg.execute(() -> {
            try {
                p.publishTyping();
            } catch (IOException ignored) {
                // just no "typing" this time
            }
        });
    }

    private void heardTyping(String userId) {
        typers.put(userId, android.os.SystemClock.elapsedRealtime());
        showTypers();
        ui.removeCallbacks(typingExpire);
        ui.postDelayed(typingExpire, 5600); // shown 5 s after the last one, like GroupMe's apps
    }

    private final Runnable typingExpire = () -> {
        long now = android.os.SystemClock.elapsedRealtime();
        typers.values().removeIf(t -> now - t > 5500);
        showTypers();
        if (!typers.isEmpty()) ui.postDelayed(this.typingExpire, 1000);
    };

    private int typingDots;
    private final Runnable typingTick = new Runnable() {
        @Override public void run() {
            if (typers.isEmpty()) return;
            typingDots = (typingDots + 1) % 4;
            showTypers();
            ui.postDelayed(this, 450);
        }
    };

    /** "Sam is typing…", "Sam and Ann are typing…", or nothing. */
    private void showTypers() {
        if (typingLabel == null) return;
        if (typers.isEmpty()) {
            if (typingLabel.getVisibility() != View.GONE) typingLabel.setVisibility(View.GONE);
            ui.removeCallbacks(typingTick);
            return;
        }
        List<String> names = new ArrayList<>();
        for (String id : typers.keySet()) names.add(nameOf(id));
        String who = names.size() == 1 ? names.get(0) : names.size() == 2 ? names.get(0) + " and " + names.get(1)
                : names.get(0) + " and " + (names.size() - 1) + " others";
        typingLabel.setText(who + (names.size() == 1 ? " is typing" : " are typing") + "...".substring(0, typingDots));
        if (typingLabel.getVisibility() != View.VISIBLE) {
            typingLabel.setVisibility(View.VISIBLE);
            ui.removeCallbacks(typingTick);
            ui.postDelayed(typingTick, 450);
        }
    }

    /** Someone's name in the open chat, from its members or its messages. */
    private String nameOf(String userId) {
        for (Feed.Member m : members) if (m.userId.equals(userId)) return m.name;
        for (int i = msgs.size() - 1; i >= 0; i--) if (msgs.get(i).senderId.equals(userId)) return msgs.get(i).sender;
        if (current != null && current.isDm) return current.name;
        return "Someone";
    }

    /** DM: the newest of your messages the other person has read, where "Read 5:34 PM" goes. */
    private String readMarkId() {
        if (current == null || !current.isDm || current.peerReadId.isEmpty()) return "";
        String me = Store.myId(this), mark = "";
        for (Feed.Msg m : msgs) {
            if (m.senderId.equals(me) && !Feed.newer(m.id, current.peerReadId)) mark = m.id;
        }
        return mark;
    }

    // ---------------- message looks, replies and reactions ----------------

    /**
     * The line under a header bar: thickest in the middle, thinning smoothly to a hairline at
     * both edges, and running the full width.
     */
    private static final class TaperLine extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float thick;

        TaperLine(int color, float thick) {
            paint.setColor(color);
            this.thick = thick;
        }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float y = b.bottom - thick / 2f - 0.5f, w = b.width(), half = thick / 2f, edge = 0.35f;
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(b.left, y - edge);
            p.cubicTo(b.left + w * 0.3f, y - edge, b.left + w * 0.35f, y - half, b.left + w / 2f, y - half);
            p.cubicTo(b.left + w * 0.65f, y - half, b.left + w * 0.7f, y - edge, b.right, y - edge);
            p.lineTo(b.right, y + edge);
            p.cubicTo(b.left + w * 0.7f, y + edge, b.left + w * 0.65f, y + half, b.left + w / 2f, y + half);
            p.cubicTo(b.left + w * 0.35f, y + half, b.left + w * 0.3f, y + edge, b.left, y + edge);
            p.close();
            c.drawPath(p, paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** A solid background with a drawable (the taper line) over it. */
    private static final class LayerDrawableCompat extends android.graphics.drawable.LayerDrawable {
        LayerDrawableCompat(int color, android.graphics.drawable.Drawable over) {
            super(new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(color), over});
        }
    }

    /** Tagged "@Name"s in the draft being typed ({start, length}). */
    private final List<int[]> draftNames = new ArrayList<>();

    /** A message's text with each tagged "@Name" in bold color, as other GroupMe apps show it. */
    private CharSequence namesBold(String text, List<int[]> mentions, int color) {
        if (mentions.isEmpty()) return text;
        List<int[]> names = new ArrayList<>();
        for (int[] m : mentions) {
            if (m[0] >= 0 && m[1] > 0 && m[0] + m[1] <= text.length()) names.add(m);
        }
        return boldNames(text, names, color);
    }

    private CharSequence boldNames(String text, List<int[]> names, int color) {
        if (names.isEmpty()) return text;
        android.text.SpannableString sp = new android.text.SpannableString(text);
        for (int[] n : names) {
            int a = n[0], b = Math.min(text.length(), n[0] + n[1]);
            if (a >= b) continue;
            sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new android.text.style.ForegroundColorSpan(color), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    /**
     * A message pill. Yours: a soft top-to-bottom blue. Theirs: a light fill with a hairline
     * edge so it stands off the page. The corner nearest the sender stays tight, like a tail.
     */
    private GradientDrawable bubbleBg(boolean mine) {
        GradientDrawable g;
        if (mine) {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Palette.BLUE_TOP, Palette.BLUE_BOTTOM});
        } else {
            g = new GradientDrawable();
            g.setColor(Palette.BUBBLE);
            g.setStroke(Math.max(1, px(0.75f)), Palette.BUBBLE_EDGE);
        }
        float r = px(18), t = px(5); // corners: top-left, top-right, bottom-right, bottom-left
        g.setCornerRadii(mine ? new float[]{r, r, r, r, t, t, r, r} : new float[]{r, r, r, r, r, r, t, t});
        return g;
    }

    /** A small soft shadow under a pill so it lifts off the page (shadows don't show on black). */
    private void lift(View v, boolean mine) {
        if (Palette.dark && watch) return;
        v.setElevation(px(mine ? 2.5f : 1.5f));
        v.setOutlineAmbientShadowColor(mine ? 0xFF0077B6 : 0xFF000000);
        v.setOutlineSpotShadowColor(mine ? 0xFF0077B6 : 0xFF000000);
    }

    /** The small quoted line for a reply: "↩ Name: what they said". */
    private TextView quoteLabel(String text, boolean onDraft) {
        TextView q = label("↩ " + text, sp(watch ? 10.5f : 12), GREY, false);
        q.setSingleLine(true);
        q.setEllipsize(TextUtils.TruncateAt.END);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Palette.ROW);
        g.setCornerRadius(px(10));
        g.setStroke(Math.max(1, px(0.75f)), onDraft ? BLUE : Palette.ROW_EDGE);
        q.setBackground(g);
        q.setPadding(px(9), px(4), px(9), px(5));
        q.setMaxWidth((int) (W * (watch ? 0.7f : 0.68f)));
        return q;
    }

    /**
     * The message a reply answers, quoted small above it the way GroupMe shows it: the original's
     * picture (if it had one) on the left, who wrote it, and what they said.
     */
    private View quoteView(Feed.Msg orig, int maxW) {
        if (orig == null) {
            TextView q = quoteLabel("Reply to an earlier message", false);
            q.setMaxWidth(maxW);
            return q;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Palette.ROW);
        g.setCornerRadius(px(10));
        g.setStroke(Math.max(1, px(0.75f)), Palette.ROW_EDGE);
        box.setBackground(g);
        int textRoom = maxW - px(9 + 9);
        Feed.Media pic = null;
        for (Feed.Media md : orig.media) {
            String still = md.kind == Feed.Media.VIDEO ? md.preview : (md.preview.isEmpty() ? md.url : md.preview);
            if (still != null && !still.isEmpty()) {
                pic = md;
                break;
            }
        }
        if (pic != null && photosOn()) {
            int ts = px(watch ? 28 : 36);
            ImageView th = new ImageView(this);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable r = new GradientDrawable();
            r.setColor(Palette.AVATAR);
            r.setCornerRadius(px(6));
            th.setBackground(r);
            th.setClipToOutline(true);
            String still = pic.kind == Feed.Media.VIDEO ? pic.preview : (pic.preview.isEmpty() ? pic.url : pic.preview);
            Images.load(th, still, ts * 2, false);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
            tp.rightMargin = px(7);
            box.addView(th, tp);
            box.setPadding(px(5), px(5), px(10), px(5));
            textRoom -= ts + px(7) - px(4);
        } else {
            box.setPadding(px(9), px(4), px(10), px(5));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        String who = orig.senderId.equals(Store.myId(this)) ? "You" : orig.sender;
        TextView name = label("\u21A9 " + who, sp(watch ? 10 : 11.5f), GREY, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(textRoom);
        col.addView(name);
        String said = orig.text;
        if (said.isEmpty() && !orig.media.isEmpty()) {
            said = orig.media.get(0).kind == Feed.Media.VIDEO ? "Video" : "Photo";
        }
        if (!said.isEmpty()) {
            TextView what = label(said, sp(watch ? 10.5f : 12), GREY, false);
            what.setSingleLine(true);
            what.setEllipsize(TextUtils.TruncateAt.END);
            what.setMaxWidth(textRoom);
            what.setAlpha(0.9f);
            col.addView(what);
        }
        box.addView(col);
        return box;
    }

    private String quoteText(Feed.Msg m) {
        String who = m.senderId.equals(Store.myId(this)) ? "You" : m.sender;
        String said = m.text;
        String what = !said.isEmpty() ? said : m.media.isEmpty() ? "a message"
                : m.media.get(0).kind == Feed.Media.VIDEO ? "a video" : "a photo";
        return who + ": " + what;
    }

    private void showReplyQuote() {
        if (replying == null) {
            draftQuote.setVisibility(View.GONE);
            return;
        }
        draftQuote.setText("↩ Replying to " + quoteText(replying));
        draftQuote.setVisibility(View.VISIBLE);
    }

    /**
     * Each message answers three touches: one tap does what it always did (opens the photo or
     * the event), a double tap brings up emoji reactions, and holding it starts a spoken reply.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void messageGestures(View v, Feed.Msg m) {
        if (m.system) return;
        android.view.GestureDetector g = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (v.hasOnClickListeners()) v.performClick();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                showReactions(m, v);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (dragging || pulling) return;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                messageActions(m, v);
            }
        });
        g.setIsLongpressEnabled(true);
        v.setOnTouchListener((view, e) -> {
            g.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(2.5f)).start();
            }
            return true;
        });
        // screen readers: the same two actions, by name
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.act_reply, "Reply by voice"));
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.act_react, "React"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.act_reply) { startReply(m); return true; }
                if (action == R.id.act_react) { showReactions(m, host); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    /** Hold a message: reply to it by voice, react to it, or (yours) unsend it. */
    private void messageActions(Feed.Msg m, View anchor) {
        boolean mine = m.senderId.equals(Store.myId(this));
        String what = m.text.isEmpty() ? (m.media.isEmpty() ? "Message" : "Photo") : m.text;
        List<String> labels = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        labels.add("Reply by voice");
        runs.add(() -> startReply(m));
        labels.add("React");
        runs.add(() -> showReactions(m, anchor));
        if (mine) {
            labels.add("Unsend");
            runs.add(() -> unsend(m));
        }
        showChoices(what, labels.toArray(new String[0]), runs.toArray(new Runnable[0]));
    }

    /** Takes one of your messages back, for everyone (GroupMe allows it for a while after sending). */
    private void unsend(Feed.Msg m) {
        final Feed.Convo c = current;
        if (c == null) return;
        int at = msgs.indexOf(m);
        if (at >= 0) { // gone at once; it comes back if GroupMe says no
            msgs.remove(at);
            msgAdapter.notifyDataSetChanged();
        }
        final String token = Store.token(this), conv = conversationIdOf(c);
        bg.execute(() -> {
            try {
                Feed.unsend(token, conv, m.id);
                ui.post(() -> status(c.isDm ? "Direct message" : "Group"));
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> {
                    status("Couldn't unsend that one");
                    loadMessages(false);
                });
            }
        });
    }

    /** Hold a message: it glows, and Say So listens for your answer to it. */
    private void startReply(Feed.Msg m) {
        if (current == null || m.system) return;
        closeReactions();
        replying = m;
        flashId = m.id;
        msgAdapter.notifyDataSetChanged();
        startVoice();
    }

    /** GroupMe's id for this chat's likes and reactions. */
    private String conversationId() {
        return current.isDm ? Feed.dmConversationId(Store.myId(this), current.id) : current.id;
    }

    /** The emoji under a message, small pills tucked against its bottom edge. Yours is outlined. */
    private View reactionChips(Feed.Msg m, boolean mine) {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String me = Store.myId(this);
        int shown = 0;
        for (Feed.Reaction r : m.reactions) {
            if (shown == (watch ? 3 : 5)) break;
            boolean minePick = r.userIds.contains(me);
            int n = r.userIds.size();
            String face = r.code.isEmpty() ? "✦" : r.code; // a GroupMe sticker-pack reaction
            TextView c = label(n > 1 ? face + " " + n : face, sp(watch ? 11 : 12.5f), INK, true);
            GradientDrawable g = new GradientDrawable();
            g.setColor(minePick ? Palette.BADGE : Palette.CARD);
            g.setCornerRadius(px(12));
            g.setStroke(Math.max(1, px(minePick ? 1.25f : 0.75f)), minePick ? BLUE : Palette.ROW_EDGE);
            c.setBackground(g);
            c.setPadding(px(7), px(2), px(7), px(3));
            c.setGravity(Gravity.CENTER);
            if (!(Palette.dark && watch)) c.setElevation(px(3));
            if (!r.code.isEmpty()) {
                final String code = r.code;
                c.setOnClickListener(v -> react(m, minePick ? "" : code)); // tap one: same as picking it
                Press.attach(c);
            }
            c.setContentDescription(face + ", " + n);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (shown > 0) cl.leftMargin = px(3);
            chips.addView(c, cl);
            shown++;
        }
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = -px(7); // overlaps the pill's bottom edge a little
        if (mine) l.rightMargin = px(10);
        else l.leftMargin = px(10);
        chips.setLayoutParams(l);
        return chips;
    }

    /** Double-tap: a row of emoji pops up beside the message. Your current one is ringed. */
    private void showReactions(Feed.Msg m, View anchor) {
        if (current == null || m.system) return;
        closeReactions();
        String me = Store.myId(this), mineNow = "";
        for (Feed.Reaction r : m.reactions) if (r.userIds.contains(me)) mineNow = r.code;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(glass());
        bgd.setCornerRadius(px(40));
        bgd.setStroke(Math.max(1, px(0.75f)), Palette.ROW_EDGE);
        card.setBackground(bgd);
        card.setElevation(px(12));
        card.setPadding(px(6), px(4), px(6), px(4));
        int cell = px(watch ? 27 : 44);
        for (int i = 0; i < REACTIONS.length; i++) {
            String code = REACTIONS[i];
            TextView e = new TextView(this);
            e.setText(code);
            e.setTextSize(sp(watch ? 17 : 26));
            e.setGravity(Gravity.CENTER);
            e.setIncludeFontPadding(false);
            if (code.equals(mineNow)) {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(Palette.BADGE);
                ring.setStroke(px(1.5f), BLUE);
                e.setBackground(ring);
            }
            final boolean again = code.equals(mineNow);
            e.setOnClickListener(v -> {
                v.animate().scaleX(1.35f).scaleY(1.35f).setDuration(110).withEndAction(this::closeReactions).start();
                react(m, again ? "" : code); // the same one again takes yours off
            });
            e.setContentDescription(again ? "Remove " + code : "React " + code);
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(cell, cell);
            el.leftMargin = el.rightMargin = px(1);
            card.addView(e, el);
            e.setScaleX(0.4f);
            e.setScaleY(0.4f);
            e.setAlpha(0f);
            e.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(30L * i).setDuration(220)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int cw = card.getMeasuredWidth(), ch = card.getMeasuredHeight();
        int[] a = new int[2], l = new int[2];
        anchor.getLocationInWindow(a);
        chatLayer.getLocationInWindow(l);
        int top = a[1] - l[1] - ch - px(6);                       // just above the message
        if (top < px(watch ? 60 : 70)) top = a[1] - l[1] + anchor.getHeight() + px(6); // or under it
        int lh = chatLayer.getHeight() > 0 ? chatLayer.getHeight() : H;
        int lo = watch ? (int) (lh * 0.2f) : px(64), hi = (watch ? (int) (lh * 0.78f) : lh - px(24)) - ch;
        top = Math.max(lo, Math.min(hi, top));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(cw, ch);
        fl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        fl.topMargin = top;
        chatLayer.addView(card, fl);
        card.setPivotX(cw / 2f);
        card.setPivotY(ch);
        card.setScaleX(0.7f);
        card.setScaleY(0.7f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
        reactCard = card;
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
    }

    private void closeReactions() {
        if (reactCard == null) return;
        View c = reactCard;
        reactCard = null;
        c.animate().cancel();
        c.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150)
                .withEndAction(() -> chatLayer.removeView(c)).start();
    }

    /** Puts your reaction on (or, with "", takes it off) right away, then tells GroupMe. */
    private void react(Feed.Msg m, String code) {
        if (current == null) return;
        String me = Store.myId(this);
        for (Feed.Reaction r : m.reactions) r.userIds.remove(me);
        if (!code.isEmpty()) {
            Feed.Reaction to = null;
            for (Feed.Reaction r : m.reactions) if (r.code.equals(code)) to = r;
            if (to == null) m.reactions.add(to = new Feed.Reaction(code));
            to.userIds.add(me);
        }
        m.reactions.removeIf(r -> r.userIds.isEmpty());
        msgAdapter.notifyDataSetChanged();
        final String token = Store.token(this), conv = conversationId(), id = m.id;
        bg.execute(() -> {
            try {
                Feed.react(token, conv, id, code);
                ui.postDelayed(() -> loadMessages(false), 600);
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> {
                    status("Couldn't react. Check your connection.");
                    loadMessages(false);
                });
            }
        });
    }

    /** Looking at older messages (jumped back to a photo): new ones don't pull the view to the end. */
    private boolean history;
    private String flashId;

    /** From a full-screen photo: back to the chat, scrolled to the message it came with, glowing. */
    private void jumpTo(String msgId) {
        stopVideo();
        if (level != 1) go(1, false);
        int i = indexOf(msgId);
        if (i >= 0) {
            flashId = msgId;
            msgAdapter.notifyDataSetChanged();
            chatList.post(() -> chatList.setSelectionFromTop(i, chatList.getHeight() / 3));
            return;
        }
        final Feed.Convo c = current;
        status("Finding that message\u2026");
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                List<Feed.Msg> window = Feed.around(token, c, msgId);
                ui.post(() -> {
                    if (current == null || !current.key().equals(c.key())) return;
                    status(c.isDm ? "Direct message" : "Group");
                    history = true;
                    showLatestPill(true);
                    msgs.clear();
                    msgs.addAll(window);
                    flashId = msgId;
                    chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
                    msgAdapter.notifyDataSetChanged();
                    fadeList(chatList);
                    int j = indexOf(msgId);
                    chatList.post(() -> chatList.setSelectionFromTop(Math.max(0, j), chatList.getHeight() / 3));
                });
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> status("Couldn't load that part of the chat"));
            }
        });
    }

    private int indexOf(String msgId) {
        for (int i = 0; i < msgs.size(); i++) if (msgs.get(i).id.equals(msgId)) return i;
        return -1;
    }

    /** Back to the newest messages from older history. */
    private void toLatest() {
        if (!history) return;
        history = false;
        showLatestPill(false);
        chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        msgs.clear();
        msgAdapter.notifyDataSetChanged();
        loadMessages(true);
    }

    /** The two "to the newest" arrows, either side of the mic. */
    private ImageView[] downArrows;

    /**
     * Two small see-through arrows either side of the mic, pulsing calmly, while you're reading
     * above the newest message. Either one glides you down. They leave the middle of the round
     * screen to the messages.
     */
    private void showLatestPill(boolean show) {
        if (downArrows == null) {
            downArrows = new ImageView[2];
            FrameLayout.LayoutParams ml = (FrameLayout.LayoutParams) micButton.getLayoutParams();
            int size = px(watch ? 30 : 36), gap = px(watch ? 10 : 14);
            for (int i = 0; i < 2; i++) {
                ImageView v = new ImageView(this);
                v.setImageDrawable(new Icons(Icons.DOWN, Palette.dark ? Color.WHITE : BLUE, 0)); // just the arrow
                v.setContentDescription("To the newest message");
                v.setOnClickListener(x -> {
                    if (history) toLatest();
                    else chatList.smoothScrollToPositionFromTop(chatList.getCount() - 1, 0, 450);
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = ml.bottomMargin + (ml.height - size) / 2;
                v.setTranslationX((i == 0 ? -1 : 1) * (ml.width / 2f + gap + size / 2f));
                v.setVisibility(View.GONE);
                chatLayer.addView(v, lp);
                downArrows[i] = v;
            }
        }
        for (ImageView v : downArrows) {
            boolean showing = v.getVisibility() == View.VISIBLE;
            if (show == showing) continue;
            v.animate().cancel();
            if (show) {
                v.setVisibility(View.VISIBLE);
                v.setAlpha(0f);
                v.animate().alpha(0.35f).setDuration(260).withEndAction(() -> pulse(v)).start();
            } else {
                v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    v.setVisibility(View.GONE);
                    v.setTranslationY(0);
                }).start();
            }
        }
    }

    /** A slow, calm bob downward and back while the arrow shows, always mostly see-through. */
    private void pulse(View v) {
        if (v.getVisibility() != View.VISIBLE) return;
        v.animate().translationY(px(3)).alpha(0.45f).setDuration(1000).setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (v.getVisibility() != View.VISIBLE) return;
                    v.animate().translationY(0).alpha(0.25f).setDuration(1000)
                            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                            .withEndAction(() -> pulse(v)).start();
                }).start();
    }

    /**
     * Above the mic while you're reading further up: a pill that takes you to the newest
     * message, saying how many new ones are waiting below ("3 new \u2193"), or "Latest \u2193".
     */
    private void updateLatestPill() {
        if (micButton == null) return;
        boolean busy = listening || pendingSend != null || (voicePanel != null && voicePanel.getVisibility() == View.VISIBLE);
        if (history) {
            showLatestPill(!busy);
            return;
        }
        int last = chatList.getLastVisiblePosition();
        int below = msgs.size() - 1 - Math.min(last, msgs.size() - 1);
        showLatestPill(chatList.getChildCount() > 0 && below > 0 && !busy);
    }

    /** Message ids that just arrived: each slides up and fades in the first time it's drawn. */
    private final java.util.HashSet<String> appearing = new java.util.HashSet<>();
    /** After a send: tries left to find the sent message and swap the draft for it in place. */
    private int handoff;

    /** The draft leaves at the exact moment its real message takes its place: never two at once. */
    private void dropDraftNow() {
        ui.removeCallbacks(typer);
        draft.animate().cancel();
        draft.setVisibility(View.GONE);
        draft.setAlpha(1f);
        showDraftButtons(null);
        followDraft();
    }

    /**
     * Nothing pops in: a new row rises a little and fades in. withLayer() draws it once into a
     * GPU texture for the length of the animation, so only the texture moves (smooth on a watch).
     */
    private void glideIn(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(px(14));
        v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.6f)).withLayer().start();
    }

    /** A whole list arriving at once (first load): one soft fade, not a row-by-row flicker. */
    private void fadeList(View list) {
        list.setAlpha(0f);
        list.animate().alpha(1f).setDuration(260).withLayer().start();
    }

    /**
     * The page's preview picture and title under a message with a link. Starts hidden and slides
     * open once the page answers, so a link without a preview leaves no gap.
     */
    private View linkCard(String url, int maxW) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.BUBBLE);
        bg.setCornerRadius(px(12));
        card.setBackground(bg);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = px(4);
        card.setLayoutParams(cl);
        card.setVisibility(View.GONE);
        card.setTag(url);
        LinkPreview.load(url, c -> {
            if (!url.equals(card.getTag())) return;
            if (c.hasImage()) {
                ImageView img = new ImageView(this);
                img.setAdjustViewBounds(true);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setMaxHeight((int) (H * (watch ? 0.36f : 0.3f)));
                img.setMinimumHeight(px(watch ? 60 : 90));
                card.addView(img, new LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT));
                boolean gif = c.image.toLowerCase().contains(".gif");
                Images.load(img, c.image, maxW, false, gif);
            }
            if (!c.title.isEmpty() && !LinkPreview.isImageUrl(url)) {
                TextView title = label(c.title, sp(watch ? 12 : 14), INK, true);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setPadding(px(8), px(5), px(8), 0);
                card.addView(title);
            }
            if (!c.site.isEmpty() && !LinkPreview.isImageUrl(url)) {
                TextView site = label(c.site, sp(watch ? 10.5f : 12), GREY, false);
                site.setSingleLine(true);
                site.setEllipsize(TextUtils.TruncateAt.END);
                site.setPadding(px(8), px(1), px(8), px(6));
                card.addView(site);
            }
            card.setVisibility(View.VISIBLE);
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(200).start();
        });
        card.setOnClickListener(v -> {
            if (watch) { // no browser on a watch: show the picture big
                Object img = card.getChildCount() > 0 ? card.getChildAt(0) : null;
                if (img instanceof ImageView) {
                    LinkPreview.load(url, c -> openMedia(new Feed.Media(
                            c.image.toLowerCase().contains(".gif") ? Feed.Media.GIF : Feed.Media.IMAGE, c.image, c.image)));
                }
            } else {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (android.content.ActivityNotFoundException ignored) {
                    // no browser installed
                }
            }
        });
        Press.attach(card);
        return card;
    }

    private View mediaView(Feed.Media media, int maxW) {
        FrameLayout box = new FrameLayout(this);
        // as wide as the picture: a tall screenshot is narrower, never boxed with empty space beside it
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = px(4);
        box.setLayoutParams(bl);
        ImageView img = new ImageView(this);
        img.setAdjustViewBounds(true);
        img.setMaxHeight((int) (H * (watch ? 0.45f : 0.4f)));
        img.setMaxWidth(maxW);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setMinimumHeight(px(watch ? 60 : 90));
        img.setMinimumWidth(px(watch ? 90 : 120)); // the placeholder while it loads
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(Palette.PLACEHOLDER);
        ph.setCornerRadius(px(12));
        img.setBackground(ph);
        img.setClipToOutline(true);
        box.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (photosOn()) {
            if (media.kind == Feed.Media.VIDEO && media.preview.isEmpty()) {
                Images.loadVideoFrame(img, media.url, maxW); // no still from GroupMe: take one from the video
            } else {
                String still = media.kind == Feed.Media.VIDEO ? media.preview
                        : Feed.sized(media.preview, watch ? "preview" : "large");
                Images.load(img, still, maxW, false, media.kind == Feed.Media.GIF);
            }
        } else {
            TextView off = label(media.kind == Feed.Media.VIDEO ? "Video" : media.kind == Feed.Media.GIF ? "GIF" : "Photo",
                    sp(11), GREY, true);
            off.setGravity(Gravity.CENTER);
            box.addView(off, new FrameLayout.LayoutParams(maxW, px(60)));
        }
        if (media.kind == Feed.Media.VIDEO) {
            ImageView play = new ImageView(this);
            play.setImageDrawable(new Icons(Icons.PLAY, Color.WHITE, 0xAA000000));
            int s = px(watch ? 32 : 44);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(s, s);
            pl.gravity = Gravity.CENTER;
            box.addView(play, pl);
        }
        box.setOnClickListener(v -> openMedia(media));
        Press.attach(box);
        return box;
    }

    // ---------------- group members ----------------

    private void buildMembers() {
        membersLayer = new FrameLayout(this);
        membersLayer.setBackgroundColor(BG);
        memberList = new ListView(this);
        cardList(memberList);
        memberList.setVerticalScrollBarEnabled(false);
        memberList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        int side = watch ? (int) (W * 0.09f) : px(12);
        memberList.setPadding(side, watch ? (int) (H * 0.1f) : px(8), side, watch ? (int) (H * 0.3f) : px(96));
        memberList.setClipToPadding(false);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, 0, 0, px(6));
        TextView title = label("Members", sp(watch ? 16 : 21), INK, true);
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        membersStatus = label("", sp(watch ? 10.5f : 12), GREY, false);
        membersStatus.setGravity(Gravity.CENTER);
        header.addView(membersStatus);
        // the group's details, and its topics (side chats), above the people
        membersTop = new LinearLayout(this);
        membersTop.setOrientation(LinearLayout.VERTICAL);
        membersTop.setPadding(0, px(6), 0, 0);
        header.addView(membersTop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        memberList.addHeaderView(header, null, false);

        memberAdapter = new MemberAdapter();
        memberList.setAdapter(memberAdapter);
        memberList.setOnItemClickListener((parent, v, pos, id) -> {
            Feed.Member m = memberAt(pos);
            if (m == null) return;
            if (picking) togglePick(m);
            else openProfile(m, 2);
        });
        memberList.setOnItemLongClickListener((parent, v, pos, id) -> {
            Feed.Member m = memberAt(pos);
            if (m == null || m.userId.equals(Store.myId(this))) return true;
            if (!picking) {
                picking = true;
                buzz(30);
            }
            togglePick(m);
            return true;
        });
        if (watch) curve(memberList);
        membersLayer.addView(memberList, match());

        tagButton = new Button(this);

        tagButton.setGravity(Gravity.CENTER);
        tagButton.setAllCaps(false);
        tagButton.setTextSize(sp(watch ? 13 : 15));
        tagButton.setTypeface(BOLD);
        tagButton.setTextColor(Color.WHITE);
        tagButton.setBackground(Press.button(this, true));

        tagButton.setPadding(px(8), 0, px(8), 0);
        tagButton.setStateListAnimator(null);
        tagButton.setSingleLine(true);
        tagButton.setOnClickListener(v -> tagPickedAndSpeak());
        Press.attach(tagButton);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(watch ? (int) (W * 0.62f) : ViewGroup.LayoutParams.MATCH_PARENT,
                px(watch ? 42 : 52));
        tl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tl.bottomMargin = watch ? (int) (H * 0.06f) : px(20);
        tl.leftMargin = tl.rightMargin = watch ? 0 : px(16);
        tagButton.setVisibility(View.GONE);
        membersLayer.addView(tagButton, tl);
        root.addView(membersLayer, match());
    }

    private Feed.Member memberAt(int pos) {
        int i = pos - memberList.getHeaderViewsCount();
        return i >= 0 && i < members.size() ? members.get(i) : null;
    }

    private LinearLayout membersTop;

    /** A card on the members page that leads somewhere (Group info, a topic). */
    private void membersCard(String text, String sub, Runnable tap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(pressable());
        liftRow(row);
        row.setPadding(px(14), px(8), px(14), px(9));
        TextView t = label(text, sp(watch ? 13 : 15), INK, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);
        if (!sub.isEmpty()) {
            TextView st = label(sub, sp(watch ? 10.5f : 12), GREY, false);
            st.setSingleLine(true);
            st.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(st);
        }
        row.setOnClickListener(v -> tap.run());
        Press.attach(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(5);
        membersTop.addView(row, lp);
    }

    private void fillMembersTop(List<Feed.Convo> topics) {
        membersTop.removeAllViews();
        membersCard("Group info", "Description, members, link", this::openGroupInfo);
        if (!topics.isEmpty()) {
            TextView h = label("TOPICS  \u00B7  " + topics.size(), sp(watch ? 10.5f : 12), GREY, true);
            h.setLetterSpacing(0.08f);
            h.setGravity(watch ? Gravity.CENTER : Gravity.START);
            h.setPadding(px(6), px(12), px(6), px(2));
            membersTop.addView(h);
            for (Feed.Convo t : topics) membersCard("# " + t.name, t.preview, () -> openChat(t));
        }
    }

    private void openMembers() {
        if (current == null || current.isDm) return;
        prepareMembers();
        go(2, true);
    }

    private void prepareMembers() {
        final Feed.Convo c = current;
        if (c == null || c.isDm) return;
        endPicking();
        if (!membersFor.equals(c.id)) {
            members.clear();
            memberAdapter.notifyDataSetChanged();
            fillMembersTop(new ArrayList<>());
        }
        membersStatus.setText(members.isEmpty() ? "Loading..." : hint());
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                List<Feed.Member> fresh = Feed.members(token, c.id);
                List<Feed.Convo> topics;
                try {
                    topics = Feed.topics(token, c.id);
                } catch (IOException e) {
                    topics = new ArrayList<>();
                }
                final List<Feed.Convo> tp = topics;
                ui.post(() -> {
                    if (current == null || !current.id.equals(c.id)) return;
                    fillMembersTop(tp);
                    membersFor = c.id;
                    if (members.isEmpty() && !fresh.isEmpty()) fadeList(memberList);
                    members.clear();
                    members.addAll(fresh);
                    memberAdapter.notifyDataSetChanged();
                    membersStatus.setText(hint());
                });
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> membersStatus.setText("Couldn't reach GroupMe"));
            }
        });
    }

    private String hint() {
        return members.size() + (members.size() == 1 ? " person" : " people") + (watch ? "\nTap: profile. Hold: tag" : " \u00B7 tap for a profile, hold to tag");
    }

    private void togglePick(Feed.Member m) {
        if (m.userId.equals(Store.myId(this))) return;
        if (!picked.remove(m.userId)) picked.add(m.userId);
        if (picked.isEmpty()) {
            endPicking();
            return;
        }
        memberAdapter.notifyDataSetChanged();
        int n = picked.size();
        tagButton.setText(watch ? "Tag " + n + " + speak" : "Tag " + n + (n == 1 ? " person" : " people") + " and speak");
        if (tagButton.getVisibility() != View.VISIBLE) {
            tagButton.setVisibility(View.VISIBLE);
            tagButton.setAlpha(0f);
            tagButton.setTranslationY(px(30));
            tagButton.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
        }
    }

    private void endPicking() {
        picking = false;
        picked.clear();
        if (memberAdapter != null) memberAdapter.notifyDataSetChanged();
        if (tagButton != null && tagButton.getVisibility() == View.VISIBLE) {
            tagButton.animate().alpha(0f).translationY(px(30)).setDuration(180)
                    .withEndAction(() -> tagButton.setVisibility(View.GONE)).start();
        }
    }

    /** Back to the chat with the chosen people tagged, already listening. */
    private void tagPickedAndSpeak() {
        tagging.clear();
        for (Feed.Member m : members) if (picked.contains(m.userId)) tagging.add(m);
        // keep the order they were picked in
        List<String> order = new ArrayList<>(picked);
        tagging.sort((a, b) -> Integer.compare(order.indexOf(a.userId), order.indexOf(b.userId)));
        endPicking();
        speakWithTags();
    }

    private void speakWithTags() {
        go(1, false);
        refreshTagRow();
        ui.postDelayed(this::startVoice, 340); // after the slide back
    }

    /**
     * The people being tagged, as a single row of small faces under the chat name. When there
     * are more than fit, the last spot says how many more ("+3").
     */
    private void refreshTagRow() {
        if (tagRow == null) return;
        tagRow.removeAllViews();
        if (tagging.isEmpty()) {
            if (tagRow.getVisibility() == View.VISIBLE) {
                tagRow.animate().cancel();
                tagRow.animate().alpha(0f).setDuration(160).withEndAction(() -> tagRow.setVisibility(View.GONE)).start();
            }
            return;
        }
        int size = px(watch ? 16 : 20), gap = px(3);
        int room = (int) (W * (watch ? 0.58f : 0.6f));
        int fit = Math.max(1, (room + gap) / (size + gap));
        int show = tagging.size() <= fit ? tagging.size() : fit - 1;
        StringBuilder said = new StringBuilder("Tagging ");
        for (int i = 0; i < tagging.size(); i++) said.append(i == 0 ? "" : ", ").append(tagging.get(i).name);
        tagRow.setContentDescription(said);
        for (int i = 0; i < show; i++) {
            Feed.Member m = tagging.get(i);
            Avatar face = new Avatar(watch ? 16 : 20);
            face.set(m.name, m.avatarUrl, true);
            face.view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(size, size);
            if (i > 0) fl.leftMargin = gap;
            tagRow.addView(face.view, fl);
        }
        if (show < tagging.size()) {
            TextView more = label("+" + (tagging.size() - show), sp(watch ? 9 : 10.5f), INK, true);
            more.setGravity(Gravity.CENTER);
            GradientDrawable o = new GradientDrawable();
            o.setShape(GradientDrawable.OVAL);
            o.setColor(Palette.AVATAR);
            more.setBackground(o);
            more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(size, size);
            fl.leftMargin = gap;
            tagRow.addView(more, fl);
        }
        if (tagRow.getVisibility() != View.VISIBLE) {
            tagRow.setVisibility(View.VISIBLE);
            tagRow.setAlpha(0f);
            tagRow.animate().alpha(1f).setDuration(200).start();
        }
    }

    private final class MemberAdapter extends BaseAdapter {
        @Override public int getCount() { return members.size(); }

        @Override public Object getItem(int i) { return members.get(i); }

        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            MemberRow r;
            if (v == null) {
                r = new MemberRow();
                v = r.build();
            } else {
                r = (MemberRow) v.getTag();
            }
            Feed.Member m = members.get(i);
            boolean me = m.userId.equals(Store.myId(HomeActivity.this));
            r.name.setText(me ? m.name + " (you)" : m.name);
            r.role.setVisibility(View.GONE); // the heading above says it
            int rk = rank(m);
            boolean first = i == 0 || rank(members.get(i - 1)) != rk;
            r.section.setVisibility(first ? View.VISIBLE : View.GONE);
            if (first) r.section.setText(sectionTitle(rk));
            r.avatar.set(m.name, m.avatarUrl, true);
            boolean on = picked.contains(m.userId);
            r.check.setVisibility(picking && !me ? View.VISIBLE : View.GONE);
            ((GradientDrawable) r.check.getBackground()).setColor(on ? BLUE : Color.TRANSPARENT);
            r.check.setText(on ? "\u2713" : "");
            ((View) r.name.getParent().getParent()).setBackground(on ? card(Palette.BADGE, BLUE) : card(Palette.ROW, Palette.ROW_EDGE)); // the row, not its heading
            return v;
        }
    }

    private final class MemberRow {
        TextView name, role, check, section;
        Avatar avatar;

        View build() {
            LinearLayout row = new LinearLayout(HomeActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(watch ? 6 : 10), px(watch ? 6 : 9), px(watch ? 6 : 10), px(watch ? 6 : 9));
            liftRow(row);
            avatar = new Avatar(watch ? 34 : 44);
            row.addView(avatar.view);
            LinearLayout text = new LinearLayout(HomeActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(px(watch ? 8 : 12), 0, px(4), 0);
            name = label("", sp(watch ? 13.5f : 16), INK, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            role = label("", sp(watch ? 10.5f : 12), BLUE, false);
            text.addView(name);
            text.addView(role);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            check = label("", sp(watch ? 11 : 13), Color.WHITE, true);
            check.setGravity(Gravity.CENTER);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setStroke(px(2), BLUE);
            check.setBackground(ring);
            int cs = px(watch ? 20 : 24);
            row.addView(check, new LinearLayout.LayoutParams(cs, cs));
            // a heading above the first person of each rank: Owner, Admins, Members
            LinearLayout box = new LinearLayout(HomeActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            section = label("", sp(watch ? 10.5f : 12), GREY, true);
            section.setAllCaps(true);
            section.setLetterSpacing(0.08f);
            section.setGravity(watch ? Gravity.CENTER : Gravity.START);
            section.setPadding(px(watch ? 6 : 10), px(watch ? 10 : 14), px(6), px(2));
            box.addView(section);
            box.addView(row);
            box.setTag(this);
            return box;
        }
    }

    /** Owner first, then admins, then everyone else (the list arrives sorted that way). */
    static int rank(Feed.Member m) {
        return "Owner".equals(m.role) ? 0 : "Admin".equals(m.role) ? 1 : 2;
    }

    /** "Owner", "Admins · 3", "Members · 253" for the heading above rank {@code r}. */
    private String sectionTitle(int r) {
        int n = 0;
        for (Feed.Member m : members) if (rank(m) == r) n++;
        String what = r == 0 ? (n == 1 ? "Owner" : "Owners") : r == 1 ? (n == 1 ? "Admin" : "Admins") : "Members";
        return r == 0 && n == 1 ? what : what + "  ·  " + n;
    }

    // ---------------- gallery: the group's photos and videos ----------------

    private void buildGallery() {
        galleryLayer = new FrameLayout(this);
        galleryLayer.setBackgroundColor(watch ? Color.BLACK : BG); // watch: photos edge to edge
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, watch ? 0 : px(10), 0, 0);
        TextView title = label("Gallery", sp(watch ? 16 : 21), INK, true);
        title.setGravity(Gravity.CENTER);
        galleryStatus = label("", sp(watch ? 12 : 12), watch ? 0xFFB8C2CC : GREY, false);
        galleryStatus.setGravity(Gravity.CENTER);
        if (!watch) {
            col.addView(title);
            col.addView(galleryStatus);
        }
        galleryGrid = new android.widget.GridView(this);
        final int cols = watch ? 2 : 3; // four big photos on a watch screen at once
        galleryGrid.setNumColumns(cols);
        int gap = watch ? 2 : px(3);
        galleryGrid.setHorizontalSpacing(gap);
        galleryGrid.setVerticalSpacing(gap);
        galleryGrid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        galleryGrid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        galleryGrid.setVerticalScrollBarEnabled(false);
        int side = watch ? 0 : px(6);
        galleryGrid.setPadding(side, watch ? 0 : px(6), side, watch ? 0 : px(24));
        galleryGrid.setClipToPadding(false);
        photoAdapter = new BaseAdapter() {
            @Override public int getCount() { return photos.size(); }

            @Override public Object getItem(int i) { return photos.get(i); }

            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                int cell = (galleryGrid.getWidth() - galleryGrid.getPaddingLeft() - galleryGrid.getPaddingRight() - (cols - 1) * gap) / cols;
                if (cell <= 0) cell = px(60);
                FrameLayout box;
                ImageView img;
                ImageView play;
                Avatar who;
                if (v == null) {
                    box = new FrameLayout(HomeActivity.this);
                    img = new ImageView(HomeActivity.this);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    GradientDrawable ph = new GradientDrawable();
                    ph.setColor(Palette.PLACEHOLDER);
                    ph.setCornerRadius(watch ? 0 : px(2));
                    img.setBackground(ph);
                    img.setClipToOutline(true);
                    box.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    play = new ImageView(HomeActivity.this);
                    play.setImageDrawable(new Icons(Icons.PLAY, Color.WHITE, 0xAA000000));
                    int ps = px(watch ? 38 : 22);
                    FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ps, ps);
                    pl.gravity = Gravity.CENTER;
                    box.addView(play, pl);
                    // who posted it: a small picture in a corner, ringed so it shows on any photo
                    who = new Avatar(watch ? 22 : 20);
                    GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setStroke(px(1.5f), Color.WHITE);
                    who.view.setForeground(ring);
                    who.view.setElevation(px(3));
                    box.addView(who.view, new FrameLayout.LayoutParams(who.size, who.size));
                    box.setTag(new Object[]{img, play, who});
                } else {
                    box = (FrameLayout) v;
                    Object[] t = (Object[]) box.getTag();
                    img = (ImageView) t[0];
                    play = (ImageView) t[1];
                    who = (Avatar) t[2];
                }
                box.setLayoutParams(new android.widget.AbsListView.LayoutParams(cell, cell));
                Feed.Media m = photos.get(i);
                play.setVisibility(m.kind == Feed.Media.VIDEO ? View.VISIBLE : View.GONE);
                // Watch: the corner toward the middle of the round screen (left column: bottom
                // right, right column: bottom left), where it never falls off the edge.
                // Phone: bottom left on every tile.
                FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) who.view.getLayoutParams();
                boolean leftColumn = i % cols == 0;
                wl.gravity = Gravity.BOTTOM | (watch && leftColumn ? Gravity.END : Gravity.START);
                wl.leftMargin = wl.rightMargin = wl.bottomMargin = px(watch ? 6 : 4);
                who.view.setLayoutParams(wl);
                who.view.setVisibility(m.from.isEmpty() ? View.GONE : View.VISIBLE);
                who.set(m.from, posterPicture(m), true);
                if (m.kind == Feed.Media.VIDEO && m.preview.isEmpty()) Images.loadVideoFrame(img, m.url, cell);
                else Images.load(img, m.kind == Feed.Media.VIDEO ? m.preview : Feed.sized(m.preview, watch ? "large" : "preview"), cell, false);
                return box;
            }
        };
        galleryGrid.setAdapter(photoAdapter);
        galleryGrid.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos >= 0 && pos < photos.size()) openMedia(photos.get(pos));
        });
        col.addView(galleryGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        galleryLayer.addView(col, match());
        if (watch) { // no title bar on a watch: loading / empty notes float over the black
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.gravity = Gravity.CENTER;
            galleryLayer.addView(galleryStatus, sl);
        }
        root.addView(galleryLayer, match());
    }

    private void openGallery() {
        if (current == null || current.isDm) return;
        prepareGallery();
        go(5, true);
    }

    private void prepareGallery() {
        final Feed.Convo c = current;
        if (c == null || c.isDm) return;
        if (!galleryFor.equals(c.id)) {
            photos.clear();
            photoAdapter.notifyDataSetChanged();
        }
        galleryStatus.setText(photos.isEmpty() ? "Loading..." : galleryHint());
        if (watch) galleryStatus.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
        if (!photosOn()) {
            galleryStatus.setVisibility(View.VISIBLE);
            galleryStatus.setText("Photos are off in settings");
            return;
        }
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                List<Feed.Media> fresh = Feed.gallery(token, c.id, watch ? 45 : 90);
                ui.post(() -> {
                    if (current == null || !current.id.equals(c.id)) return;
                    galleryFor = c.id;
                    if (photos.isEmpty() && !fresh.isEmpty()) fadeList(galleryGrid);
                    photos.clear();
                    photos.addAll(fresh);
                    photoAdapter.notifyDataSetChanged();
                    galleryStatus.setText(fresh.isEmpty() ? "No photos or videos yet" : galleryHint());
                    if (watch) galleryStatus.setVisibility(fresh.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> {
                    galleryStatus.setText("Couldn't load the gallery");
                    galleryStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private String galleryHint() {
        return photos.size() + " recent" + (watch ? "" : " \u00B7 swipe left for events");
    }

    // ---------------- events: the group's calendar ----------------

    private void buildEvents() {
        eventsLayer = new FrameLayout(this);
        eventsLayer.setBackgroundColor(BG);
        eventList = new ListView(this);
        cardList(eventList);
        eventList.setVerticalScrollBarEnabled(false);
        eventList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        int side = watch ? (int) (W * 0.1f) : px(12);
        eventList.setPadding(side, watch ? (int) (H * 0.08f) : px(10), side, watch ? (int) (H * 0.22f) : px(24));
        eventList.setClipToPadding(false);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, 0, 0, px(6));
        TextView title = label("Events", sp(watch ? 16 : 21), INK, true);
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        eventsStatus = label("", sp(watch ? 10.5f : 12), GREY, false);
        eventsStatus.setGravity(Gravity.CENTER);
        header.addView(eventsStatus);
        eventList.addHeaderView(header, null, false);
        eventAdapter = new BaseAdapter() {
            @Override public int getCount() { return events.size(); }

            @Override public Object getItem(int i) { return events.get(i); }

            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                Feed.Event e = events.get(i);
                boolean past = Math.max(e.startMs, e.endMs) < System.currentTimeMillis();
                LinearLayout card = new LinearLayout(HomeActivity.this); // the event's photo on top, if it has one
                card.setOrientation(LinearLayout.VERTICAL);
                card.setAlpha(past ? 0.55f : 1f);
                String photo = !e.imageUrl.isEmpty() ? e.imageUrl : e.albumCover;
                if (!photo.isEmpty() && photosOn()) {
                    ImageView pic = new ImageView(HomeActivity.this);
                    pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    GradientDrawable ph = new GradientDrawable();
                    ph.setColor(Palette.PLACEHOLDER);
                    float r = px(watch ? 22 : 18);
                    ph.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                    pic.setBackground(ph);
                    pic.setClipToOutline(true);
                    int bh = px(watch ? 70 : 110);
                    card.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh));
                    Images.load(pic, Feed.sized(photo, "preview"), (int) (W * 0.8f), false);
                }
                LinearLayout row = new LinearLayout(HomeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px(6), px(watch ? 6 : 9), px(6), px(watch ? 6 : 9));
                card.addView(row);
                // a little calendar page: month over day
                LinearLayout badge = new LinearLayout(HomeActivity.this);
                badge.setOrientation(LinearLayout.VERTICAL);
                badge.setGravity(Gravity.CENTER);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Palette.BADGE);
                bg.setCornerRadius(px(10));
                badge.setBackground(bg);
                TextView mon = label(fmt(e.startMs, "MMM").toUpperCase(java.util.Locale.US), sp(watch ? 9 : 10.5f), BLUE, true);
                TextView day = label(fmt(e.startMs, "d"), sp(watch ? 15 : 18), INK, true);
                mon.setGravity(Gravity.CENTER);
                day.setGravity(Gravity.CENTER);
                badge.addView(mon);
                badge.addView(day);
                int bs = px(watch ? 40 : 50);
                row.addView(badge, new LinearLayout.LayoutParams(bs, bs));
                LinearLayout text = new LinearLayout(HomeActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(px(watch ? 8 : 12), 0, 0, 0);
                TextView name = label(e.name, sp(watch ? 13 : 16), INK, true);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(name);
                TextView when = label(eventWhen(e) + (e.place.isEmpty() ? "" : " \u00B7 " + e.place), sp(watch ? 10.5f : 12.5f), GREY, false);
                when.setMaxLines(2);
                when.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(when);
                if (e.going > 0) text.addView(label(e.going + " going", sp(watch ? 10 : 12), BLUE, false));
                row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Feed.Member maker = eventPeople.get(e.creatorId);
                if (maker != null) { // who made it
                    Avatar av = new Avatar(watch ? 22 : 28);
                    av.set(maker.name, maker.avatarUrl, true);
                    av.view.setContentDescription("Made by " + maker.name);
                    LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(av.size, av.size);
                    al.leftMargin = px(6);
                    row.addView(av.view, al);
                }
                card.setBackground(pressable());
                card.setClipToOutline(true);
                liftRow(card);
                return card;
            }
        };
        eventList.setAdapter(eventAdapter);
        eventList.setOnItemClickListener((parent, v, pos, id) -> {
            int i = pos - eventList.getHeaderViewsCount();
            if (i >= 0 && i < events.size()) openEvent(events.get(i), 6);
        });
        if (watch) curve(eventList);
        eventsLayer.addView(eventList, match());
        root.addView(eventsLayer, match());
    }

    /** The current group's people by id, for event creators' pictures. */
    private final java.util.HashMap<String, Feed.Member> eventPeople = new java.util.HashMap<>();

    private void openEvents() {
        if (current == null || current.isDm) return;
        prepareEvents();
        go(6, true);
    }

    private void prepareEvents() {
        final Feed.Convo c = current;
        if (c == null || c.isDm) return;
        if (!eventsFor.equals(c.id)) {
            events.clear();
            eventAdapter.notifyDataSetChanged();
        }
        eventsStatus.setText(events.isEmpty() ? "Loading..." : eventsHint());
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                List<Feed.Event> fresh = Feed.events(token, c.id, System.currentTimeMillis());
                java.util.HashMap<String, Feed.Member> people = new java.util.HashMap<>();
                try { // who made each event: their picture beside it
                    for (Feed.Member m : Feed.members(token, c.id)) people.put(m.userId, m);
                } catch (IOException ignored) {
                    // the events still show, just without the creators' pictures
                }
                ui.post(() -> {
                    eventPeople.clear();
                    eventPeople.putAll(people);
                    if (current == null || !current.id.equals(c.id)) return;
                    eventsFor = c.id;
                    if (events.isEmpty() && !fresh.isEmpty()) fadeList(eventList);
                    events.clear();
                    events.addAll(fresh);
                    eventAdapter.notifyDataSetChanged();
                    eventsStatus.setText(fresh.isEmpty() ? "No events in this group" : eventsHint());
                });
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> eventsStatus.setText("Couldn't load events"));
            }
        });
    }

    private String eventsHint() {
        int up = 0;
        long now = System.currentTimeMillis();
        for (Feed.Event e : events) if (Math.max(e.startMs, e.endMs) >= now) up++;
        return up == 0 ? "Nothing coming up" : up + " coming up";
    }

    private static String fmt(long ms, String pattern) {
        if (ms <= 0) return "";
        return new java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(new java.util.Date(ms));
    }

    /** "Sat, Nov 30 \u00B7 6:00 PM" (or "all day"), in this device's time zone. */
    private static String eventWhen(Feed.Event e) {
        if (e.startMs <= 0) return "";
        return fmt(e.startMs, "EEE, MMM d") + " \u00B7 " + (e.allDay ? "all day" : fmt(e.startMs, "h:mm a"));
    }

    /** One event, full: when, where, who's going, and its description. */
    /** From an event message in the chat: the same details page the Events tab opens; Back returns to the chat. */
    private void openEventById(String eventId) {
        if (current == null || current.isDm || settling()) return;
        if (eventsFor.equals(current.id)) {
            for (Feed.Event e : events) if (e.id.equals(eventId)) { openEvent(e, 1); return; }
        }
        final String gid = current.id, token = Store.token(this);
        status("Opening event");
        bg.execute(() -> {
            try {
                Feed.Event e = Feed.event(token, gid, eventId);
                ui.post(() -> {
                    if (level != 1 || current == null || !current.id.equals(gid)) return; // moved on
                    if (e == null) status("That event was removed");
                    else openEvent(e, 1);
                });
            } catch (Api.AuthException x) {
                ui.post(this::signedOut);
            } catch (IOException x) {
                ui.post(() -> status("Couldn't reach GroupMe"));
            }
        });
    }

    private void openEvent(Feed.Event e, int from) {
        profileFrom = from;
        profileLayer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = watch ? (int) (W * 0.15f) : px(28);
        col.setPadding(side, watch ? (int) (H * 0.12f) : px(40), side, watch ? (int) (H * 0.2f) : px(32));
        String photo = !e.imageUrl.isEmpty() ? e.imageUrl : e.albumCover;
        if (!photo.isEmpty() && photosOn()) { // the event's photo, across the top
            ImageView pic = new ImageView(this);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(Palette.PLACEHOLDER);
            ph.setCornerRadius(px(16));
            pic.setBackground(ph);
            pic.setClipToOutline(true);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(watch ? 96 : 170));
            pp.bottomMargin = px(10);
            col.addView(pic, pp);
            Images.load(pic, Feed.sized(photo, "large"), (int) W, false);
        }
        TextView name = label(e.name, sp(watch ? 15 : 20), INK, true);
        name.setGravity(Gravity.CENTER);
        col.addView(name);
        Feed.Member maker = eventPeople.get(e.creatorId);
        if (maker != null) { // "[picture] Made by Sam"
            LinearLayout by = new LinearLayout(this);
            by.setOrientation(LinearLayout.HORIZONTAL);
            by.setGravity(Gravity.CENTER);
            by.setPadding(0, px(5), 0, 0);
            Avatar av = new Avatar(watch ? 18 : 22);
            av.set(maker.name, maker.avatarUrl, true);
            by.addView(av.view);
            TextView t = label("Made by " + maker.name, sp(watch ? 10.5f : 13), GREY, false);
            t.setPadding(px(6), 0, 0, 0);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            by.addView(t);
            col.addView(by);
        }
        String when = eventWhen(e);
        if (!e.allDay && e.endMs > e.startMs) when += " \u2013 " + fmt(e.endMs, "h:mm a");
        TextView w = label(when, sp(watch ? 11.5f : 14), BLUE, true);
        w.setGravity(Gravity.CENTER);
        w.setPadding(0, px(6), 0, 0);
        col.addView(w);
        if (!e.place.isEmpty()) {
            TextView pl = label(e.place, sp(watch ? 11 : 13.5f), GREY, false);
            pl.setGravity(Gravity.CENTER);
            col.addView(pl);
        }
        if (e.going > 0) {
            TextView g = label(e.going + " going", sp(watch ? 11 : 13.5f), GREY, false);
            g.setGravity(Gravity.CENTER);
            col.addView(g);
        }
        if (!e.description.isEmpty()) {
            TextView d = label(e.description, sp(watch ? 12 : 15), INK, false);
            d.setGravity(watch ? Gravity.CENTER : Gravity.START);
            d.setPadding(0, px(10), 0, 0);
            col.addView(d);
        }
        if (!e.albumCover.isEmpty() && !e.albumCover.equals(photo) && photosOn()) { // its photo album
            TextView at = label(e.albumTitle.isEmpty() ? "Photos" : "Photos \u00B7 " + e.albumTitle, sp(watch ? 11 : 13), GREY, true);
            at.setGravity(Gravity.CENTER);
            at.setPadding(0, px(12), 0, px(4));
            col.addView(at);
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(Palette.PLACEHOLDER);
            ph.setCornerRadius(px(14));
            cover.setBackground(ph);
            cover.setClipToOutline(true);
            col.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(watch ? 90 : 160)));
            Images.load(cover, Feed.sized(e.albumCover, "large"), (int) W, false);
        }
        sv.addView(col);
        profileLayer.addView(sv, match());
        go(3, true);
    }

    // ---------------- watch button: talk with one press ----------------

    /**
     * Apps can't claim a hardware button themselves: the watch owner picks one in the watch's
     * Settings. This page says where, for the watch in hand, and opens Settings.
     */
    private void openButtonHelp() {
        closeSettings();
        profileFrom = 0;
        profileLayer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding((int) (W * 0.14f), (int) (H * 0.12f), (int) (W * 0.14f), (int) (H * 0.2f));
        TextView t = label("Talk with a button", sp(15), INK, true);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        String make = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.US);
        String steps;
        if (make.contains("samsung")) {
            steps = "Settings \u2192 Advanced features \u2192 Customize keys \u2192 Double press \u2192 Say So."
                    + "\n\nThen a double press of the Home key starts talking.";
        } else if (make.contains("google")) {
            steps = "The Pixel Watch side button only opens Google's assistant, not other apps."
                    + "\n\nSay \u201CHey Google, open Say So\u201D and it starts listening.";
        } else {
            steps = "Look in Settings for Buttons, Keys or Gestures, and choose Say So for a button."
                    + "\n\nIf your watch has no such setting, say \u201CHey Google, open Say So\u201D.";
        }
        TextView b = label("", sp(12), GREY, false);
        b.setText(brandColored(steps));
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, px(8), 0, px(4));
        col.addView(b);
        buttonNote = label("", sp(10.5f), BLUE, false);
        buttonNote.setGravity(Gravity.CENTER);
        buttonNote.setPadding(0, 0, 0, px(8));
        fillButtonNote();
        col.addView(buttonNote);
        addProfileButton(col, "Open watch Settings", true, () -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (android.content.ActivityNotFoundException e) {
                status("Couldn't open Settings");
            }
        });
        sv.addView(col);
        profileLayer.addView(sv, match());
        go(3, true);
    }

    private TextView buttonNote;

    /** "Say So" picked out in the app's blue wherever it appears in a block of text. */
    private CharSequence brandColored(String s) {
        android.text.SpannableString out = new android.text.SpannableString(s);
        for (int i = s.indexOf("Say So"); i >= 0; i = s.indexOf("Say So", i + 6)) {
            out.setSpan(new android.text.style.ForegroundColorSpan(BLUE), i, i + 6, 0);
            out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + 6, 0);
        }
        return out;
    }

    /**
     * Which chat the button opens: the default group as it's named right now (a rename in GroupMe
     * shows once the list refreshes), redone whenever the default changes.
     */
    private void fillButtonNote() {
        if (buttonNote == null) return;
        Phrase.Group dg = Store.defaultGroup(this);
        String name = null;
        if (dg != null) {
            name = dg.name;
            for (Feed.Convo c : convos) if (!c.isDm && c.id.equals(dg.id) && !c.name.isEmpty()) name = c.name;
        }
        String text = name == null ? "Tip: set a default group and the button opens its chat, listening."
                : "It opens " + name + ", listening.";
        if (text.contentEquals(buttonNote.getText())) return;
        if (buttonNote.getText().length() == 0) {
            buttonNote.setText(text);
            return;
        }
        buttonNote.animate().cancel();
        buttonNote.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            buttonNote.setText(text);
            buttonNote.animate().alpha(1f).setDuration(180).start();
        }).start();
    }

    /** A watch's extra (programmable) buttons start talking while Say So is open. */
    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (watch && (code == android.view.KeyEvent.KEYCODE_STEM_1 || code == android.view.KeyEvent.KEYCODE_STEM_2
                || code == android.view.KeyEvent.KEYCODE_STEM_3) && e.getRepeatCount() == 0) {
            if (level != 1) {
                Feed.Convo c = pickConvo();
                if (c == null) return super.onKeyDown(code, e);
                openChat(c);
                ui.postDelayed(this::startVoice, 340);
            } else if (listening && rec != null) {
                rec.stopListening(); // press again to finish
            } else {
                startVoice();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------------- a person's profile ----------------

    private void buildProfile() {
        profileLayer = new FrameLayout(this);
        profileLayer.setBackgroundColor(Palette.SURFACE);
        root.addView(profileLayer, match());
    }

    /** {@code from}: 2 when opened from the member list, 1 from a direct message. */
    private void openProfile(Feed.Member m, int from) {
        prepareProfile(m, from);
        go(3, true);
    }

    /** The member behind a message: with their group role when the member list is loaded. */
    private Feed.Member memberFor(Feed.Msg m) {
        if (current != null && membersFor.equals(current.id)) {
            for (Feed.Member x : members) if (x.userId.equals(m.senderId)) return x;
        }
        return new Feed.Member(m.senderId, m.sender, m.avatarUrl, "");
    }

    private void prepareProfile(Feed.Member m, int from) {
        profileFrom = from;
        profileLayer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = watch ? (int) (W * 0.15f) : px(28);
        col.setPadding(side, watch ? (int) (H * 0.1f) : px(40), side, watch ? (int) (H * 0.16f) : px(32));

        Avatar big = new Avatar(watch ? 70 : 110);
        big.setLive(m.name, m.avatarUrl, true);
        if (!m.avatarUrl.isEmpty()) {
            big.view.setOnClickListener(v -> openPicture(m.name, m.avatarUrl));
            Press.attach(big.view);
        }
        col.addView(big.view);
        boolean me = m.userId.equals(Store.myId(this));
        TextView name = label(me ? m.name + " (you)" : m.name, sp(watch ? 16 : 21), INK, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, px(8), 0, 0);
        col.addView(name);
        if (!m.role.isEmpty()) { // Owner / Admin, as a small badge
            TextView badge = label(m.role, sp(watch ? 10 : 11.5f), BLUE, true);
            badge.setAllCaps(true);
            badge.setLetterSpacing(0.08f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Palette.BADGE);
            bg.setCornerRadius(px(10));
            bg.setStroke(Math.max(1, px(0.75f)), BLUE);
            badge.setBackground(bg);
            badge.setPadding(px(9), px(2), px(9), px(3));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = px(4);
            col.addView(badge, bl);
        }
        if (!m.accountName.isEmpty()) { // the name on their GroupMe account, when this group knows them by another
            TextView acct = label("GroupMe name: " + m.accountName, sp(watch ? 10.5f : 12.5f), GREY, false);
            acct.setGravity(Gravity.CENTER);
            acct.setMaxLines(2);
            acct.setEllipsize(TextUtils.TruncateAt.END);
            acct.setPadding(0, px(3), 0, 0);
            col.addView(acct);
        }
        String about = current == null ? "" : current.isDm ? "Direct message" : "In " + current.name;
        TextView sub = label(about, sp(watch ? 11 : 13), GREY, false);
        sub.setGravity(Gravity.CENTER);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, px(2), 0, px(10));
        col.addView(sub);

        if (!me) {
            if (current != null && !current.isDm) {
                addProfileButton(col, "Message", true, () -> {
                    tagging.clear();
                    refreshTagRow();
                    openChat(new Feed.Convo(m.userId, m.name, m.avatarUrl, true, 0, ""));
                });
                addProfileButton(col, "Tag in a message", false, () -> {
                    tagging.clear();
                    tagging.add(m);
                    speakWithTags();
                });
            } else {
                addProfileButton(col, "Back to chat", true, () -> go(1, false));
            }
        }
        if (current != null && !current.isDm) profileExtras(col, m);
        sv.addView(col);
        profileLayer.addView(sv, match());
    }

    /** The swipeable photo row on a profile: sideways drags on it scroll it, not the page. */
    private android.widget.HorizontalScrollView photoStrip;
    private boolean stripTouch;

    /** Which profile is showing, so late answers from GroupMe land only on the right one. */
    private String profileShowing = "";
    /** Groups' member ids, fetched for "Groups you share" (phone), kept for 10 minutes. */
    private static final java.util.Map<String, java.util.Set<String>> GROUP_PEOPLE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long groupPeopleAt;

    /**
     * Below the buttons: when they last posted here, their photos from this group, and (phone)
     * the other groups you're both in. Each part appears when it's known; nothing is a guess.
     */
    private void profileExtras(LinearLayout col, Feed.Member m) {
        final Feed.Convo c = current;
        final String key = c.id + ":" + m.userId;
        profileShowing = key;
        TextView last = label("", sp(watch ? 11 : 13), GREY, false);
        last.setGravity(Gravity.CENTER);
        last.setPadding(0, px(12), 0, 0);
        last.setVisibility(View.GONE);
        col.addView(last);
        LinearLayout photoBox = new LinearLayout(this);
        photoBox.setOrientation(LinearLayout.VERTICAL);
        photoBox.setVisibility(View.GONE);
        col.addView(photoBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout groupBox = new LinearLayout(this);
        groupBox.setOrientation(LinearLayout.VERTICAL);
        groupBox.setVisibility(View.GONE);
        col.addView(groupBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // last posted: the chat already on screen first, then the group's last 100 messages
        long seen = 0;
        for (Feed.Msg x : msgs) if (x.senderId.equals(m.userId)) seen = Math.max(seen, x.at);
        if (seen > 0) showLastPosted(last, seen);
        final long known = seen;
        final List<Feed.Media> theirs = new ArrayList<>();
        if (c.id.equals(galleryFor)) for (Feed.Media x : photos) if (x.fromId.equals(m.userId)) theirs.add(x);
        if (!theirs.isEmpty()) showTheirPhotos(photoBox, theirs, c.name);
        final boolean havePhotos = c.id.equals(galleryFor);
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                if (known == 0) {
                    long at = 0;
                    for (Feed.Msg x : Feed.messages(token, c, 100)) if (x.senderId.equals(m.userId)) at = Math.max(at, x.at);
                    final long found = at;
                    ui.post(() -> {
                        if (!profileShowing.equals(key)) return;
                        if (found > 0) showLastPosted(last, found);
                        else {
                            last.setText("No posts in the last 100 messages");
                            last.setVisibility(View.VISIBLE);
                        }
                    });
                }
                if (!havePhotos) {
                    List<Feed.Media> all = Feed.gallery(token, c.id, 100);
                    List<Feed.Media> mine = new ArrayList<>();
                    for (Feed.Media x : all) if (x.fromId.equals(m.userId)) mine.add(x);
                    ui.post(() -> { if (profileShowing.equals(key) && !mine.isEmpty()) showTheirPhotos(photoBox, mine, c.name); });
                }
                if (!watch && !m.userId.equals(Store.myId(this))) { // groups you share: phone only (one look per group)
                    if (System.currentTimeMillis() - groupPeopleAt > 10 * 60 * 1000L) {
                        GROUP_PEOPLE.clear();
                        groupPeopleAt = System.currentTimeMillis();
                    }
                    List<String> shared = new ArrayList<>();
                    int looked = 0;
                    for (Feed.Convo g : new ArrayList<>(convos)) {
                        if (g.isDm || looked >= 40) continue;
                        looked++;
                        java.util.Set<String> ids = GROUP_PEOPLE.get(g.id);
                        if (ids == null) {
                            ids = new java.util.HashSet<>();
                            for (Feed.Member x : Feed.members(token, g.id)) ids.add(x.userId);
                            GROUP_PEOPLE.put(g.id, ids);
                        }
                        if (ids.contains(m.userId)) shared.add(g.name);
                    }
                    ui.post(() -> { if (profileShowing.equals(key)) showSharedGroups(groupBox, shared); });
                }
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException ignored) {
                // offline: the profile simply shows less
            }
        });
    }

    private void showLastPosted(TextView last, long atSec) {
        long now = System.currentTimeMillis();
        String ago = Feed.ago(atSec, now);                 // "now", "13m", "5h", "Mon", "Nov 4"
        String when = Feed.stamp(atSec, now);              // "5:34 PM", "Yesterday 5:34 PM", "Nov 4, 5:34 PM"
        boolean recent = ago.equals("now") || ago.matches("\\d+[mh]");
        // "how long ago" goes on its own line, whole: never "1h" on one line and "ago" on the next
        last.setText("Last posted " + when.replace("Yesterday", "yesterday")
                + (recent ? "\n" + (ago.equals("now") ? "just now" : ago.replace(" ", "\u00A0") + "\u00A0ago") : ""));
        if (last.getVisibility() != View.VISIBLE) {
            last.setVisibility(View.VISIBLE);
            last.setAlpha(0f);
            last.animate().alpha(1f).setDuration(200).start();
        }
    }

    /** Their photos from this group, in a row you can swipe; tap one to see it full screen. */
    private void showTheirPhotos(LinearLayout box, List<Feed.Media> list, String groupName) {
        box.removeAllViews();
        TextView t = label("Photos in " + groupName + "  \u00B7  " + list.size(), sp(watch ? 10.5f : 12), GREY, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, px(14), 0, px(6));
        box.addView(t);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        photoStrip = hs;
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int sz = px(watch ? 64 : 84);
        for (int i = 0; i < Math.min(list.size(), 12); i++) {
            Feed.Media x = list.get(i);
            FrameLayout cell = new FrameLayout(this);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(Palette.PLACEHOLDER);
            ph.setCornerRadius(px(12));
            img.setBackground(ph);
            img.setClipToOutline(true);
            cell.addView(img, new FrameLayout.LayoutParams(sz, sz));
            if (x.kind == Feed.Media.VIDEO) {
                ImageView play = new ImageView(this);
                play.setImageDrawable(new Icons(Icons.PLAY, Color.WHITE, 0xAA000000));
                FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(px(24), px(24));
                pl.gravity = Gravity.CENTER;
                cell.addView(play, pl);
                if (x.preview.isEmpty()) Images.loadVideoFrame(img, x.url, sz);
                else Images.load(img, x.preview, sz, false);
            } else {
                Images.load(img, Feed.sized(x.preview, "preview"), sz, false);
            }
            cell.setOnClickListener(v -> openMedia(x));
            Press.attach(cell);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(sz, sz);
            if (i > 0) cl.leftMargin = px(6);
            row.addView(cell, cl);
        }
        hs.addView(row);
        box.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) hs.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        box.setVisibility(View.VISIBLE);
        box.setAlpha(0f);
        box.animate().alpha(1f).setDuration(220).start();
    }

    /** Phone: the other groups you're both in, as small names. */
    private void showSharedGroups(LinearLayout box, List<String> names) {
        box.removeAllViews();
        List<String> others = new ArrayList<>(names);
        if (current != null) others.remove(current.name);
        TextView t = label("Groups you share  \u00B7  " + others.size(), sp(12), GREY, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(14), 0, px(6));
        box.addView(t);
        if (others.isEmpty()) {
            TextView none = label("Just this one", sp(13), GREY, false);
            none.setGravity(Gravity.CENTER);
            box.addView(none);
        }
        for (String n : others) {
            TextView g = label(n, sp(14), INK, false);
            g.setGravity(Gravity.CENTER);
            g.setSingleLine(true);
            g.setEllipsize(TextUtils.TruncateAt.END);
            g.setBackground(card(Palette.ROW, Palette.ROW_EDGE));
            g.setPadding(px(12), px(8), px(12), px(8));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gl.topMargin = px(4);
            box.addView(g, gl);
        }
        box.setVisibility(View.VISIBLE);
        box.setAlpha(0f);
        box.animate().alpha(1f).setDuration(220).start();
    }

    private void addProfileButton(LinearLayout col, String text, boolean primary, Runnable tap) {
        Button b = new Button(this);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(false);
        b.setText(text);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextSize(sp(watch ? 13 : 15));
        b.setTypeface(BOLD);
        b.setTextColor(primary ? Color.WHITE : BLUE);
        b.setBackground(Press.button(this, primary));

        b.setPadding(px(8), 0, px(8), 0);
        b.setStateListAnimator(null);
        b.setOnClickListener(v -> tap.run());
        Press.attach(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(watch ? 40 : 48));
        lp.topMargin = px(5);
        col.addView(b, lp);
    }

    private void buzz(int ms) {
        android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
        if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
    }

    // ---------------- full-screen media ----------------

    private void buildMedia() {
        mediaLayer = new FrameLayout(this);
        mediaLayer.setBackgroundColor(Color.BLACK);
        root.addView(mediaLayer, match());
    }

    /** The zoomable picture on the media page (null for a video). */
    private ZoomImageView zoomer;

    private void openMedia(Feed.Media media) {
        stopVideo();
        mediaLayer.removeAllViews();
        zoomer = null;
        if (media.kind == Feed.Media.VIDEO) {
            VideoView vv = new VideoView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            mediaLayer.addView(vv, lp);
            bg.execute(() -> { // GroupMe's upload links redirect to a signed address: start from that
                String real = Images.finalUrl(media.url);
                ui.post(() -> { if (playing == vv) vv.setVideoURI(Uri.parse(real)); });
            });
            vv.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                vv.start();
            });
            vv.setOnClickListener(v -> {
                if (vv.isPlaying()) vv.pause();
                else vv.start();
            });
            playing = vv;
        } else {
            zoomer = new ZoomImageView(this);
            mediaLayer.addView(zoomer, match());
            String full = media.kind == Feed.Media.GIF ? media.url : Feed.sized(media.url, "large");
            // decoded at up to twice the screen so it stays sharp when zoomed in
            Images.load(zoomer.image, full, Math.min(Math.max(W, H) * 2, 2048), false, media.kind == Feed.Media.GIF);
        }
        View card = mediaCaption(media);
        if (card != null && zoomer != null) { // zoomed in: the caption steps out of the way
            zoomer.setListener(z -> showCard(card, !z));
        }
        if (card != null) {
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.gravity = Gravity.BOTTOM;
            mediaLayer.addView(card, cl);
            // tap the picture to hide or bring back who posted it (videos: tap plays and pauses)
            View pic = mediaLayer.getChildAt(0);
            if (media.kind != Feed.Media.VIDEO) {
                pic.setOnClickListener(v -> showCard(card, card.getVisibility() != View.VISIBLE || card.getAlpha() < 0.5f));
            }
            // tap who posted it, when, or what they wrote: go to that exact spot in the chat
            if (!media.msgId.isEmpty() && current != null && !current.isDm) card.setOnClickListener(v -> jumpTo(media.msgId));
        }
        TextView hint = label(zoomer != null ? (watch ? "Double-tap to zoom" : "Pinch or double-tap to zoom")
                : "Swipe right to go back", sp(10), 0xAAFFFFFF, false);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hl.topMargin = watch ? (int) (H * 0.07f) : px(18);
        mediaLayer.addView(hint, hl);
        hint.animate().alpha(0f).setStartDelay(2500).setDuration(600).start();
        if (level != 4) mediaFrom = level;
        go(4, true);
    }

    /** Fades the caption in or out. Once it's gone it takes no touches (so it can't be tapped unseen). */
    private void showCard(View card, boolean show) {
        card.animate().cancel();
        if (show) {
            card.setVisibility(View.VISIBLE);
            card.animate().alpha(1f).setDuration(200).start();
        } else {
            card.animate().alpha(0f).setDuration(200).withEndAction(() -> card.setVisibility(View.INVISIBLE)).start();
        }
    }

    /**
     * Over a full-screen photo or video: who posted it (picture and name), how long ago, and the
     * words they sent with it, on a dark fade so it reads over any picture. Null if nothing's known.
     */
    private View mediaCaption(Feed.Media m) {
        if (m.from.isEmpty() && m.caption.isEmpty()) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(watch ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        box.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x99000000, 0x00000000}));
        int side = watch ? (int) (W * 0.17f) : px(16);
        box.setPadding(side, px(watch ? 28 : 36), side, watch ? (int) (H * 0.09f) : px(28));

        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        // Their picture: the gallery feed sometimes leaves it out, so fall back to the chat or the
        // members list, and to their initials if there's none at all.
        Avatar av = new Avatar(watch ? 20 : 28);
        av.set(m.from, posterPicture(m), true);
        who.addView(av.view);
        TextView name = label(m.from, sp(watch ? 11 : 13), 0xFF8FDCFF, true); // the poster: bold, light blue
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth((int) (W * (watch ? 0.4f : 0.5f)));
        name.setPadding(px(7), 0, 0, 0);
        name.setShadowLayer(px(3), 0, px(1), 0x99000000);
        who.addView(name);
        if (m.at > 0) {
            TextView when = label("  \u00B7  " + Feed.ago(m.at, System.currentTimeMillis()), sp(watch ? 10 : 12), 0xB3FFFFFF, false);
            when.setSingleLine(true);
            who.addView(when);
        }
        box.addView(who);
        if (!m.caption.isEmpty()) {
            TextView cap = label(m.caption, sp(watch ? 12 : 15), 0xFFFFFFFF, false); // what they said: plain, larger
            cap.setGravity(watch ? Gravity.CENTER : Gravity.START);
            cap.setLineSpacing(0, 1.05f);
            cap.setShadowLayer(px(3), 0, px(1), 0x99000000);
            cap.setMaxLines(watch ? 3 : 6);
            cap.setEllipsize(TextUtils.TruncateAt.END);
            cap.setPadding(0, px(4), 0, 0);
            box.addView(cap);
        }
        return box;
    }

    /**
     * The mic's face: round and domed, brightest just above the middle and curving darker to the
     * edge, like a rounded button that rises in the center. Pressed, the dome dims.
     */
    private android.graphics.drawable.Drawable domed() {
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(70);
        s.setExitFadeDuration(160);
        s.addState(new int[]{android.R.attr.state_pressed}, dome(0xFF33C2F4, 0xFF0096D6, 0xFF006FA3));
        s.addState(new int[]{}, dome(0xFF6BDBFF, 0xFF00AFF0, 0xFF0080BA));
        return s;
    }

    private GradientDrawable dome(int top, int mid, int edge) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{top, mid, edge});
        g.setGradientCenter(0.5f, 0.36f);
        g.setGradientRadius(px(watch ? 46 : 60) * 0.62f);
        return g;
    }

    /**
     * A person's small picture in a chat: round, and playing if it's an animated GIF. (The chat
     * list keeps chat pictures still.)
     */
    private void loadFace(ImageView v, String url, int size) {
        if (url != null && url.toLowerCase(java.util.Locale.US).contains(".gif")) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View x, android.graphics.Outline o) { o.setOval(0, 0, x.getWidth(), x.getHeight()); }
            });
            v.setClipToOutline(true);
            Images.load(v, url, size * 2, false, true);
        } else {
            Images.load(v, Feed.sized(url, "avatar"), size, true);
        }
    }

    /** Someone's profile picture full screen (zoomable), with their name over the bottom. */
    private void openPicture(String name, String url) {
        if (url == null || url.isEmpty()) return;
        boolean gif = url.toLowerCase(java.util.Locale.US).contains(".gif"); // an animated picture plays
        Feed.Media x = new Feed.Media(gif ? Feed.Media.GIF : Feed.Media.IMAGE, url, url);
        x.from = name == null ? "" : name;
        x.fromAvatar = url;
        openMedia(x);
    }

    /** The poster's picture for a photo or video: from the media, its chat message, or the members list. */
    private String posterPicture(Feed.Media m) {
        if (!m.fromAvatar.isEmpty()) return m.fromAvatar;
        int i = m.msgId.isEmpty() ? -1 : indexOf(m.msgId);
        if (i >= 0 && !msgs.get(i).avatarUrl.isEmpty()) return msgs.get(i).avatarUrl;
        for (Feed.Msg x : msgs) if (x.sender.equals(m.from) && !x.avatarUrl.isEmpty()) return x.avatarUrl;
        for (Feed.Member x : members) if (x.name.equals(m.from) && !x.avatarUrl.isEmpty()) return x.avatarUrl;
        return "";
    }

    private void stopVideo() {
        if (playing != null) {
            playing.stopPlayback();
            playing = null;
        }
    }

    // ---------------- moving between screens ----------------

    private View layer(int n) {
        return n == 0 ? homeLayer : n == 1 ? chatLayer : n == 2 ? membersLayer : n == 3 ? profileLayer
                : n == 5 ? galleryLayer : n == 6 ? eventsLayer : mediaLayer;
    }

    /** Slide to {@code to}: deeper screens come in from the right, going back slides them away. */
    /** When the last slide started: a second move (or a tap) right on its heels is the same gesture. */
    private long navAt;
    private static final long SETTLE_MS = 380;

    private boolean settling() { return android.os.SystemClock.uptimeMillis() - navAt < SETTLE_MS; }

    private void go(int to, boolean forward) {
        if (to == level) return;
        navAt = android.os.SystemClock.uptimeMillis();
        View out = layer(level), in = layer(to);
        if (instant) { // opened by voice: the chat is simply there
            instant = false;
            out.animate().cancel();
            in.animate().cancel();
            out.setVisibility(View.GONE);
            in.setVisibility(View.VISIBLE);
            in.setTranslationX(0);
            in.setAlpha(1f);
            in.setScaleX(1f);
            in.setScaleY(1f);
            in.bringToFront();
            level = to;
            navAt = 0;
            return;
        }
        int dir = forward ? 1 : -1;
        in.setVisibility(View.VISIBLE);
        in.setTranslationX(dir * W * (forward ? 1f : 0.35f));
        in.setAlpha(forward ? 1f : 0f);
        in.setScaleX(forward ? 1f : 0.94f);
        in.setScaleY(forward ? 1f : 0.94f);
        in.bringToFront();
        if (!forward) out.bringToFront();
        in.animate().translationX(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.6f)).withLayer().start();
        out.animate().translationX(-dir * W * (forward ? 0.35f : 1f)).alpha(forward ? 0f : 1f)
                .scaleX(forward ? 0.94f : 1f).scaleY(forward ? 0.94f : 1f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.6f)).withLayer()
                .withEndAction(() -> {
                    out.setVisibility(View.GONE);
                    out.setTranslationX(0);
                    out.setAlpha(1f);
                    out.setScaleX(1f);
                    out.setScaleY(1f);
                }).start();
        leave(level, to);
        level = to;
        arrive(to);
    }

    /** What leaving a page means: the mic stops, a video stops, picking ends. */
    private void leave(int from, int to) {
        if (from == 1) saveChatPosition(); // still on the chat: remember where you were
        if (from == 1 && to == 0) cancelVoice();
        if (from == 4) stopVideo();
        if (from == 2 && picking) endPicking();
    }

    private void arrive(int to) {
        if (to == 0) PushService.visibleKey = null;
        if (to == 0) {
            stopLive();
            startListLive();
            ui.removeCallbacks(chatPoll);
            newSince = ""; // the "New messages" line is worked out again next time
            tagging.clear();
            refreshTagRow();
            refreshList();
        }
    }

    /**
     * The carousel: the page a left swipe brings in. A group's pages go round in a loop
     * (chat, members, gallery, events, then the chat again); a DM loops chat and profile.
     * -1 when there's nothing that way.
     */
    private int nextOf(int at) {
        switch (at) {
            case 0: return pickConvo() != null ? 1 : -1;
            case 1: return current == null ? -1 : current.isDm ? 3 : 2;
            case 2: return picking ? -1 : 5;
            case 5: return 6;
            case 6: return 1;
            case 3: return profileFrom == 1 ? 1 : -1;
            default: return -1;
        }
    }

    /** The page a right swipe brings back. */
    private int prevOf(int at) {
        switch (at) {
            case 1: return 0;
            case 2: return picking ? -1 : 1;
            case 5: return 2;
            case 6: return 5;
            case 3: return profileFrom;
            case 4: return mediaFrom;
            default: return -1;
        }
    }

    /** The chat a left swipe from the list opens: the last one, else the newest, else the default group. */
    private Feed.Convo pickConvo() {
        Feed.Convo c = current;
        if (c == null && !convos.isEmpty()) c = convos.get(0);
        if (c == null) {
            Phrase.Group dg = Store.defaultGroup(this);
            if (dg != null) c = new Feed.Convo(dg.id, dg.name, "", false, 0, "");
        }
        return c;
    }

    /** Gets page {@code to} ready before it slides in (forward moves only; back pages already exist). */
    private void prepare(int to) {
        if (to == 1 && level == 0) {
            if (settingsOpen) closeSettings();
            prepareChat(pickConvo());
        } else if (to == 2) {
            prepareMembers();
        } else if (to == 5) {
            prepareGallery();
        } else if (to == 6) {
            prepareEvents();
        } else if (to == 3 && level == 1 && current != null) {
            prepareProfile(new Feed.Member(current.id, current.name, current.avatarUrl, ""), 1);
        }
    }

    private void forward() {
        if (settling()) return;
        navAt = android.os.SystemClock.uptimeMillis();
        int to = nextOf(level);
        if (to < 0) return;
        prepare(to);
        go(to, true);
    }

    private void back() {
        // A swipe from the watch's edge is also the system's Back: both arrive for one swipe.
        if (settling()) return;
        navAt = android.os.SystemClock.uptimeMillis();
        if (menu != null) {
            closeChoices();
        } else if (reactCard != null) {
            closeReactions();
        } else if (settingsOpen) {
            closeSettings();
        } else if (level == 6) {
            go(5, false);
        } else if (level == 5) {
            go(2, false);
        } else if (level == 4) {
            stopVideo();
            go(mediaFrom, false);
        } else if (level == 3) {
            go(profileFrom, false);
        } else if (level == 2) {
            if (picking) endPicking();
            else go(1, false);
        } else if (level == 1) {
            cancelVoice();
            go(0, false);
        } else if (settingsOpen) {
            closeSettings();
        } else {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    public void onBackPressed() { back(); }

    // ---------------- gestures: sideways to move, pull down for settings ----------------

    private float padX, padY;
    private long padAt;

    /**
     * A laptop touchpad (or mouse wheel) sends scrolls, not touches. Two fingers sideways turn
     * the page like a swipe; two fingers down at the top of a page open settings, and up at the
     * bottom of settings close it. Lists still scroll with two fingers as usual.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_SCROLL || !e.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
                || reactCard != null || zoomer != null && level == 4 && zoomer.zoomed()) {
            return super.dispatchGenericMotionEvent(e);
        }
        float h = e.getAxisValue(MotionEvent.AXIS_HSCROLL), v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
        long now = android.os.SystemClock.uptimeMillis();
        if (now - padAt > 350) padX = padY = 0; // a new gesture
        padAt = now;
        boolean busy = settling() || now - navAt < 450 || dragging || pulling;
        if (Math.abs(h) > Math.abs(v)) {
            padX += h;
            if (!busy && !settingsOpen && Math.abs(padX) >= 1.2f) {
                boolean fwd = padX > 0;
                padX = 0;
                navAt = now;
                startDrag(fwd ? -1 : 1);
                endDrag(fwd ? -W : W, 0); // the same slide a finger swipe makes
            }
            return true;
        }
        if (v > 0 && !settingsOpen && !scrollableToward(1) && !(level == 1 && (listening || pendingSend != null))) {
            padY += v;
            if (!busy && padY >= 2f) {
                padY = 0;
                navAt = now;
                under = null;
                openSettings();
            }
            return true;
        }
        if (v < 0 && settingsOpen && !settings.canScrollVertically(1)) {
            padY += v;
            if (!busy && padY <= -2f) {
                padY = 0;
                navAt = now;
                closeSettings();
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private float downX, downY;
    private boolean pulling, closing, swallow, zoomTouch;
    private boolean dragging, dragFwd;
    private int dragTo = -1;

    /** Moves the current page (and the one coming in beside it) with the finger. */
    private void dragTo(float dx) {
        dx = dragFwd ? Math.min(0, dx) : Math.max(0, dx);
        View cur = layer(level);
        if (dragTo < 0) { // nothing that way: a little give, then it springs back
            cur.setTranslationX(dx * 0.22f);
            return;
        }
        View in = layer(dragTo);
        cur.setTranslationX(dx);
        in.setTranslationX(dx + (dragFwd ? W : -W));
        float p = Math.min(1f, Math.abs(dx) / W);
        cur.setScaleX(1f - 0.05f * p);
        cur.setScaleY(1f - 0.05f * p);
        in.setScaleX(0.95f + 0.05f * p);
        in.setScaleY(0.95f + 0.05f * p);
    }

    /** Whether this page's list can still move the way the finger went (if so, the list has the drag). */
    private boolean scrollableToward(float dy) {
        View v = settingsOpen ? settings : level == 0 ? list : level == 1 ? chatList : level == 2 ? memberList
                : level == 5 ? galleryGrid : level == 6 ? eventList
                : level == 3 && profileLayer.getChildCount() > 0 ? profileLayer.getChildAt(0) : null;
        if (v == null) return false;
        return v.canScrollVertically(dy < 0 ? 1 : -1);
    }

    private void startDrag(float dx) {
        dragging = true;
        layer(level).setLayerType(View.LAYER_TYPE_HARDWARE, null); // the page moves as one GPU texture
        dragFwd = dx < 0;
        dragTo = dragFwd ? nextOf(level) : prevOf(level);
        if (dragTo == level) dragTo = -1;
        if (dragTo >= 0) {
            if (dragFwd) prepare(dragTo);
            View in = layer(dragTo);
            in.animate().cancel();
            in.setAlpha(1f);
            in.setVisibility(View.VISIBLE);
            in.setTranslationX(dragFwd ? W : -W);
            in.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            in.bringToFront();
        }
    }

    private void endDrag(float dx, float vx) {
        dragging = false;
        final View cur = layer(level);
        final int to = dragTo;
        ui.postDelayed(() -> { // back to normal drawing once the pages settle
            for (int n : new int[]{0, 1, 2, 3, 4, 5, 6}) layer(n).setLayerType(View.LAYER_TYPE_NONE, null);
        }, 320);
        boolean far = dragFwd ? dx < -W * 0.3f || vx < -900 : dx > W * 0.3f || vx > 900;
        if (to < 0) {
            cur.animate().translationX(0).setDuration(220).setInterpolator(new OvershootInterpolator(1.4f)).start();
            if (!dragFwd && far) { // a firm right swipe with nowhere to go back to
                if (level == 2 && picking) endPicking();
                else if (level == 0 && settingsOpen) closeSettings();
                else if (level == 0) {
                    finish();
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }
            }
            return;
        }
        final View in = layer(to);
        long ms = 240;
        if (far) {
            navAt = android.os.SystemClock.uptimeMillis();
            cur.animate().translationX(dragFwd ? -W : W).scaleX(0.95f).scaleY(0.95f).setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).withLayer().withEndAction(() -> {
                        cur.setVisibility(View.GONE);
                        cur.setTranslationX(0);
                        cur.setScaleX(1f);
                        cur.setScaleY(1f);
                    }).start();
            in.animate().translationX(0).scaleX(1f).scaleY(1f).setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).withLayer().start();
            int from = level;
            leave(from, to);
            level = to;
            arrive(to);
        } else { // not far enough: both spring back
            cur.animate().translationX(0).scaleX(1f).scaleY(1f).setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            in.animate().translationX(dragFwd ? W : -W).setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(() -> {
                        in.setVisibility(View.GONE);
                        in.setTranslationX(0);
                        in.setScaleX(1f);
                        in.setScaleY(1f);
                    }).start();
        }
    }
    private VelocityTracker vt;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (menu != null) return super.dispatchTouchEvent(e); // a menu is up: it gets the touch
        // The emoji row is up: a touch on it picks, a touch anywhere else just closes it.
        if (reactCard != null || reactTouch) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                android.graphics.Rect r = new android.graphics.Rect();
                reactTouch = reactCard != null && reactCard.getGlobalVisibleRect(r) && r.contains((int) e.getRawX(), (int) e.getRawY());
                if (!reactTouch) {
                    closeReactions();
                    swallow = true;
                    return true;
                }
            }
            if (reactTouch) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) reactTouch = false;
                return super.dispatchTouchEvent(e);
            }
        }
        // A zoomed picture, or a second finger on one (a pinch), owns the touch: no page swipes.
        if (level == 4 && zoomer != null && !swallow && !settingsOpen) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) zoomTouch = zoomer.zoomed();
            if (a == MotionEvent.ACTION_POINTER_DOWN && !zoomTouch) {
                zoomTouch = true;
                if (dragging) endDrag(0, 0); // a page swipe had begun: put it back
                if (pulling) {
                    pulling = false;
                    closeSettings();
                }
            }
            if (zoomTouch) {
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) zoomTouch = false;
                return super.dispatchTouchEvent(e);
            }
        }
        if (swallow && e.getActionMasked() != MotionEvent.ACTION_DOWN) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) swallow = false;
            return true;
        }
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swallow = settling(); // a touch that lands while a screen is still sliding in does nothing
                if (swallow) return true;
                if (level == 1 && voicePanel.getVisibility() == View.VISIBLE && pendingSend == null && !listening) {
                    android.graphics.Rect r = new android.graphics.Rect();
                    voicePanel.getGlobalVisibleRect(r);
                    boolean typing = voicePanel.findFocus() instanceof EditText;
                    if (!typing && !r.contains((int) e.getRawX(), (int) e.getRawY())) cancelPanel(); // tap elsewhere: close it
                }
                downX = e.getRawX();
                downY = e.getRawY();
                pulling = closing = false;
                android.graphics.Rect sr = new android.graphics.Rect();
                stripTouch = level == 3 && photoStrip != null && photoStrip.isShown()
                        && photoStrip.getGlobalVisibleRect(sr) && sr.contains((int) e.getRawX(), (int) e.getRawY());
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                break;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                // Pulling down past the top of any page brings settings down over it.
                boolean busyTalking = level == 1 && (listening || pendingSend != null
                        || voicePanel.getVisibility() == View.VISIBLE);
                if (!pulling && !dragging && !busyTalking) {
                    boolean down = dy > slop * 1.5f && dy > Math.abs(dx) * 1.3f;
                    boolean up = -dy > slop * 1.5f && -dy > Math.abs(dx) * 1.3f;
                    if (!settingsOpen && down && !scrollableToward(dy)) {
                        under = null;
                        pulling = true;
                        closing = false;
                    } else if (settingsOpen && up && !settings.canScrollVertically(1)) {
                        pulling = true;
                        closing = true;
                    }
                    if (pulling) {
                        MotionEvent c = MotionEvent.obtain(e);
                        c.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(c);
                        c.recycle();
                    }
                }
                if (pulling) {
                    setPull(closing ? 1f + dy / H : dy / (H * 0.8f));
                    return true;
                }
                if (stripTouch && photoStrip.canScrollHorizontally(dx < 0 ? 1 : -1)) break; // the strip scrolls first
                if (!dragging && Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.3f
                        && !(settingsOpen && (level != 0 || Math.abs(dy) > Math.abs(dx)))) {
                    MotionEvent c = MotionEvent.obtain(e);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(c); // no tap or scroll under a page drag
                    c.recycle();
                    startDrag(dx);
                }
                if (dragging) {
                    dragTo(dx);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (pulling) {
                    pulling = false;
                    boolean open = closing ? dy > -H * 0.25f : dy > H * 0.22f;
                    if (open) openSettings();
                    else closeSettings();
                    if (!open && !closing && level == 0 && convos.isEmpty()) refreshList(); // a short pull also retries
                    return true;
                }
                // A finger that moved is a drag, never a tap: even when nothing scrolled (a list
                // already at its end), the press underneath gets cancelled instead of clicked.
                if (!dragging && e.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.hypot(dx, dy) > slop * 2 && !scrollableToward(dy)) {
                    MotionEvent c = MotionEvent.obtain(e);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    boolean r = super.dispatchTouchEvent(c);
                    c.recycle();
                    return r;
                }
                if (dragging) {
                    float vx = 0;
                    if (vt != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                    }
                    endDrag(e.getActionMasked() == MotionEvent.ACTION_UP ? dx : 0, vx);
                    return true;
                }
                break;
            }
            default:
                break;
        }
        return super.dispatchTouchEvent(e);
    }

    // ---------------- the mic: speak straight into this conversation ----------------

    private void startVoice() {
        if (current == null) return;
        toLatest(); // talking happens at the newest messages
        if (!history) scrollChatToEnd();
        Feed.Msg keep = replying; // a retry or a fresh listen still answers the same message
        cancelVoice();
        replying = keep;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showTyping("Voice isn't set up here. Type your message:");
            return;
        }
        hidePanel();
        showDraftButtons(null);
        showDraft(watch ? "Listening" : "Listening \u00B7 tap the mic when done");
        autoRetried = false;
        spokenSoFar = "";
        userDone = quickRetried = false;
        listenUnder = false;
        listening = true;
        micAside(true);
        beginListening();
    }

    /** Set once the speech service has the mic open for this listen. */
    private boolean ready, autoRetried;

    /**
     * A fresh recognizer each time: after a miss, Google's recognizer often refuses to start
     * again (it errors at once, before the mic even opens), which made Retry need two taps.
     */
    /** The words heard so far in this listen (partial results), kept in case it ends in an error. */
    private String heardSoFar = "";


    /** Sentences already finished in this message (you can pause between them), and how it ends. */
    private String spokenSoFar = "";
    /** Where you paused while talking: commas and periods. */
    private final Pauses pauses = new Pauses();
    private boolean userDone, quickRetried;
    /**
     * You paused, so the countdown keeps listening under it (talk again and it stops and adds your
     * words). The countdown starts that listen itself when it comes later: after going to the group
     * you named.
     */
    private boolean listenUnder;
    /** Quick retries of a listen under the countdown that failed at once (the mic hadn't let go). */
    private int underRetries;
    private long listenAt;

    private static String joinSpoken(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " " + Tidy.PAUSE + " " + b; // the speech service stopped on a pause: a new sentence
    }

    /** Ends a listen still running under a countdown (it's sending, or you cancelled). */
    private void stopListeningQuietly() {
        listenUnder = false;
        if (rec != null) rec.cancel(); // kept for the next listen: rebuilding it each time trips the speech service
        listening = false;
        spokenSoFar = "";
        heardSoFar = "";
    }

    /** When the recognizer was last thrown away; a new one right after gets "disconnected". */
    private long recDroppedAt;

    /** Throws the recognizer away (it broke); the next listen builds a new one, after a short gap. */
    private void dropRecognizer() {
        if (rec == null) return;
        rec.cancel();
        rec.destroy();
        rec = null;
        recDroppedAt = android.os.SystemClock.elapsedRealtime();
    }

    /** After an error: keep the recognizer unless the speech service itself went away. */
    private void afterError(int code) {
        if (code == SpeechRecognizer.ERROR_SERVER || code == 11 /* ERROR_SERVER_DISCONNECTED */) dropRecognizer();
        else if (rec != null) rec.cancel();
    }

    /** The message is complete: count down and send it (or go to the chat it names). */
    private void finishSpoken(String text) {
        listening = false;
        listenUnder = false;
        spokenSoFar = "";
        if (rec != null) rec.cancel();
        if (voiceEntry && redirect(text)) {
            // "Weekend Crew that says ..." or "DM Sam ...": going to that chat instead
        } else {
            countdown(Tidy.spoken(text));
        }
        voiceEntry = false;
    }

    /**
     * Starts a listen on the one recognizer this screen keeps (any listen still going is stopped
     * first, so two never fight over the mic). A recognizer thrown away a moment ago is replaced
     * only after a short gap: a new one right away gets "disconnected" while the old one still
     * holds the mic.
     */
    private void beginListening() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (rec == null && now - recDroppedAt < 400) {
            ui.removeCallbacks(beginLater);
            ui.postDelayed(beginLater, 400 - (now - recDroppedAt));
            return;
        }
        heardSoFar = "";
        pauses.reset();
        listenAt = now;
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new Heard());
        } else {
            rec.cancel();
        }
        ready = false;
        rec.startListening(listenIntent());
    }

    private final Runnable beginLater = () -> { if (listening) beginListening(); };

    private Intent listenIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // a breath after "Yes," isn't the end of the message: wait for a real pause
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                // punctuation and capitals from the speech service itself (Android 13+)
                .putExtra("android.speech.extra.ENABLE_FORMATTING", "quality")
                // say it how you said it: no "f***" masking
                .putExtra("android.speech.extra.PROFANITY_FILTER", false)
                .putExtra("android.speech.extra.MASK_OFFENSIVE_WORDS", false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_NOTIF) {
            boolean yes = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            Store.setPushOn(this, yes);
            PushService.sync(this);
            fillSettings();
            return;
        }
        if (req != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startVoice();
        else showTyping("The microphone is off. Type your message:");
    }

    private TextView panelTitle, panelBody;
    private View panelBar;

    private void showPanel(String title, String body, Object[] buttons) {
        voicePanel.removeAllViews();
        panelTitle = label(title, sp(watch ? 13 : 16), INK, true);
        panelTitle.setGravity(Gravity.CENTER);
        voicePanel.addView(panelTitle);
        panelBody = label(body, sp(watch ? 12 : 14), GREY, false);
        panelBody.setGravity(Gravity.CENTER);
        panelBody.setMaxLines(4);
        panelBody.setEllipsize(TextUtils.TruncateAt.END);
        voicePanel.addView(panelBody);
        panelBar = null;
        if (buttons != null) {
            LinearLayout rowB = new LinearLayout(this);
            rowB.setOrientation(LinearLayout.HORIZONTAL);
            rowB.setGravity(Gravity.CENTER);
            rowB.setPadding(0, px(8), 0, 0);
            for (int i = 0; i + 1 < buttons.length; i += 2) {
                Button b = new Button(this);
                b.setGravity(Gravity.CENTER);
                b.setAllCaps(false);
                b.setText((String) buttons[i]);
                b.setTextSize(sp(watch ? 12 : 14));
                b.setTypeface(BOLD);
                boolean primary = i == 0;
                b.setTextColor(primary ? Color.WHITE : BLUE);
                b.setBackground(Press.button(this, primary));

                b.setPadding(px(8), 0, px(8), 0);
                b.setStateListAnimator(null);
                fitButtonText(b);
                Runnable r = (Runnable) buttons[i + 1];
                b.setOnClickListener(v -> r.run());
                Press.attach(b);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, px(watch ? 36 : 44), 1f);
                bl.leftMargin = bl.rightMargin = px(3);
                rowB.addView(b, bl);
            }
            voicePanel.addView(rowB, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // A close may still be fading out (Retry closes, then reopens at once): cancel it, or its
        // end would hide this panel and you'd be listening with nothing on screen.
        voicePanel.animate().cancel();
        if (voicePanel.getVisibility() != View.VISIBLE) {
            voicePanel.setVisibility(View.VISIBLE);
            voicePanel.setAlpha(0f);
            voicePanel.setTranslationY(watch ? 0 : px(40)); // a watch backdrop just fades
        }
        voicePanel.animate().alpha(1f).translationY(0).setDuration(240)
                .setInterpolator(new OvershootInterpolator(1.2f)).withLayer().start();
        ui.removeCallbacks(panelTimeout);
        if (buttons != null && !"Type it".equals(title) && !"Message request".equals(title)) ui.postDelayed(panelTimeout, 7000); // a notice, not a trap
        micButton.animate().cancel();
        micButton.animate().alpha(0f).translationX(0).translationY(0).scaleX(0.6f).scaleY(0.6f).setDuration(180).start();
    }

    private final Runnable panelTimeout = () -> {
        if (voicePanel.getVisibility() == View.VISIBLE && pendingSend == null && !listening) cancelPanel();
    };

    private void hidePanel() {
        if (voicePanel.getVisibility() == View.VISIBLE) {
            voicePanel.animate().cancel();
            voicePanel.animate().alpha(0f).translationY(watch ? 0 : px(40)).setDuration(200)
                    .withEndAction(() -> voicePanel.setVisibility(View.GONE)).start();
        }
    }

    /** Cancel on a panel (or a tap away from it): also lets go of the message being replied to. */
    private void cancelPanel() {
        replying = null;
        hideVoice();
    }

    private void hideVoice() {
        micButton.animate().cancel();
        if (voicePanel.getVisibility() == View.VISIBLE) {
            voicePanel.animate().cancel();
            voicePanel.animate().alpha(0f).translationY(watch ? 0 : px(40)).setDuration(200)
                    .withEndAction(() -> voicePanel.setVisibility(View.GONE)).start();
        }
        micButton.animate().alpha(1f).translationX(0).translationY(0).scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private void cancelVoice() {
        if (listening && rec != null) rec.cancel();
        listening = false;
        if (pendingSend != null) {
            ui.removeCallbacks(pendingSend);
            pendingSend = null;
        }
        hideDraft();
        hideVoice();
    }

    private final class Heard implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) { ready = true; }
        @Override public void onBeginningOfSpeech() { ready = true; }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEvent(int t, Bundle b) { }
        @Override public void onEndOfSpeech() { if (listening) draftNote.setText("One moment"); }

        @Override public void onRmsChanged(float db) { // the mic breathes with your voice
            if (!listening) return;
            float s = micScale * (1f + Math.max(0f, Math.min(10f, db)) / 45f);
            micButton.setScaleX(s);
            micButton.setScaleY(s);
        }

        @Override public void onPartialResults(Bundle b) {
            String t = top(b);
            if (listening && t != null && !t.isEmpty() && pendingSend != null) {
                // talking again before it sent: stop the countdown and keep adding to the message
                ui.removeCallbacks(pendingSend);
                pendingSend = null;
                draftBar.animate().cancel();
                draftBar.setVisibility(View.INVISIBLE);
                draftNote.setText(watch ? "Listening" : "Listening \u00B7 tap the mic when done");
                showDraftButtons(null); // the mic comes back beside the chat
            }
            if (listening && t != null) {
                sendTyping(); // the chat sees you're writing
                pauses.heard(t, android.os.SystemClock.elapsedRealtime());
                t = pauses.mark(t);
                heardSoFar = t;
                // earlier sentences stay; capitals and commas as you go, so nothing jumps at the end
                typeTo(Tidy.live(joinSpoken(spokenSoFar, t)));
            }
        }

        @Override public void onResults(Bundle b) {
            if (!listening) return;
            String t = pauses.mark(top(b));
            if (t == null && !heardSoFar.isEmpty()) t = heardSoFar;
            String full = joinSpoken(spokenSoFar, t);
            if (full.isEmpty()) {
                listening = false;
                hideDraft();
                didntCatch();
                return;
            }
            if (userDone || t == null) { // you tapped the mic, or stopped talking: send it
                finishSpoken(full);
                return;
            }
            // A pause: the send countdown starts as usual, but Say So keeps listening under it.
            // Start talking again before the bar runs out and the countdown stops and your words
            // are added; stay quiet and it sends.
            listenUnder = true;
            underRetries = 0;
            if (voiceEntry) {
                voiceEntry = false; // only the first thing said can name another chat
                if (rec != null) rec.cancel();
                listening = false; // the countdown in that chat listens again
                spokenSoFar = "";
                heardSoFar = "";
                if (redirect(full)) return; // going to the chat you named; its countdown listens on
                listening = true;
            }
            spokenSoFar = full;
            heardSoFar = "";
            countdown(Tidy.spoken(full));
            ui.postDelayed(() -> { if (listening && pendingSend != null) beginListening(); }, 120);
        }

        @Override public void onError(int code) {
            android.util.Log.w("SaySo", "speech error " + code + " ready=" + ready + " heard=" + !heardSoFar.isEmpty());
            if (!listening) return;
            if (!ready && !autoRetried && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    && code != SpeechRecognizer.ERROR_NETWORK && code != SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                // refused before the mic even opened (the last listen hadn't let go): quietly
                // start once more, so one tap of the mic or Retry is enough
                autoRetried = true;
                afterError(code);
                ui.postDelayed(() -> { if (listening) beginListening(); }, 350);
                return;
            }
            if (pendingSend != null) {
                afterError(code);
                boolean atOnce = android.os.SystemClock.elapsedRealtime() - listenAt < 1500
                        && code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
                if (atOnce && listenUnder && underRetries++ < 2) { // the mic hadn't let go yet: once more
                    ui.postDelayed(() -> { if (listening && pendingSend != null) beginListening(); }, 250);
                    return;
                }
                listening = false; // still counting down: no more words came, so it simply sends
                return;
            }
            boolean nothing = heardSoFar.isEmpty() && spokenSoFar.isEmpty();
            boolean quick = android.os.SystemClock.elapsedRealtime() - listenAt < 2500;
            if (nothing && quick && !quickRetried && (code == SpeechRecognizer.ERROR_NO_MATCH
                    || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_CLIENT)) {
                // gave up before you could have said anything (the speech service wasn't ready):
                // start once more, quietly, the way tapping Retry would
                quickRetried = true;
                afterError(code);
                ui.postDelayed(() -> { if (listening) beginListening(); }, 300);
                return;
            }
            if (!nothing && code != SpeechRecognizer.ERROR_NETWORK && code != SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                // You've stopped talking (or the speech service gave up after hearing you): send
                // what you said. Never throw it away. (It still counts down, so you can cancel.)
                finishSpoken(joinSpoken(spokenSoFar, heardSoFar));
                return;
            }
            listening = false;
            hideDraft();
            afterError(code);
            if (code == SpeechRecognizer.ERROR_AUDIO) {
                showPanel("Can't hear the mic", "Another app may be using it.",
                        new Object[]{(watch ? "Retry" : "Try again"), (Runnable) HomeActivity.this::startVoice,
                                "Type it", (Runnable) () -> showTyping(null)});
            } else if (code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                showPanel("No connection", "Check your connection, then try again.",
                        new Object[]{(watch ? "Retry" : "Try again"), (Runnable) HomeActivity.this::startVoice,
                                "Cancel", (Runnable) HomeActivity.this::cancelPanel});
            } else {
                didntCatch();
            }
        }
    }

    private static String top(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() || r.get(0).trim().isEmpty() ? null : r.get(0).trim();
    }

    /**
     * The first thing said after "open Say So" can still name a chat: "Weekend Crew that says
     * hi" or "DM Sam saying hi". Returns false when it doesn't (the words go to this chat).
     */
    private boolean redirect(String heard) {
        Phrase dm = Phrase.parseDm(heard);
        Phrase said = dm != null ? dm : Phrase.parse(heard);
        if (said == null) said = Phrase.parseNamed(heard, Store.groups(this)); // "I'm late, tell Weekend Crew"
        if (said == null) return false;
        final Phrase p = said;
        if (p.text.isEmpty()) { // only a group's name: open that chat and listen for the message
            Phrase.Match m = Phrase.match(Store.groups(this), p.group);
            if (m.group == null) return false;
            if (current == null || current.isDm || !current.id.equals(m.group.id)) {
                String pic = "";
                for (Feed.Convo c : convos) if (!c.isDm && c.id.equals(m.group.id)) pic = c.avatarUrl;
                openChat(new Feed.Convo(m.group.id, m.group.name, pic, false, 0, ""));
            }
            ui.postDelayed(this::startVoice, 340);
            return true;
        }
        final boolean person = dm != null;
        draftNote.setText("Finding " + p.group);
        final String token = Store.token(this);
        bg.execute(() -> {
            Phrase.Match m;
            try {
                List<Phrase.Group> pool = person ? Store.contacts(this) : Store.groups(this);
                m = Phrase.match(pool, p.group);
                if (m == null || m.group == null) {
                    pool = person ? Api.listContacts(token) : Api.listGroups(token);
                    if (person) Store.saveContacts(this, pool);
                    else Store.saveGroups(this, pool);
                    m = Phrase.match(pool, p.group);
                }
            } catch (IOException e) {
                m = null;
            }
            final Phrase.Match found = m;
            ui.post(() -> {
                if (found == null || found.group == null) {
                    hideDraft();
                    showPanel("Couldn't find " + p.group, "\u201C" + Tidy.spoken(p.text) + "\u201D",
                            new Object[]{"Send here", (Runnable) () -> countdown(Tidy.spoken(p.text)), "Cancel", (Runnable) this::cancelPanel});
                    return;
                }
                Feed.Convo here = current;
                if (listenUnder) spokenSoFar = p.text; // more words add to the message, not the "tell X"
                if (here != null && here.isDm == person && here.id.equals(found.group.id)) {
                    countdown(Tidy.spoken(p.text));
                    return;
                }
                String pic = "";
                for (Feed.Convo c : convos) if (c.isDm == person && c.id.equals(found.group.id)) pic = c.avatarUrl;
                openChat(new Feed.Convo(found.group.id, found.group.name, pic, person, 0, ""));
                ui.postDelayed(() -> countdown(Tidy.spoken(p.text)), 340);
            });
        });
        return true;
    }

    private void didntCatch() {
        showPanel("I didn't catch that", "Nothing was sent.",
                new Object[]{(watch ? "Retry" : "Try again"), (Runnable) this::startVoice, "Type it", (Runnable) () -> showTyping(null)});
    }

    private void showTyping(String why) {
        if (current == null) return;
        EditText e = new EditText(this);
        Press.field(e);
        e.setHint(replying != null ? "Your reply" : "Message " + current.name);
        // A watch keyboard hands over the words only when you tap done, so while it's open on
        // this box, the chat is told you're typing (every 5 s, like GroupMe's apps).
        e.setOnFocusChangeListener((v, has) -> {
            ui.removeCallbacks(typingWhileFocused);
            if (has) ui.post(typingWhileFocused);
        });
        typingBox = e;
        e.addTextChangedListener(new android.text.TextWatcher() { // typing: the chat sees you're writing
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) { if (a.length() > 0) sendTyping(); }
            @Override public void afterTextChanged(android.text.Editable a) { }
        });
        e.setSingleLine(true);
        if (replying != null) why = (why != null && why.startsWith("The microphone is off") ? "Mic off. " : "")
                + "Replying to " + quoteText(replying);
        showPanel("Type it", why != null ? why : "Your message for " + current.name,
                new Object[]{"Send", (Runnable) () -> {
                    String t = e.getText().toString().trim();
                    if (!t.isEmpty()) {
                        android.view.inputmethod.InputMethodManager m = getSystemService(android.view.inputmethod.InputMethodManager.class);
                        if (m != null) m.hideSoftInputFromWindow(e.getWindowToken(), 0);
                        countdown(t);
                    }
                }, "Cancel", (Runnable) this::cancelPanel});
        voicePanel.addView(e, 2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!watch) e.requestFocus(); // a watch keyboard covers the whole screen: it opens when you tap the box
    }

    /** Shows what's about to go out, with 3 seconds to cancel, then sends it. */
    private void countdown(String text) {
        final Feed.Convo to = current;
        if (to == null) return;
        if (pendingSend != null) ui.removeCallbacks(pendingSend); // never two countdowns at once
        pendingSend = null;
        final List<Feed.Member> tags = new ArrayList<>(tagging);
        final String replyId = replying == null ? "" : replying.id;
        if (text.length() > 1000) {
            showPanel("That's too long", "GroupMe allows 1000 characters.",
                    new Object[]{(watch ? "Retry" : "Try again"), (Runnable) this::startVoice, "Cancel", (Runnable) this::cancelPanel});
            return;
        }
        hidePanel();
        if (draft.getVisibility() != View.VISIBLE) showDraft("");
        draftNames.clear();
        typeTo(Feed.withMentions(tags, text, draftNames));
        draftNote.setText("To " + to.name + " \u00B7 sending");
        draftBar.setVisibility(View.VISIBLE);
        draftBar.setScaleX(1f);
        draftBar.animate().scaleX(0f).setDuration(SEND_DELAY_MS).setInterpolator(null).start();
        showDraftButtons(new Runnable[]{() -> {
            if (pendingSend != null) ui.removeCallbacks(pendingSend);
            pendingSend = null;
            stopListeningQuietly();
            replying = null;
            hideDraft();
        }, () -> {
            if (pendingSend != null) ui.removeCallbacks(pendingSend);
            pendingSend = null;
            stopListeningQuietly();
            send(to, text, tags, replyId);
        }});
        pendingSend = () -> {
            pendingSend = null;
            stopListeningQuietly();
            send(to, text, tags, replyId);
        };
        ui.postDelayed(pendingSend, SEND_DELAY_MS);
        if (listenUnder && !listening) { // came a moment after the pause (a redirect): listen under it
            listening = true;
            ui.postDelayed(() -> { if (listening && pendingSend != null) beginListening(); }, 120);
        }
    }

    private void send(Feed.Convo to, String text, List<Feed.Member> tags, String replyId) {
        draftBar.animate().cancel();
        draftBar.setVisibility(View.INVISIBLE);
        draftNote.setText("Sending\u2026");
        draftButtons.setVisibility(View.GONE);
        final String token = Store.token(this);
        bg.execute(() -> {
            try {
                Feed.sendTagged(token, to, tags, text, replyId);
                ui.post(() -> {
                    replying = null;
                    tagging.clear();
                    refreshTagRow();
                    status(to.isDm ? "Direct message" : "Group");
                    vibrate();
                    chime(true); // sent
                    draftNote.setText("Sent");
                    handoff = 4;            // the draft becomes the real message when it arrives
                    loadMessages(false);
                });
            } catch (Api.AuthException e) {
                ui.post(this::signedOut);
            } catch (IOException e) {
                ui.post(() -> hideDraft());
                ui.post(() -> showPanel("Not sent", "Couldn't reach GroupMe.",
                        new Object[]{(watch ? "Retry" : "Try again"), (Runnable) () -> send(to, text, tags, replyId), "Cancel", (Runnable) this::cancelPanel}));
            }
        });
    }

    private void vibrate() {
        android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
        if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void signedOut() {
        Store.forgetSignIn(this);
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }

    // ---------------- small helpers ----------------

    private TextView label(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(bold ? BOLD : REGULAR);
        return t;
    }

    /** A list row as a soft rounded card with a hairline edge; it tints blue while pressed. */
    private StateListDrawable pressable() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, card(Palette.BADGE, BLUE));
        s.addState(new int[]{}, card(Palette.ROW, Palette.ROW_EDGE));
        s.setExitFadeDuration(180);
        return s;
    }

    private GradientDrawable card(int fill, int edge) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(watch ? 22 : 18));
        g.setStroke(Math.max(1, px(0.75f)), edge);
        return g;
    }

    /** Space between card rows (instead of a line), and room at the sides for their shadows. */
    private void cardList(ListView l) {
        l.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        l.setDividerHeight(px(watch ? 5 : 7));
    }

    /** A card row lifts a little off the page (not on a black watch screen, where shadows vanish). */
    private void liftRow(View row) {
        if (Palette.dark && watch) return;
        row.setElevation(px(1.5f));
        row.setOutlineAmbientShadowColor(0x66000000);
        row.setOutlineSpotShadowColor(0x66000000);
    }
}
