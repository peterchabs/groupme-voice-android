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
}
