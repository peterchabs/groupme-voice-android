package io.github.peterchabs.voiceme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hears where you paused. The speech service sends your words a few at a time as you talk; when the
 * next words come noticeably later than they usually do for you in this message, that's a pause: a
 * breath (a comma) or a longer one (the sentence ended). {@link #mark} puts {@link Tidy#BREATH} or
 * {@link Tidy#PAUSE} there, and {@link Tidy#breaks} turns them into punctuation.
 * Judged against your own pace, since some speech services send words in bursts. Pure Java.
 */
final class Pauses {
    static final long COMMA_MS = 650, PERIOD_MS = 1100;
    /** Assumed pace until enough words have come in to judge: words arrive about this far apart. */
    static final long USUAL_MS = 300;

    private final List<String[]> before = new ArrayList<>(); // the words heard just before each gap
    private final List<Long> gaps = new ArrayList<>();
    private String[] last = new String[0];
    private long lastGrowth;

    void reset() {
        before.clear();
        gaps.clear();
        last = new String[0];
        lastGrowth = 0;
    }

    /** Words so far in this listen (the speech service's latest guess), heard at {@code now} ms. */
    void heard(String text, long now) {
        if (text == null) return;
        String[] w = words(text);
        if (w.length > last.length) {
            if (last.length > 0) {
                before.add(last);
                gaps.add(now - lastGrowth);
            }
            lastGrowth = now;
        } else if (w.length < last.length) {
            lastGrowth = now; // it took words back: start timing again
        }
        last = w;
    }

    /** {@code text} (what was finally heard) with the pauses marked between its words. */
    String mark(String text) {
        if (text == null || gaps.isEmpty()) return text;
        String[] tok = text.trim().split("\\s+");
        String[] w = words(text);
        if (w.length != tok.length) return text;
        long usual = usual();
        String[] at = new String[tok.length + 1];
        for (int i = 0; i < gaps.size(); i++) {
            long g = gaps.get(i);
            String m = g >= Math.max(PERIOD_MS, 3 * usual) ? Tidy.PAUSE
                    : g >= Math.max(COMMA_MS, 2 * usual) ? Tidy.BREATH : null;
            if (m == null) continue;
            int pos = locate(w, before.get(i));
            if (pos <= 0 || pos >= tok.length) continue;
            if (at[pos] == null || m.equals(Tidy.PAUSE)) at[pos] = m;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tok.length; i++) {
            if (i > 0) b.append(' ');
            if (at[i] != null) b.append(at[i]).append(' ');
            b.append(tok[i]);
        }
        return b.toString();
    }

    /** How far apart your words usually come in this message (the middle gap). */
    private long usual() {
        if (gaps.size() < 3) return USUAL_MS;
        long[] g = new long[gaps.size()];
        for (int i = 0; i < g.length; i++) g[i] = gaps.get(i);
        Arrays.sort(g);
        return g[g.length / 2];
    }

    /** Where the words {@code prefix} end in {@code w}: exactly, or near there if a word was re-guessed. */
    private static int locate(String[] w, String[] prefix) {
        int n = prefix.length;
        if (n <= w.length) {
            boolean same = true;
            for (int i = 0; i < n && same; i++) same = w[i].equals(prefix[i]);
            if (same) return n;
        }
        if (n < 2) return -1;
        for (int d = 0; d <= 2; d++) {
            for (int pos : new int[]{n - d, n + d}) {
                if (pos < 2 || pos > w.length) continue;
                if (w[pos - 1].equals(prefix[n - 1]) && w[pos - 2].equals(prefix[n - 2])) return pos;
            }
        }
        return -1;
    }

    private static String[] words(String text) {
        String t = text.trim();
        if (t.isEmpty()) return new String[0];
        String[] tok = t.split("\\s+");
        for (int i = 0; i < tok.length; i++) tok[i] = Phrase.norm(tok[i]);
        return tok;
    }
}
