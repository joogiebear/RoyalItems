package com.mystipixel.royalitems;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The {@code tooltip-borders} config, read in one place so the packet borders a player sees and the
 * explanation {@code /royalitems inspect} gives can never disagree about which rarities have a border.
 *
 * @param active   all three opt-ins are on: enabled, resource-pack-ready and include-custom-items
 * @param styles   UPPER-CASE rarity token -> valid {@code namespace:id} border location
 * @param context  UPPER-CASE words that qualify a rarity sharing a lore line with other text
 * @param debug    log the detector's verdicts
 */
record BorderSettings(boolean active, Map<String, String> styles, Set<String> context, boolean debug) {

    static final List<String> DEFAULT_CONTEXT = List.of("TIER", "RARITY", "ROYAL");

    /** Read from the plugin config. {@code log} receives a warning per invalid style, or is null to stay quiet. */
    static BorderSettings read(ConfigurationSection config, Logger log) {
        boolean active = config.getBoolean("tooltip-borders.enabled", false)
                && config.getBoolean("tooltip-borders.resource-pack-ready", false)
                && config.getBoolean("tooltip-borders.include-custom-items", false);

        Map<String, String> styles = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("tooltip-borders.styles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String location = section.getString(key);
                if (location == null || location.isBlank()) {
                    continue;
                }
                try {
                    net.kyori.adventure.key.Key.key(location.trim());
                    styles.put(key.trim().toUpperCase(Locale.ROOT), location.trim());
                } catch (RuntimeException ex) {
                    if (log != null) log.warning("Invalid tooltip border style for " + key + ": " + location);
                }
            }
        }

        List<String> configured = config.getStringList("tooltip-borders.context-keywords");
        Set<String> context = new LinkedHashSet<>();
        for (String keyword : configured.isEmpty() ? DEFAULT_CONTEXT : configured) {
            context.add(keyword.trim().toUpperCase(Locale.ROOT));
        }

        return new BorderSettings(active, Collections.unmodifiableMap(styles),
                Collections.unmodifiableSet(context), config.getBoolean("tooltip-borders.debug", false));
    }
}
