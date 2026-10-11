package dev.scrapsky.games;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Order-2 letter Markov chain trained on No Man's Sky's own proper nouns. */
public final class PlanetNames {
    private final Map<String, List<Character>> next = new HashMap<>();
    private final List<String> words;

    public PlanetNames(List<String> vocabulary) {
        this.words = vocabulary;
        for (String w : vocabulary) {
            String s = "^^" + w.toLowerCase(Locale.ROOT) + "$";
            for (int i = 2; i < s.length(); i++) next.computeIfAbsent(s.substring(i - 2, i), k -> new ArrayList<>()).add(s.charAt(i));
        }
    }

    public boolean usable() { return words.size() >= 20; }

    public String name(long seed) {
        Random r = new Random(seed);
        for (int attempt = 0; attempt < 60; attempt++) {
            StringBuilder b = new StringBuilder();
            String ctx = "^^";
            while (b.length() < 12) {
                List<Character> opts = next.get(ctx);
                if (opts == null) break;
                char c = opts.get(r.nextInt(opts.size()));
                if (c == '$') break;
                b.append(c);
                ctx = ctx.substring(1) + c;
            }
            String n = b.toString();
            if (n.length() < 4 || n.length() > 10 || words.contains(n)) continue;
            String name = Character.toUpperCase(n.charAt(0)) + n.substring(1);
            int suffix = r.nextInt(6);
            return switch (suffix) {
                case 0 -> name + " Prime";
                case 1 -> name + " " + new String[]{"II", "III", "IV", "V", "VI", "IX", "XI"}[r.nextInt(7)];
                case 2 -> name + "-" + (char) ('A' + r.nextInt(26)) + (r.nextInt(9) + 1);
                default -> name;
            };
        }
        return null;
    }
}
