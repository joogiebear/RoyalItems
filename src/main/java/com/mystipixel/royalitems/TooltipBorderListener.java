package com.mystipixel.royalitems;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipStyle;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Draws a rarity-coloured border around the whole tooltip of <em>any</em> item — eco gear, vanilla
 * drops, bazaar purchases, minion items — by stamping the client-only {@code minecraft:tooltip_style}
 * component onto outgoing item packets.
 *
 * <p>It never touches the stored item: the border is decided from the item's own rarity lore line and
 * written only on the wire, so it works no matter which plugin built the item and reverts the instant
 * the feature is turned off. The border sprites live in the server's resource pack under the
 * {@code royalitems:} namespace. An item that already declares its own tooltip style is left alone.
 *
 * <p><b>Rarity detection.</b> Each lore line is stripped of legacy {@code §} codes, upper-cased and split
 * into words. A rarity is recognised when its word appears either <em>on its own line</em> (the eco/
 * RoyalItems ladder writes {@code &5&lEPIC}) or <em>on a line that also carries a context keyword</em>
 * such as {@code TIER} or {@code ROYAL} (EcoArmor writes {@code Tier: Royal Legendary}). Requiring the
 * context keyword keeps flavour text that merely mentions "a legendary blade" from being mistaken for a
 * rarity. When several rarities match, the highest tier wins.
 */
public final class TooltipBorderListener extends PacketListenerAbstract {

    /** Fixed low-to-high tier order, used to pick the strongest rarity when a lore mentions several. */
    private static final List<String> RANK =
            List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    private final Map<String, ResourceLocation> styleByRarity; // UPPER-CASE rarity word -> border location
    private final Set<String> contextKeywords;                 // UPPER-CASE words that qualify an in-line rarity
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    private final boolean debug;
    private final java.util.logging.Logger log;
    private int debugDumps = 0; // cap the per-item lore dump so a busy server can't be flooded

    public TooltipBorderListener(Map<String, ResourceLocation> styleByRarity, Set<String> contextKeywords,
                                 boolean debug, java.util.logging.Logger log) {
        super(PacketListenerPriority.LOW);
        this.styleByRarity = styleByRarity;
        this.contextKeywords = contextKeywords;
        this.debug = debug;
        this.log = log;
    }

    /**
     * Build the listener from raw config (rarity token -&gt; {@code namespace:id} string) and register it
     * with PacketEvents. Returned as {@link Object} so the caller never has to touch a PacketEvents type —
     * that keeps every PacketEvents reference inside this class, which only loads when the plugin is present.
     */
    public static Object enable(Map<String, String> rawStyles, List<String> contextKeywords,
                                boolean debug, java.util.logging.Logger log) {
        Map<String, ResourceLocation> styles = new HashMap<>();
        for (Map.Entry<String, String> entry : rawStyles.entrySet()) {
            styles.put(entry.getKey(), new ResourceLocation(entry.getValue()));
        }
        Set<String> context = new HashSet<>();
        for (String keyword : contextKeywords) {
            context.add(keyword.trim().toUpperCase(Locale.ROOT));
        }
        TooltipBorderListener listener = new TooltipBorderListener(styles, context, debug, log);
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    /** Unregister a listener returned by {@link #enable}. */
    public static void disable(Object listener) {
        if (listener instanceof PacketListenerAbstract packetListener) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            ItemStack item = packet.getItem();
            if (apply(item)) {
                packet.setItem(item);
                event.markForReEncode(true);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            boolean changed = false;
            List<ItemStack> items = packet.getItems();
            for (ItemStack item : items) {
                changed |= apply(item);
            }
            Optional<ItemStack> carried = packet.getCarriedItem();
            if (carried.isPresent() && apply(carried.get())) {
                packet.setCarriedItem(carried.get());
                changed = true;
            }
            if (changed) {
                packet.setItems(items);
                event.markForReEncode(true);
            }
        }
    }

    /** Stamp the matching border on {@code item} in place; return whether it was changed. */
    private boolean apply(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        if (item.hasComponent(ComponentTypes.TOOLTIP_STYLE)) {
            return false; // an explicit style (e.g. a RoyalItems-dressed drop) wins
        }
        ResourceLocation style = styleFor(item);
        if (style == null) {
            return false;
        }
        item.setComponent(ComponentTypes.TOOLTIP_STYLE, new ItemTooltipStyle(style));
        return true;
    }

    /** The border for an item's rarity, read from its lore, or null if it carries no known rarity. */
    private ResourceLocation styleFor(ItemStack item) {
        Optional<ItemLore> lore = item.getComponent(ComponentTypes.LORE);
        if (lore.isEmpty()) {
            return null;
        }
        boolean dump = debug && debugDumps < 40;
        StringBuilder dumped = dump ? new StringBuilder() : null;

        String bestRarity = null;
        ResourceLocation bestStyle = null;
        for (Component line : lore.get().getLines()) {
            String text = sanitize(plain.serialize(line));
            if (dump) {
                dumped.append(" | '").append(text).append('\'');
            }
            if (text.isEmpty()) {
                continue;
            }
            Set<String> words = new HashSet<>(Arrays.asList(text.split("[^A-Z]+")));
            boolean standalone = words.size() == 1;
            boolean hasContext = !java.util.Collections.disjoint(words, contextKeywords);
            if (!standalone && !hasContext) {
                continue; // a rarity word buried in flavour text is not a rarity declaration
            }
            for (Map.Entry<String, ResourceLocation> entry : styleByRarity.entrySet()) {
                String rarity = entry.getKey();
                if (words.contains(rarity) && rank(rarity) > rank(bestRarity)) {
                    bestRarity = rarity;
                    bestStyle = entry.getValue();
                }
            }
        }
        if (dump) {
            debugDumps++;
            log.info("[tooltip-borders] " + item.getType()
                    + " -> " + (bestRarity == null ? "no-match" : bestRarity + " " + bestStyle) + " lore:" + dumped);
        }
        return bestStyle;
    }

    /** Strip legacy {@code §x} codes, trim, and upper-case — eco lore ships literal section codes. */
    private static String sanitize(String raw) {
        return raw.replaceAll("§.", "").trim().toUpperCase(Locale.ROOT);
    }

    /** Tier index of a rarity word (higher = stronger); -1 for null/unknown so any real match beats it. */
    private static int rank(String rarity) {
        return rarity == null ? -1 : RANK.indexOf(rarity);
    }
}
