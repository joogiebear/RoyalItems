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
 * Dresses a family of items from one config block. Matches by glob ({@code *_SWORD}) or explicit list,
 * picks a rarity (flat or by tier prefix, {@code DIAMOND_} -> rare), and fills name/lore templates.
 * Placeholders: %name% %type% %rarity% %rarity_color% %material% %category% %category_upper%.
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

    private FormattingRule(List<Pattern> globs, Set<Material> materials, String category, String defaultRarity,
                           Map<String, String> tierRarity, String nameTemplate, List<String> loreTemplate,
                           Map<String, String> extraTags) {
        this.globs = globs;
        this.materials = materials;
        this.category = category;
        this.defaultRarity = defaultRarity;
        this.tierRarity = tierRarity;
        this.nameTemplate = nameTemplate;
        this.loreTemplate = loreTemplate;
        this.extraTags = extraTags;
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

        return new FormattingRule(globs, materials, category, defaultRarity, tierRarity,
                nameTemplate, loreTemplate, extraTags);
    }

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

    /** A tier-prefix match if any, else the rule's default rarity. */
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
