package com.mystipixel.royalitems;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Dresses supported items the moment they are created or acquired — never by scanning inventories on a
 * timer. The granular creation events (mined / mob / harvest / craft / smith) respect each item's
 * {@code format-on} opt-in; the acquisition moments (chest loot, a furnace's output, and the optional
 * format-on-join) dress any supported item, since "you now hold this" is when everything should match.
 * Anything already carrying an identity is left alone by the service, and a disabled world is skipped.
 */
public final class FormatListener implements Listener {

    private final FormattedItemService service;

    public FormatListener(FormattedItemService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!service.worldEnabled(event.getBlock().getWorld())) {
            return;
        }
        for (Item entity : event.getItems()) {
            ItemStack formatted = formatFor(entity.getItemStack(), FormattedItemDefinition.Source.MINED);
            if (formatted != null) {
                entity.setItemStack(formatted);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (service.worldEnabled(event.getEntity().getWorld())) {
            replaceIn(event.getDrops(), FormattedItemDefinition.Source.MOB);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (service.worldEnabled(event.getPlayer().getWorld())) {
            replaceIn(event.getItemsHarvested(), FormattedItemDefinition.Source.HARVEST);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!service.worldEnabled(event.getWhoClicked().getWorld())) {
            return;
        }
        ItemStack formatted = formatFor(event.getCurrentItem(), FormattedItemDefinition.Source.CRAFT);
        if (formatted != null) {
            event.setCurrentItem(formatted);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        if (!service.worldEnabled(event.getWhoClicked().getWorld())) {
            return;
        }
        ItemStack formatted = formatFor(event.getCurrentItem(), FormattedItemDefinition.Source.SMITH);
        if (formatted != null) {
            event.setCurrentItem(formatted);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        if (event.getWorld() != null && !service.worldEnabled(event.getWorld())) {
            return;
        }
        List<ItemStack> loot = event.getLoot();
        for (int i = 0; i < loot.size(); i++) {
            ItemStack formatted = service.formatIfSupported(loot.get(i));
            if (formatted != loot.get(i)) {
                loot.set(i, formatted);
            }
        }
    }

    /** Dress the smelted item as it is taken from a furnace / blast furnace / smoker output slot. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnaceTake(InventoryClickEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.RESULT) {
            return;
        }
        InventoryType type = event.getInventory().getType();
        if (type != InventoryType.FURNACE && type != InventoryType.BLAST_FURNACE && type != InventoryType.SMOKER) {
            return;
        }
        if (!service.worldEnabled(event.getWhoClicked().getWorld())) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack formatted = service.formatIfSupported(current);
        if (current != null && formatted != current) {
            event.setCurrentItem(formatted);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (service.formatOnJoin() && service.worldEnabled(event.getPlayer().getWorld())) {
            service.formatInventory(event.getPlayer());
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
