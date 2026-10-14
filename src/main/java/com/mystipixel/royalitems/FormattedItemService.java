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
import org.bukkit.inventory.meta.CompassMeta;
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
 * RoyalItems' public API. Identity lives in the PDC ({@code item_id}, {@code fuel_id}, ...); name and
 * lore are display only, never read for logic.
 *
 * <p>Get it from the ServicesManager: {@code getServicesManager().load(FormattedItemService.class)}.
 *
 * <p>Threading: {@link #getItemId}, {@link #getFuelId} and {@link #getTag} are safe from any thread.
 * Anything that reads the catalog (formatting, {@link #byId}, {@link #all}) must run on the main thread.
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
    // name stamp of an item whose name we left unset
    private static final String NO_NAME = fingerprint(null);

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Logger logger;
    private final NamespacedKey itemIdKey;

    private boolean globalEnabled = true;
    private boolean customTooltipStyles;
    private boolean formatOnJoin;
    private List<String> autoLore = List.of();
    private String defaultCategory;
    private final Set<String> disabledWorlds = new HashSet<>();

    private final Map<String, FormattedItemDefinition> byId = new HashMap<>();
    private final Map<Material, FormattedItemDefinition> byMaterial = new EnumMap<>(Material.class);
    private final Map<String, ItemStack> templates = new HashMap<>();   // id -> prebuilt stack (amount 1)
    // parsed name and lore per definition and their stamps, so dressing doesn't re-parse and re-hash them
    private final Map<FormattedItemDefinition, Rendered> rendered = new java.util.IdentityHashMap<>();
    // concurrent: getTag/getFuelId are public and may be called off-thread
    private final Map<String, NamespacedKey> tagKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Rarity> rarities = new HashMap<>();
    private final List<FormattingRule> rules = new ArrayList<>();       // expanded over materials at load

    // per-load state, only set on the throwaway instance that reload() builds
    private boolean lenient;
    private int skipped;
    private final Set<String> unknownRarities = new java.util.TreeSet<>();

    public FormattedItemService(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.itemIdKey = key(ITEM_ID);
    }

    /**
     * Reload rarities, explicit items and rules. Explicit items win over rules, and the first claim of an
     * id or material wins. Throws on any broken entry and keeps the previous catalog.
     */
    public void reload() {
        reload(false);
    }

    /**
     * Startup load: broken entries, rules or files are logged and skipped instead of failing, so one
     * stale material can't stop the plugin from enabling. Returns how many pieces were skipped.
     */
    public int reloadSkippingInvalid() {
        return reload(true);
    }

    private int reload(boolean lenient) {
        // build away from the published service so a broken file can't erase the live catalog
        FormattedItemService next = new FormattedItemService(plugin);
        next.lenient = lenient;
        next.load();
        byId.clear(); byId.putAll(next.byId);
        byMaterial.clear(); byMaterial.putAll(next.byMaterial);
        templates.clear(); templates.putAll(next.templates);
        rendered.clear(); rendered.putAll(next.rendered);
        rarities.clear(); rarities.putAll(next.rarities);
        rules.clear(); rules.addAll(next.rules);
        disabledWorlds.clear(); disabledWorlds.addAll(next.disabledWorlds);
        globalEnabled = next.globalEnabled;
        customTooltipStyles = next.customTooltipStyles;
        formatOnJoin = next.formatOnJoin;
        return next.skipped;
    }

    private void load() {
        byId.clear();
        byMaterial.clear();
        templates.clear();
        rendered.clear();
        rarities.clear();
        rules.clear();

        globalEnabled = plugin.getConfig().getBoolean("enabled", true);
        customTooltipStyles = plugin.getConfig().getBoolean("tooltip-borders.enabled", false)
                && plugin.getConfig().getBoolean("tooltip-borders.resource-pack-ready", false);
        formatOnJoin = plugin.getConfig().getBoolean("format-on-join", false);
        autoLore = List.copyOf(plugin.getConfig().getStringList("auto-lore.lines"));
        defaultCategory = plugin.getConfig().getString("auto-lore.default-category");
        disabledWorlds.clear();
        for (String w : plugin.getConfig().getStringList("disabled-worlds")) {
            disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        }

        loadRarities(plugin.getConfig().getConfigurationSection("rarities"));
        loadRarities(fileSection("rarities.yml", "rarities"));

        loadSection(plugin.getConfig().getConfigurationSection("formatted-items"), "config.yml");
        for (File file : yamlFiles(new File(plugin.getDataFolder(), "items"))) {
            YamlConfiguration yaml = readYamlOrSkip(file);
            if (yaml != null) {
                loadSection(yaml.getConfigurationSection("formatted-items"), "items/" + file.getName());
            }
        }
        int explicit = byId.size();

        loadRules(plugin.getConfig().getConfigurationSection("rules"), "config.yml");
        loadRules(fileSection("rules.yml", "rules"), "rules.yml");
        int expanded = expandRules();

        logger.info("Loaded " + byId.size() + " item(s): " + explicit + " defined, " + expanded
                + " from " + rules.size() + " rule(s), " + rarities.size() + " rarities.");
        if (!unknownRarities.isEmpty()) {
            logger.warning("Unknown rarity " + unknownRarities + ": those items are shown as common. "
                    + "Fix the spelling or define it in rarities.yml.");
        }
    }

    private void skipOrThrow(RuntimeException ex, String what) {
        if (!lenient) {
            throw ex;
        }
        skipped++;
        logger.warning("Skipping " + what + ": " + ex.getMessage());
    }

    private YamlConfiguration readYamlOrSkip(File file) {
        try {
            return readYaml(file);
        } catch (IllegalArgumentException ex) {
            skipOrThrow(ex, file.getName());
            return null;
        }
    }

    private ConfigurationSection fileSection(String fileName, String key) {
        File file = new File(plugin.getDataFolder(), fileName);
        YamlConfiguration yaml = file.isFile() ? readYamlOrSkip(file) : null;
        return yaml == null ? null : yaml.getConfigurationSection(key);
    }

    // falls back to common and records the id so the load warns about the typo once
    private Rarity rarity(String id) {
        Rarity rarity = rarities.get(id);
        if (rarity != null) {
            return rarity;
        }
        if (!id.equals(Rarity.DEFAULT.id())) {
            unknownRarities.add(id);
        }
        return Rarity.DEFAULT;
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
            String style = r.getString("tooltip-style", null);
            if (customTooltipStyles && style != null && !style.isBlank()) {
                try {
                    Key.key(style);
                } catch (RuntimeException ex) {
                    skipOrThrow(ex, "rarity '" + id + "'");
                    continue;
                }
            }
            rarities.putIfAbsent(key, new Rarity(key, r.getString("display", "&f&l" + id.toUpperCase(Locale.ROOT)),
                    r.getString("color", "&f"), style));
        }
    }

    private void loadRules(ConfigurationSection sec, String source) {
        if (sec == null) {
            return;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) {
                continue;
            }
            try {
                FormattingRule rule = FormattingRule.parse(r);
                // validated here so a bad tag drops the whole rule rather than failing mid-expansion
                rule.extraTags().keySet().forEach(this::validateTag);
                rules.add(rule);
            } catch (RuntimeException ex) {
                skipOrThrow(ex, "rule '" + id + "' in " + source);
            }
        }
    }

    private void loadSection(ConfigurationSection items, String source) {
        if (items == null) {
            return;
        }
        for (String id : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            FormattedItemDefinition def;
            try {
                def = parse(id.toLowerCase(Locale.ROOT), sec);
            } catch (RuntimeException ex) {
                skipOrThrow(ex, "item '" + id + "' in " + source);
                continue;
            }
            if (def != null) {
                register(def);
            }
        }
    }

    // first matching rule claims a material
    private int expandRules() {
        if (rules.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Material material : Material.values()) {
            if (!isDressable(material) || byMaterial.containsKey(material)) {
                continue;   // explicit entries and earlier rules claim first
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
        Rarity rarity = rarity(rule.rarityFor(material));
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
            tags.put(e.getKey(), apply(e.getValue(), ph));
        }
        return new FormattedItemDefinition(id, material, apply(rule.nameTemplate(), ph), lore, tags,
                customTooltipStyles ? rarity.tooltipStyle() : null);
    }

    // false if the id was taken or the material can't hold a dressed template
    private boolean register(FormattedItemDefinition def) {
        if (byId.containsKey(def.id())) {
            logger.warning("Duplicate formatted-item id '" + def.id() + "': keeping the first.");
            return false;
        }
        ItemStack template = build(def, 1);
        if (!template.hasItemMeta()) {
            return false;   // technical material that can't hold a name, lore or identity
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
        key(tag); // throws on an invalid key before any definition is published
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

    // sorted by path so a file named to sort first (0-overrides.yml) claims its ids before the catalog;
    // _-prefixed template files are skipped
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

    // Only the key is required: it is the item_id and, upper-cased, the default material. Name defaults to
    // the rarity-coloured title case of the key, lore to config.yml auto-lore.
    private FormattedItemDefinition parse(String id, ConfigurationSection sec) {
        String materialName = sec.getString("material", id.toUpperCase(Locale.ROOT));
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Formatted item '" + id + "' has invalid item material '" + materialName + "'");
        }

        Rarity rarity = rarity(sec.getString("rarity", "common").toLowerCase(Locale.ROOT));
        String category = sec.getString("category", defaultCategory);

        String name = sec.getString("name", sec.getString("display-name", null));
        if (name == null) {
            name = rarity.color() + title(id);
        }

        List<String> lore = sec.getStringList("lore");
        if (lore.isEmpty()) {
            lore = autoLore(autoLore, category, rarity);
        }

        // fuel: is shorthand for a fuel_id tag; tags: adds any others
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

        String tooltipStyle = customTooltipStyles ? sec.getString("tooltip-style", rarity.tooltipStyle()) : null;
        if (tooltipStyle != null && !tooltipStyle.isBlank()) Key.key(tooltipStyle);
        return new FormattedItemDefinition(id, material, name, lore, tags, tooltipStyle);
    }

    static List<String> autoLore(List<String> template, String category, Rarity rarity) {
        List<String> lore = new ArrayList<>(template.size());
        for (String line : template) {
            lore.add(MessageManager.fill(line, "category", category, "rarity", rarity.display()));
        }
        return lore;
    }

    private ItemStack build(FormattedItemDefinition def, int amount) {
        return dress(new ItemStack(def.material(), Math.max(1, amount)), def, true);
    }

    // MiniMessage when the string contains a <tag>, otherwise legacy & codes; always non-italic
    private static Component text(String s) {
        Component c = looksMiniMessage(s) ? MINI.deserialize(s) : LEGACY.deserialize(s);
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private static boolean looksMiniMessage(String s) {
        int open = s.indexOf('<');
        return open >= 0 && s.indexOf('>', open) > open;
    }

    /** Whether formatting is on in this world (the global toggle and the disabled-worlds list). */
    public boolean worldEnabled(World world) {
        return globalEnabled && (world == null || !disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT)));
    }

    public boolean formatOnJoin() {
        return formatOnJoin;
    }

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
        // the read-only view avoids the full meta copy getItemMeta() would make
        return stack.getPersistentDataContainer().get(k, PersistentDataType.STRING);
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
     * A formatted copy of {@code stack} if its material is supported and it is a plain vanilla item,
     * otherwise {@code stack} unchanged. Items with a name, lore, enchants or another plugin's data are
     * never touched. Items already ours are refreshed if their definition changed. Amount is preserved.
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
        ItemStack template = stack.hasItemMeta() ? null : templates.get(def.id());
        if (template != null) {
            // a stack with no meta dresses exactly like the template it was built from
            ItemStack copy = template.clone();
            copy.setAmount(stack.getAmount());
            return copy;
        }
        return dress(stack, def, true);
    }

    /** Automatic formatting for integrations with world context. The legacy overload checks the master switch. */
    public ItemStack formatIfSupported(ItemStack stack, World world) {
        return worldEnabled(world) ? formatIfSupported(stack) : stack;
    }

    // Rewrites only the fields the definition owns, on the existing meta, so anvil enchants and damage
    // survive. Items whose definition was removed or whose material changed are left alone.
    private ItemStack refreshIfStale(ItemStack stack, String id) {
        FormattedItemDefinition def = byId.get(id);
        if (def == null || def.material() != stack.getType()) {
            return stack;
        }
        if (def.hash().equals(readTag(stack, key(DEF_HASH))) && readTag(stack, key(OWNED_TAGS)) != null
                && !needsStyleCleanup(stack) && !hidesLodestoneName(stack)) {
            return stack;
        }
        return dress(stack, def, false);
    }

    // returns a clone; only fields owned by the definition are changed
    private ItemStack dress(ItemStack stack, FormattedItemDefinition def, boolean fresh) {
        ItemStack updated = stack.clone();
        ItemMeta meta = updated.getItemMeta();
        if (meta == null) {
            return stack;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Rendered look = rendered.computeIfAbsent(def, FormattedItemService::render);
        // A bound compass is named "Lodestone Compass" by vanilla; any name of ours would hide that.
        Component name = isLodestoneCompass(meta) ? null : look.name();
        List<Component> lore = look.lore();
        // A field is ours to rewrite only while its stamp matches what we last wrote. Unstamped names are
        // still ours if italics are explicitly off (anvil renames are unstyled); unstamped lore is not.
        Component currentName = meta.displayName();
        if (fresh || owns(pdc, NAME_STAMP, fingerprint(currentName)) || styledByUs(currentName)) meta.displayName(name);
        if (fresh || owns(pdc, LORE_STAMP, loreFingerprint(meta.lore()))) meta.lore(lore);
        pdc.set(key(NAME_STAMP), PersistentDataType.STRING, name == null ? NO_NAME : look.nameStamp());
        pdc.set(key(LORE_STAMP), PersistentDataType.STRING, look.loreStamp());

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
        String desiredStyle = !customTooltipStyles || def.tooltipStyle() == null || def.tooltipStyle().isBlank()
                ? "" : Key.key(def.tooltipStyle()).asString();
        boolean writeStyle = (fresh && currentStyle == null)
                || owns(pdc, STYLE_STAMP, currentStyle == null ? "" : currentStyle.asString())
                || (!fresh && needsStyleCleanup(meta));
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

    private record Rendered(Component name, List<Component> lore, String nameStamp, String loreStamp) {
    }

    private static Rendered render(FormattedItemDefinition def) {
        Component name = def.displayName() == null ? null : text(def.displayName());
        List<Component> lines = new ArrayList<>();
        for (String line : def.lore()) {
            lines.add(text(line));
        }
        List<Component> lore = lines.isEmpty() ? null : List.copyOf(lines);
        return new Rendered(name, lore, fingerprint(name), loreFingerprint(lore));
    }

    // text() always sets italics explicitly off
    private static boolean styledByUs(Component name) {
        return name != null && name.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE;
    }

    private boolean owns(PersistentDataContainer pdc, String stamp, String value) {
        return value.equals(pdc.get(key(stamp), PersistentDataType.STRING));
    }

    private static boolean isLodestoneCompass(ItemMeta meta) {
        // isLodestoneTracked stays true after the lodestone is broken; vanilla still names it then.
        return meta instanceof CompassMeta compass && (compass.hasLodestone() || compass.isLodestoneTracked());
    }

    // A compass we named that has since been bound to a lodestone. Cheap checks first so other items
    // skip the meta copy.
    private boolean hidesLodestoneName(ItemStack stack) {
        // By name: the unit tests' Material doubles are copies of the constants, never the constants.
        return stack.getType().name().equals("COMPASS") && !NO_NAME.equals(readTag(stack, key(NAME_STAMP)))
                && isLodestoneCompass(stack.getItemMeta());
    }

    // Hot path. An empty style stamp means we last wrote no style, so any style now is someone else's:
    // answered without copying the meta.
    private boolean needsStyleCleanup(ItemStack stack) {
        if (customTooltipStyles || "".equals(readTag(stack, key(STYLE_STAMP)))) return false;
        return needsStyleCleanup(stack.getItemMeta());
    }

    private boolean needsStyleCleanup(ItemMeta meta) {
        NamespacedKey style = meta.getTooltipStyle();
        if (customTooltipStyles || style == null) return false;
        String previous = meta.getPersistentDataContainer().get(key(STYLE_STAMP), PersistentDataType.STRING);
        if (previous != null) return previous.equals(style.asString());
        // Only the six styles shipped by older RoyalItems defaults are safe to infer ownership of.
        return style.getNamespace().equals("royalitems") && Set.of("common", "uncommon", "rare", "epic", "legendary", "mythic")
                .contains(style.getKey());
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
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                || meta.hasItemName() || meta.hasCustomModelDataComponent()
                || !meta.getPersistentDataContainer().isEmpty()
                || hasDynamicState(meta);
    }

    // Items whose vanilla name or state is their identity; never dressed, even by a catch-all rule.
    // Gear with specialised meta (crossbow, shield) is deliberately not listed.
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

    /** Why {@link #formatIfSupported} would leave a non-RoyalItems stack alone. */
    public enum SkipReason {
        NO_ITEM, NO_DEFINITION, VANILLA_STATE, CUSTOM_NAME, HAS_LORE, ENCHANTED, CUSTOM_ITEM_NAME, PLUGIN_DATA
    }

    /** Why {@link #formatIfSupported} would leave this non-RoyalItems stack alone, or null if it would dress it. */
    public SkipReason skipReason(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return SkipReason.NO_ITEM;
        }
        if (byMaterial.get(stack.getType()) == null) {
            return SkipReason.NO_DEFINITION;
        }
        if (!stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (hasDynamicState(meta)) {
            return SkipReason.VANILLA_STATE;
        }
        if (meta.hasDisplayName()) {
            return SkipReason.CUSTOM_NAME;
        }
        if (meta.hasLore()) {
            return SkipReason.HAS_LORE;
        }
        if (meta.hasEnchants()) {
            return SkipReason.ENCHANTED;
        }
        if (meta.hasItemName() || meta.hasCustomModelDataComponent()) {
            return SkipReason.CUSTOM_ITEM_NAME;
        }
        if (!meta.getPersistentDataContainer().isEmpty()) {
            return SkipReason.PLUGIN_DATA;
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
     * Whether RoyalItems can dress this material: a real item that can hold a name, lore and identity,
     * and whose vanilla state is not its identity (potions, maps, books, heads, banners, ...).
     */
    public boolean isDressable(Material material) {
        if (material == null || !material.isItem() || material.isAir()) {
            return false;
        }
        ItemMeta meta = new ItemStack(material).getItemMeta();
        return meta != null && !hasDynamicState(meta);
    }
}
