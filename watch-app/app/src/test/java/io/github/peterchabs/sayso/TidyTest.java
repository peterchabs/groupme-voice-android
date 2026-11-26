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

    private static final String P = Tidy.PAUSE, B = Tidy.BREATH;

    @Test public void aLongPauseEndsTheSentence() {
        assertEquals("I'm running late. Save me a seat.", Tidy.spoken("I'm running late " + P + " save me a seat"));
        assertEquals("Are you coming? I'm outside.", Tidy.spoken("are you coming " + P + " I'm outside"));
        assertEquals("I'm outside. Are you coming?", Tidy.spoken("I'm outside " + P + " are you coming"));
    }

    @Test public void aBreathIsAComma() {
        assertEquals("I'm outside, come down when you can.", Tidy.spoken("I'm outside " + B + " come down when you can"));
    }

    @Test public void aHesitationAddsNothing() {
        assertEquals("I went to the store.", Tidy.spoken("I went to the " + P + " store"));
        assertEquals("I'm late and I'm sorry.", Tidy.spoken("I'm late " + P + " and I'm sorry"));
        assertEquals("I'd come, but I'm stuck.", Tidy.spoken("I'd come " + P + " but I'm stuck"));
    }

    @Test public void moreQuestions() {
        assertEquals("So are you coming tonight?", Tidy.spoken("so are you coming tonight"));
        assertEquals("We're still on for six, right?", Tidy.spoken("we're still on for six " + B + " right"));
        assertEquals("You're coming or not?", Tidy.spoken("you're coming or not"));
    }

    @Test public void cheersGetAnExclamationPoint() {
        assertEquals("Happy birthday Sam!", Tidy.spoken("happy birthday Sam"));
        assertEquals("Congrats on the new job! See you Friday.", Tidy.spoken("congrats on the new job " + P + " see you Friday"));
        assertEquals("Thank you so much!", Tidy.spoken("thank you so much"));
        assertEquals("Nicely done on the tables.", Tidy.spoken("nicely done on the tables"));
    }

    @Test public void punctuationSaidOutLoud() {
        assertEquals("Are you coming? See you soon.", Tidy.spoken("are you coming question mark see you soon"));
        assertEquals("We won!", Tidy.spoken("we won exclamation point"));
        assertEquals("Eggs, bacon and toast.", Tidy.spoken("eggs comma bacon and toast"));
        assertEquals("I'm here.", Tidy.spoken("I'm here period"));
        assertEquals("Check the pay period.", Tidy.spoken("check the pay period"));
    }

    @Test public void liveTextGetsTheSameBreaks() {
        assertEquals("I'm running late. Save me", Tidy.live("I'm running late " + P + " save me"));
    }
}
