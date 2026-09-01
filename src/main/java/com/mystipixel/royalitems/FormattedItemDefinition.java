package com.mystipixel.royalitems;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * One entry: a vanilla {@link Material} plus the visual dressing (name, lore) and the identity tags to
 * stamp into a formatted copy of it. The identity is the {@code tags} — always at least {@code item_id} —
 * written to the item's persistent data; the name and lore are only what a player sees. Supported items
 * always format, no matter how they are obtained; there is no per-source opt-in.
 */
public final class FormattedItemDefinition {

    private final String id;
    private final Material material;
    private final String displayName;      // legacy '&' string, or null to leave the item's own name
    private final List<String> lore;       // legacy '&' strings
    private final Map<String, String> tags; // PDC key -> value; always contains "item_id"
    private final String tooltipStyle;     // resource location for a custom tooltip border, or null

    public FormattedItemDefinition(String id, Material material, String displayName, List<String> lore,
                                   Map<String, String> tags, String tooltipStyle) {
        this.id = id;
        this.material = material;
        this.displayName = displayName;
        this.lore = lore;
        this.tags = tags;
        this.tooltipStyle = tooltipStyle;
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

    /** The custom tooltip-border style (a resource location like {@code royalitems:rare}), or null. */
    public String tooltipStyle() {
        return tooltipStyle;
    }
}
