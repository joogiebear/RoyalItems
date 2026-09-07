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
        this.lore = List.copyOf(lore);
        this.tags = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(tags));
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

    private String hash;

    /**
     * Content stamp over every field this definition owns, as a short hex string. Dressing writes it
     * into the item's persistent data; the event paths compare it against the current definition and
     * re-dress on mismatch — which is how a config edit reaches items dropped before it was made.
     */
    public String hash() {
        if (hash == null) {
            StringBuilder sb = new StringBuilder(id).append('\0').append(material.name()).append('\0')
                    .append(displayName == null ? "" : displayName).append('\0');
            for (String line : lore) {
                sb.append(line).append('\0');
            }
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                sb.append(tag.getKey()).append('=').append(tag.getValue()).append('\0');
            }
            sb.append(tooltipStyle == null ? "" : tooltipStyle);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hash = Long.toHexString(crc.getValue());
        }
        return hash;
    }
}
