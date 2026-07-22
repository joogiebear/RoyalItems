package com.mystipixel.royalitems;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A pattern that dresses a whole family of items from one config block, so "every tool and every armour
 * piece" is a handful of rules instead of hundreds of entries. A rule matches materials by name glob
 * ({@code *_SWORD}, {@code DIAMOND_*}) or an explicit list, picks a rarity — a flat one, or by the
 * material's tier prefix ({@code DIAMOND_} → rare, {@code NETHERITE_} → epic) — and fills a name/lore
 * template with placeholders (%name% %type% %rarity% %rarity_color% %material%). At load each rule is
 * expanded into concrete per-material definitions, so runtime stays a plain map lookup.
 */
public final class FormattingRule {

    private final List<Pattern> globs;
    private final Set<Material> materials;
    private final String category;                 // static %category%, or null to use %type%
    private final String defaultRarity;
    private final Map<String, String> tierRarity;  // material name prefix -> rarity id (longest set wins)
    private final String nameTemplate;
    private final List<String> loreTemplate;
    private final Map<String, String> extraTags;   // PDC tags beyond item_id; values may hold placeholders
    private final Set<FormattedItemDefinition.Source> sources;

    private FormattingRule(List<Pattern> globs, Set<Material> materials, String category, String defaultRarity,
                           Map<String, String> tierRarity, String nameTemplate, List<String> loreTemplate,
                           Map<String, String> extraTags, Set<FormattedItemDefinition.Source> sources) {
        this.globs = globs;
        this.materials = materials;
        this.category = category;
        this.defaultRarity = defaultRarity;
        this.tierRarity = tierRarity;
        this.nameTemplate = nameTemplate;
        this.loreTemplate = loreTemplate;
        this.extraTags = extraTags;
        this.sources = sources;
    }

    public static FormattingRule parse(ConfigurationSection sec) {
        List<Pattern> globs = new ArrayList<>();
        for (String g : sec.getStringList("match")) {
            globs.add(globToPattern(g));
        }
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String m : sec.getStringList("materials")) {
            Material mat = Material.matchMaterial(m);
            if (mat != null) {
                materials.add(mat);
            }
        }
        String category = sec.getString("category", null);
        String defaultRarity = sec.getString("rarity", "common").toLowerCase(Locale.ROOT);

        Map<String, String> tierRarity = new LinkedHashMap<>();
        ConfigurationSection tr = sec.getConfigurationSection("tier-rarity");
        if (tr != null) {
            for (String prefix : tr.getKeys(false)) {
                tierRarity.put(prefix.toUpperCase(Locale.ROOT), tr.getString(prefix).toLowerCase(Locale.ROOT));
            }
        }

        String nameTemplate = sec.getString("display-name", "%rarity_color%%name%");
        List<String> loreTemplate = sec.getStringList("lore");

        Map<String, String> extraTags = new LinkedHashMap<>();
        ConfigurationSection tagSec = sec.getConfigurationSection("tags");
        if (tagSec != null) {
            for (String k : tagSec.getKeys(false)) {
                extraTags.put(k, tagSec.getString(k));
            }
        }

        Set<FormattedItemDefinition.Source> sources = EnumSet.noneOf(FormattedItemDefinition.Source.class);
        for (String s : sec.getStringList("format-on")) {
            FormattedItemDefinition.Source src = FormattedItemDefinition.Source.from(s);
            if (src != null) {
                sources.add(src);
            }
        }
        if (sources.isEmpty()) {
            sources.add(FormattedItemDefinition.Source.CRAFT);   // gear is crafted; sensible default
        }
        return new FormattingRule(globs, materials, category, defaultRarity, tierRarity,
                nameTemplate, loreTemplate, extraTags, sources);
    }

    /** Whether this rule applies to {@code material}. */
    public boolean matches(Material material) {
        if (materials.contains(material)) {
            return true;
        }
        String name = material.name();
        for (Pattern glob : globs) {
            if (glob.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The rarity id for a material: a tier-prefix match if any, else the rule's default. */
    public String rarityFor(Material material) {
        String name = material.name();
        for (Map.Entry<String, String> e : tierRarity.entrySet()) {
            if (name.startsWith(e.getKey() + "_") || name.equals(e.getKey())) {
                return e.getValue();
            }
        }
        return defaultRarity;
    }

    public String category() {
        return category;
    }

    public String nameTemplate() {
        return nameTemplate;
    }

    public List<String> loreTemplate() {
        return loreTemplate;
    }

    public Map<String, String> extraTags() {
        return extraTags;
    }

    public Set<FormattedItemDefinition.Source> sources() {
        return sources;
    }

    /** Turn a glob ({@code *_SWORD}) into an anchored, case-insensitive pattern over the material name. */
    private static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.trim().toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '*') {
                sb.append(".*");
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }
}
