package io.github.peterchabs.groupmevoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class PhraseTest {
    private static void parses(String heard, String group, String text) {
        Phrase p = Phrase.parse(heard);
        assertNotNull(heard, p);
        assertEquals(heard, group, p.group);
        assertEquals(heard, text, p.text);
    }

    @Test public void parsesTheWaysPeopleTalk() {
        parses("Weekend Crew that says I'm running \"10\" minutes late", "Weekend Crew", "I'm running \"10\" minutes late");
        parses("Hey Google, send a message to Weekend Crew that says on my way", "Weekend Crew", "on my way");
        parses("send a message to the family group saying dinner at 7", "family", "dinner at 7");
        parses("text crew on GroupMe that says hi", "crew", "hi");
        parses("GroupMe weekend crew that says hello", "weekend crew", "hello");
        parses("groupme=:=Book club saying see you Thursday", "Book club", "see you Thursday");
        parses("Messengers that says yo", "Messengers", "yo");
    }

    @Test public void refusesWhenItCantSplit() {
        assertNull(Phrase.parse("weekend crew on my way"));
        assertNull(Phrase.parse("that says hi"));
        assertNull(Phrase.parse(""));
    }

    private static final List<Phrase.Group> GROUPS = Arrays.asList(
            new Phrase.Group("1", "Weekend Crew"),
            new Phrase.Group("2", "Family"),
            new Phrase.Group("3", "Book Club"),
            new Phrase.Group("4", "Book Club Planning"),
            new Phrase.Group("5", "Twins"),
            new Phrase.Group("6", "Twins"));

    @Test public void matchesGroups() {
        assertEquals("1", Phrase.match(GROUPS, "weekend crew").group.id);
        assertEquals("1", Phrase.match(GROUPS, "crew").group.id);          // part of the name
        assertEquals("2", Phrase.match(GROUPS, "famly").group.id);         // small mishearing
        assertEquals("3", Phrase.match(GROUPS, "book club").group.id);     // exact beats partial
    }

    @Test public void neverGuesses() {
        assertNull(Phrase.match(GROUPS, "book").group);                    // two Book groups
        assertNull(Phrase.match(GROUPS, "twins").group);                   // duplicate names
        assertNull(Phrase.match(GROUPS, "soccer").group);                  // not a group
    }
}
