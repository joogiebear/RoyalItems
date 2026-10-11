package com.mystipixel.royalitems;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemCommandTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    private MessageManager messages() {
        return TestMessages.bundled(directory.resolve("messages").toFile());
    }

    // stack double that remembers its amount and clones into new doubles
    private static ItemStack stack(int maxStack) {
        ItemStack stack = mock(ItemStack.class);
        int[] amount = {1};
        when(stack.getMaxStackSize()).thenReturn(maxStack);
        when(stack.getAmount()).thenAnswer(i -> amount[0]);
        doAnswer(i -> { amount[0] = i.getArgument(0); return null; }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenAnswer(i -> stack(maxStack));
        return stack;
    }

    private List<Integer> give(int maxStack, String amount) {
        List<Integer> given = new ArrayList<>();
        FormattedItemService service = mock(FormattedItemService.class);
        ItemStack template = stack(maxStack);
        when(service.format("iron_sword", 1)).thenReturn(template);
        Player target = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(target.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(i -> {
            given.add(((ItemStack) i.getArgument(0)).getAmount());
            return new HashMap<>();
        });
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
            new ItemCommand(mock(RoyalItemsPlugin.class), service, messages())
                    .onCommand(sender, mock(Command.class), "royalitems",
                            new String[]{"give", "Steve", "iron_sword", amount});
        }
        return given;
    }

    private List<String> inspect(String sub) {
        FormattedItemService service = mock(FormattedItemService.class);
        ItemStack held = mock(ItemStack.class);
        org.bukkit.Material stone = spy(org.bukkit.Material.STONE);   // isAir() needs a server; stub it
        doReturn(false).when(stone).isAir();
        when(held.getType()).thenReturn(stone);
        when(held.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class));
        when(service.getItemId(held)).thenReturn("coal");
        when(service.getTag(held, "bonus")).thenReturn("5");
        when(service.byId("coal")).thenReturn(new FormattedItemDefinition("coal", org.bukkit.Material.STONE,
                "Coal", List.of(), java.util.Map.of("item_id", "coal", "bonus", "5"), null));
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(player.hasPermission(anyString())).thenReturn(true);
        List<String> messages = new ArrayList<>();
        doAnswer(i -> messages.add(i.getArgument(0))).when(player).sendMessage(anyString());
        RoyalItemsPlugin plugin = mock(RoyalItemsPlugin.class);
        when(plugin.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        new ItemCommand(plugin, service, messages()).onCommand(player, mock(Command.class), "royalitems", new String[]{sub});
        return messages;
    }

    @Test void infoIsTheSameInspectionAndShowsExtraTags() {
        List<String> inspected = inspect("inspect");
        assertEquals(inspected, inspect("info"));
        assertTrue(inspected.stream().anyMatch(line -> line.contains("bonus: ") && line.endsWith("5")), inspected::toString);
    }

    @Test void inspectShowsTheSameTextAsBefore() {
        org.bukkit.ChatColor gray = org.bukkit.ChatColor.GRAY;
        org.bukkit.ChatColor white = org.bukkit.ChatColor.WHITE;
        org.bukkit.ChatColor yellow = org.bukkit.ChatColor.YELLOW;
        assertEquals(List.of(
                org.bukkit.ChatColor.GOLD + "" + org.bukkit.ChatColor.BOLD + "Item Inspection",
                gray + "Material: " + white + "STONE",
                gray + "item_id:  " + white + "coal",
                gray + "bonus: " + white + "5",
                gray + "State:    " + yellow + "dressed, but the definition now uses STONE; left as-is",
                gray + "Border:   " + yellow
                        + "vanilla: custom packet borders are not opted in (rarity lore still works)"),
                inspect("inspect"));
    }

    @Test void giveAndUsageShowTheSameTextAsBefore() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        List<String> sent = new ArrayList<>();
        doAnswer(i -> sent.add(i.getArgument(0))).when(sender).sendMessage(anyString());
        FormattedItemService service = mock(FormattedItemService.class);
        ItemCommand command = new ItemCommand(mock(RoyalItemsPlugin.class), service, messages());
        try (var bukkit = mockStatic(Bukkit.class)) {
            command.onCommand(sender, mock(Command.class), "ri", new String[]{});
            command.onCommand(sender, mock(Command.class), "ri", new String[]{"give", "Steve"});
            command.onCommand(sender, mock(Command.class), "ri", new String[]{"give", "Steve", "coal"});
        }
        assertEquals(List.of(
                org.bukkit.ChatColor.GRAY + "Usage: /ri <give|reload|inspect|formatinv|export>",
                org.bukkit.ChatColor.GRAY + "Usage: /royalitems give <player> <id> [amount]",
                org.bukkit.ChatColor.RED + "Player 'Steve' is not online."), sent);
    }

    @Test void unstackableItemsAreGivenOnePerStack() {
        assertEquals(List.of(1, 1, 1), give(1, "3"));
    }

    @Test void largeAmountsAreSplitAtTheItemsStackSize() {
        assertEquals(List.of(16, 16, 16, 16), give(16, "64"));
        assertEquals(List.of(64), give(64, "64"));
    }
}
