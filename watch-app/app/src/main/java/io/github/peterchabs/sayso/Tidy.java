package io.github.peterchabs.sayso;

import java.util.regex.Pattern;

/**
 * Light clean-up of spoken text before it's sent: a capital to start, "I" as a capital, and an
 * ending mark (a question mark when it opens like a question). Words and any punctuation the
 * speech service already added are left alone. Pure Java, unit tested.
 */
final class Tidy {
    private Tidy() { }

    private static final Pattern LONE_I = Pattern.compile("(?<![\\p{L}'])i(?=('m|'ll|'d|'ve)?(?![\\p{L}]))");
    private static final Pattern QUESTION = Pattern.compile(
            "^(who|what|when|where|why|how|which|whose|is|are|am|was|were|do|does|did|can|could|"
            + "will|would|should|shall|may|might|have|has|had|isn't|aren't|don't|doesn't|didn't|"
            + "can't|won't|wouldn't|shouldn't|haven't|hasn't|anyone|anybody|you coming|wanna|want to)\\b.*",
            Pattern.CASE_INSENSITIVE);

    static String spoken(String text) {
        if (text == null) return "";
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return t;
        t = LONE_I.matcher(t).replaceAll("I");
        t = t.substring(0, 1).toUpperCase(java.util.Locale.US) + t.substring(1);
        char last = t.charAt(t.length() - 1);
        boolean ends = ".!?…".indexOf(last) >= 0;
        boolean link = t.matches(".*https?://\\S+$");     // don't glue a period onto a link
        boolean emojiEnd = Character.isSurrogate(last) || !Character.isLetterOrDigit(last) && last != '"' && last != '\'';
        if (!ends && !link && !emojiEnd) t += QUESTION.matcher(t).matches() ? "?" : ".";
        return t;
    }
}
