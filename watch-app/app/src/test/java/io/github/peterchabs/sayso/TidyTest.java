package io.github.peterchabs.sayso;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TidyTest {
    @Test public void capitalStartAndFullStop() {
        assertEquals("On my way.", Tidy.spoken("on my way"));
        assertEquals("I think I'm late.", Tidy.spoken("i think i'm late"));
    }

    @Test public void questionsGetAQuestionMark() {
        assertEquals("Are we still on for lunch?", Tidy.spoken("are we still on for lunch"));
        assertEquals("What time is it?", Tidy.spoken("what time is it"));
        assertEquals("Can you grab milk?", Tidy.spoken("can you grab milk"));
    }

    @Test public void leavesExistingPunctuationAndLinksAlone() {
        assertEquals("Hey, it's done!", Tidy.spoken("Hey, it's done!"));
        assertEquals("Look at https://example.com/x", Tidy.spoken("look at https://example.com/x"));
        assertEquals("Its fine.", Tidy.spoken("its fine."));
        assertEquals("", Tidy.spoken("  "));
    }

    @Test public void wordsWithIInsideAreUntouched() {
        assertEquals("Visit it in the evening.", Tidy.spoken("visit it in the evening"));
    }

    @Test public void aCommaAfterAnOpeningYesOrHey() {
        assertEquals("Yes, see you at 9:00.", Tidy.spoken("Yes see you at 9:00."));
        assertEquals("Okay, thanks.", Tidy.spoken("okay thanks"));
        assertEquals("Hey, are you coming?", Tidy.spoken("hey are you coming"));
        assertEquals("Thanks a lot.", Tidy.spoken("thanks a lot"));
        assertEquals("Hey there.", Tidy.spoken("hey there"));
        assertEquals("No problem.", Tidy.spoken("no problem"));
        assertEquals("Yes, sir.", Tidy.spoken("yes, sir"));
        assertEquals("Yesterday was fun.", Tidy.spoken("yesterday was fun"));
    }

    @Test public void liveTextIsTidiedButNotFinished() {
        assertEquals("Yes, see you at", Tidy.live("yes see you at"));
        assertEquals("I think so", Tidy.live("i think so"));
    }

    @Test public void onlyCapitalsAndPunctuationChangingKeepsTheWords() {
        String t = "Yes, see you at 9:00.";
        assertEquals(t.length(), HomeActivity.sameWords("yes see you at 9:00", t));
        assertEquals("Yes, see you".length(), HomeActivity.sameWords("yes see you", t));
        assertEquals("Yes, see you at ".length(), HomeActivity.sameWords("yes see you at 10", t));
    }
}
