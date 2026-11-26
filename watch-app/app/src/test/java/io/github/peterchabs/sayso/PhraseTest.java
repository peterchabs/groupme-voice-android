package io.github.peterchabs.sayso;

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

    @Test public void understandsReplies() {
        assertEquals("on my way", Phrase.parseReply("reply saying on my way"));
        assertEquals("sounds good", Phrase.parseReply("Respond to that message saying sounds good"));
        assertEquals("see you at 7", Phrase.parseReply("reply back with see you at 7"));
        assertEquals("I'm in", Phrase.parseReply("reply to the last message I'm in"));
        assertEquals("yes", Phrase.parseReply("Hey Google, reply: yes"));
        assertNull(Phrase.parseReply("Weekend Crew that says reply soon"));
        assertNull(Phrase.parseReply("picking up pizza"));
        assertNull(Phrase.parseReply("reply"));
        assertNull(Phrase.parseReply("answer is yes"));
    }

    @Test public void understandsLikes() {
        org.junit.Assert.assertTrue(Phrase.parseLike("like that"));
        org.junit.Assert.assertTrue(Phrase.parseLike("Heart that message"));
        org.junit.Assert.assertTrue(Phrase.parseLike("like the last message"));
        org.junit.Assert.assertTrue(Phrase.parseLike("react to that with a heart"));
        org.junit.Assert.assertFalse(Phrase.parseLike("like that pizza place on 5th"));
        org.junit.Assert.assertFalse(Phrase.parseLike("Family that says I like that"));
    }

    @Test public void understandsDirectMessages() {
        Phrase p = Phrase.parseDm("DM Sam saying on my way");
        assertEquals("Sam", p.group);
        assertEquals("on my way", p.text);
        p = Phrase.parseDm("direct message Jordan Lee that says call me");
        assertEquals("Jordan Lee", p.group);
        assertEquals("call me", p.text);
        p = Phrase.parseDm("D.M. Sam saying hi");
        assertEquals("Sam", p.group);
        assertNull(Phrase.parseDm("Weekend Crew that says DM me"));
        assertNull(Phrase.parseDm("DM Sam"));
    }

    private static java.util.List<Phrase.Group> crews() {
        java.util.List<Phrase.Group> g = new java.util.ArrayList<>();
        g.add(new Phrase.Group("1", "Weekend Crew"));
        g.add(new Phrase.Group("2", "Yolk"));
        g.add(new Phrase.Group("3", "Yolk West Loop"));
        g.add(new Phrase.Group("4", "Yolk.\uD835\uDE3E\uD835\uDE43\uD835\uDE44\uD835\uDE3E\uD835\uDE3C\uD835\uDE42\uD835\uDE4A")); // Yolk.CHICAGO in math letters
        return g;
    }

    @Test public void aGroupNamedFirstNeedsNoThatSays() {
        Phrase p = Phrase.parseNamed("Weekend Crew I'm running late", crews());
        assertEquals("Weekend Crew", p.group);
        assertEquals("I'm running late", p.text);
        p = Phrase.parseNamed("tell weekend crew that I'm running late", crews());
        assertEquals("Weekend Crew", p.group);
        assertEquals("I'm running late", p.text);
        p = Phrase.parseNamed("text Weekend Crew saying on my way", crews());
        assertEquals("on my way", p.text);
    }

    @Test public void theLongestGroupNameWins() {
        assertEquals("Yolk West Loop", Phrase.parseNamed("Yolk West Loop we need more eggs", crews()).group);
        assertEquals("Yolk", Phrase.parseNamed("Yolk we need more eggs", crews()).group);
    }

    @Test public void fancyLetterNamesMatchPlainSpeech() {
        Phrase p = Phrase.parseNamed("yolk chicago doors open at seven", crews());
        assertEquals("doors open at seven", p.text);
    }

    @Test public void justTheNameMeansAskForTheMessage() {
        Phrase p = Phrase.parseNamed("Weekend Crew", crews());
        assertEquals("Weekend Crew", p.group);
        assertEquals("", p.text);
        assertNull(Phrase.parseNamed("hello there everyone", crews()));
    }
}
