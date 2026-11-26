package io.github.peterchabs.sayso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PersonPickTest {
    // one person can appear under several names: their GroupMe name and a nickname per group
    private static final List<Phrase.Group> PEOPLE = Arrays.asList(
            new Phrase.Group("1", "Taki aka DukeOfYolk"),
            new Phrase.Group("1", "Taki Kalogeropoulos"),
            new Phrase.Group("2", "Old Man River"),
            new Phrase.Group("3", "Sam Ortiz"),
            new Phrase.Group("4", "Sam Lee"),
            new Phrase.Group("5", "Frank Scafuri"),
            new Phrase.Group("5", "Frank"));

    @Test public void theWordsAfterMessage() {
        assertEquals("Duke of York", Phrase.afterMessage("Message Duke of York."));
        assertEquals("Sam I'm late", Phrase.afterMessage("text Sam I'm late"));
        assertEquals("Old Man River hi", Phrase.afterMessage("send a message to Old Man River hi"));
        assertEquals("Frank", Phrase.afterMessage("DM Frank"));
        assertNull(Phrase.afterMessage("Tell Test I'm on my way"));
    }

    @Test public void aRunTogetherNameIsFound() {
        Phrase.PersonPick p = Phrase.pickPerson(PEOPLE, "Duke of York");
        assertEquals("1", p.people.get(0).id);
        assertEquals("", p.text);
    }

    @Test public void theNameAndTheMessageComeApart() {
        Phrase.PersonPick p = Phrase.pickPerson(PEOPLE, "Old Man River I'm running late");
        assertTrue(p.sure);
        assertEquals("2", p.people.get(0).id);
        assertEquals("I'm running late", p.text);
        Phrase.PersonPick q = Phrase.pickPerson(PEOPLE, "Frank that the truck is here");
        assertEquals("5", q.people.get(0).id);
        assertEquals("the truck is here", q.text);
    }

    @Test public void eachPersonComesBackOnce() {
        Phrase.PersonPick p = Phrase.pickPerson(PEOPLE, "Taki");
        assertEquals(1, p.people.size());
        assertTrue(p.sure);
    }

    @Test public void twoPeopleWithTheNameAreBothOffered() {
        Phrase.PersonPick p = Phrase.pickPerson(PEOPLE, "Sam");
        assertFalse(p.sure);
        assertEquals(2, p.people.size());
        assertTrue(Phrase.pickPerson(PEOPLE, "Sam Lee").sure);
    }

    @Test public void noOneLikeItFindsNothing() {
        assertNull(Phrase.pickPerson(PEOPLE, "Zebediah"));
        assertNull(Phrase.pickPerson(PEOPLE, ""));
    }
}
