package com.mystipixel.royalitems;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;

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

    /** Opaque handle to the PacketEvents border listener (null if off/absent). Kept as Object so this
     *  class never references a PacketEvents type — see {@link #setupTooltipBorders()}. */
    private Object tooltipBorders;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copyDefaultItems();
        service = new FormattedItemService(this);
        service.reload();

        getServer().getServicesManager().register(FormattedItemService.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new FormatListener(this, service), this);

        ItemCommand command = new ItemCommand(this, service);
        getCommand("royalitems").setExecutor(command);
        getCommand("royalitems").setTabCompleter(command);

        setupTooltipBorders();
        setupInventorySweep();
        setupMetrics();
        if (getConfig().getBoolean("update-checker", true)) {
            new UpdateChecker(this, "joogiebear/RoyalItems").check();
        }

        getLogger().info("RoyalItems enabled — " + service.all().size() + " formatted item(s).");
    }

    @Override
    public void onDisable() {
        if (tooltipBorders != null) {
            TooltipBorderListener.disable(tooltipBorders);
            tooltipBorders = null;
        }
    }

    /**
     * Turn on the universal rarity tooltip borders. Reads the rarity→style map from config into plain
     * strings (no PacketEvents types here), then — only if the PacketEvents plugin is installed — hands
     * off to {@link TooltipBorderListener#enable(Map)}. That class is the sole holder of PacketEvents
     * references, so a missing PacketEvents just skips this step instead of failing to load the plugin.
     */
    private void setupTooltipBorders() {
        if (!getConfig().getBoolean("tooltip-borders.enabled", true)) {
            return;
        }
        Map<String, String> styles = new HashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("tooltip-borders.styles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String location = section.getString(key);
                if (location != null && !location.isBlank()) {
                    styles.put(key.trim().toUpperCase(Locale.ROOT), location.trim());
                }
            }
        }
        if (styles.isEmpty()) {
            return;
        }
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().warning("tooltip-borders is enabled but PacketEvents is not installed — rarity "
                    + "borders will only appear on RoyalItems-dressed items. Install PacketEvents "
                    + "(https://modrinth.com/plugin/packetevents) to extend them to every item.");
            return;
        }
        java.util.List<String> context = getConfig().getStringList("tooltip-borders.context-keywords");
        if (context.isEmpty()) {
            context = java.util.List.of("TIER", "RARITY", "ROYAL");
        }
        boolean debug = getConfig().getBoolean("tooltip-borders.debug", false);
        tooltipBorders = TooltipBorderListener.enable(styles, context, debug, getLogger());
        getLogger().info("Rarity tooltip borders enabled for " + styles.size()
                + " rarities via PacketEvents (covers all items on the wire)."
                + (debug ? " [debug on]" : ""));
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

    /**
     * Optional periodic backstop. Dressing is normally event-driven (see {@link FormatListener} —
     * drop/craft/harvest/pickup dress instantly, and closing any container dresses the inventory), which
     * covers every normal way an item is obtained at zero idle cost. This timer is off by default
     * ({@code format-sweep-seconds: 0}); enable it only to also catch items injected straight into an
     * inventory with no GUI and no event (e.g. {@code /give} or a plugin API). {@code formatInventory}
     * skips already-dressed and foreign items and only writes when a stack changed, so it stays cheap.
     */
    private void setupInventorySweep() {
        long seconds = getConfig().getLong("format-sweep-seconds", 0);
        if (seconds <= 0) {
            return;
        }
        long ticks = seconds * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                if (service.worldEnabled(player.getWorld())) {
                    service.formatInventory(player);
                }
            }
        }, ticks, ticks);
        getLogger().info("Inventory backstop sweep every " + seconds + "s (event-driven dressing is primary).");
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

