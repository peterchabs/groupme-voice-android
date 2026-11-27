package io.github.peterchabs.voiceme;

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

    private static java.util.List<Phrase.Group> withTest() {
        java.util.List<Phrase.Group> g = crews();
        g.add(new Phrase.Group("5", "Test"));
        return g;
    }

    private static void said(String heard, String group, String text) {
        Phrase p = Phrase.parseNamed(heard, withTest());
        org.junit.Assert.assertNotNull(heard, p);
        assertEquals(heard, group, p.group);
        assertEquals(heard, text, p.text);
    }

    @Test public void theGroupCanComeLast() {
        said("I'm running late, tell Weekend Crew", "Weekend Crew", "I'm running late");
        said("I'm running late tell weekend crew", "Weekend Crew", "I'm running late");
        said("send I'm running late to Weekend Crew", "Weekend Crew", "I'm running late");
        said("I'm running late, send that to the weekend crew group", "Weekend Crew", "I'm running late");
        said("running late to Weekend Crew", "Weekend Crew", "running late");
        said("we need more eggs, post it in Test", "Test", "we need more eggs");
    }

    @Test public void theGroupCanComeInTheMiddle() {
        said("can you let Weekend Crew know I'm running late", "Weekend Crew", "I'm running late");
        said("hey, tell Weekend Crew that I'm running late", "Weekend Crew", "I'm running late");
        said("so I'm running late, tell Weekend Crew, save me a seat", "Weekend Crew", "so I'm running late, save me a seat");
        said("please remind Test the meeting is at 3", "Test", "the meeting is at 3");
    }

    @Test public void aGroupNameInsideTheMessageIsNotWhereItGoes() {
        assertNull(Phrase.parseNamed("I have a test tomorrow", withTest()));
        assertNull(Phrase.parseNamed("I'm bringing my test", withTest()));
        assertNull(Phrase.parseNamed("I'm going to test the oven", withTest()));
        assertNull(Phrase.parseNamed("see you at the yolk", withTest()));
        // named first, the rest stays as said
        said("Weekend Crew that was fun", "Weekend Crew", "that was fun");
    }

    @Test public void theShorterOfTwoSimilarNamesIsStillReachable() {
        // default "Yolk West Loop", sending to "Yolk"
        said("tell Yolk we need eggs", "Yolk", "we need eggs");
        said("we need eggs, tell Yolk", "Yolk", "we need eggs");
        said("Yolk we need eggs", "Yolk", "we need eggs");
        // the message itself starts with the rest of the longer name: "that" separates them
        said("tell Yolk West Loop is out of eggs", "Yolk West Loop", "is out of eggs");
        said("tell Yolk that West Loop is out of eggs", "Yolk", "West Loop is out of eggs");
    }

    @Test public void twoGroupsNamedEquallyIsNeverAGuess() {
        assertNull(Phrase.parseNamed("tell Weekend Crew and tell Test I'm late", withTest()));
        // a clear command beats a name that's merely first
        said("Yolk is slammed, tell Weekend Crew", "Weekend Crew", "Yolk is slammed");
    }

    @Test public void whichGroupTakesANameOrTheWholeThingAgain() {
        Phrase a = Phrase.whichGroupAnswer("Test", withTest());
        assertEquals("Test", a.group);
        assertEquals("", a.text);                                          // keep the message said before
        a = Phrase.whichGroupAnswer("send it to the weekend crew group", withTest());
        assertEquals("Weekend Crew", Phrase.match(withTest(), a.group).group.name);
        assertEquals("", a.text);
        a = Phrase.whichGroupAnswer("Tell Test I'm on my way", withTest()); // said it all again, with the group
        assertEquals("Test", a.group);
        assertEquals("I'm on my way", a.text);
        a = Phrase.whichGroupAnswer("I'm on my way, tell Weekend Crew", withTest());
        assertEquals("I'm on my way", a.text);
        assertNull(Phrase.whichGroupAnswer("I'm on my way", withTest()));   // the message again: no group in it
        assertNull(Phrase.whichGroupAnswer("I'll be there in ten minutes", withTest()));
        assertNull(Phrase.whichGroupAnswer("", withTest()));
    }

    @Test public void answeringWhichGroup() {
        assertEquals("Test", Phrase.groupAnswer("Test"));
        assertEquals("Test", Phrase.groupAnswer("to Test"));
        assertEquals("Weekend Crew", Phrase.groupAnswer("send it to the Weekend Crew group please"));
        assertEquals("weekend crew", Phrase.groupAnswer("um the weekend crew chat"));
    }
}
