package io.github.peterchabs.sayso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ChatSearchTest {
    private static final List<Phrase.Group> CHATS = Arrays.asList(
            new Phrase.Group("1", "Test"),
            new Phrase.Group("2", "Old Man River"),
            new Phrase.Group("3", "Yolk.CHICAGO"),
            new Phrase.Group("4", "Weekend Crew"),
            new Phrase.Group("5", "Weekend Brunch Team"),
            new Phrase.Group("6", "Peter Chabot"));

    private static String first(String said) {
        List<Phrase.Group> hits = Phrase.findChats(CHATS, said);
        return hits.isEmpty() ? null : hits.get(0).name;
    }

    @Test public void theWordsAroundTheNameAreDropped() {
        assertEquals("Test", Phrase.chatAnswer("Open the Test group"));
        assertEquals("Old Man River", Phrase.chatAnswer("find Old Man River"));
        assertEquals("Weekend Crew", Phrase.chatAnswer("go to my Weekend Crew chat please"));
        assertEquals("Peter", Phrase.chatAnswer("message Peter"));
    }

    @Test public void aWholeNameOpensAtOnce() {
        assertEquals("Test", first("Test"));
        assertTrue(Phrase.clearWinner(CHATS, "open test"));
        assertTrue(Phrase.clearWinner(CHATS, "Old Man River"));
    }

    @Test public void partOfANameFindsIt() {
        assertEquals("Old Man River", first("river"));
        assertEquals("Old Man River", first("old river"));
        assertEquals("Peter Chabot", first("Peter"));
        assertEquals("Yolk.CHICAGO", first("yolk chicago"));
    }

    @Test public void aNameSharedByTwoChatsAsks() {
        List<Phrase.Group> hits = Phrase.findChats(CHATS, "weekend");
        assertEquals(2, hits.size());
        assertFalse(Phrase.clearWinner(CHATS, "weekend"));
    }

    @Test public void aMisheardNameStillFindsIt() {
        assertEquals("Weekend Crew", first("weekend crue"));
        assertEquals("Test", first("tess"));
    }

    @Test public void somethingUnlikeAnyChatFindsNothing() {
        assertTrue(Phrase.findChats(CHATS, "basketball").isEmpty());
        assertTrue(Phrase.findChats(CHATS, "").isEmpty());
        assertTrue(Phrase.findChats(CHATS, "open").isEmpty());
    }
}
