package io.github.peterchabs.sayso;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Splits what you said into (group, message) and finds the matching group. Pure Java, no Android. */
public final class Phrase {
    public final String group;
    public final String text;

    private Phrase(String group, String text) {
        this.group = Tidy.plain(group); // a pause mark is never part of a name
        this.text = text;
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern PREFIX = Pattern.compile("^\\s*[\\w-]+=:=\\s*");
    private static final Pattern LEAD = Pattern.compile(
            "^\\s*(?:hey google[,\\s]*|ok google[,\\s]*)?"
            + "(?:please\\s+)?(?:groupme\\s+)?"
            + "(?:(?:send|text|post|message|tell)\\s+(?:a\\s+)?(?:groupme\\s+)?(?:message\\s+)?(?:(?:to|in)\\s+)?)?"
            + "(?:the\\s+)?(?:groupme\\s+)?(?:group\\s+)?", CI);
    private static final Pattern SPLIT = Pattern.compile(
            "\\s+(?:that says|saying|which says|and say|message)\\s*[:,]?\\s+", CI);
    private static final Pattern ON_GROUPME = Pattern.compile("\\s+on groupme$", CI);
    private static final Pattern GROUP_WORD = Pattern.compile("\\s+(?:group|chat)$", CI);

    private static final Pattern REPLY = Pattern.compile(
            "^\\s*(?:hey google[,\\s]*)?(?:please\\s+)?(?:respond|reply)(?:\\s+back)?"
            + "(?:\\s+to\\s+(?:that|the last|the latest|the|their|his|her)(?:\\s+(?:message|text|one))?)?"
            + "(?:\\s+(?:saying|that says|with|and say|say))?\\s*[:,]?\\s+(.+)$", CI);

    /**
     * "reply saying on my way", "respond to that message saying sounds good":
     * returns the words to send, or null if this isn't a reply.
     */
    public static String parseReply(String heard) {
        if (heard == null) return null;
        java.util.regex.Matcher m = REPLY.matcher(PREFIX.matcher(heard).replaceFirst(""));
        if (!m.matches()) return null;
        String text = m.group(1).trim();
        return text.isEmpty() ? null : text;
    }

    private static final Pattern LIKE = Pattern.compile(
            "^\\s*(?:hey google[,\\s]*)?(?:please\\s+)?(?:like|heart|react to|react with a heart to)"
            + "(?:\\s+(?:that|this|it|the last|the latest|their|his|her))?"
            + "(?:\\s+(?:message|text|one|post))?(?:\\s+with a heart)?\\s*[.!]?\\s*$", CI);

    /** "like that", "heart that message", "react to that with a heart". */
    public static boolean parseLike(String heard) {
        return heard != null && LIKE.matcher(PREFIX.matcher(heard).replaceFirst("")).matches();
    }

    private static final Pattern DM = Pattern.compile(
            "^\\s*(?:hey google[,\\s]*)?(?:please\\s+)?"
            + "(?:d\\.?\\s?m\\.?|direct message|private message|privately message|message privately)"
            + "(?:\\s+to)?\\s+(.+)$", CI);

    /** "DM Sam saying hi", "direct message Sam that says hi": person + message, or null. */
    public static Phrase parseDm(String heard) {
        if (heard == null) return null;
        java.util.regex.Matcher m = DM.matcher(PREFIX.matcher(heard).replaceFirst(""));
        if (!m.matches()) return null;
        String[] parts = SPLIT.split(m.group(1), 2);
        if (parts.length != 2) return null;
        String who = strip(parts[0], " ,.:");
        String text = parts[1].trim();
        return who.isEmpty() || text.isEmpty() ? null : new Phrase(who, text);
    }

    /** Returns null when the phrase has no "that says" (or similar) between group and message. */
    public static Phrase parse(String heard) {
        if (heard == null) return null;
        String s = PREFIX.matcher(heard).replaceFirst("");
        s = LEAD.matcher(s).replaceFirst("");
        String[] parts = SPLIT.split(s, 2);
        if (parts.length != 2) return null;
        String group = strip(parts[0], " ,.:");
        group = ON_GROUPME.matcher(group).replaceFirst("");
        group = GROUP_WORD.matcher(group).replaceFirst("");
        String text = parts[1].trim();
        if (group.isEmpty() || text.isEmpty()) return null;
        return new Phrase(group, text);
    }

    private static final Pattern WAKE = Pattern.compile("^\\s*(?:hey google|ok google|okay google)[,.]?\\s*", CI);
    /** Words that send something somewhere: "tell X", "let X know", "send this to X". */
    private static final java.util.Set<String> VERB = new java.util.HashSet<>(java.util.Arrays.asList(
            "tell", "text", "message", "send", "post", "ping", "remind", "ask", "let", "dm", "say", "update", "warn"));
    /** "to X", "in X": only a hint on their own ("I'm late to practice" names no group). */
    private static final java.util.Set<String> PREP = new java.util.HashSet<>(java.util.Arrays.asList(
            "to", "in", "into", "on", "for"));
    /** Small words between the command and the name: "send THAT MESSAGE to THE X GROUP". */
    private static final java.util.Set<String> GLUE = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "my", "our", "a", "this", "that", "it", "message", "text", "over", "groupme", "group", "chat"));
    /** Words that only lead into a command: "can you", "please", "and then". */
    private static final java.util.Set<String> POLITE = new java.util.HashSet<>(java.util.Arrays.asList(
            "can", "could", "would", "will", "you", "please", "and", "then", "so", "go", "ahead", "also", "just", "quickly"));
    /** Said before a command, not part of the message: "hey, tell X ...", "okay so tell X ...". */
    private static final java.util.Set<String> FILLER = new java.util.HashSet<>(java.util.Arrays.asList(
            "hey", "ok", "okay", "please", "so", "um", "uh", "alright", "and", "yeah"));
    private static final Pattern AFTER_LEAD = Pattern.compile(
            "^(?:\\s*[:,.-])?\\s*(?:(?:group|chat)\\b[,:]?\\s*)?(?:on groupme\\b[,:]?\\s*)?", CI);
    private static final java.util.Set<String> TELLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "tell", "let", "remind", "ask", "warn", "update", "say"));
    private static final Pattern SEND_LEAD = Pattern.compile(
            "^(?:(?:hey|ok|okay|so|um|can you|could you|would you|please)[,\\s]+)*"
            + "(?:send|text|post|message|say)\\s+(?:a\\s+)?(?:message\\s+)?(?:(?:saying|that says)\\s+)?", CI);
    private static final Pattern AFTER_SAYS = Pattern.compile(
            "^(?:that says|which says|saying|says|to say|that)\\b[,:]?\\s*", CI);

    /** One place a group's name was heard, and how sure we are it's who you meant. */
    private static final class Spot {
        Group group;
        int start, end;   // the name's words: [start, end)
        int cueStart;     // where the command before it begins ("can you tell ..."); == start if none
        String verb;      // the command word, or null
        int score;
    }

    /**
     * Finds the group you named anywhere in what you said, without "that says":
     * "Weekend Crew I'm running late", "I'm running late, tell Weekend Crew",
     * "can you let Weekend Crew know I'm running late", "send I'm running late to Weekend Crew".
     * The words that only route it (tell, let ... know, send this to) are dropped and the rest is the
     * message ("" if you only said the name). Takes the longest group name that fits.
     *
     * A name counts when it's the first thing you say, or when a command points at it ("tell X",
     * "send it to X"). At the very end after a plain "to"/"in", it needs a name of two words or more
     * (or "X group"), so "I'm bringing my test" never goes to a group called Test. Two different
     * groups named equally clearly gives null: never guess between groups.
     */
    public static Phrase parseNamed(String heard, List<Group> groups) {
        if (heard == null || groups == null || groups.isEmpty()) return null;
        String s = WAKE.matcher(PREFIX.matcher(heard).replaceFirst("")).replaceFirst("").trim();
        if (s.isEmpty()) return null;
        String[] tok = s.split("\\s+");
        String[] n = new String[tok.length];
        for (int i = 0; i < tok.length; i++) n[i] = norm(tok[i]);

        Map<String, Group> byName = new LinkedHashMap<>();
        Map<String, Integer> words = new java.util.HashMap<>();
        for (Group g : groups) {
            String k = norm(g.name);
            if (k.isEmpty()) continue;
            byName.putIfAbsent(k, g);
            byName.putIfAbsent(k.replace(" ", ""), g); // "Yolk.CHICAGO" is said "yolk chicago"
            words.merge(g.name, k.split(" ").length, Math::max);
        }

        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < tok.length; i++) {
            if (n[i].isEmpty()) continue;
            for (int k = Math.min(tok.length - i, 10); k >= 1; k--) { // longest name first
                StringBuilder b = new StringBuilder();
                for (int j = i; j < i + k; j++) b.append(' ').append(n[j]);
                String cand = norm(b.toString());
                Group g = byName.get(cand);
                if (g == null) g = byName.get(cand.replace(" ", ""));
                if (g == null) continue;
                Spot sp = new Spot();
                sp.group = g;
                sp.start = i;
                sp.end = i + k;
                rate(sp, n, words.getOrDefault(g.name, 1));
                if (sp.score > 0) spots.add(sp);
                break;
            }
        }
        if (spots.isEmpty()) return null;
        Spot best = null;
        boolean tie = false;
        for (Spot sp : spots) {
            if (best == null || sp.score > best.score) {
                tie = false;
                best = sp;
            } else if (sp.score == best.score && sp.group != best.group) {
                tie = true; // "tell Weekend Crew and tell Test ...": which? never guess
            }
        }
        if (tie) return null;
        return new Phrase(best.group.name, message(tok, n, best));
    }

    /** How clearly this spot names the recipient (0 = it's just a word in the message). */
    private static void rate(Spot sp, String[] n, int nameWords) {
        sp.cueStart = sp.start;
        // walk back over "the", "that message", then look for "to"/"in", then the command word
        int j = back(n, sp.start - 1);
        boolean prep = false;
        if (j >= 0 && PREP.contains(n[j])) {
            prep = true;
            int p = back(n, j - 1);
            if (p >= 0 && VERB.contains(n[p])) j = p;
        }
        if (j >= 0 && VERB.contains(n[j])) {
            sp.verb = n[j];
            int c = j - 1;
            while (c >= 0 && POLITE.contains(n[c])) c--;
            sp.cueStart = c + 1;
            sp.score = 3;
            return;
        }
        boolean atEnd = trailing(n, sp.end);
        boolean groupWord = sp.end < n.length && ("group".equals(n[sp.end]) || "chat".equals(n[sp.end]));
        if (prep && (atEnd || groupWord) && (nameWords >= 2 || groupWord)) {
            sp.cueStart = j; // "... to Weekend Crew": drop the "to"
            sp.score = 2;
            return;
        }
        boolean atStart = true;
        for (int i = 0; i < sp.start; i++) if (!n[i].isEmpty() && !FILLER.contains(n[i])) atStart = false;
        if (atStart) {
            sp.cueStart = 0;
            sp.score = 1;
        }
    }

    /**
     * Steps back from {@code j} over small words ("the", "that message") and returns where it stops.
     * "text" and "message" are both: "send a text to X" (small word) but "text X" (the command), so
     * when it runs off the start or into another word, the first command word it skipped wins.
     */
    private static int back(String[] n, int j) {
        int from = j;
        while (j >= 0 && (GLUE.contains(n[j]) || n[j].isEmpty())) j--;
        if (j >= 0 && (VERB.contains(n[j]) || PREP.contains(n[j]))) return j;
        for (int i = j + 1; i <= from; i++) if (VERB.contains(n[i])) return i;
        return j;
    }

    /** True if nothing but "group", "chat", "please" or "on groupme" follows. */
    private static boolean trailing(String[] n, int from) {
        for (int i = from; i < n.length; i++) {
            String w = n[i];
            if (!w.isEmpty() && !w.equals("group") && !w.equals("chat") && !w.equals("please")
                    && !w.equals("on") && !w.equals("groupme") && !w.equals("thanks")) return false;
        }
        return true;
    }

    /** Everything you said except the routing words around the group's name. */
    private static String message(String[] tok, String[] n, Spot sp) {
        StringBuilder before = new StringBuilder();
        boolean onlyFiller = true;
        for (int i = 0; i < sp.cueStart; i++) {
            before.append(i == 0 ? "" : " ").append(tok[i]);
            if (!n[i].isEmpty() && !FILLER.contains(n[i])) onlyFiller = false;
        }
        if (onlyFiller) before.setLength(0); // "hey, tell Crew ..." : the "hey" leads the command
        StringBuilder after = new StringBuilder();
        for (int i = sp.end; i < tok.length; i++) after.append(i == sp.end ? "" : " ").append(tok[i]);
        String a = AFTER_LEAD.matcher(after.toString()).replaceFirst("");
        if ("let".equals(sp.verb)) a = a.replaceFirst("(?i)^know\\b[,:]?\\s*", ""); // "let Crew know ..."
        if (sp.verb != null && TELLS.contains(sp.verb)) {
            a = AFTER_SAYS.matcher(a).replaceFirst(""); // "tell Crew that I'm late"
        } else {
            a = a.replaceFirst("(?i)^(?:that says|which says|saying|says)\\b[,:]?\\s*", "");
        }
        a = a.replaceFirst("(?i)[,\\s]*\\b(?:on groupme|please|thanks)[.!]?$", "").trim();
        String b = before.toString().trim();
        if (sp.score == 2) b = SEND_LEAD.matcher(b).replaceFirst(""); // "send I'm late to Crew"
        b = strip(b, " ,;:-");
        a = strip(a, " ,;:-");
        String text;
        if (b.isEmpty()) text = a;
        else if (a.isEmpty()) text = b;
        else text = b + (b.matches(".*[.!?]$") ? " " : ", ") + a;
        return strip(text, " ,;:-").trim();
    }

    private static final Pattern ANSWER_LEAD = Pattern.compile(
            "^\\s*(?:(?:um|uh|oh|ok|okay|so)[,\\s]+)?(?:(?:send|post|text|put)\\s+)?(?:(?:it|that|this)\\s+)?"
            + "(?:(?:to|in|into|for)\\s+)?(?:the\\s+)?", CI);
    private static final Pattern ANSWER_TAIL = Pattern.compile("(?:\\s+(?:group|chat))?(?:\\s+please)?[\\s.!?]*$", CI);

    /** "Which group?" answered: "Test", "to Test", "send it to the Test group" all give "Test". */
    public static String groupAnswer(String heard) {
        if (heard == null) return "";
        String s = ANSWER_LEAD.matcher(PREFIX.matcher(heard).replaceFirst("")).replaceFirst("");
        return ANSWER_TAIL.matcher(s).replaceFirst("").trim();
    }

    /**
     * "Which group?" answered for a message already said. A group name ("Test", "send it to the Test group")
     * gives that group with empty text: keep the message. Saying it all again with a group ("Tell Test I'm on
     * my way") gives the group and the new text. Null when no group is in it, meaning these words are the
     * message again.
     */
    public static Phrase whichGroupAnswer(String heard, List<Group> groups) {
        if (heard == null || heard.trim().isEmpty()) return null;
        Phrase p = parse(heard);
        if (p == null) p = parseNamed(heard, groups);
        if (p != null && match(groups, p.group).group != null) return p;
        String name = groupAnswer(Tidy.plain(heard));
        // a group name is a few words; a longer answer is a message, never squeezed into a name
        if (!name.isEmpty() && name.split("\\s+").length <= 4 && match(groups, name).group != null) return new Phrase(name, "");
        return null;
    }

    private static String strip(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    // ---------------- group matching ----------------

    public static final class Group {
        public final String id;
        public final String name;

        public Group(String id, String name) {
            this.id = id;
            this.name = name == null ? "" : name;
        }
    }

    /** Either {@code group} is set, or {@code problem} says why nothing will be sent. */
    public static final class Match {
        public final Group group;
        public final String problem;

        Match(Group group, String problem) {
            this.group = group;
            this.problem = problem;
        }
    }

    static String norm(String s) {
        // fancy letters (bold, italic, script) count as plain ones: "Yolk.CHICAGO" written in 𝘾𝙃𝙄𝘾𝘼𝙂𝙊
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    public static Match match(List<Group> groups, String spoken) {
        String key = norm(spoken);
        Map<String, List<Group>> byName = new LinkedHashMap<>();
        for (Group g : groups) {
            byName.computeIfAbsent(norm(g.name), k -> new ArrayList<>()).add(g);
        }
        List<Group> hits = byName.get(key);
        if (hits == null) {
            List<String> cands = new ArrayList<>();
            for (String n : byName.keySet()) {
                if (n.isEmpty()) continue;
                if ((!key.isEmpty() && n.contains(key)) || similarity(key, n) >= 0.6) cands.add(n);
            }
            if (cands.isEmpty()) {
                return new Match(null, "I couldn't find a group called \"" + spoken + "\".");
            }
            if (cands.size() > 1) { // never guess between two groups
                StringBuilder sb = new StringBuilder("Which one: ");
                for (int i = 0; i < Math.min(3, cands.size()); i++) {
                    if (i > 0) sb.append(" or ");
                    sb.append(byName.get(cands.get(i)).get(0).name);
                }
                return new Match(null, sb.append("?").toString());
            }
            hits = byName.get(cands.get(0));
        }
        if (hits.size() > 1) {
            return new Match(null, "You have " + hits.size() + " groups named \""
                    + hits.get(0).name + "\". Rename one in GroupMe.");
        }
        return new Match(hits.get(0), null);
    }

    // ---------------- finding a chat by voice (Chats screen) ----------------

    private static final Pattern FIND_LEAD = Pattern.compile(
            "^(?:(?:please|can you|could you)\\s+)?(?:open(?:\\s+up)?|go\\s+to|find|show(?:\\s+me)?|search(?:\\s+for)?|look\\s+up"
                    + "|chat\\s+with|message|text|talk\\s+to|switch\\s+to|take\\s+me\\s+to)\\s+(?:my\\s+|the\\s+)?", CI);
    private static final Pattern FIND_TAIL = Pattern.compile(
            "(?:\\s+(?:group|chat|conversation|dm|messages?))?(?:\\s+on groupme)?(?:\\s+please)?[\\s.!?]*$", CI);

    /** "Open the Test group", "find Old Man River", "Test chat" all give the name alone. */
    public static String chatAnswer(String heard) {
        if (heard == null) return "";
        String s = PREFIX.matcher(Tidy.plain(heard)).replaceFirst("").trim();
        s = FIND_LEAD.matcher(s).replaceFirst("");
        return FIND_TAIL.matcher(s).replaceFirst("").trim();
    }

    /**
     * Chats whose names fit what was said, best first (at most four). Whole names beat parts of
     * names, parts beat sound-alikes. Nothing that only faintly resembles it.
     */
    public static List<Group> findChats(List<Group> chats, String spoken) {
        String key = norm(chatAnswer(spoken));
        List<Group> out = new ArrayList<>();
        if (key.isEmpty()) return out;
        String[] words = key.split(" ");
        List<double[]> scored = new ArrayList<>(); // {score, index}
        for (int i = 0; i < chats.size(); i++) {
            String n = norm(chats.get(i).name);
            if (n.isEmpty()) continue;
            double sc = 0;
            if (n.equals(key)) sc = 1.0;
            else if ((" " + n + " ").contains(" " + key + " ")) sc = 0.9;
            else if (n.contains(key) && key.length() >= 3) sc = 0.8;
            else if (allPrefixes(words, n.split(" "))) sc = 0.75;
            else {
                double sim = Math.max(similarity(key, n), bestWindow(key, n));
                if (sim >= 0.6) sc = sim * 0.85;
            }
            if (sc > 0) scored.add(new double[]{sc, i});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        for (double[] d : scored) {
            if (out.size() == 4) break;
            out.add(chats.get((int) d[1]));
        }
        return out;
    }

    /** True when the best fit is clear enough to open it without asking. */
    public static boolean clearWinner(List<Group> chats, String spoken) {
        List<Group> hits = findChats(chats, spoken);
        if (hits.isEmpty()) return false;
        if (hits.size() == 1) return true;
        String key = norm(chatAnswer(spoken));
        boolean exact = norm(hits.get(0).name).equals(key);
        return exact && !norm(hits.get(1).name).equals(key); // two chats with that exact name: ask
    }

    /** Every spoken word starts some word of the name ("old riv" fits "Old Man River"). */
    private static boolean allPrefixes(String[] said, String[] name) {
        for (String w : said) {
            boolean hit = false;
            for (String n : name) if (n.startsWith(w)) { hit = true; break; }
            if (!hit) return false;
        }
        return said.length > 0;
    }

    /** How well what was said fits the closest run of the name's words of the same length. */
    private static double bestWindow(String key, String name) {
        String[] n = name.split(" ");
        int k = key.split(" ").length;
        double best = 0;
        for (int i = 0; i + k <= n.length; i++) {
            best = Math.max(best, similarity(key, String.join(" ", java.util.Arrays.copyOfRange(n, i, i + k))));
        }
        return best;
    }

    /** 1.0 = identical, 0.0 = nothing alike (edit-distance based). */
    static double similarity(String a, String b) {
        int n = a.length(), m = b.length();
        if (n + m == 0) return 1.0;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - (double) prev[m] / Math.max(n, m);
    }
}
