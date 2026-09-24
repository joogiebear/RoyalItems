package com.mystipixel.royalitems;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class FormatListenerTest {

    @Test void partialPickupIsNotDressed() {
        FormattedItemService service = mock(FormattedItemService.class);
        when(service.worldEnabled(any())).thenReturn(true);
        Player player = mock(Player.class);
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(mock(ItemStack.class));
        EntityPickupItemEvent event = new EntityPickupItemEvent(player, entity, 54);

        new FormatListener(mock(RoyalItemsPlugin.class), service).onPickup(event);

        verify(service, never()).formatIfSupported(any(ItemStack.class));
        verify(entity, never()).setItemStack(any());
    }

    @Test void wholePickupIsDressed() {
        FormattedItemService service = mock(FormattedItemService.class);
        when(service.worldEnabled(any())).thenReturn(true);
        Player player = mock(Player.class);
        Item entity = mock(Item.class);
        ItemStack plain = mock(ItemStack.class);
        ItemStack dressed = mock(ItemStack.class);
        when(entity.getItemStack()).thenReturn(plain);
        when(service.formatIfSupported(plain)).thenReturn(dressed);
        EntityPickupItemEvent event = new EntityPickupItemEvent(player, entity, 0);

        new FormatListener(mock(RoyalItemsPlugin.class), service).onPickup(event);

        verify(entity).setItemStack(dressed);
    }
}
