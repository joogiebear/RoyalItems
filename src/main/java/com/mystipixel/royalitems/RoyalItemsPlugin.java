package com.mystipixel.royalitems;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Dresses vanilla drops with lore and a persistent-data identity, keeping the same Material so a formatted
 * coal is still {@code Material.COAL} to every other plugin — it just also reads as {@code item_id: coal}
 * and {@code fuel_id: coal}. The {@link FormattedItemService} is published to the Bukkit ServicesManager
 * so a soft-dependent plugin (RoyalMinions) can look it up and read a drop's identity.
 */
public final class RoyalItemsPlugin extends JavaPlugin {

    /** bStats project id for RoyalItems. TODO: register at https://bstats.org and set this (0 = inert). */
    private static final int BSTATS_PLUGIN_ID = 0;

    private FormattedItemService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copyDefaultItems();
        service = new FormattedItemService(this);
        service.reload();

        getServer().getServicesManager().register(FormattedItemService.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new FormatListener(service), this);

        ItemCommand command = new ItemCommand(this, service);
        getCommand("royalitems").setExecutor(command);
        getCommand("royalitems").setTabCompleter(command);

        setupMetrics();
        if (getConfig().getBoolean("update-checker", true)) {
            new UpdateChecker(this, "joogiebear/RoyalItems").check();
        }

        getLogger().info("RoyalItems enabled — " + service.all().size() + " formatted item(s).");
    }

    /** Anonymous usage stats via bStats. Inert until the project id is set; disable in plugins/bStats. */
    private void setupMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) {
            return;
        }
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SingleLineChart("formatted_items", () -> service.all().size()));
        metrics.addCustomChart(new SimplePie("format_on_join",
                () -> getConfig().getBoolean("format-on-join", false) ? "enabled" : "disabled"));
    }

    /** The formatting service, for direct in-JVM access (other plugins should prefer the ServicesManager). */
    public FormattedItemService service() {
        return service;
    }

    /** Re-read config.yml and every items/**.yml, and rebuild the item maps and templates. */
    public void reloadFormatting() {
        reloadConfig();
        service.reload();
    }

    /**
     * Drop the bundled defaults into the data folder on first run, without overwriting edits: the
     * rarity ladder, the rules, and every {@code items/*.yml} (enumerated from the jar so new category
     * files ship automatically as the bundle grows).
     */
    private void copyDefaultItems() {
        for (String top : new String[]{"rarities.yml", "rules.yml"}) {
            if (!new File(getDataFolder(), top).exists()) {
                saveResource(top, false);
            }
        }
        try (JarFile jar = new JarFile(getFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("items/") && name.endsWith(".yml")
                        && !new File(getDataFolder(), name).exists()) {
                    saveResource(name, false);
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not copy default item files from the jar", e);
        }
    }
}

