package io.github.peterchabs.sayso;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PresenceTest {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36";

    @Test public void theSignInCarriesTheBrowserToTheWatch() {
        Link link = new Link("K7Q2M9XAPZ");
        long now = System.currentTimeMillis();
        assertArrayEquals(new String[]{"tok123", UA}, link.openSignIn(link.sealWithBrowser("tok123", UA, now), now));
    }

    @Test public void anOlderPhoneAppStillSignsIn() {
        Link link = new Link("K7Q2M9XAPZ");
        long now = System.currentTimeMillis();
        String v1 = link.seal("tok123", now);
        assertArrayEquals(new String[]{"tok123", ""}, link.openSignIn(v1, now));
        assertEquals("tok123", link.open(v1, now));
    }

    @Test public void anotherWatchsCodeOpensNothing() {
        long now = System.currentTimeMillis();
        String sealed = new Link("K7Q2M9XAPZ").sealWithBrowser("tok123", UA, now);
        assertNull(new Link("AAAAABBBBB").openSignIn(sealed, now));
    }

    @Test public void groupMembersOnlineAndAway() throws Exception {
        Map<String, String> m = Api.parseGroupPresence(new JSONObject(
                "{\"group_id\":\"1\",\"online\":[{\"user_id\":\"18\"},{\"user_id\":\"13\"}],\"away\":[{\"user_id\":\"11\"}]}"));
        assertEquals("online", m.get("18"));
        assertEquals("away", m.get("11"));
        assertNull(m.get("99"));
    }

    @Test public void aProfileReadsWhatTheyFilledIn() throws Exception {
        Api.Profile p = Api.parseProfile(new JSONObject("{\"user\":{\"id\":\"5\",\"name\":\"Sam\",\"bio\":\"Coffee first\","
                + "\"created_at\":1400000000,\"direct_message_capable\":false,\"app_installed\":true,\"song_url\":\"https://open.spotify.com/track/x\","
                + "\"interests\":[1001,1002],\"photo_urls\":[\"https://i.groupme.com/a.jpeg\"]},"
                + "\"shared_groups\":[{\"group_id\":\"9\",\"group_name\":\"Test\",\"group_avatar\":null}]}"));
        assertEquals("Coffee first", p.bio);
        assertEquals(1400000000L, p.createdAtSec);
        assertEquals(false, p.dmCapable);
        assertEquals("https://open.spotify.com/track/x", p.songUrl);
        assertEquals(2, p.interests.size());
        assertEquals(1, p.photos.size());
        assertEquals("Test", p.sharedGroups.get(0)[1]);
        assertEquals("", p.sharedGroups.get(0)[2]);
        Api.Profile empty = Api.parseProfile(new JSONObject("{\"user\":{\"id\":\"6\",\"bio\":{}},\"shared_groups\":[]}"));
        assertEquals("", empty.bio); // GroupMe sends {} for an unset bio
    }

    @Test public void theActiveLineReadsLikeGroupMes() throws Exception {
        long now = 1_800_000_000_000L;
        assertEquals("Active now", Api.activeLine(Api.parsePresence(new JSONObject("{\"status\":\"online\",\"last_active\":" + now + "}")), now));
        assertEquals("Active just now", Api.activeLine(new Api.Presence("away", now - 20_000), now));
        assertEquals("Active 5 min ago", Api.activeLine(new Api.Presence("away", now - 5 * 60_000), now));
        assertEquals("Active 2 hr ago", Api.activeLine(new Api.Presence("offline", now - 125 * 60_000), now));
        assertEquals("", Api.activeLine(new Api.Presence("offline", now - 7 * 3600_000L), now));
        assertEquals("", Api.activeLine(null, now));
    }
}
