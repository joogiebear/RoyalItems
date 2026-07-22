package com.mystipixel.royalitems;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Dresses supported drops the moment they are created — never by scanning inventories. Each source only
 * touches items whose definition opts in to it: mined blocks by default, mob and harvest drops only when
 * the config lists them. Anything already carrying an identity (ours or another plugin's) is left alone
 * by {@link FormattedItemService#formatIfSupported}.
 */
public final class DropFormatListener implements Listener {

    private final FormattedItemService service;

    public DropFormatListener(FormattedItemService service) {
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
        List<ItemStack> drops = event.getDrops();
        for (int i = 0; i < drops.size(); i++) {
            ItemStack formatted = formatFor(drops.get(i), FormattedItemDefinition.Source.MOB);
            if (formatted != null) {
                drops.set(i, formatted);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        List<ItemStack> harvested = event.getItemsHarvested();
        for (int i = 0; i < harvested.size(); i++) {
            ItemStack formatted = formatFor(harvested.get(i), FormattedItemDefinition.Source.HARVEST);
            if (formatted != null) {
                harvested.set(i, formatted);
            }
        }
    }

    /** Format only when this material's definition opts in to {@code source}; null = leave the drop as-is. */
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
