package com.mystipixel.royalitems;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The heart of RoyalItems and its public API. Loads the config into maps (by id and by material), builds
 * one template ItemStack per definition for explicit generation, preserves input state when dressing, and
 * reads/writes the identity in persistent data. Identity is the PDC ({@code item_id}, {@code fuel_id},
 * ...); the name and lore are only what a player sees, never read for logic.
 *
 * <p>Other plugins get this via the Bukkit ServicesManager: {@code getServicesManager().load(FormattedItemService.class)}.
 */
public final class FormattedItemService {

    public static final String ITEM_ID = "item_id";
    public static final String FUEL_ID = "fuel_id";
    /** PDC stamp of the definition an item was dressed with; a mismatch triggers a refresh. */
    public static final String DEF_HASH = "def_hash";
    private static final String OWNED_TAGS = "_ri_owned_tags";
    private static final String NAME_STAMP = "_ri_name";
    private static final String LORE_STAMP = "_ri_lore";
    private static final String STYLE_STAMP = "_ri_style";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Logger logger;
    private final NamespacedKey itemIdKey;

    private boolean globalEnabled = true;
    private boolean formatOnJoin;
    private final Set<String> disabledWorlds = new HashSet<>();

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
        // Build away from the published service. A broken file must not erase the live catalog.
        FormattedItemService next = new FormattedItemService(plugin);
        next.load();
        byId.clear(); byId.putAll(next.byId);
        byMaterial.clear(); byMaterial.putAll(next.byMaterial);
        templates.clear(); templates.putAll(next.templates);
        rarities.clear(); rarities.putAll(next.rarities);
        rules.clear(); rules.addAll(next.rules);
        disabledWorlds.clear(); disabledWorlds.addAll(next.disabledWorlds);
        globalEnabled = next.globalEnabled;
        formatOnJoin = next.formatOnJoin;
    }

    private void load() {
        byId.clear();
        byMaterial.clear();
        templates.clear();
        rarities.clear();
        rules.clear();

        globalEnabled = plugin.getConfig().getBoolean("enabled", true);
        formatOnJoin = plugin.getConfig().getBoolean("format-on-join", false);
        disabledWorlds.clear();
        for (String w : plugin.getConfig().getStringList("disabled-worlds")) {
            disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        }

        loadRarities(plugin.getConfig().getConfigurationSection("rarities"));
        loadRarities(fileSection("rarities.yml", "rarities"));

        loadSection(plugin.getConfig().getConfigurationSection("formatted-items"));
        for (File file : yamlFiles(new File(plugin.getDataFolder(), "items"))) {
            loadSection(readYaml(file).getConfigurationSection("formatted-items"));
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
        return file.isFile() ? readYaml(file).getConfigurationSection(key) : null;
    }

    private static YamlConfiguration readYaml(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IllegalArgumentException("Cannot load " + file.getName() + ": " + ex.getMessage(), ex);
        }
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
                    r.getString("color", "&f"), r.getString("tooltip-style", null)));
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
            if (!isDressable(material) || byMaterial.containsKey(material)) {
                continue;   // not a dressable item, or already claimed by an explicit entry / earlier rule
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
        String category = rule.category() != null ? rule.category() : ph.get("type");
        ph.put("category", category);                                   // as written, for a top descriptor line
        ph.put("category_upper", category.toUpperCase(Locale.ROOT));    // for the bold RARITY CATEGORY footer

        String id = material.name().toLowerCase(Locale.ROOT);
        List<String> lore = new ArrayList<>();
        for (String line : rule.loreTemplate()) {
            lore.add(apply(line, ph));
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("item_id", id);
        for (Map.Entry<String, String> e : rule.extraTags().entrySet()) {
            validateTag(e.getKey());
            tags.put(e.getKey(), apply(e.getValue(), ph));
        }
        return new FormattedItemDefinition(id, material, apply(rule.nameTemplate(), ph), lore, tags,
                rarity.tooltipStyle());
    }

    /** Add a definition; false if its id was taken or the material cannot hold a dressed template. */
    private boolean register(FormattedItemDefinition def) {
        if (byId.containsKey(def.id())) {
            logger.warning("Duplicate formatted-item id '" + def.id() + "' — keeping the first.");
            return false;
        }
        ItemStack template = build(def, 1);
        if (!template.hasItemMeta()) {
            return false;   // a technical material that can't hold a name/lore/identity — skip it
        }
        byId.put(def.id(), def);
        FormattedItemDefinition prev = byMaterial.putIfAbsent(def.material(), def);
        if (prev != null) {
            logger.warning("Both '" + prev.id() + "' and '" + def.id() + "' format " + def.material()
                    + "; drops of it will use '" + prev.id() + "'.");
        }
        templates.put(def.id(), template);
        return true;
    }

    private void validateTag(String tag) {
        if (!tag.matches("[a-z0-9._/-]+")) throw new IllegalArgumentException("Invalid lowercase identity tag: " + tag);
        if (tag.equals(ITEM_ID) || tag.equals(DEF_HASH) || tag.startsWith("_ri_")) {
            throw new IllegalArgumentException("Reserved RoyalItems tag: " + tag);
        }
        key(tag); // Validate before publishing any definitions.
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

    /**
     * Every {@code .yml} under {@code dir} (recursively), skipping {@code _}-prefixed template files, sorted
     * by path so load order is deterministic — a file named to sort first (e.g. {@code 0-overrides.yml})
     * reliably claims its ids before the generated catalog, so hand-tuned items win.
     */
    private static List<File> yamlFiles(File dir) {
        List<File> out = new ArrayList<>();
        collect(dir, out);
        out.sort(java.util.Comparator.comparing(File::getPath));
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

    /**
     * Parse one clean entry. Only the key is required — it is the {@code item_id} and, upper-cased, the
     * default {@link Material} ({@code ender_pearl} → {@code ENDER_PEARL}). Everything else is optional and
     * has a sensible default: {@code rarity} (common) drives the border, name colour and footer; the name
     * auto-fills to the rarity-coloured title case of the key; the lore auto-fills to the Hypixel footer
     * ({@code &8<category>}, blank, {@code <rarity>}). {@code fuel:} is shorthand for a {@code fuel_id} tag,
     * {@code material:} overrides the material, and {@code tags:} still adds any extra identity.
     */
    private FormattedItemDefinition parse(String id, ConfigurationSection sec) {
        String materialName = sec.getString("material", id.toUpperCase(Locale.ROOT));
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Formatted item '" + id + "' has invalid item material '" + materialName + "'");
        }

        Rarity rarity = rarities.getOrDefault(sec.getString("rarity", "common").toLowerCase(Locale.ROOT),
                Rarity.DEFAULT);
        String category = sec.getString("category", "Collection Item");

        String name = sec.getString("name", sec.getString("display-name", null));
        if (name == null) {
            name = rarity.color() + title(id);
        }

        List<String> lore = sec.getStringList("lore");
        if (lore.isEmpty()) {
            lore = new ArrayList<>(List.of("&8" + category, "", rarity.display()));
        }

        // Identity tags: item_id is always the key; fuel: is shorthand for fuel_id; tags: adds any others.
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(ITEM_ID, id);
        String fuel = sec.getString("fuel", null);
        if (fuel != null) {
            tags.put(FUEL_ID, fuel);
        }
        ConfigurationSection tagSec = sec.getConfigurationSection("tags");
        if (tagSec != null) {
            for (String k : tagSec.getKeys(false)) {
                validateTag(k);
                tags.put(k, tagSec.getString(k));
            }
        }

        String tooltipStyle = sec.getString("tooltip-style", rarity.tooltipStyle());
        if (tooltipStyle != null && !tooltipStyle.isBlank()) Key.key(tooltipStyle);
        return new FormattedItemDefinition(id, material, name, lore, tags, tooltipStyle);
    }

    // ------------------------------------------------------------------ building

    private ItemStack build(FormattedItemDefinition def, int amount) {
        return dress(new ItemStack(def.material(), Math.max(1, amount)), def, true);
    }

    /**
     * Render a config string: MiniMessage when it contains a tag ({@code <red>}, {@code <#ff00ff>},
     * {@code <gradient:..>}), otherwise legacy {@code &} codes — and always non-italic, so a formatted
     * item reads like a real one.
     */
    private static Component text(String s) {
        Component c = looksMiniMessage(s) ? MINI.deserialize(s) : LEGACY.deserialize(s);
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private static boolean looksMiniMessage(String s) {
        int open = s.indexOf('<');
        return open >= 0 && s.indexOf('>', open) > open;
    }

    // ------------------------------------------------------------------ settings

    /** Whether formatting is on in this world (the global toggle and the disabled-worlds list). */
    public boolean worldEnabled(World world) {
        return globalEnabled && (world == null || !disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT)));
    }

    /** Whether to dress a player's inventory when they join. */
    public boolean formatOnJoin() {
        return formatOnJoin;
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
     * otherwise {@code stack} unchanged. Never clobbers an item that already carries a name, lore,
     * enchant, or another plugin's data (e.g. an EcoItem on the base material) — those keep their
     * identity. The amount is preserved.
     *
     * <p>An item that is already ours is not re-formatted — but it IS refreshed when the definition it
     * was dressed with has since changed (see {@link #refreshIfStale}), so config edits reach old
     * items through the same events that dress new ones.
     */
    public ItemStack formatIfSupported(ItemStack stack) {
        if (!globalEnabled || stack == null || stack.getType().isAir()) {
            return stack;
        }
        String ourId = getItemId(stack);
        if (ourId != null) {
            return refreshIfStale(stack, ourId);
        }
        FormattedItemDefinition def = byMaterial.get(stack.getType());
        if (def == null || looksCustom(stack)) {
            return stack;
        }
        return dress(stack, def, true);
    }

    /** Automatic formatting for integrations with world context. The legacy overload checks the master switch. */
    public ItemStack formatIfSupported(ItemStack stack, World world) {
        return worldEnabled(world) ? formatIfSupported(stack) : stack;
    }

    /**
     * Re-apply the current definition to one of our items whose stamped {@code def_hash} no longer
     * matches — the mechanism that lets a lore tweak or rarity change propagate to items dropped
     * before the edit, through normal play, with no sweep.
     *
     * <p>Only the fields the definition owns are rewritten — name, lore, identity tags, the stamp and
     * the tooltip style — on the item's <em>existing</em> meta, so enchants added at an anvil, damage,
     * and anything else the player earned survive the refresh. An item whose definition was removed
     * from the catalog, or whose material no longer matches it, is left exactly as it is.
     */
    private ItemStack refreshIfStale(ItemStack stack, String id) {
        FormattedItemDefinition def = byId.get(id);
        if (def == null || def.material() != stack.getType()) {
            return stack;
        }
        if (def.hash().equals(readTag(stack, key(DEF_HASH))) && readTag(stack, key(OWNED_TAGS)) != null) {
            return stack;                        // dressed with the current definition — nothing to do
        }
        return dress(stack, def, false);
    }

    /** Clone the input, preserving every component except fields explicitly owned by this definition. */
    private ItemStack dress(ItemStack stack, FormattedItemDefinition def, boolean fresh) {
        ItemStack updated = stack.clone();
        ItemMeta meta = updated.getItemMeta();
        if (meta == null) {
            return stack;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Component name = def.displayName() == null ? null : text(def.displayName());
        List<Component> lines = new ArrayList<>();
        for (String line : def.lore()) {
            lines.add(text(line));
        }
        List<Component> lore = lines.isEmpty() ? null : lines;
        // Older items have no ownership stamps: preserve their presentation rather than guess
        // whether a player renamed them. Adopt the desired baseline for subsequent refreshes.
        if (fresh || owns(pdc, NAME_STAMP, fingerprint(meta.displayName()))) meta.displayName(name);
        if (fresh || owns(pdc, LORE_STAMP, loreFingerprint(meta.lore()))) meta.lore(lore);
        pdc.set(key(NAME_STAMP), PersistentDataType.STRING, fingerprint(name));
        pdc.set(key(LORE_STAMP), PersistentDataType.STRING, loreFingerprint(lore));

        String previous = pdc.get(key(OWNED_TAGS), PersistentDataType.STRING);
        Set<String> oldTags = new HashSet<>();
        if (previous != null) {
            for (String tag : previous.split(",")) if (!tag.isEmpty()) oldTags.add(tag);
        } else if (!fresh) {
            // v0.1 used our namespace for identity tags but did not record the owned keys.
            for (NamespacedKey tag : pdc.getKeys()) {
                if (tag.getNamespace().equals(itemIdKey.getNamespace())
                        && !tag.getKey().startsWith("_ri_") && !tag.getKey().equals(DEF_HASH)) oldTags.add(tag.getKey());
            }
        }
        for (String old : oldTags) {
            if (!def.tags().containsKey(old)) pdc.remove(key(old));
        }
        for (Map.Entry<String, String> tag : def.tags().entrySet()) {
            pdc.set(key(tag.getKey()), PersistentDataType.STRING, tag.getValue());
        }
        pdc.set(key(DEF_HASH), PersistentDataType.STRING, def.hash());
        pdc.set(key(OWNED_TAGS), PersistentDataType.STRING, String.join(",", new java.util.TreeSet<>(def.tags().keySet())));
        NamespacedKey currentStyle = meta.getTooltipStyle();
        String desiredStyle = def.tooltipStyle() == null || def.tooltipStyle().isBlank() ? "" : Key.key(def.tooltipStyle()).asString();
        boolean writeStyle = (fresh && currentStyle == null)
                || owns(pdc, STYLE_STAMP, currentStyle == null ? "" : currentStyle.asString());
        pdc.set(key(STYLE_STAMP), PersistentDataType.STRING, desiredStyle);
        if (writeStyle) {
            if (!desiredStyle.isEmpty()) {
                meta.setTooltipStyle(NamespacedKey.fromString(desiredStyle));
            } else {
                meta.setTooltipStyle(null);
            }
        }
        updated.setItemMeta(meta);
        return updated;
    }

    private boolean owns(PersistentDataContainer pdc, String stamp, String value) {
        return value.equals(pdc.get(key(stamp), PersistentDataType.STRING));
    }

    private static String fingerprint(Component component) {
        return digest(component == null ? "null" : net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(component));
    }

    private static String loreFingerprint(List<Component> lines) {
        return digest(lines == null || lines.isEmpty() ? "null" : lines.stream().map(FormattedItemService::fingerprint)
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError("Java must provide SHA-256", ex);
        }
    }

    /** Dress every supported plain item in a player's inventory; returns how many stacks changed. */
    public int formatInventory(Player player) {
        if (!player.isOnline() || !worldEnabled(player.getWorld())) return 0;
        ItemStack[] contents = player.getInventory().getContents();
        int changed = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack formatted = formatIfSupported(contents[i]);
            if (formatted != contents[i]) {
                contents[i] = formatted;
                changed++;
            }
        }
        if (changed > 0) {
            player.getInventory().setContents(contents);
        }
        return changed;
    }

    private boolean looksCustom(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;   // truly plain — safe to format
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                || meta.hasItemName() || meta.hasCustomModelDataComponent()
                || !meta.getPersistentDataContainer().isEmpty()
                || hasDynamicState(meta);
    }

    /**
     * True for items whose vanilla name or state IS their identity — a potion's effect, an enchanted
     * book's enchants, a written book, a map, a firework, a stew, a goat horn, a head, a bundle, a fish
     * bucket, a banner. These are never dressed (even under a catch-all rule), so a "Potion of Healing"
     * never becomes just "Potion". Gear that carries specialised meta (a crossbow, a shield) is NOT
     * listed here, so it still formats.
     */
    private static boolean hasDynamicState(ItemMeta meta) {
        return meta instanceof PotionMeta
                || meta instanceof EnchantmentStorageMeta
                || meta instanceof WritableBookMeta
                || meta instanceof MapMeta
                || meta instanceof FireworkMeta
                || meta instanceof FireworkEffectMeta
                || meta instanceof SuspiciousStewMeta
                || meta instanceof MusicInstrumentMeta
                || meta instanceof SkullMeta
                || meta instanceof BundleMeta
                || meta instanceof AxolotlBucketMeta
                || meta instanceof TropicalFishBucketMeta
                || meta instanceof BannerMeta;
    }

    /**
     * Why {@link #formatIfSupported} would leave this (non-ours) stack alone, or null when it would
     * dress it. Exists for {@code /royalitems inspect} — the skip conditions are deliberate, and an
     * admin asking "why isn't this item dressed" deserves the specific one.
     */
    public String skipReason(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "there is no item";
        }
        if (byMaterial.get(stack.getType()) == null) {
            return "no definition or rule covers " + stack.getType();
        }
        if (!stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (hasDynamicState(meta)) {
            return "its vanilla state is its identity (potion, book, head, banner, …)";
        }
        if (meta.hasDisplayName()) {
            return "it already has a custom name";
        }
        if (meta.hasLore()) {
            return "it already has lore";
        }
        if (meta.hasEnchants()) {
            return "it is enchanted";
        }
        if (meta.hasItemName() || meta.hasCustomModelDataComponent()) {
            return "it already has a custom item name or model";
        }
        if (!meta.getPersistentDataContainer().isEmpty()) {
            return "another plugin owns it (persistent data present)";
        }
        return null;
    }

    private NamespacedKey key(String name) {
        return tagKeys.computeIfAbsent(name, n -> new NamespacedKey(plugin, n));
    }

    public Collection<FormattedItemDefinition> all() {
        return List.copyOf(byId.values());
    }

    public FormattedItemDefinition byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public FormattedItemDefinition byMaterial(Material material) {
        return byMaterial.get(material);
    }

    /**
     * Whether RoyalItems can dress this material: a real, obtainable item that can hold a name/lore/identity
     * and whose vanilla name or state is not its identity (potions, maps, books, heads, banners, …). Used by
     * the catalog exporter to enumerate exactly the items worth listing.
     */
    public boolean isDressable(Material material) {
        if (material == null || !material.isItem() || material.isAir()) {
            return false;
        }
        ItemMeta meta = new ItemStack(material).getItemMeta();
        return meta != null && !hasDynamicState(meta);
    }
}
