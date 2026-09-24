package com.mystipixel.royalitems;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CatalogExporterTest {
    @TempDir Path directory;

    @Test void bundledOverridesAreTheIdsTheExportSkips() throws Exception {
        try (var bundled = Files.newBufferedReader(Path.of("src/main/resources/items/0-overrides.yml"))) {
            assertEquals(Set.of("coal", "golden_apple", "enchanted_golden_apple", "nether_star", "elytra",
                    "totem_of_undying", "heart_of_the_sea", "beacon", "conduit", "netherite_ingot",
                    "ancient_debris", "dragon_egg", "experience_bottle"),
                    CatalogExporter.overriddenIds(directory.resolve("missing.yml").toFile(), bundled));
        }
    }

    @Test void theLiveOverridesFileWinsOverTheBundledOne() throws Exception {
        Path live = directory.resolve("0-overrides.yml");
        Files.writeString(live, "formatted-items:\n  coal: {}\n  Ender_Pearl: {}\n");
        assertEquals(Set.of("coal", "ender_pearl"), CatalogExporter.overriddenIds(live.toFile(),
                new StringReader("formatted-items:\n  beacon: {}\n")));
    }

    @Test void noOverridesFileMeansNothingIsSkipped() {
        assertEquals(Set.of(), CatalogExporter.overriddenIds(directory.resolve("missing.yml").toFile(), null));
    }
}
