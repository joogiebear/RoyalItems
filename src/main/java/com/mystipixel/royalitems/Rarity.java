package com.mystipixel.royalitems;

/**
 * A rarity tier: an id ({@code rare}), the line shown at the foot of an item's lore ({@code &9&lRARE}),
 * and a colour ({@code &9}) a rule can tint a name with. Defined once in {@code rarities.yml} and
 * referenced by rules and items, so the whole ladder is retuned in one place.
 */
public record Rarity(String id, String display, String color) {

    public static final Rarity DEFAULT = new Rarity("common", "&f&lCOMMON", "&f");
}
