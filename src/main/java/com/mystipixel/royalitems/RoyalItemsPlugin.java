package com.mystipixel.royalitems;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Dresses vanilla drops with lore and a persistent-data identity, keeping the same Material so a formatted
 * coal is still {@code Material.COAL} to every other plugin — it just also reads as {@code item_id: coal}
 * and {@code fuel_id: coal}. The {@link FormattedItemService} is published to the Bukkit ServicesManager
 * so a soft-dependent plugin (RoyalMinions) can look it up and read a drop's identity.
 */
public final class RoyalItemsPlugin extends JavaPlugin {

    private FormattedItemService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new FormattedItemService(this);
        service.load(getConfig());

        getServer().getServicesManager().register(FormattedItemService.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new DropFormatListener(service), this);

        ItemCommand command = new ItemCommand(this, service);
        getCommand("royalitems").setExecutor(command);
        getCommand("royalitems").setTabCompleter(command);

        getLogger().info("RoyalItems enabled — " + service.all().size() + " formatted item(s).");
    }

    /** The formatting service, for direct in-JVM access (other plugins should prefer the ServicesManager). */
    public FormattedItemService service() {
        return service;
    }

    /** Re-read config.yml and rebuild the item maps and templates. */
    public void reloadFormatting() {
        reloadConfig();
        service.load(getConfig());
    }
}
