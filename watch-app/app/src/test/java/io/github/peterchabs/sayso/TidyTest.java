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
}
