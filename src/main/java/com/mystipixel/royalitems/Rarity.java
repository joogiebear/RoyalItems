package com.mystipixel.royalitems;

/**
 * A rarity tier, e.g. id {@code rare}, display {@code &9&lRARE}, color {@code &9}, tooltipStyle
 * {@code royalitems:rare}. tooltipStyle may be null; only set it when the resource pack ships the sprite,
 * otherwise the client renders the missing-texture checkerboard.
 */
public record Rarity(String id, String display, String color, String tooltipStyle) {

    public static final Rarity DEFAULT = new Rarity("common", "&f&lCOMMON", "&f", null);
}
