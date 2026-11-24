package io.github.peterchabs.groupmevoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The sample below was made by docs/index.html (the sign-in page), so this proves page and watch agree. */
public class LinkTest {
    private static final String CODE = "K7Q2M9XAPZ";
    private static final long MADE_AT = 1700000000000L;
    private static final String FROM_PAGE =
            "+ZR+OO3wG0gr2NLZOVOQtaDUIYZKnsW932d4bdUt9RR05hNBHGtEugl2vDDfLoueEdU/ryE4fUoDytgAfm0AQafwMUX6/rDW9dKI6DU=";

    @Test public void watchReadsWhatThePageSent() {
        Link l = new Link("k7q2m-9xapz"); // typed lowercase with a dash still works
        assertEquals("gmv-74567b24d73cd07fe131747d91c5d996", l.topic());
        assertEquals("abcDEF1234567890abcdef1234567890", l.open(FROM_PAGE, MADE_AT + 5000));
    }

    @Test public void ignoresWrongCodeOldMessagesAndJunk() {
        assertNull(new Link("K7Q2M9XAPY").open(FROM_PAGE, MADE_AT));
        assertNull(new Link(CODE).open(FROM_PAGE, MADE_AT + 16 * 60 * 1000L));
        assertNull(new Link(CODE).open("hello", MADE_AT));
        assertNull(new Link(CODE).open("", MADE_AT));
    }

    @Test public void newCodesLookRight() {
        Link l = Link.create();
        assertEquals(10, l.code.length());
        for (char c : l.code.toCharArray()) assertTrue(Link.ALPHABET.indexOf(c) >= 0);
        assertEquals(11, l.pretty().length());
        assertTrue(l.pageUrl().endsWith("#" + l.code));
    }
}
