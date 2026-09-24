package com.mystipixel.royalitems;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeReloadTest {
    @TempDir Path directory;
    private RoyalItemsPlugin plugin;
    private FormattedItemService service;
    private YamlConfiguration config;
    private BukkitScheduler scheduler;
    private BukkitTask task;

    @BeforeEach void setup() throws Exception {
        plugin = mock(RoyalItemsPlugin.class);
        service = mock(FormattedItemService.class);
        config = new YamlConfiguration();
        config.set("tooltip-borders.enabled", false);
        scheduler = mock(BukkitScheduler.class);
        task = mock(BukkitTask.class);
        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(service.worldEnabled(null)).thenReturn(true);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(task);
        field("service", service);
        field("borderAudience", ConcurrentHashMap.newKeySet());
        doCallRealMethod().when(plugin).reloadFormatting();
        Files.writeString(directory.resolve("config.yml"), "enabled: true\n");
    }

    private void field(String name, Object value) throws Exception {
        var field = RoyalItemsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    @Test void reloadStartsReschedulesAndStopsSweep() {
        config.set("format-sweep-seconds", 5);
        plugin.reloadFormatting();
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(100L), eq(100L));
        config.set("format-sweep-seconds", 10);
        plugin.reloadFormatting();
        verify(task).cancel();
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(200L), eq(200L));
        config.set("format-sweep-seconds", 0);
        plugin.reloadFormatting();
        verify(task, times(2)).cancel();
        verify(scheduler, times(2)).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
    }

    @Test void rejectedCatalogDoesNotCancelExistingSweep() {
        config.set("format-sweep-seconds", 5);
        plugin.reloadFormatting();
        doThrow(new IllegalArgumentException("broken item file")).when(service).reload();
        assertThrows(IllegalArgumentException.class, plugin::reloadFormatting);
        verify(task, never()).cancel();
    }

    @Test void malformedMainConfigDoesNotReloadCatalogOrTasks() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "enabled: [broken");
        assertThrows(IllegalArgumentException.class, plugin::reloadFormatting);
        verify(service, never()).reload();
        verifyNoInteractions(scheduler);
    }

    @Test void disabledMasterDoesNotScheduleSweep() {
        when(service.worldEnabled(null)).thenReturn(false);
        config.set("format-sweep-seconds", 5);
        plugin.reloadFormatting();
        verifyNoInteractions(scheduler);
    }

    @Test void oldEnabledFlagAloneCannotActivatePacketBorders() {
        config.set("tooltip-borders.enabled", true);
        config.set("tooltip-borders.styles.EPIC", "royalitems:epic");
        try (var borders = mockStatic(TooltipBorderListener.class)) {
            plugin.reloadFormatting();
            borders.verifyNoInteractions();
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<java.util.UUID> audience() throws Exception {
        var field = RoyalItemsPlugin.class.getDeclaredField("borderAudience");
        field.setAccessible(true);
        return (java.util.Set<java.util.UUID>) field.get(plugin);
    }

    private org.bukkit.entity.Player player(org.bukkit.GameMode mode) {
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.getGameMode()).thenReturn(mode);
        when(player.isOnline()).thenReturn(true);
        when(service.worldEnabled(any())).thenReturn(true);
        return player;
    }

    @Test void creativePlayersAreNeverShownPacketBorders() throws Exception {
        doCallRealMethod().when(plugin).updateBorderAudience(any());
        doCallRealMethod().when(plugin).updateBorderAudience(any(), any());
        var creative = player(org.bukkit.GameMode.CREATIVE);
        var survival = player(org.bukkit.GameMode.SURVIVAL);
        plugin.updateBorderAudience(creative);
        plugin.updateBorderAudience(survival);
        assertFalse(audience().contains(creative.getUniqueId()));
        assertTrue(audience().contains(survival.getUniqueId()));
    }

    @Test void enteringCreativeResendsACleanInventory() throws Exception {
        doCallRealMethod().when(plugin).updateBorderAudience(any(), any());
        var player = player(org.bukkit.GameMode.SURVIVAL);
        plugin.updateBorderAudience(player, org.bukkit.GameMode.SURVIVAL);
        field("tooltipBorders", new Object());

        plugin.updateBorderAudience(player, org.bukkit.GameMode.CREATIVE);
        assertFalse(audience().contains(player.getUniqueId()));
        var resend = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), resend.capture());
        resend.getValue().run();
        verify(player).updateInventory();

        plugin.updateBorderAudience(player, org.bukkit.GameMode.ADVENTURE);   // borders come back
        assertTrue(audience().contains(player.getUniqueId()));
        plugin.updateBorderAudience(player, org.bukkit.GameMode.SURVIVAL);    // no change in audience, no resend
        verify(scheduler, times(2)).runTask(eq(plugin), any(Runnable.class));
    }

    @Test void joiningPlayerIsResentTheirInventoryWithBorders() throws Exception {
        doCallRealMethod().when(plugin).updateBorderAudience(any(), any());
        field("tooltipBorders", new Object());
        var player = player(org.bukkit.GameMode.SURVIVAL);
        plugin.updateBorderAudience(player, org.bukkit.GameMode.SURVIVAL);
        assertTrue(audience().contains(player.getUniqueId()));
        verify(scheduler).runTask(eq(plugin), any(Runnable.class));
    }

    @Test void noResendWhileBordersAreOff() throws Exception {
        doCallRealMethod().when(plugin).updateBorderAudience(any(), any());
        plugin.updateBorderAudience(player(org.bukkit.GameMode.SURVIVAL), org.bukkit.GameMode.SURVIVAL);
        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
    }

    @Test void reloadReplacesAndDisablesPacketListener() {
        config.set("tooltip-borders.resource-pack-ready", true);
        config.set("tooltip-borders.include-custom-items", true);
        config.set("tooltip-borders.enabled", true);
        config.set("tooltip-borders.styles.EPIC", "royalitems:epic");
        when(plugin.getServer().getPluginManager().isPluginEnabled("packetevents")).thenReturn(true);
        Object first = new Object();
        Object second = new Object();
        try (var borders = mockStatic(TooltipBorderListener.class)) {
            borders.when(() -> TooltipBorderListener.enable(anyMap(), anyList(), anyBoolean(), any(), any()))
                    .thenReturn(first, second);
            plugin.reloadFormatting();
            plugin.reloadFormatting();
            borders.verify(() -> TooltipBorderListener.disable(first));
            config.set("tooltip-borders.enabled", false);
            plugin.reloadFormatting();
            borders.verify(() -> TooltipBorderListener.disable(second));
            borders.verify(() -> TooltipBorderListener.enable(anyMap(), anyList(), anyBoolean(), any(), any()), times(2));
        }
    }
}
