package com.mystipixel.royalitems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rarity-from-lore heuristic as one pure function, shared by the packet border listener and
 * {@code /royalitems inspect} — so the border a player sees and the explanation an admin reads can
 * never come from two drifting copies of the algorithm.
 *
 * <p>A rarity token counts when its word stands alone on a lore line (the eco/RoyalItems ladder
 * writes {@code &5&lEPIC}) or shares a line with a context keyword (EcoArmor writes
 * {@code Tier: Royal Legendary}); flavour text that merely mentions "a legendary blade" does not.
 * When several match, the highest-ranked tier wins; a token outside the known ladder (a custom
 * rarity) still wins when nothing ranked matches.
 */
public final class RarityDetect {

    /** Fixed low-to-high tier order, used to pick the strongest rarity when a lore declares several. */
    public static final List<String> RANK =
            List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    /** A verdict: the matched token, the sanitized line it matched on, and why the line counted. */
    public record Match(String rarity, String line, boolean standalone) {
    }

    private RarityDetect() {
    }

    /** Strip legacy {@code §x} codes, trim, and upper-case — eco lore ships literal section codes. */
    public static String sanitize(String raw) {
        return raw.replaceAll("§.", "").trim().toUpperCase(Locale.ROOT);
    }

    /** Convenience: sanitize a whole lore. */
    public static List<String> sanitizeAll(List<String> rawLines) {
        List<String> out = new ArrayList<>(rawLines.size());
        for (String raw : rawLines) {
            out.add(sanitize(raw));
        }
        return out;
    }

    /**
     * The strongest rarity declared by these (already sanitized) lines, or null when none is.
     * {@code contextKeywords} and {@code tokens} must be upper-case.
     */
    public static Match detect(List<String> sanitizedLines, Set<String> contextKeywords,
                               Collection<String> tokens) {
        Match best = null;
        for (String text : sanitizedLines) {
            if (text.isEmpty()) {
                continue;
            }
            Set<String> words = new HashSet<>(Arrays.asList(text.split("[^A-Z]+")));
            boolean standalone = words.size() == 1;
            boolean hasContext = !Collections.disjoint(words, contextKeywords);
            if (!standalone && !hasContext) {
                continue;   // a rarity word buried in flavour text is not a rarity declaration
            }
            for (String token : tokens) {
                if (words.contains(token) && rank(token) > (best == null ? Integer.MIN_VALUE : rank(best.rarity()))) {
                    best = new Match(token, text, standalone);
                }
            }
        }
        return best;
    }

    /**
     * Tier index of a rarity token; -1 for a token outside the ladder, so custom rarities lose to
     * every ranked tier but still beat "nothing" — previously they could never win at all.
     */
    public static int rank(String rarity) {
        return RANK.indexOf(rarity);
    }
}
