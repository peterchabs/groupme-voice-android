package io.github.peterchabs.groupmevoice;

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
        this.group = group;
        this.text = text;
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern PREFIX = Pattern.compile("^\\s*[\\w-]+=:=\\s*");
    private static final Pattern LEAD = Pattern.compile(
            "^\\s*(?:hey google[,\\s]*|ok google[,\\s]*)?"
            + "(?:please\\s+)?(?:groupme\\s+)?"
            + "(?:(?:send|text|post|message)\\s+(?:a\\s+)?(?:groupme\\s+)?(?:message\\s+)?(?:(?:to|in)\\s+)?)?"
            + "(?:the\\s+)?(?:groupme\\s+)?(?:group\\s+)?", CI);
    private static final Pattern SPLIT = Pattern.compile(
            "\\s+(?:that says|saying|which says|and say|message)\\s*[:,]?\\s+", CI);
    private static final Pattern ON_GROUPME = Pattern.compile("\\s+on groupme$", CI);
    private static final Pattern GROUP_WORD = Pattern.compile("\\s+(?:group|chat)$", CI);

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
                StringBuilder sb = new StringBuilder("Which one? ");
                for (int i = 0; i < Math.min(3, cands.size()); i++) {
                    if (i > 0) sb.append(" or ");
                    sb.append(byName.get(cands.get(i)).get(0).name);
                }
                return new Match(null, sb.append(". Say more of the name.").toString());
            }
            hits = byName.get(cands.get(0));
        }
        if (hits.size() > 1) {
            return new Match(null, "You have " + hits.size() + " groups named \""
                    + hits.get(0).name + "\". Rename one in GroupMe.");
        }
        return new Match(hits.get(0), null);
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
