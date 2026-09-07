package com.mystipixel.royalitems;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RarityDetectTest {
    private RarityDetect.Match detect(String... lines) {
        return RarityDetect.detect(RarityDetect.sanitizeAll(List.of(lines)), Set.of("TIER", "RARITY", "ROYAL"), RarityDetect.RANK);
    }

    @Test void recognizesDecoratedStandaloneLabels() {
        assertEquals("EPIC", detect("§5§l[EPIC]").rarity());
        assertEquals("UNCOMMON", detect("★ Uncommon ★").rarity());
    }

    @Test void flavorTextDoesNotBecomeRarity() {
        assertNull(detect("An epic adventure awaits"));
        assertNull(detect("Rarely found in the wild"));
    }

    @Test void strongestContextualRarityWins() {
        assertEquals("LEGENDARY", detect("COMMON", "Tier: Royal Legendary").rarity());
    }
}
