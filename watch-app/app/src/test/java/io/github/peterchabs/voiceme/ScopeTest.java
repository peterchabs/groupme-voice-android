package io.github.peterchabs.voiceme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ScopeTest {
    @After public void reset() { Scope.forUser(""); }

    private static void refused(String method, String path) {
        try {
            Scope.check(method, path);
            fail("should refuse " + path);
        } catch (Api.RefusedException expected) {
            // refused
        }
    }

    @Test public void reviewAccountSeesOnlyTheTestGroup() throws Exception {
        Scope.forUser(Scope.REVIEW_USER);
        JSONObject groups = new JSONObject("{\"response\":[{\"id\":\"6490857\",\"name\":\"Yolk\"},"
                + "{\"id\":\"" + Scope.REVIEW_GROUP + "\",\"name\":\"Test\"},{\"id\":\"38454045\",\"name\":\"Maintenance\"}]}");
        JSONObject out = Scope.filter("GET", "/groups?per_page=100&page=1", groups);
        assertEquals(1, out.getJSONArray("response").length());
        assertEquals("Test", out.getJSONArray("response").getJSONObject(0).getString("name"));
        JSONObject chats = Scope.filter("GET", "/chats?per_page=100&page=1",
                new JSONObject("{\"response\":[{\"other_user\":{\"id\":\"7\"}}]}"));
        assertEquals(0, chats.getJSONArray("response").length());
    }

    @Test public void reviewAccountIsRefusedOtherChats() throws Exception {
        Scope.forUser(Scope.REVIEW_USER);
        refused("POST", "/groups/6490857/messages");
        refused("GET", "/groups/38454045/messages?limit=20");
        refused("GET", "/groups/38454045");
        refused("POST", "/messages/38454045/123/like");
        refused("GET", "/direct_messages?other_user_id=7");
        refused("POST", "/direct_messages");
        Scope.check("POST", "/groups/" + Scope.REVIEW_GROUP + "/messages");
        Scope.check("GET", "/groups/" + Scope.REVIEW_GROUP + "/messages?limit=20");
        Scope.check("GET", "/users/me");
    }

    @Test public void reviewAccountHearsOnlyTheTestGroup() throws Exception {
        Scope.forUser(Scope.REVIEW_USER);
        assertNull(Push.parse(new JSONObject("{\"data\":{\"type\":\"line.create\",\"subject\":{\"id\":\"m1\","
                + "\"group_id\":\"38454045\",\"sender_id\":\"7\",\"name\":\"Sam\",\"text\":\"hi\",\"attachments\":[]}}}"),
                Scope.REVIEW_USER));
        assertNull(Push.parse(new JSONObject("{\"data\":{\"type\":\"direct_message.create\",\"subject\":{\"id\":\"m2\","
                + "\"sender_id\":\"7\",\"name\":\"Sam\",\"text\":\"hi\",\"attachments\":[]}}}"), Scope.REVIEW_USER));
        assertNotNull(Push.parse(new JSONObject("{\"data\":{\"type\":\"line.create\",\"subject\":{\"id\":\"m3\","
                + "\"group_id\":\"" + Scope.REVIEW_GROUP + "\",\"sender_id\":\"7\",\"name\":\"Sam\",\"text\":\"hi\","
                + "\"attachments\":[]}}}"), Scope.REVIEW_USER));
    }

    @Test public void everyoneElseIsUnaffected() throws Exception {
        Scope.forUser("999");
        Scope.check("GET", "/groups/38454045/messages");
        Scope.check("POST", "/direct_messages");
        JSONObject groups = new JSONObject("{\"response\":[{\"id\":\"1\"},{\"id\":\"2\"}]}");
        assertEquals(2, Scope.filter("GET", "/groups?page=1", groups).getJSONArray("response").length());
    }
}
