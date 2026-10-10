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

// Dresses supported plain items when they are created or acquired. Closing any container covers items
// bought, traded or withdrawn from a GUI.
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

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !service.worldEnabled(player.getWorld())) {
            return;
        }
        // Skip partial pickups: swapping the stack here skips the server's restore of the remainder and
        // the rest of the stack vanishes. The next inventory close dresses it.
        if (event.getRemaining() > 0) {
            return;
        }
        Item entity = event.getItem();
        ItemStack formatted = service.formatIfSupported(entity.getItemStack());
        if (formatted != entity.getItemStack()) {
            entity.setItemStack(formatted);
        }
    }

    // next tick, so the closing transaction has settled before the inventory is written
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

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        plugin.updateBorderAudience(event.getPlayer(), event.getNewGameMode());
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
