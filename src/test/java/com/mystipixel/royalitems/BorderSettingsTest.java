package com.mystipixel.royalitems;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BorderSettingsTest {

    private static YamlConfiguration config(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test void activeOnlyWhenAllThreeOptInsAreOn() throws Exception {
        assertFalse(BorderSettings.read(config("tooltip-borders: {enabled: true, resource-pack-ready: true}"), null).active());
        assertTrue(BorderSettings.read(config("tooltip-borders: {enabled: true, resource-pack-ready: true,"
                + " include-custom-items: true}"), null).active());
    }

    @Test void stylesAreUpperCasedAndInvalidOnesDropped() throws Exception {
        var settings = BorderSettings.read(config("""
                tooltip-borders:
                  styles:
                    epic: royalitems:epic
                    Rare: ' royalitems:rare '
                    broken: 'Not A Key!'
                    blank: ''
                """), null);
        assertEquals(Map.of("EPIC", "royalitems:epic", "RARE", "royalitems:rare"), settings.styles());
    }

    @Test void contextKeywordsDefaultAndAreUpperCased() throws Exception {
        assertEquals(Set.copyOf(BorderSettings.DEFAULT_CONTEXT), BorderSettings.read(config("{}"), null).context());
        assertEquals(Set.of("GRADE"), BorderSettings.read(config("tooltip-borders: {context-keywords: [' grade']}"), null).context());
        assertEquals(List.of("TIER", "RARITY", "ROYAL"), BorderSettings.DEFAULT_CONTEXT);
    }
}
