package com.mystipixel.royalitems;

/**
 * A rarity tier: an id ({@code rare}), the line shown at the foot of an item's lore ({@code &9&lRARE}),
 * a colour ({@code &9}) a rule can tint a name with, and an optional {@code tooltipStyle} — a resource
 * location ({@code royalitems:rare}) for a custom tooltip border sprite. Defined once in
 * {@code rarities.yml} and referenced by rules and items, so the whole ladder is retuned in one place.
 *
 * <p>{@code tooltipStyle} is opt-in and null by default: an item only gets a coloured border when the
 * rarity names one AND the client's resource pack actually ships that sprite. A style pointing at a
 * missing sprite renders the missing-texture checkerboard, so it must never be set speculatively.
 */
public record Rarity(String id, String display, String color, String tooltipStyle) {

    public static final Rarity DEFAULT = new Rarity("common", "&f&lCOMMON", "&f", null);
}
