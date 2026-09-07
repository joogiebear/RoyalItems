package com.mystipixel.royalitems;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service-level regressions using isolated Bukkit doubles; not a substitute for Paper gameplay tests. */
class FormattedItemServiceTest {
    @TempDir Path directory;
    private FormattedItemService service;
    private final Map<ItemStack, StackState> states = new IdentityHashMap<>();
    private final Map<Material, Material> materials = new EnumMap<>(Material.class);

    private Material testMaterial(Material value) {
        return materials.computeIfAbsent(value, original -> {
            Material copy = spy(original);
            doReturn(false).when(copy).isAir();
            doReturn(true).when(copy).isItem();
            return copy;
        });
    }

    @BeforeEach void setup() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("RoyalItems");
        when(plugin.namespace()).thenReturn("royalitems");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        service = new FormattedItemService(plugin);
    }

    private FormattedItemDefinition define(Material material, String name, List<String> lore, Map<String, String> extras) throws Exception {
        material = testMaterial(material);
        String id = material.name().toLowerCase(Locale.ROOT);
        Map<String, String> tags = new HashMap<>(extras);
        tags.put("item_id", id);
        var definition = new FormattedItemDefinition(id, material, name, lore, tags, null);
        this.<Map<String, FormattedItemDefinition>>field("byId").put(id, definition);
        this.<Map<Material, FormattedItemDefinition>>field("byMaterial").put(material, definition);
        Class<? extends ItemMeta> templateMeta = material.name().endsWith("SHULKER_BOX") ? BlockStateMeta.class
                : material.name().endsWith("SWORD") ? Damageable.class : ItemMeta.class;
        StackState template = new StackState(material, templateMeta);
        template.name = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        template.lore = lore.stream().<Component>map(line -> net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(line).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList();
        tags.forEach((key, value) -> template.tags.put(new NamespacedKey("royalitems", key), value));
        template.tags.put(new NamespacedKey("royalitems", "def_hash"), definition.hash());
        this.<Map<String, ItemStack>>field("templates").put(id, template.stack);
        return definition;
    }

    @SuppressWarnings("unchecked") private <T> T field(String name) throws Exception {
        var field = FormattedItemService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(service);
    }

    @Test void dressingPreservesContainerStateAndDoesNotMutateInput() throws Exception {
        define(Material.SHULKER_BOX, "Box", List.of("Common"), Map.of());
        StackState original = new StackState(Material.SHULKER_BOX, BlockStateMeta.class);
        ItemStack result = service.formatIfSupported(original.stack);
        assertNotSame(original.stack, result);
        assertSame(original.blockState, ((BlockStateMeta) result.getItemMeta()).getBlockState());
        assertNull(original.name);
        assertTrue(original.tags.isEmpty());
        assertEquals("shulker_box", service.getItemId(result));
    }

    @Test void dressingAndRefreshPreserveDamageAndAmount() throws Exception {
        define(Material.DIAMOND_SWORD, "Sword", List.of("Old"), Map.of());
        StackState original = new StackState(Material.DIAMOND_SWORD, Damageable.class);
        original.damage = 413;
        original.amount = 1;
        ItemStack dressed = service.formatIfSupported(original.stack);
        define(Material.DIAMOND_SWORD, "Sword", List.of("New"), Map.of());
        ItemStack refreshed = service.formatIfSupported(dressed);
        assertEquals(413, ((Damageable) refreshed.getItemMeta()).getDamage());
        assertEquals(1, refreshed.getAmount());
        assertEquals(413, original.damage);
    }

    @Test void playerNameAndForeignLoreSurviveRepeatedRefreshes() throws Exception {
        define(Material.DIAMOND_SWORD, "Sword", List.of("Old"), Map.of());
        ItemStack dressed = service.formatIfSupported(new StackState(Material.DIAMOND_SWORD, Damageable.class).stack);
        StackState owned = states.get(dressed);
        owned.name = Component.text("Excalibur");
        owned.lore = List.of(Component.text("Quest reward"));
        define(Material.DIAMOND_SWORD, "New sword", List.of("New"), Map.of());
        ItemStack refreshed = service.formatIfSupported(dressed);
        define(Material.DIAMOND_SWORD, "Another name", List.of("Again"), Map.of());
        ItemStack again = service.formatIfSupported(refreshed);
        assertEquals(Component.text("Excalibur"), again.getItemMeta().displayName());
        assertEquals(List.of(Component.text("Quest reward")), again.getItemMeta().lore());
    }

    @Test void unchangedOwnedPresentationRefreshesAndRemovedTagsDisappear() throws Exception {
        define(Material.COAL, "Coal", List.of("Old"), Map.of("fuel_id", "coal", "bonus", "5"));
        ItemStack dressed = service.formatIfSupported(new StackState(Material.COAL, ItemMeta.class).stack);
        states.get(dressed).tags.put(new NamespacedKey("quests", "progress"), "42");
        define(Material.COAL, "New coal", List.of("New"), Map.of());
        ItemStack refreshed = service.formatIfSupported(dressed);
        assertNull(service.getFuelId(refreshed));
        assertNull(service.getTag(refreshed, "bonus"));
        assertEquals("42", states.get(refreshed).tags.get(new NamespacedKey("quests", "progress")));
        assertNotEquals(dressed.getItemMeta().displayName(), refreshed.getItemMeta().displayName());
        assertNotEquals(dressed.getItemMeta().lore(), refreshed.getItemMeta().lore());
        assertSame(refreshed, service.formatIfSupported(refreshed));
        assertEquals("coal", service.getFuelId(dressed));
    }

    @Test void legacyMigrationPreservesUnknownPresentationAndRemovesOldFuel() throws Exception {
        var def = define(Material.COAL, "Coal", List.of("New"), Map.of());
        StackState old = new StackState(Material.COAL, ItemMeta.class);
        old.name = Component.text("Player's coal");
        old.tags.put(new NamespacedKey("royalitems", "item_id"), "coal");
        old.tags.put(new NamespacedKey("royalitems", "fuel_id"), "coal");
        old.tags.put(new NamespacedKey("royalitems", "def_hash"), def.hash());
        ItemStack migrated = service.formatIfSupported(old.stack);
        assertEquals(old.name, migrated.getItemMeta().displayName());
        assertNull(service.getFuelId(migrated));
        assertSame(migrated, service.formatIfSupported(migrated));
    }

    @Test void foreignItemIsUntouched() throws Exception {
        define(Material.COAL, "Coal", List.of(), Map.of());
        StackState custom = new StackState(Material.COAL, ItemMeta.class);
        custom.tags.put(new NamespacedKey("ecoitems", "id"), "special_coal");
        assertSame(custom.stack, service.formatIfSupported(custom.stack));
    }

    @Test void disabledWorldAndMasterSwitchBlockAutomaticFormatting() throws Exception {
        define(Material.COAL, "Coal", List.of(), Map.of());
        StackState plain = new StackState(Material.COAL, ItemMeta.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("LOBBY");
        this.<Set<String>>field("disabledWorlds").add("lobby");
        assertSame(plain.stack, service.formatIfSupported(plain.stack, world));
        var enabled = FormattedItemService.class.getDeclaredField("globalEnabled");
        enabled.setAccessible(true);
        enabled.set(service, false);
        assertSame(plain.stack, service.formatIfSupported(plain.stack));
        assertSame(plain.stack, service.formatIfSupported(plain.stack, null));
    }

    @Test void malformedReloadRetainsPublishedDefinitions() throws Exception {
        var original = define(Material.COAL, "Coal", List.of(), Map.of());
        Files.createDirectories(directory.resolve("items"));
        Files.writeString(directory.resolve("items/broken.yml"), "formatted-items: [broken");
        assertThrows(IllegalArgumentException.class, service::reload);
        assertSame(original, service.byId("coal"));
        assertSame(original, service.byMaterial(Material.COAL));
    }

    @Test void definitionsCannotBeChangedThroughApiCollections() throws Exception {
        var def = define(Material.COAL, "Coal", List.of("Common"), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> service.all().clear());
        assertThrows(UnsupportedOperationException.class, () -> def.tags().put("fuel_id", "other"));
        assertThrows(UnsupportedOperationException.class, () -> def.lore().clear());
    }

    @Test void externalTooltipStyleSurvivesDressingAndRefresh() throws Exception {
        define(Material.COAL, "Coal", List.of("Old"), Map.of());
        StackState plain = new StackState(Material.COAL, ItemMeta.class);
        plain.style = new NamespacedKey("another", "custom");
        ItemStack dressed = service.formatIfSupported(plain.stack);
        define(Material.COAL, "Coal", List.of("New"), Map.of());
        assertEquals(plain.style, service.formatIfSupported(dressed).getItemMeta().getTooltipStyle());
    }

    private final class StackState {
        final Material material;
        final Class<? extends ItemMeta> metaClass;
        final ItemStack stack = mock(ItemStack.class);
        final ItemMeta meta;
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final Map<NamespacedKey, String> tags = new HashMap<>();
        org.bukkit.block.BlockState blockState = mock(org.bukkit.block.BlockState.class);
        Component name;
        List<Component> lore;
        NamespacedKey style;
        int damage;
        int amount = 32;

        StackState(Material material, Class<? extends ItemMeta> metaClass) {
            this.material = testMaterial(material);
            this.metaClass = metaClass;
            meta = mock(metaClass);
            states.put(stack, this);
            when(stack.getType()).thenReturn(this.material);
            when(stack.getAmount()).thenAnswer(i -> amount);
            doAnswer(i -> { amount = i.getArgument(0); return null; }).when(stack).setAmount(anyInt());
            when(stack.hasItemMeta()).thenReturn(true);
            when(stack.getItemMeta()).thenReturn(meta);
            when(stack.clone()).thenAnswer(i -> copy().stack);
            when(meta.getTooltipStyle()).thenAnswer(i -> style);
            doAnswer(i -> { style = i.getArgument(0); return null; }).when(meta).setTooltipStyle(nullable(NamespacedKey.class));
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(meta.displayName()).thenAnswer(i -> name);
            doAnswer(i -> { name = i.getArgument(0); return null; }).when(meta).displayName(nullable(Component.class));
            when(meta.hasDisplayName()).thenAnswer(i -> name != null);
            when(meta.lore()).thenAnswer(i -> lore);
            doAnswer(i -> { lore = i.getArgument(0); return null; }).when(meta).lore(nullable(List.class));
            when(meta.hasLore()).thenAnswer(i -> lore != null && !lore.isEmpty());
            when(pdc.isEmpty()).thenAnswer(i -> tags.isEmpty());
            when(pdc.getKeys()).thenAnswer(i -> Set.copyOf(tags.keySet()));
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(i -> tags.get(i.getArgument(0)));
            doAnswer(i -> { tags.put(i.getArgument(0), i.getArgument(2)); return null; }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
            doAnswer(i -> { tags.remove(i.getArgument(0)); return null; }).when(pdc).remove(any(NamespacedKey.class));
            if (meta instanceof BlockStateMeta block) when(block.getBlockState()).thenAnswer(i -> blockState);
            if (meta instanceof Damageable tool) when(tool.getDamage()).thenAnswer(i -> damage);
        }

        StackState copy() {
            StackState copy = new StackState(material, metaClass);
            copy.tags.putAll(tags);
            copy.name = name;
            copy.lore = lore == null ? null : List.copyOf(lore);
            copy.damage = damage;
            copy.amount = amount;
            copy.style = style;
            copy.blockState = blockState;
            return copy;
        }
    }
}
