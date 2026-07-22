package com.mystipixel.royalitems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The heart of RoyalItems and its public API. Loads the config into maps (by id and by material), builds
 * one template ItemStack per definition and clones it to format — never rebuilding meta per drop — and
 * reads/writes the identity in persistent data. Identity is the PDC ({@code item_id}, {@code fuel_id},
 * ...); the name and lore are only what a player sees, never read for logic.
 *
 * <p>Other plugins get this via the Bukkit ServicesManager: {@code getServicesManager().load(FormattedItemService.class)}.
 */
public final class FormattedItemService {

    public static final String ITEM_ID = "item_id";
    public static final String FUEL_ID = "fuel_id";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final Logger logger;
    private final NamespacedKey itemIdKey;

    private final Map<String, FormattedItemDefinition> byId = new HashMap<>();
    private final Map<Material, FormattedItemDefinition> byMaterial = new EnumMap<>(Material.class);
    private final Map<String, ItemStack> templates = new HashMap<>();   // id -> prebuilt stack (amount 1)
    private final Map<String, NamespacedKey> tagKeys = new HashMap<>(); // tag name -> cached key

    public FormattedItemService(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.itemIdKey = key(ITEM_ID);
    }

    // ------------------------------------------------------------------ config

    public void load(ConfigurationSection root) {
        byId.clear();
        byMaterial.clear();
        templates.clear();
        ConfigurationSection items = root == null ? null : root.getConfigurationSection("formatted-items");
        if (items == null) {
            logger.warning("No 'formatted-items' section in config.yml — nothing to format.");
            return;
        }
        for (String id : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            FormattedItemDefinition def = parse(id.toLowerCase(Locale.ROOT), sec);
            if (def == null) {
                continue;
            }
            byId.put(def.id(), def);
            FormattedItemDefinition prev = byMaterial.putIfAbsent(def.material(), def);
            if (prev != null) {
                logger.warning("Both '" + prev.id() + "' and '" + def.id() + "' format " + def.material()
                        + "; drops of it will use '" + prev.id() + "'.");
            }
            templates.put(def.id(), build(def, 1));
        }
        logger.info("Loaded " + byId.size() + " formatted item(s).");
    }

    private FormattedItemDefinition parse(String id, ConfigurationSection sec) {
        Material material = Material.matchMaterial(sec.getString("material", ""));
        if (material == null) {
            logger.warning("Formatted item '" + id + "' has an unknown material — skipped.");
            return null;
        }
        String name = sec.getString("display-name", null);
        List<String> lore = sec.getStringList("lore");

        // Identity tags. item_id always resolves to the definition id so isFormattedItem() works, even if
        // the config leaves the tags block out; a config value may add fuel_id and anything else.
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(ITEM_ID, id);
        ConfigurationSection tagSec = sec.getConfigurationSection("tags");
        if (tagSec != null) {
            for (String k : tagSec.getKeys(false)) {
                tags.put(k, tagSec.getString(k));
            }
        }

        Set<FormattedItemDefinition.Source> sources = new LinkedHashSet<>();
        List<String> raw = sec.getStringList("format-on");
        if (raw.isEmpty()) {
            sources.add(FormattedItemDefinition.Source.MINED);   // default: only mined blocks
        } else {
            for (String r : raw) {
                FormattedItemDefinition.Source s = FormattedItemDefinition.Source.from(r);
                if (s != null) {
                    sources.add(s);
                }
            }
        }
        return new FormattedItemDefinition(id, material, name, lore, tags, sources);
    }

    // ------------------------------------------------------------------ building

    private ItemStack build(FormattedItemDefinition def, int amount) {
        ItemStack item = new ItemStack(def.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (def.displayName() != null) {
            meta.displayName(noItalic(LEGACY.deserialize(def.displayName())));
        }
        if (!def.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : def.lore()) {
                lines.add(noItalic(LEGACY.deserialize(line)));
            }
            meta.lore(lines);
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (Map.Entry<String, String> tag : def.tags().entrySet()) {
            pdc.set(key(tag.getKey()), PersistentDataType.STRING, tag.getValue());
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Item name/lore render italic by default; reset it so a formatted item reads like a real one. */
    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------ public API

    public boolean isFormattedItem(ItemStack stack) {
        return getItemId(stack) != null;
    }

    public String getItemId(ItemStack stack) {
        return readTag(stack, itemIdKey);
    }

    public String getFuelId(ItemStack stack) {
        return readTag(stack, key(FUEL_ID));
    }

    /** Read any identity tag by name, or null if absent. */
    public String getTag(ItemStack stack, String tag) {
        return readTag(stack, key(tag));
    }

    private String readTag(ItemStack stack, NamespacedKey k) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING);
    }

    /** Build {@code amount} of the formatted item with this id, or null if there is no such definition. */
    public ItemStack format(String id, int amount) {
        if (id == null) {
            return null;
        }
        ItemStack template = templates.get(id.toLowerCase(Locale.ROOT));
        if (template == null) {
            return null;
        }
        ItemStack copy = template.clone();
        copy.setAmount(Math.max(1, amount));
        return copy;
    }

    /**
     * A formatted version of {@code stack} if its material is supported and it is a plain vanilla item;
     * otherwise {@code stack} unchanged. Never re-formats one of ours and never clobbers an item that
     * already carries a name, lore, enchant, or another plugin's data (e.g. an EcoItem on the base
     * material) — those keep their identity. The amount is preserved.
     */
    public ItemStack formatIfSupported(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return stack;
        }
        FormattedItemDefinition def = byMaterial.get(stack.getType());
        if (def == null || looksCustom(stack)) {
            return stack;
        }
        return format(def.id(), stack.getAmount());
    }

    private boolean looksCustom(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;   // truly plain — safe to format
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                || !meta.getPersistentDataContainer().isEmpty();
    }

    private NamespacedKey key(String name) {
        return tagKeys.computeIfAbsent(name, n -> new NamespacedKey(plugin, n));
    }

    public Collection<FormattedItemDefinition> all() {
        return byId.values();
    }

    public FormattedItemDefinition byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public FormattedItemDefinition byMaterial(Material material) {
        return byMaterial.get(material);
    }
}
