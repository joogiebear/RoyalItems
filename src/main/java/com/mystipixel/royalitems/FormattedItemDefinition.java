package com.mystipixel.royalitems;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One entry from {@code config.yml}: a vanilla {@link Material} plus the visual dressing (name, lore) and
 * the identity tags to stamp into a formatted copy of it. The identity is the {@code tags} — always at
 * least {@code item_id} — written to the item's persistent data; the name and lore are only what a player
 * sees. {@link #sources()} is which gameplay drops get formatted (mined by default), so a coal from a
 * pickaxe can be dressed while a coal from a burning mob is left plain unless the config opts in.
 */
public final class FormattedItemDefinition {

    /** Where a formatted item may be created — a drop, or a craft/smith bench. */
    public enum Source {
        MINED, MOB, HARVEST, CRAFT, SMITH;

        public static Source from(String raw) {
            return switch (raw == null ? "" : raw.trim().toLowerCase()) {
                case "mined", "mine", "block", "break" -> MINED;
                case "mob", "kill", "entity", "death" -> MOB;
                case "harvest", "harvested" -> HARVEST;
                case "craft", "crafted", "workbench" -> CRAFT;
                case "smith", "smithing", "upgrade" -> SMITH;
                default -> null;
            };
        }
    }

    private final String id;
    private final Material material;
    private final String displayName;      // legacy '&' string, or null to leave the item's own name
    private final List<String> lore;       // legacy '&' strings
    private final Map<String, String> tags; // PDC key -> value; always contains "item_id"
    private final Set<Source> sources;

    public FormattedItemDefinition(String id, Material material, String displayName,
                                   List<String> lore, Map<String, String> tags, Set<Source> sources) {
        this.id = id;
        this.material = material;
        this.displayName = displayName;
        this.lore = lore;
        this.tags = tags;
        this.sources = sources;
    }

    public String id() {
        return id;
    }

    public Material material() {
        return material;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> lore() {
        return lore;
    }

    public Map<String, String> tags() {
        return tags;
    }

    public String tag(String key) {
        return tags.get(key);
    }

    public boolean formatsFrom(Source source) {
        return sources.contains(source);
    }
}
