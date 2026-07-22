package com.mystipixel.royalitems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
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
    private final Map<String, Rarity> rarities = new HashMap<>();       // rarity id -> tier
    private final List<FormattingRule> rules = new ArrayList<>();       // expanded over materials at load

    public FormattedItemService(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.itemIdKey = key(ITEM_ID);
    }

    // ------------------------------------------------------------------ config

    /**
     * (Re)load everything: rarities, the explicit {@code formatted-items} (config.yml catch-all, then
     * every {@code items/**.yml}), and the {@code rules} — which are expanded over every material into
     * concrete definitions. Explicit items always win over a rule, and the first claim of an id or
     * material wins over any later one. So runtime is only ever a map lookup; the expansion is one-time.
     */
    public void reload() {
        byId.clear();
        byMaterial.clear();
        templates.clear();
        rarities.clear();
        rules.clear();

        loadRarities(plugin.getConfig().getConfigurationSection("rarities"));
        loadRarities(fileSection("rarities.yml", "rarities"));

        loadSection(plugin.getConfig().getConfigurationSection("formatted-items"));
        for (File file : yamlFiles(new File(plugin.getDataFolder(), "items"))) {
            loadSection(YamlConfiguration.loadConfiguration(file).getConfigurationSection("formatted-items"));
        }
        int explicit = byId.size();

        loadRules(plugin.getConfig().getConfigurationSection("rules"));
        loadRules(fileSection("rules.yml", "rules"));
        int expanded = expandRules();

        logger.info("Loaded " + byId.size() + " item(s): " + explicit + " defined, " + expanded
                + " from " + rules.size() + " rule(s), " + rarities.size() + " rarities.");
    }

    private ConfigurationSection fileSection(String fileName, String key) {
        File file = new File(plugin.getDataFolder(), fileName);
        return file.isFile() ? YamlConfiguration.loadConfiguration(file).getConfigurationSection(key) : null;
    }

    private void loadRarities(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            rarities.putIfAbsent(key, new Rarity(key, r.getString("display", "&f&l" + id.toUpperCase(Locale.ROOT)),
                    r.getString("color", "&f")));
        }
    }

    private void loadRules(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r != null) {
                rules.add(FormattingRule.parse(r));
            }
        }
    }

    private void loadSection(ConfigurationSection items) {
        if (items == null) {
            return;
        }
        for (String id : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            FormattedItemDefinition def = parse(id.toLowerCase(Locale.ROOT), sec);
            if (def != null) {
                register(def);
            }
        }
    }

    /** Expand every rule over all real items, dressing each material the first matching rule claims. */
    private int expandRules() {
        int count = 0;
        for (Material material : Material.values()) {
            if (!material.isItem() || byMaterial.containsKey(material)) {
                continue;   // not an obtainable item, or already claimed by an explicit entry / earlier rule
            }
            for (FormattingRule rule : rules) {
                if (rule.matches(material) && register(expand(rule, material))) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private FormattedItemDefinition expand(FormattingRule rule, Material material) {
        Rarity rarity = rarities.getOrDefault(rule.rarityFor(material), Rarity.DEFAULT);
        String[] parts = material.name().split("_");
        Map<String, String> ph = new HashMap<>();
        ph.put("name", title(material.name()));
        ph.put("type", title(parts[parts.length - 1]));
        ph.put("material", material.name());
        ph.put("rarity", rarity.display());
        ph.put("rarity_color", rarity.color());
        ph.put("category", rule.category() != null ? rule.category() : ph.get("type"));

        String id = material.name().toLowerCase(Locale.ROOT);
        List<String> lore = new ArrayList<>();
        for (String line : rule.loreTemplate()) {
            lore.add(apply(line, ph));
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("item_id", id);
        for (Map.Entry<String, String> e : rule.extraTags().entrySet()) {
            tags.put(e.getKey(), apply(e.getValue(), ph));
        }
        return new FormattedItemDefinition(id, material, apply(rule.nameTemplate(), ph), lore, tags, rule.sources());
    }

    /** Add a definition; false if its id was already taken (a material clash keeps the first, and logs). */
    private boolean register(FormattedItemDefinition def) {
        if (byId.containsKey(def.id())) {
            logger.warning("Duplicate formatted-item id '" + def.id() + "' — keeping the first.");
            return false;
        }
        byId.put(def.id(), def);
        FormattedItemDefinition prev = byMaterial.putIfAbsent(def.material(), def);
        if (prev != null) {
            logger.warning("Both '" + prev.id() + "' and '" + def.id() + "' format " + def.material()
                    + "; drops of it will use '" + prev.id() + "'.");
        }
        templates.put(def.id(), build(def, 1));
        return true;
    }

    private static String title(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String word : raw.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String apply(String template, Map<String, String> placeholders) {
        String out = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue());
        }
        return out;
    }

    /** Every {@code .yml} under {@code dir} (recursively), skipping {@code _}-prefixed template files. */
    private static List<File> yamlFiles(File dir) {
        List<File> out = new ArrayList<>();
        collect(dir, out);
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File f : entries) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".yml") && !f.getName().startsWith("_")) {
                out.add(f);
            }
        }
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
