package io.github.peterchabs.voiceme;

import java.util.regex.Pattern;

/**
 * Light clean-up of spoken text before it's sent: a capital to start, "I" as a capital, and an
 * ending mark (a question mark when it opens like a question). Where you paused between
 * sentences ({@link #PAUSE}, marked by {@link Pauses}), a period or question mark and a capital.
 * Words and any punctuation the speech service already added are left alone. Pure Java, unit tested.
 */
final class Tidy {
    private Tidy() { }

    /** Marks a long pause in what was heard (a sentence ends); never sent. See {@link #breaks}. */
    static final String PAUSE = "\uE000";
    /** Marks a short pause (a breath: a comma); never sent. */
    static final String BREATH = "\uE001";

    /** Words that carry on the same sentence after a pause ("I went to ... the store"). */
    private static final Pattern CARRY_ON = Pattern.compile(
            "^(and|or|nor|because|cause|cuz|to|of|the|a|an|that|which|who|with|for|in|on|at|from|by|"
            + "is|was|are|were|than|then|if|when|while|until|unless|as|like|um|uh|plus|too|also)\\b.*",
            Pattern.CASE_INSENSITIVE);
    /** "is that ok", "are you coming": after a pause, a new question, not "the eggs ... are ready". */
    private static final Pattern ASKS_AGAIN = Pattern.compile(
            "^(is|was|are|were)\\s+(you|we|they|he|she|it|that|this|there|everyone|anyone|anybody|someone|"
            + "everybody|y'all)\\b.*", Pattern.CASE_INSENSITIVE);
    /** These carry on after a comma: "I'd come, but I'm stuck". */
    private static final Pattern COMMA_ON = Pattern.compile("^(but|so|though|although|except)\\b.*",
            Pattern.CASE_INSENSITIVE);
    /** A pause after one of these is a hesitation, never the end of a phrase: "I went to the ... store". */
    private static final Pattern HANGING = Pattern.compile(
            "(?:^|.*\\s)(the|a|an|to|of|my|your|our|their|his|her|its|and|or|with|for|in|on|at|from|by|"
            + "is|are|was|were|am|be|i|i'm|we|you|they|he|she|it's|that|um|uh|very|really|just|gonna|going|"
            + "this|these|those|some|any|no|not|if|when|than)$", Pattern.CASE_INSENSITIVE);

    /** The text with the pause marks taken out (for matching or showing names). */
    static String plain(String text) {
        return text == null ? "" : text.replace(PAUSE, " ").replace(BREATH, " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Pauses become punctuation: a long one ends the sentence ("running late. Save me a seat", or
     * "?" when it asked something), a breath is a comma ("I'm outside, come down"). Nothing after a
     * word that can't end a phrase ("to the ... store") or before one that carries it on ("and",
     * "to"); "but" and "so" after a long pause get a comma. Text without marks comes back as it was.
     */
    static String breaks(String text) {
        if (text == null || (!text.contains(PAUSE) && !text.contains(BREATH))) return text;
        StringBuilder out = new StringBuilder();
        StringBuilder piece = new StringBuilder();
        String mark = null; // the mark before the piece being collected
        for (int i = 0; i <= text.length(); i++) {
            String c = i < text.length() ? text.substring(i, i + 1) : null;
            if (c != null && !c.equals(PAUSE) && !c.equals(BREATH)) {
                piece.append(c);
                continue;
            }
            String p = piece.toString().trim();
            piece.setLength(0);
            if (!p.isEmpty()) {
                join(out, mark, p);
                mark = null;
            }
            if (c != null && (mark == null || c.equals(PAUSE))) mark = c; // the longer pause wins
        }
        return out.toString();
    }

    private static void join(StringBuilder out, String mark, String p) {
        if (out.length() == 0) {
            out.append(p);
            return;
        }
        char end = out.charAt(out.length() - 1);
        boolean ended = ".!?,;:\u2026-".indexOf(end) >= 0 || !Character.isLetterOrDigit(end);
        if (mark == null || ended || HANGING.matcher(out).matches()
                || (CARRY_ON.matcher(p).matches() && !ASKS_AGAIN.matcher(p).matches())) {
            out.append(' ').append(p);
        } else if (mark.equals(BREATH) || COMMA_ON.matcher(p).matches()) {
            out.append(", ").append(p);
        } else {
            out.append(endMark(lastSentence(out.toString()))).append(' ');
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            return;
        }
        if (".!?".indexOf(end) >= 0) { // a sentence already ended there ("... question mark")
            int at = out.length() - p.length();
            out.setCharAt(at, Character.toUpperCase(out.charAt(at)));
        }
    }

    /** How a sentence ends: "?" when it asks, "!" when it cheers, else ".". */
    static char endMark(String sentence) {
        String s = sentence.trim();
        if (QUESTION.matcher(s).matches() || TAG.matcher(s).matches()) return '?';
        if (EXCLAIM.matcher(s).matches()) return '!';
        return '.';
    }

    /** "..., right", "..., or not": a statement turned into a question at the end. */
    private static final Pattern TAG = Pattern.compile(
            ".*\\s(right|correct|or not|or what|or no|you know|isn't it|aren't you|don't you|didn't you|"
            + "won't you|can you|could you|would you|will you|you think|ya think|sound good|cool with you|"
            + "work for you|okay with you|ok with you)$", Pattern.CASE_INSENSITIVE);
    /** Short sentences that cheer or thank: "Happy birthday!", "Congrats on the new job!". */
    private static final Pattern EXCLAIM = Pattern.compile(
            "^(?:(?:oh|and|so|hey|guys|wow)[,\\s]+)?(wow|yay|woohoo|woo|congrats|congratulations|happy birthday|"
            + "happy (?:new year|thanksgiving|holidays|anniversary|friday|mother's day|father's day|fourth)|"
            + "merry christmas|thank you so much|thanks so much|thank you guys|thanks guys|awesome|amazing|"
            + "let's go|lets go|oh my god|omg|great job|good job|nice job|well done|hell yeah|heck yeah|"
            + "no way|love you|love it|love that|so proud|can't wait|cannot wait|good luck|welcome back|"
            + "welcome to the team|nice|perfect|sweet|hooray|cheers)\\b.{0,40}$", Pattern.CASE_INSENSITIVE);

    /** Punctuation said out loud: "are you coming question mark" → "are you coming?". */
    static String sayMarks(String t) {
        t = t.replaceAll("(?i)\\s*\\bquestion mark\\b", "?");
        t = t.replaceAll("(?i)\\s*\\bexclamation (?:point|mark)\\b", "!");
        t = t.replaceAll("(?i)(?<=\\p{L})\\s+comma\\b(?=\\s+\\p{L})", ",");
        // "period" only ends a sentence (before a pause or the end), and never "pay period", "the period"
        t = t.replaceAll("(?i)(?<!\\b(?:pay|the|a|this|that|trial|grace|time|waiting|my|your|her|each|every|"
                + "per|one|first|second|third|last|next|free|rest|cooling|class|any|study|lunch))"
                + "\\s+(?:period|full stop)(?=\\s*(?:" + PAUSE + "|" + BREATH + "|$))", ".");
        return t;
    }

    private static final Pattern AFTER_END = Pattern.compile("([.!?]\\s+)(\\p{Ll})");

    /** A capital after every sentence's end: "Are you coming? see you" → "... See you". */
    private static String capitals(String t) {
        java.util.regex.Matcher m = AFTER_END.matcher(t);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, m.group(1) + m.group(2).toUpperCase(java.util.Locale.US));
        m.appendTail(b);
        return b.toString();
    }

    /** The words after the last sentence's ending mark. */
    private static String lastSentence(String t) {
        java.util.regex.Matcher m = Pattern.compile("[.!?\u2026]\\s+").matcher(t);
        int from = 0;
        while (m.find()) from = m.end();
        return t.substring(from).trim();
    }

    private static final Pattern LONE_I = Pattern.compile("(?<![\\p{L}'])i(?=('m|'ll|'d|'ve)?(?![\\p{L}]))");
    private static final Pattern QUESTION = Pattern.compile(
            "^(?:(?:so|and|but|hey|ok|okay|also|wait|um|well|yo|guys|oh|then)[,\\s]+)?"
            + "(who|what|when|where|why|how|which|whose|is|are|am|was|were|do|does|did|can|could|"
            + "will|would|should|shall|may|might|have|has|had|isn't|aren't|don't|doesn't|didn't|"
            + "can't|won't|wouldn't|shouldn't|haven't|hasn't|anyone|anybody|you coming|wanna|want to)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /** "Yes see you at nine" → "Yes, see you at nine": a comma after a word that opens a reply. */
    private static final Pattern OPENER = Pattern.compile(
            "^(yes|yeah|yep|yup|nope|ok|okay|alright|all right|hey|hi|hello|thanks|thank you|sorry)"
            // not when the next words belong to it: "thanks a lot", "hey there", "thank you so much"
            + "(?!\\s+(a lot|so much|for|again|you|there|guys|all|everyone|man|dude|buddy|bud)\\b)"
            + "(?=\\s+\\p{L})", Pattern.CASE_INSENSITIVE);

    /**
     * The same clean-up for words still being spoken (capital, "I", the comma after "Yes"), but no
     * ending mark yet, so the finished message only adds its last period, never retypes.
     */
    static String live(String text) {
        if (text == null) return "";
        String t = capitals(breaks(sayMarks(text)).trim().replaceAll("\\s+", " "));
        if (t.isEmpty()) return t;
        java.util.regex.Matcher op = OPENER.matcher(t);
        if (op.find()) t = t.substring(0, op.end()) + "," + t.substring(op.end());
        t = LONE_I.matcher(t).replaceAll("I");
        return t.substring(0, 1).toUpperCase(java.util.Locale.US) + t.substring(1);
    }

    static String spoken(String text) {
        if (text == null) return "";
        String t = capitals(breaks(sayMarks(text)).trim().replaceAll("\\s+", " "));
        if (t.isEmpty()) return t;
        java.util.regex.Matcher op = OPENER.matcher(t);
        String rest = t;                                   // what follows "Hey, ": is it a question?
        if (op.find()) {
            rest = t.substring(op.end()).trim();
            t = t.substring(0, op.end()) + "," + t.substring(op.end());
        }
        t = LONE_I.matcher(t).replaceAll("I");
        t = t.substring(0, 1).toUpperCase(java.util.Locale.US) + t.substring(1);
        char last = t.charAt(t.length() - 1);
        boolean ends = ".!?…".indexOf(last) >= 0;
        boolean link = t.matches(".*https?://\\S+$");     // don't glue a period onto a link
        boolean emojiEnd = Character.isSurrogate(last) || !Character.isLetterOrDigit(last) && last != '"' && last != '\'';
        String lastOne = lastSentence(t);                 // only the last sentence decides its mark
        char mark = lastOne.equals(t) && QUESTION.matcher(rest).matches() ? '?' : endMark(lastOne);
        if (!ends && !link && !emojiEnd) t += mark;
        return t;
    }
}
