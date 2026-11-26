package io.github.peterchabs.sayso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class) // org.json is Android's, so it needs Robolectric
public class FeedTest {
    @Test public void groupRowShowsWhoSaidWhatAndWhen() throws Exception {
        Feed.Convo c = Feed.parseGroup(new JSONObject("{\"id\":\"42\",\"name\":\"Family\","
                + "\"image_url\":\"https://i.groupme.com/abc\","
                + "\"messages\":{\"last_message_created_at\":1700000000,"
                + "\"preview\":{\"nickname\":\"Sam\",\"text\":\"on my way\",\"attachments\":[]}}}"));
        assertEquals("42", c.id);
        assertEquals("Family", c.name);
        assertEquals("Sam: on my way", c.preview);
        assertEquals(1700000000L, c.lastAt);
        assertFalse(c.isDm);
        assertEquals("g:42", c.key());
    }

    @Test public void photoOnlyMessageSaysPhoto() throws Exception {
        Feed.Convo c = Feed.parseGroup(new JSONObject("{\"id\":\"1\",\"name\":\"G\",\"image_url\":null,"
                + "\"messages\":{\"last_message_created_at\":5,\"preview\":{\"nickname\":\"Ann\",\"text\":null,"
                + "\"attachments\":[{\"type\":\"image\",\"url\":\"https://i.groupme.com/x.jpeg\"}]}}}"));
        assertEquals("Ann: Photo", c.preview);
        assertEquals("", c.avatarUrl);
    }

    @Test public void directMessageRowUsesTheOtherPerson() throws Exception {
        Feed.Convo c = Feed.parseChat(new JSONObject("{\"other_user\":{\"id\":\"77\",\"name\":\"Jo\","
                + "\"avatar_url\":\"https://i.groupme.com/jo\"},\"last_message\":{\"created_at\":99,"
                + "\"text\":\"hey\"}}"));
        assertTrue(c.isDm);
        assertEquals("77", c.id);
        assertEquals("Jo", c.name);
        assertEquals("hey", c.preview);
        assertEquals(99, c.lastAt);
        assertEquals("dm:77", c.key());
        assertNull(Feed.parseChat(new JSONObject("{}")));
    }

    @Test public void newestConversationComesFirst() {
        List<Feed.Convo> l = new ArrayList<>();
        l.add(new Feed.Convo("a", "A", "", false, 10, ""));
        l.add(new Feed.Convo("b", "B", "", true, 30, ""));
        l.add(new Feed.Convo("c", "C", "", false, 20, ""));
        Feed.sortNewestFirst(l);
        assertEquals("b", l.get(0).id);
        assertEquals("c", l.get(1).id);
        assertEquals("a", l.get(2).id);
    }

    @Test public void messageKeepsPhotosGifsVideosAndLikes() throws Exception {
        Feed.Msg m = Feed.parseMessage(new JSONObject("{\"id\":\"m1\",\"sender_id\":\"5\",\"name\":\"Sam\","
                + "\"avatar_url\":null,\"text\":\"look\",\"created_at\":100,\"favorited_by\":[\"1\",\"2\"],"
                + "\"attachments\":[{\"type\":\"image\",\"url\":\"https://i.groupme.com/p.jpeg\"},"
                + "{\"type\":\"linked_image\",\"url\":\"https://media.giphy.com/x/giphy.gif\"},"
                + "{\"type\":\"video\",\"url\":\"https://v.groupme.com/v.mp4\",\"preview_url\":\"https://v.groupme.com/v.jpg\"},"
                + "{\"type\":\"mentions\",\"user_ids\":[\"1\"]}]}"));
        assertEquals("Sam", m.sender);
        assertEquals("5", m.senderId);
        assertEquals("", m.avatarUrl);
        assertEquals(2, m.likes);
        assertFalse(m.system);
        assertEquals(3, m.media.size());
        assertEquals(Feed.Media.IMAGE, m.media.get(0).kind);
        assertEquals(Feed.Media.GIF, m.media.get(1).kind);
        assertEquals(Feed.Media.VIDEO, m.media.get(2).kind);
        assertEquals("https://v.groupme.com/v.jpg", m.media.get(2).preview);
    }

    @Test public void videoSentAsALinkBecomesAVideo() throws Exception {
        String url = "https://m.groupme.com/uploads/a912/1920x1080.original.mp4";
        Feed.Msg m = Feed.parseMessage(new JSONObject("{\"id\":\"v\",\"sender_id\":\"1\",\"text\":\"" + url + "\","
                + "\"created_at\":1,\"attachments\":[]}"));
        assertEquals("", m.text);                 // the bare link isn't shown as text
        assertEquals(1, m.media.size());
        assertEquals(Feed.Media.VIDEO, m.media.get(0).kind);
        assertEquals(url, m.media.get(0).url);
        assertEquals("https://m.groupme.com/uploads/a912/thumbnail.jpg", m.media.get(0).preview); // GroupMe's own still
        assertEquals("Video", Feed.describe(url, null));
        assertEquals("", Feed.videoThumb("https://example.com/v.mp4"));
    }

    @Test public void mediaLinksLeaveTheTextButNotTheWords() throws Exception {
        String url = "https://m.groupme.com/uploads/abc/1920x1080.original.mp4";
        Feed.Msg m = Feed.parseMessage(new JSONObject("{\"id\":\"v\",\"sender_id\":\"1\",\"created_at\":1,"
                + "\"text\":\"check this out " + url + "\","
                + "\"attachments\":[{\"type\":\"video\",\"url\":\"" + url + "\","
                + "\"preview_url\":\"https://m.groupme.com/uploads/abc/1920x1080.thumbnail.jpg\"}]}"));
        assertEquals("check this out", m.text);
        assertEquals(1, m.media.size());
        assertEquals("https://example.com/page", Feed.withoutMediaLinks("https://example.com/page", m.media));
    }

    @Test public void systemNoticesAreMarked() throws Exception {
        Feed.Msg m = Feed.parseMessage(new JSONObject("{\"id\":\"s\",\"sender_type\":\"system\","
                + "\"text\":\"Sam joined the group\",\"created_at\":1}"));
        assertTrue(m.system);
    }

    @Test public void membersAreSortedWithTheirRoles() throws Exception {
        List<Feed.Member> m = Feed.parseMembers(new JSONObject("{\"members\":["
                + "{\"user_id\":\"2\",\"nickname\":\"zed\",\"image_url\":null,\"roles\":[\"user\"]},"
                + "{\"user_id\":\"1\",\"nickname\":\"Amy\",\"image_url\":\"https://i.groupme.com/a\",\"roles\":[\"admin\",\"owner\"]},"
                + "{\"user_id\":\"3\",\"nickname\":\"Bo\",\"roles\":[\"admin\"]}]}"));
        assertEquals(3, m.size());
        assertEquals("Amy", m.get(0).name);
        assertEquals("Owner", m.get(0).role);
        assertEquals("Admin", m.get(1).role);
        assertEquals("", m.get(2).role);
        assertEquals("", m.get(2).avatarUrl);
        assertTrue(Feed.parseMembers(null).isEmpty());
    }

    @Test public void mentionsMarkWhereEachNameSits() {
        List<Feed.Member> tags = new ArrayList<>();
        tags.add(new Feed.Member("1", "Sam", "", ""));
        tags.add(new Feed.Member("2", "Jo Ann", "", ""));
        List<int[]> loci = new ArrayList<>();
        String full = Feed.withMentions(tags, "lunch?", loci);
        assertEquals("@Sam @Jo Ann lunch?", full);
        assertEquals(0, loci.get(0)[0]);
        assertEquals(4, loci.get(0)[1]);          // "@Sam"
        assertEquals(5, loci.get(1)[0]);
        assertEquals(7, loci.get(1)[1]);          // "@Jo Ann"
        assertEquals("@Jo Ann", full.substring(loci.get(1)[0], loci.get(1)[0] + loci.get(1)[1]));
    }

    @Test public void onlyGroupMeImagesGetSized() {
        assertEquals("https://i.groupme.com/abc.avatar", Feed.sized("https://i.groupme.com/abc", "avatar"));
        assertEquals("https://i.groupme.com/abc.large", Feed.sized("https://i.groupme.com/abc.large", "preview"));
        assertEquals("https://media.giphy.com/x.gif", Feed.sized("https://media.giphy.com/x.gif", "large"));
        assertEquals("", Feed.sized(null, "avatar"));
    }

    @Test public void agoIsShortEnoughForAWatch() {
        long now = 1_700_000_000_000L;
        long s = now / 1000;
        assertEquals("", Feed.ago(0, now));
        assertEquals("now", Feed.ago(s - 20, now));
        assertEquals("5m", Feed.ago(s - 300, now));
        assertEquals("3h", Feed.ago(s - 3 * 3600, now));
        assertEquals(3, Feed.ago(s - 3 * 86400, now).length());          // "Mon"
        assertTrue(Feed.ago(s - 40L * 86400, now).matches("[A-Z][a-z]{2} \\d{1,2}")); // "Oct 5"
        assertEquals("now", Feed.ago(s + 30, now));                       // clock skew
    }

    @Test public void eventMessagesKnowTheirEvent() throws Exception {
        Feed.Msg m = Feed.parseMessage(new JSONObject("{\"id\":\"1\",\"name\":\"Sam\",\"text\":\"Sam created event 'Team breakfast'\","
                + "\"created_at\":1,\"attachments\":[{\"type\":\"event\",\"event_id\":\"abc123\",\"view\":\"full\"}]}"));
        assertEquals("abc123", m.eventId);
        assertEquals("Sam created event 'Team breakfast'", m.text);
        Feed.Msg plain = Feed.parseMessage(new JSONObject("{\"id\":\"2\",\"text\":\"hi\",\"attachments\":[]}"));
        assertEquals("", plain.eventId);
    }

    @Test public void membersListOwnerThenAdminsThenEveryoneAToZ() throws Exception {
        List<Feed.Member> ms = Feed.parseMembers(new JSONObject("{\"members\":["
                + "{\"user_id\":\"1\",\"nickname\":\"zed\",\"roles\":[\"user\"]},"
                + "{\"user_id\":\"2\",\"nickname\":\"Bea\",\"roles\":[\"admin\"]},"
                + "{\"user_id\":\"3\",\"nickname\":\"Al\",\"roles\":[\"user\"]},"
                + "{\"user_id\":\"4\",\"nickname\":\"Yan\",\"roles\":[\"owner\",\"admin\"]},"
                + "{\"user_id\":\"5\",\"nickname\":\"Ann\",\"roles\":[\"admin\"]}]}"));
        StringBuilder order = new StringBuilder();
        for (Feed.Member m : ms) order.append(m.name).append(m.role.isEmpty() ? "" : "/" + m.role).append(' ');
        assertEquals("Yan/Owner Ann/Admin Bea/Admin Al zed ", order.toString());
    }

    @Test
    public void reactionsGroupByEmojiAndPlainLikesAreHearts() throws Exception {
        org.json.JSONObject m = new org.json.JSONObject("{\"id\":\"9\",\"sender_id\":\"1\",\"name\":\"A\",\"text\":\"hi\","
                + "\"favorited_by\":[\"2\",\"3\",\"4\",\"5\"],"
                + "\"reactions\":[{\"type\":\"unicode\",\"code\":\"\uD83D\uDE02\",\"user_ids\":[\"2\",\"3\"]},"
                + "{\"type\":\"emoji\",\"pack_id\":\"2\",\"pack_index\":\"58\",\"user_ids\":[\"4\"]}],"
                + "\"attachments\":[{\"type\":\"reply\",\"reply_id\":\"7\",\"base_reply_id\":\"7\"}]}");
        Feed.Msg x = Feed.parseMessage(m);
        assertEquals("7", x.replyId);
        assertEquals(3, x.reactions.size());
        assertEquals("\uD83D\uDE02", x.reactions.get(0).code);
        assertEquals(2, x.reactions.get(0).userIds.size());
        boolean heart = false, pack = false;
        for (Feed.Reaction r : x.reactions) {
            if (r.code.equals(Feed.Reaction.HEART)) heart = r.userIds.equals(java.util.Collections.singletonList("5"));
            if (r.code.isEmpty()) pack = r.userIds.equals(java.util.Collections.singletonList("4"));
        }
        assertTrue(heart);
        assertTrue(pack);
    }

    @Test
    public void replyAndMentionsTravelTogether() throws Exception {
        java.util.List<Feed.Member> tags = new java.util.ArrayList<>();
        tags.add(new Feed.Member("42", "Sam", "", ""));
        org.json.JSONObject msg = Feed.outgoing(tags, "see you", "777");
        org.json.JSONArray a = msg.getJSONArray("attachments");
        assertEquals(2, a.length());
        assertEquals("mentions", a.getJSONObject(0).getString("type"));
        assertEquals("reply", a.getJSONObject(1).getString("type"));
        assertEquals("777", a.getJSONObject(1).getString("reply_id"));
        assertFalse(Feed.outgoing(new java.util.ArrayList<>(), "plain", "").has("attachments"));
    }

    @Test
    public void dmConversationIdPutsTheLowerIdFirst() {
        assertEquals("5+12", Feed.dmConversationId("12", "5"));
        assertEquals("5+12", Feed.dmConversationId("5", "12"));
    }

    @Test
    public void stampsSayTheActualTime() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.NOVEMBER, 25, 17, 34, 0);
        long now = c.getTimeInMillis();
        assertEquals("5:34 PM", Feed.stamp(now / 1000, now));
        c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        assertEquals("Yesterday 5:34 PM", Feed.stamp(c.getTimeInMillis() / 1000, now));
        c.add(java.util.Calendar.DAY_OF_YEAR, -3);
        assertEquals("Nov 21, 5:34 PM", Feed.stamp(c.getTimeInMillis() / 1000, now));
        c.set(2025, java.util.Calendar.MARCH, 3);
        assertEquals("Mar 3, 2025", Feed.stamp(c.getTimeInMillis() / 1000, now));
    }

    @Test
    public void eventsKnowTheirPhotoAlbumAndCreator() throws Exception {
        JSONObject r = new JSONObject("{\"events\":[{\"event_id\":\"e1\",\"name\":\"Picnic\",\"creator_id\":\"77\","
                + "\"start_at\":\"2026-12-01T18:00:00Z\",\"image_url\":\"https://i.groupme.com/1080x1080.jpeg.abc\","
                + "\"album\":{\"title\":\"Picnic pics \",\"cover_image_url\":\"https://m.groupme.com/uploads/x/1.jpeg\"}}]}");
        Feed.Event e = Feed.parseEvents(r, 0).get(0);
        assertEquals("77", e.creatorId);
        assertEquals("https://i.groupme.com/1080x1080.jpeg.abc", e.imageUrl);
        assertEquals("https://m.groupme.com/uploads/x/1.jpeg", e.albumCover);
        assertEquals("Picnic pics", e.albumTitle);
    }

    @Test
    public void membersKeepTheirAccountNameWhenTheirNicknameDiffers() throws Exception {
        JSONObject g = new JSONObject("{\"members\":[{\"user_id\":\"1\",\"nickname\":\"Egg\",\"name\":\"Aphrodite Urania\",\"roles\":[\"admin\"]},"
                + "{\"user_id\":\"2\",\"nickname\":\"Sam\",\"name\":\"sam\",\"roles\":[]}]}");
        List<Feed.Member> m = Feed.parseMembers(g);
        assertEquals("Admin", m.get(0).role);
        assertEquals("Aphrodite Urania", m.get(0).accountName);
        assertEquals("", m.get(1).accountName); // same name, different case: nothing extra to show
    }

    @Test
    public void photosKnowWhoPostedThem() throws Exception {
        JSONObject msg = new JSONObject("{\"id\":\"5\",\"sender_id\":\"77\",\"name\":\"Sam\",\"text\":\"\","
                + "\"attachments\":[{\"type\":\"image\",\"url\":\"https://i.groupme.com/100x100.jpeg.abc\"}]}");
        assertEquals("77", Feed.parseMessage(msg).media.get(0).fromId);
    }

    @Test
    public void groupsAndChatsCarryTheirCountsAndReadPlace() throws Exception {
        Feed.Convo g = Feed.parseGroup(new JSONObject("{\"id\":\"1\",\"name\":\"Crew\",\"unread_count\":3,"
                + "\"last_read_message_id\":\"170\",\"messages\":{\"count\":42,\"last_message_id\":\"173\"}}"));
        assertEquals(42, g.count);
        assertEquals("173", g.lastMsgId);
        assertEquals(3, g.unread);
        assertEquals("170", g.lastReadId);
        Feed.Convo quiet = Feed.parseGroup(new JSONObject("{\"id\":\"2\",\"name\":\"X\",\"unread_count\":null,\"messages\":{\"count\":5}}"));
        assertEquals(-1, quiet.unread);
        assertEquals("", quiet.lastReadId);
    }

    @Test
    public void newerComparesGroupMeIdsByValue() {
        assertTrue(Feed.newer("179566118384028616", "179564226018470287"));
        assertFalse(Feed.newer("99", "100"));
        assertTrue(Feed.newer("100", ""));
        assertFalse(Feed.newer("", "100"));
    }
}
