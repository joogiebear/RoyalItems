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

    /** A stack double that remembers its amount and clones into new doubles. */
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
            new ItemCommand(mock(RoyalItemsPlugin.class), service)
                    .onCommand(sender, mock(Command.class), "royalitems",
                            new String[]{"give", "Steve", "iron_sword", amount});
        }
        return given;
    }

    @Test void unstackableItemsAreGivenOnePerStack() {
        assertEquals(List.of(1, 1, 1), give(1, "3"));
    }

    @Test void largeAmountsAreSplitAtTheItemsStackSize() {
        assertEquals(List.of(16, 16, 16, 16), give(16, "64"));
        assertEquals(List.of(64), give(64, "64"));
    }
}
