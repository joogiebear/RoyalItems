package com.mystipixel.royalitems;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Dresses supported items the moment they are created — never by scanning inventories. Each source only
 * touches items whose definition opts in to it: mined blocks, mob and harvest drops, and items pulled
 * off a crafting or smithing bench. Anything already carrying an identity (ours or another plugin's) is
 * left alone by {@link FormattedItemService#formatIfSupported}.
 */
public final class FormatListener implements Listener {

    private final FormattedItemService service;

    public FormatListener(FormattedItemService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        for (Item entity : event.getItems()) {
            ItemStack formatted = formatFor(entity.getItemStack(), FormattedItemDefinition.Source.MINED);
            if (formatted != null) {
                entity.setItemStack(formatted);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        replaceIn(event.getDrops(), FormattedItemDefinition.Source.MOB);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        replaceIn(event.getItemsHarvested(), FormattedItemDefinition.Source.HARVEST);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack formatted = formatFor(event.getCurrentItem(), FormattedItemDefinition.Source.CRAFT);
        if (formatted != null) {
            event.setCurrentItem(formatted);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        ItemStack formatted = formatFor(event.getCurrentItem(), FormattedItemDefinition.Source.SMITH);
        if (formatted != null) {
            event.setCurrentItem(formatted);
        }
    }

    private void replaceIn(List<ItemStack> items, FormattedItemDefinition.Source source) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack formatted = formatFor(items.get(i), source);
            if (formatted != null) {
                items.set(i, formatted);
            }
        }
    }

    /** Format only when this material's definition opts in to {@code source}; null = leave the item as-is. */
    private ItemStack formatFor(ItemStack stack, FormattedItemDefinition.Source source) {
        if (stack == null) {
            return null;
        }
        FormattedItemDefinition def = service.byMaterial(stack.getType());
        if (def == null || !def.formatsFrom(source)) {
            return null;
        }
        ItemStack formatted = service.formatIfSupported(stack);
        return formatted == stack ? null : formatted;   // unchanged (already custom) → nothing to write
    }
}
