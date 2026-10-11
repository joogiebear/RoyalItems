package com.mystipixel.royalitems;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Dresses vanilla drops with lore and a PDC identity while keeping the Material, so a formatted coal is
 * still {@code Material.COAL}. {@link FormattedItemService} is published to the ServicesManager.
 */
public final class RoyalItemsPlugin extends JavaPlugin {

    // TODO: register at https://bstats.org and set this (0 = inert)
    private static final int BSTATS_PLUGIN_ID = 0;

    private FormattedItemService service;

    // Object, not a PacketEvents type, so this class loads without PacketEvents installed
    private Object tooltipBorders;
    private org.bukkit.scheduler.BukkitTask inventorySweep;
    private final java.util.Set<java.util.UUID> borderAudience = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copyDefaultItems();
        service = new FormattedItemService(this);
        int skipped = service.reloadSkippingInvalid();
        if (skipped > 0) {
            getLogger().warning(skipped + " broken catalog entr" + (skipped == 1 ? "y was" : "ies were")
                    + " skipped (see above). /royalitems reload refuses to load until they are fixed.");
        }

        getServer().getServicesManager().register(FormattedItemService.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new FormatListener(this, service), this);

        ItemCommand command = new ItemCommand(this, service, new MessageManager(this));
        getCommand("royalitems").setExecutor(command);
        getCommand("royalitems").setTabCompleter(command);

        setupTooltipBorders();
        setupInventorySweep();
        setupMetrics();
        if (getConfig().getBoolean("update-checker", true)) {
            new UpdateChecker(this, "joogiebear/RoyalItems").check();
        }

        getLogger().info("RoyalItems enabled: " + service.all().size() + " formatted item(s).");
    }

    @Override
    public void onDisable() {
        stopRuntimeTasks();
        getServer().getServicesManager().unregisterAll(this);
    }

    private void stopRuntimeTasks() {
        if (inventorySweep != null) {
            inventorySweep.cancel();
            inventorySweep = null;
        }
        if (tooltipBorders != null) {
            try {
                TooltipBorderListener.disable(tooltipBorders);
            } catch (RuntimeException | LinkageError ex) {
                getLogger().log(Level.WARNING, "Could not unregister PacketEvents borders", ex);
            } finally {
                tooltipBorders = null;
            }
        }
        borderAudience.clear();
    }

    void updateBorderAudience(org.bukkit.entity.Player player) {
        updateBorderAudience(player, player.getGameMode());
    }

    // Never for creative: the client sends whole stacks back, so a wire-only border would be saved into
    // the real item. On a change, re-send the inventory a tick later, once the new state has applied.
    void updateBorderAudience(org.bukkit.entity.Player player, org.bukkit.GameMode mode) {
        boolean show = mode != org.bukkit.GameMode.CREATIVE && service.worldEnabled(player.getWorld());
        boolean changed = show ? borderAudience.add(player.getUniqueId())
                : borderAudience.remove(player.getUniqueId());
        if (changed && tooltipBorders != null) {
            getServer().getScheduler().runTask(this, () -> {
                if (player.isOnline()) player.updateInventory();
            });
        }
    }

    void removeBorderAudience(org.bukkit.entity.Player player) {
        borderAudience.remove(player.getUniqueId());
    }

    // TooltipBorderListener is the only class that references PacketEvents; keep it that way so a
    // missing PacketEvents skips this step instead of failing to load the plugin.
    private void setupTooltipBorders() {
        if (!service.worldEnabled(null)) {
            return;
        }
        BorderSettings settings = BorderSettings.read(getConfig(), getLogger());
        if (!settings.active() || settings.styles().isEmpty()) {
            return;
        }
        if (!getServer().getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().warning("tooltip-borders is enabled but PacketEvents is not installed. Rarity "
                    + "borders will only appear on RoyalItems-dressed items. Install PacketEvents "
                    + "(https://modrinth.com/plugin/packetevents) to extend them to every item.");
            return;
        }
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) updateBorderAudience(player);
        try {
            tooltipBorders = TooltipBorderListener.enable(settings.styles(), List.copyOf(settings.context()),
                    settings.debug(), getLogger(), borderAudience::contains);
        } catch (RuntimeException | LinkageError ex) {
            getLogger().log(Level.WARNING, "PacketEvents borders unavailable; item formatting remains active", ex);
            return;
        }
        getLogger().info("Rarity tooltip borders enabled for " + settings.styles().size()
                + " rarities via PacketEvents (covers all items on the wire)."
                + (settings.debug() ? " [debug on]" : ""));
    }

    private void setupMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) {
            return;
        }
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SingleLineChart("formatted_items", () -> service.all().size()));
        metrics.addCustomChart(new SimplePie("format_on_join",
                () -> getConfig().getBoolean("format-on-join", false) ? "enabled" : "disabled"));
    }

    // Backstop for items injected with no event (/give, plugin APIs); FormatListener does the real work.
    private void setupInventorySweep() {
        long seconds = getConfig().getLong("format-sweep-seconds", 0);
        if (!service.worldEnabled(null) || seconds <= 0) {
            return;
        }
        long ticks = Math.min(seconds, Long.MAX_VALUE / 20) * 20L;
        inventorySweep = getServer().getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                if (service.worldEnabled(player.getWorld())) {
                    service.formatInventory(player);
                }
            }
        }, ticks, ticks);
        getLogger().info("Inventory backstop sweep every " + seconds + "s (event-driven dressing is primary).");
    }

    /** Other plugins should prefer the ServicesManager. */
    public FormattedItemService service() {
        return service;
    }

    /** Re-read config.yml and every items/**.yml. Throws and keeps the previous catalog on a bad file. */
    public void reloadFormatting() {
        // reloadConfig would log malformed YAML and silently substitute defaults, so reject it first
        try {
            new org.bukkit.configuration.file.YamlConfiguration().load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IllegalArgumentException("Cannot load config.yml: " + ex.getMessage(), ex);
        }
        String previousConfig = getConfig().saveToString();
        reloadConfig();
        try {
            service.reload();
        } catch (RuntimeException ex) {
            try {
                getConfig().loadFromString(previousConfig);
            } catch (org.bukkit.configuration.InvalidConfigurationException impossible) {
                ex.addSuppressed(impossible);
            }
            throw ex;
        }
        stopRuntimeTasks();
        setupTooltipBorders();
        setupInventorySweep();
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) player.updateInventory();
    }

    // items/*.yml is enumerated from the jar so new category files ship without a code change
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

