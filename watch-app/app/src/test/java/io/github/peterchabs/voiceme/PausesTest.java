package io.github.peterchabs.voiceme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PausesTest {
    private static final String P = Tidy.PAUSE, B = Tidy.BREATH;

    /** Feeds words in one at a time, {@code gaps[i]} ms before word i+1. */
    private static Pauses talk(String text, long... gaps) {
        Pauses p = new Pauses();
        String[] w = text.split(" ");
        long t = 1000;
        StringBuilder so = new StringBuilder(w[0]);
        p.heard(so.toString(), t);
        for (int i = 1; i < w.length; i++) {
            t += gaps[i - 1];
            so.append(' ').append(w[i]);
            p.heard(so.toString(), t);
        }
        return p;
    }

    @Test public void aLongGapIsAPauseAndAShortOneABreath() {
        Pauses p = talk("I'm outside come down now please", 300, 1300, 300, 750, 300);
        assertEquals("I'm outside " + P + " come down " + B + " now please", p.mark("I'm outside come down now please"));
    }

    @Test public void steadyTalkingMarksNothing() {
        Pauses p = talk("we need more eggs at west loop", 300, 280, 320, 300, 310, 290);
        assertEquals("we need more eggs at west loop", p.mark("we need more eggs at west loop"));
    }

    @Test public void wordsThatComeInBurstsAreJudgedByTheirOwnPace() {
        // this speech service sends a word every ~700 ms even mid-sentence: that's not a pause
        Pauses p = talk("we need more eggs at west loop", 700, 720, 690, 710, 700, 700);
        assertEquals("we need more eggs at west loop", p.mark("we need more eggs at west loop"));
    }

    @Test public void theFinalWordsMayDifferInCapitalsAndMarks() {
        Pauses p = talk("i'm outside come down", 300, 1300, 300);
        assertEquals("I'm outside, " + P + " come down", p.mark("I'm outside, come down"));
        assertEquals("I'm outside. Come down.", Tidy.spoken(p.mark("I'm outside come down")));
    }
}
