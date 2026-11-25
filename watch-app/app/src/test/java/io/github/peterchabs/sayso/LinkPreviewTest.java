package io.github.peterchabs.sayso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinkPreviewTest {
    @Test public void findsTheLinkInAMessage() {
        assertEquals("https://example.com/a?b=1", LinkPreview.firstUrl("look https://example.com/a?b=1."));
        assertEquals("http://x.co/path", LinkPreview.firstUrl("(http://x.co/path)"));
        assertNull(LinkPreview.firstUrl("no link here"));
        assertNull(LinkPreview.firstUrl(null));
    }

    @Test public void readsOpenGraphTags() {
        String html = "<html><head><title>Fallback</title>"
                + "<meta property=\"og:title\" content=\"Big &amp; Bold\">"
                + "<meta content='/img/card.jpg' property='og:image'>"
                + "<meta property=\"og:site_name\" content=\"Example\"></head><body>";
        LinkPreview.Card c = LinkPreview.parse("https://www.example.com/post/1", html);
        assertEquals("Big & Bold", c.title);
        assertEquals("https://www.example.com/img/card.jpg", c.image); // relative made absolute
        assertEquals("Example", c.site);
        assertTrue(c.hasImage());
    }

    @Test public void fallsBackToTwitterTagsAndTitle() {
        String html = "<head><meta name=\"twitter:image\" content=\"https://cdn.x.com/p.png\">"
                + "<title>  Page\n Title </title></head>";
        LinkPreview.Card c = LinkPreview.parse("https://www.news.com/a", html);
        assertEquals("https://cdn.x.com/p.png", c.image);
        assertEquals("Page Title", c.title);
        assertEquals("news.com", c.site);
    }

    @Test public void pageWithoutTagsHasNoPicture() {
        LinkPreview.Card c = LinkPreview.parse("https://a.com/", "<head></head>");
        assertFalse(c.hasImage());
    }

    @Test public void spotsDirectImageLinks() {
        assertTrue(LinkPreview.isImageUrl("https://a.com/x/photo.JPG?w=200"));
        assertTrue(LinkPreview.isImageUrl("https://media.giphy.com/media/abc/giphy.gif"));
        assertFalse(LinkPreview.isImageUrl("https://a.com/article"));
    }
}
