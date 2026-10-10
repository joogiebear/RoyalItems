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
 * Rarity-from-lore heuristic shared by the packet borders and {@code /royalitems inspect}.
 *
 * <p>A token counts when it stands alone on a lore line ({@code &5&lEPIC}) or shares a line with a
 * context keyword ({@code Tier: Royal Legendary}). The highest-ranked tier wins; an unranked custom
 * rarity wins only when nothing ranked matches.
 */
public final class RarityDetect {

    /** Low-to-high tier order. */
    public static final List<String> RANK =
            List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    /** {@code standalone} is false when the line counted because of a context keyword. */
    public record Match(String rarity, String line, boolean standalone) {
    }

    private RarityDetect() {
    }

    /** Strip legacy {@code §x} codes, trim and upper-case (eco lore ships literal section codes). */
    public static String sanitize(String raw) {
        return raw.replaceAll("§.", "").trim().toUpperCase(Locale.ROOT);
    }

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
            words.remove("");
            boolean standalone = words.size() == 1;
            boolean hasContext = !Collections.disjoint(words, contextKeywords);
            if (!standalone && !hasContext) {
                continue;   // "a legendary blade" in flavour text is not a declaration
            }
            for (String token : tokens) {
                if (words.contains(token) && rank(token) > (best == null ? Integer.MIN_VALUE : rank(best.rarity()))) {
                    best = new Match(token, text, standalone);
                }
            }
        }
        return best;
    }

    /** Tier index, or -1 for a custom rarity so it loses to every ranked tier but still beats none. */
    public static int rank(String rarity) {
        return RANK.indexOf(rarity);
    }
}
