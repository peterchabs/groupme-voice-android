package io.github.peterchabs.sayso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PushTest {
    @Test public void groupMessageFromSomeoneElse() throws Exception {
        Push.Event e = Push.parse(new JSONObject("{\"channel\":\"/user/1\",\"data\":{\"type\":\"line.create\","
                + "\"subject\":{\"id\":\"m9\",\"group_id\":\"42\",\"sender_id\":\"7\",\"name\":\"Sam\","
                + "\"text\":\"on my way\",\"avatar_url\":null,\"attachments\":[]}}}"), "1");
        assertEquals("42", e.convoId);
        assertEquals("Sam", e.sender);
        assertEquals("on my way", e.text);
        assertEquals("", e.avatar);
    }

    @Test public void directMessageIsKeyedByThePerson() throws Exception {
        Push.Event e = Push.parse(new JSONObject("{\"data\":{\"type\":\"direct_message.create\","
                + "\"subject\":{\"id\":\"d1\",\"sender_id\":\"7\",\"recipient_id\":\"1\",\"name\":\"Jo\","
                + "\"text\":null,\"attachments\":[{\"type\":\"image\",\"url\":\"x\"}]}}}"), "1");
        assertTrue(e.dm);
        assertEquals("7", e.convoId);
        assertTrue(e.photo);
        assertEquals("", e.text);
    }

    @Test public void readsGroupMesWrappedReply() throws Exception {
        org.json.JSONArray a = Push.unwrap("/**/jsonpcallback([{\"id\":\"1\",\"successful\":true}]);");
        assertEquals(1, a.length());
        assertTrue(a.getJSONObject(0).getBoolean("successful"));
        assertEquals(0, Push.unwrap("[]").length());
    }

    @Test public void ignoresYourOwnAndOtherEvents() throws Exception {
        assertNull(Push.parse(new JSONObject("{\"data\":{\"type\":\"line.create\",\"subject\":{\"group_id\":\"4\","
                + "\"sender_id\":\"1\",\"text\":\"mine\"}}}"), "1"));
        assertNull(Push.parse(new JSONObject("{\"data\":{\"type\":\"like.create\",\"subject\":{}}}"), "1"));
        assertNull(Push.parse(new JSONObject("{\"data\":{\"type\":\"ping\"}}"), "1"));
        assertNull(Push.parse(new JSONObject("{\"channel\":\"/meta/connect\",\"successful\":true}"), "1"));
    }

    @Test
    public void aPhotoMessageCarriesItsPictureForTheNotification() throws Exception {
        Push.Event e = Push.parse(new JSONObject("{\"data\":{\"type\":\"line.create\",\"subject\":{\"group_id\":\"4\","
                + "\"id\":\"9\",\"sender_id\":\"2\",\"name\":\"Sam\",\"text\":\"look\","
                + "\"attachments\":[{\"type\":\"image\",\"url\":\"https://i.groupme.com/800x600.jpeg.abc\"}]}}}"), "1");
        assertTrue(e.photo);
        assertEquals("https://i.groupme.com/800x600.jpeg.abc", e.imageUrl);
        Push.Event v = Push.parse(new JSONObject("{\"data\":{\"type\":\"line.create\",\"subject\":{\"group_id\":\"4\","
                + "\"id\":\"10\",\"sender_id\":\"2\",\"name\":\"Sam\",\"text\":\"\","
                + "\"attachments\":[{\"type\":\"video\",\"url\":\"https://v.groupme.com/x.mp4\",\"preview_url\":\"https://v.groupme.com/x.jpg\"}]}}}"), "1");
        assertEquals("https://v.groupme.com/x.jpg", v.imageUrl);
        assertTrue(v.video);
    }

    @Test
    public void typingAndReadSignalsAreRecognized() throws Exception {
        Push.Signal t = Push.signal(new JSONObject("{\"channel\":\"/group/4\",\"data\":{\"type\":\"typing\",\"user_id\":\"2\",\"started\":1}}"), "1");
        assertEquals("typing", t.type);
        assertEquals("2", t.userId);
        assertEquals("/group/4", t.chatId);
        assertNull(Push.signal(new JSONObject("{\"channel\":\"/group/4\",\"data\":{\"type\":\"typing\",\"user_id\":\"1\"}}"), "1")); // mine
        Push.Signal r = Push.signal(new JSONObject("{\"channel\":\"/direct_message/1_2\",\"data\":{\"type\":\"read_receipt.create\","
                + "\"subject\":{\"user_id\":\"2\",\"message_id\":\"77\",\"read_at\":1700000000,\"chat_id\":\"1+2\"}}}"), "1");
        assertEquals("read", r.type);
        assertEquals("77", r.messageId);
        assertEquals(1700000000L, r.readAt);
        assertEquals("/direct_message/1_2", Push.chatChannel(true, "1+2"));
        assertEquals("/group/9", Push.chatChannel(false, "9"));
    }

    @Test public void pinsChangedInAnotherAppArriveLive() throws Exception {
        Push.Signal p = Push.signal(new JSONObject("{\"channel\":\"/user/1\",\"data\":{\"type\":\"user.pinned_chats\","
                + "\"subject\":{\"pinned_conversation_ids\":[\"109842457\",\"1+2\"]}}}"), "1");
        assertEquals("pins", p.type);
        assertEquals(java.util.Arrays.asList("109842457", "1+2"), p.ids);
        Push.Signal none = Push.signal(new JSONObject("{\"channel\":\"/user/1\",\"data\":{\"type\":\"user.pinned_chats\","
                + "\"subject\":{\"pinned_conversation_ids\":[]}}}"), "1");
        assertEquals(0, none.ids.size()); // everything unpinned
        assertEquals("sync", Push.signal(new JSONObject("{\"channel\":\"/user/1\",\"data\":{\"type\":\"read_receipt.sync\","
                + "\"subject\":{\"receipts\":[]}}}"), "1").type);
    }

    @Test public void aMessagePinnedInAnotherAppArrivesLive() throws Exception {
        // the shape GroupMe pushed when a message was pinned in Test (11/26/2026)
        Push.Signal p = Push.signal(new JSONObject("{\"channel\":\"/user/1\",\"data\":{\"type\":\"line.create\","
                + "\"alert\":\"Sam pinned a message.\",\"subject\":{\"group_id\":\"109842457\",\"system\":true,"
                + "\"sender_type\":\"system\",\"text\":\"Sam pinned a message.\",\"event\":{\"type\":\"message.pinned\","
                + "\"data\":{\"message_id\":\"179569028660723805\",\"pinned\":true,\"pinned_by\":\"7\",\"pinned_at\":1795691252}}}}}"), "1");
        assertEquals("pinned", p.type);
        assertEquals("109842457", p.chatId);
        assertEquals("179569028660723805", p.messageId);
        // an ordinary message is not a signal (it's an Event)
        assertNull(Push.signal(new JSONObject("{\"channel\":\"/user/1\",\"data\":{\"type\":\"line.create\","
                + "\"subject\":{\"group_id\":\"109842457\",\"text\":\"hi\",\"user_id\":\"7\"}}}"), "1"));
    }
}
