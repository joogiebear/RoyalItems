package com.mystipixel.royalitems;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Dresses a supported item the instant it is created or acquired — mined, killed, harvested, crafted,
 * smithed, looted, taken from a furnace, picked up, or (via {@link #onInventoryClose}) bought, traded or
 * withdrawn from any GUI. There is no per-source opt-in: if the material is supported and the item is
 * still plain, it is dressed. This event coverage replaces the old polling sweep — the closing of a
 * container is exactly when a bought/traded item arrives, so no timer is needed. The service leaves any
 * item that already carries a name, lore, enchant, or another plugin's data untouched; disabled worlds
 * are skipped.
 */
public final class FormatListener implements Listener {

    private final RoyalItemsPlugin plugin;
    private final FormattedItemService service;

    public FormatListener(RoyalItemsPlugin plugin, FormattedItemService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!service.worldEnabled(event.getBlock().getWorld())) {
            return;
        }
        for (Item entity : event.getItems()) {
            ItemStack formatted = service.formatIfSupported(entity.getItemStack());
            if (formatted != entity.getItemStack()) {
                entity.setItemStack(formatted);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (service.worldEnabled(event.getEntity().getWorld())) {
            replaceIn(event.getDrops());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (service.worldEnabled(event.getPlayer().getWorld())) {
            replaceIn(event.getItemsHarvested());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (service.worldEnabled(event.getWhoClicked().getWorld())) {
            replaceCurrent(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        if (service.worldEnabled(event.getWhoClicked().getWorld())) {
            replaceCurrent(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        if (service.worldEnabled(event.getWorld())) {
            replaceIn(event.getLoot());
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
        if (service.worldEnabled(event.getWhoClicked().getWorld())) {
            replaceCurrent(event);
        }
    }

    /** Dress an item the instant a player picks it up off the ground (traded, tossed, or otherwise). */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !service.worldEnabled(player.getWorld())) {
            return;
        }
        Item entity = event.getItem();
        ItemStack formatted = service.formatIfSupported(entity.getItemStack());
        if (formatted != entity.getItemStack()) {
            entity.setItemStack(formatted);
        }
    }

    /**
     * Dress a player's inventory when they close any container — a bazaar, a trade, a chest, or their own
     * inventory. This is the moment a bought, traded or withdrawn item lands, so it is the catch-all that
     * the polling sweep used to be. The pass is scheduled one tick later so the closing transaction has
     * fully settled before the inventory is written.
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !service.worldEnabled(player.getWorld())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> service.formatInventory(player));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.updateBorderAudience(event.getPlayer());
        if (service.formatOnJoin() && service.worldEnabled(event.getPlayer().getWorld())) {
            service.formatInventory(event.getPlayer());
        }
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        plugin.updateBorderAudience(event.getPlayer());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        plugin.removeBorderAudience(event.getPlayer());
    }

    private void replaceIn(List<ItemStack> items) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack formatted = service.formatIfSupported(items.get(i));
            if (formatted != items.get(i)) {
                items.set(i, formatted);
            }
        }
    }

    private void replaceCurrent(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack formatted = service.formatIfSupported(current);
        if (current != null && formatted != current) {
            event.setCurrentItem(formatted);
        }
    }
}
