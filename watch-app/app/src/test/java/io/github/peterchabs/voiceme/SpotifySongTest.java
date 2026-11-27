package io.github.peterchabs.voiceme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpotifySongTest {
    @Test public void everyKindOfSpotifyLinkIsRead() {
        assertArrayEquals(new String[]{"track", "4cOdK2wGLETKBW3PvgPWqT"},
                SpotifySong.parseLink("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc123"));
        assertArrayEquals(new String[]{"track", "4cOdK2wGLETKBW3PvgPWqT"},
                SpotifySong.parseLink("https://open.spotify.com/intl-es/track/4cOdK2wGLETKBW3PvgPWqT"));
        assertArrayEquals(new String[]{"album", "1A2GTWGtFfWp7KSQTwWOyo"},
                SpotifySong.parseLink("spotify:album:1A2GTWGtFfWp7KSQTwWOyo"));
        assertNull(SpotifySong.parseLink("https://youtube.com/watch?v=x"));
        assertEquals("https://open.spotify.com/embed/track/abc1234567",
                SpotifySong.embedUrl(new String[]{"track", "abc1234567"}));
    }

    @Test public void theEmbedPageGivesTheSongAndItsPreview() {
        String html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":"
                + "{\"type\":\"track\",\"name\":\"Never Gonna Give You Up\",\"title\":\"Never Gonna Give You Up\","
                + "\"artists\":[{\"name\":\"Rick Astley\"},{\"name\":\"Guest\"}],"
                + "\"audioPreview\":{\"url\":\"https://p.scdn.co/mp3-preview/40a7\"},"
                + "\"visualIdentity\":{\"backgroundBase\":{\"alpha\":255,\"red\":0,\"green\":89,\"blue\":119},"
                + "\"image\":[{\"url\":\"https://i/640\",\"maxWidth\":640},{\"url\":\"https://i/300\",\"maxWidth\":300},{\"url\":\"https://i/64\",\"maxWidth\":64}]}"
                + "}}}}}}</script></html>";
        SpotifySong s = SpotifySong.parse(new String[]{"track", "4cOd"}, html);
        assertEquals("Never Gonna Give You Up", s.title);
        assertEquals("Rick Astley, Guest", s.artist);
        assertEquals("https://p.scdn.co/mp3-preview/40a7", s.preview);
        assertEquals("https://i/300", s.cover);
        assertEquals(0xFF005977, s.tint);
        assertNull(SpotifySong.parse(new String[]{"track", "x"}, "<html>no data</html>"));
    }
}
