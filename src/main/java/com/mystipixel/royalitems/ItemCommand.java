package com.mystipixel.royalitems;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// /royalitems give <player> <id> [amount] | reload | inspect (alias: info) | formatinv | export
public final class ItemCommand implements CommandExecutor, TabCompleter {

    private final RoyalItemsPlugin plugin;
    private final FormattedItemService service;
    private final MessageManager messages;

    public ItemCommand(RoyalItemsPlugin plugin, FormattedItemService service, MessageManager messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
        switch (sub) {
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            case "info" -> inspect(sender);   // older name for inspect
            case "formatinv" -> formatInv(sender, args);
            case "export" -> export(sender);
            case "inspect" -> inspect(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void formatInv(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalitems.formatinv")) {
            messages.send(sender, "no-permission");
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                messages.send(sender, "player-not-online", "player", args[1]);
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            messages.send(sender, "formatinv.usage");
            return;
        }
        int changed = service.formatInventory(target);
        messages.send(sender, "formatinv.done", "count", String.valueOf(changed), "player", target.getName());
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalitems.give")) {
            messages.send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "give.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "player-not-online", "player", args[1]);
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
            }
        }
        ItemStack item = service.format(args[2], 1);
        if (item == null) {
            messages.send(sender, "give.unknown-item", "id", args[2]);
            return;
        }
        // respect max stack size: 16 swords are 16 stacks, not one stack of 16
        int perStack = Math.max(1, item.getMaxStackSize());
        for (int left = amount; left > 0; left -= perStack) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(left, perStack));
            for (ItemStack overflow : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            }
        }
        messages.send(sender, "give.done", "amount", String.valueOf(amount),
                "id", args[2].toLowerCase(java.util.Locale.ROOT), "player", target.getName());
    }

    private void export(CommandSender sender) {
        if (!sender.hasPermission("royalitems.admin")) {
            messages.send(sender, "no-permission");
            return;
        }
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "catalog-export");
        try (java.io.InputStream bundled = plugin.getResource("items/0-overrides.yml")) {
            java.util.Set<String> overridden = CatalogExporter.overriddenIds(
                    new java.io.File(plugin.getDataFolder(), "items/0-overrides.yml"),
                    bundled == null ? null : new java.io.InputStreamReader(bundled, java.nio.charset.StandardCharsets.UTF_8));
            int total = CatalogExporter.export(service, dir, overridden);
            messages.send(sender, "export.done", "count", String.valueOf(total));
        } catch (java.io.IOException ex) {
            messages.send(sender, "export.failed", "error", String.valueOf(ex.getMessage()));
        }
    }

    // identity, dress state and the border detector's verdict with its reason
    private void inspect(CommandSender sender) {
        if (!sender.hasPermission("royalitems.admin")) {
            messages.send(sender, "no-permission");
            return;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            messages.send(sender, "inspect.players-only");
            return;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.send(sender, "inspect.hold-item");
            return;
        }

        messages.send(sender, "inspect.header");
        messages.send(sender, "inspect.material", "material", String.valueOf(held.getType()));

        String id = service.getItemId(held);
        if (id != null) {
            String fuel = service.getFuelId(held);
            messages.send(sender, "inspect.item-id", "id", id,
                    "fuel", fuel == null ? "" : messages.get("inspect.fuel-id", "fuel", fuel));
            FormattedItemDefinition def = service.byId(id);
            if (def != null) {
                for (Map.Entry<String, String> tag : def.tags().entrySet()) {
                    if (!tag.getKey().equals(FormattedItemService.ITEM_ID)
                            && !tag.getKey().equals(FormattedItemService.FUEL_ID)) {
                        String value = service.getTag(held, tag.getKey());
                        if (value == null) {
                            messages.send(sender, "inspect.tag-missing", "tag", tag.getKey());
                        } else {
                            messages.send(sender, "inspect.tag", "tag", tag.getKey(), "value", value);
                        }
                    }
                }
            }
            String state;
            if (def == null) {
                state = messages.get("inspect.states.definition-missing");
            } else if (def.material() != held.getType()) {
                state = messages.get("inspect.states.material-changed", "material", String.valueOf(def.material()));
            } else if (def.hash().equals(service.getTag(held, FormattedItemService.DEF_HASH))) {
                state = messages.get("inspect.states.up-to-date");
            } else {
                state = messages.get("inspect.states.outdated");
            }
            messages.send(sender, "inspect.state", "state", state);
        } else {
            FormattedItemService.SkipReason reason = service.skipReason(held);
            String state = reason == null
                    ? messages.get("inspect.states.supported", "id", service.byMaterial(held.getType()).id())
                    : messages.get("inspect.states.not-dressed", "reason", skipReason(reason, held));
            messages.send(sender, "inspect.state", "state", state);
        }
        inspectBorder(sender, held);
    }

    private void inspectBorder(CommandSender sender, org.bukkit.inventory.ItemStack held) {
        net.kyori.adventure.key.Key explicit = held.getItemMeta().getTooltipStyle();
        if (explicit != null) {
            messages.send(sender, "inspect.border.explicit", "style", explicit.toString());
            return;
        }
        BorderSettings settings = BorderSettings.read(plugin.getConfig(), null);
        if (!settings.active()) {
            messages.send(sender, "inspect.border.vanilla");
            return;
        }

        java.util.List<net.kyori.adventure.text.Component> lore =
                held.hasItemMeta() ? held.getItemMeta().lore() : null;
        if (lore == null || lore.isEmpty()) {
            messages.send(sender, "inspect.border.no-lore");
            return;
        }
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        java.util.List<String> lines = new java.util.ArrayList<>(lore.size());
        for (var line : lore) {
            lines.add(RarityDetect.sanitize(plain.serialize(line)));
        }
        RarityDetect.Match match = RarityDetect.detect(lines, settings.context(), settings.styles().keySet());
        if (match == null) {
            messages.send(sender, "inspect.border.no-match");
            return;
        }
        messages.send(sender, "inspect.border.matched",
                "rarity", match.rarity().toLowerCase(java.util.Locale.ROOT),
                "style", String.valueOf(settings.styles().get(match.rarity())),
                "line", match.line(),
                "match", match.standalone() ? messages.get("inspect.border.standalone")
                        : messages.get("inspect.border.context"));
        if (plugin.getServer().getPluginManager().getPlugin("packetevents") == null) {
            messages.send(sender, "inspect.border.no-packetevents");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("royalitems.reload")) {
            messages.send(sender, "no-permission");
            return;
        }
        try {
            plugin.reloadFormatting();
        } catch (RuntimeException ex) {
            messages.send(sender, "reload.failed", "error", String.valueOf(ex.getMessage()));
            plugin.getLogger().log(java.util.logging.Level.WARNING, "RoyalItems reload failed", ex);
            return;
        }
        messages.reload();
        messages.send(sender, "reload.done", "count", String.valueOf(service.all().size()));
    }

    private void usage(CommandSender sender, String label) {
        messages.send(sender, "usage", "label", label);
    }

    private String skipReason(FormattedItemService.SkipReason reason, ItemStack held) {
        return switch (reason) {
            case NO_ITEM -> messages.get("inspect.reasons.no-item");
            case NO_DEFINITION -> messages.get("inspect.reasons.no-definition", "material", String.valueOf(held.getType()));
            case VANILLA_STATE -> messages.get("inspect.reasons.vanilla-state");
            case CUSTOM_NAME -> messages.get("inspect.reasons.custom-name");
            case HAS_LORE -> messages.get("inspect.reasons.has-lore");
            case ENCHANTED -> messages.get("inspect.reasons.enchanted");
            case CUSTOM_ITEM_NAME -> messages.get("inspect.reasons.custom-item-name");
            case PLUGIN_DATA -> messages.get("inspect.reasons.plugin-data");
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("royalitems.give")) {
                subs.add("give");
            }
            if (sender.hasPermission("royalitems.reload")) {
                subs.add("reload");
            }
            if (sender.hasPermission("royalitems.admin")) {
                subs.add("export");
                subs.add("inspect");
            }
            if (sender.hasPermission("royalitems.formatinv")) {
                subs.add("formatinv");
            }
            return filter(subs, args[0]);
        }
        if (args[0].equalsIgnoreCase("formatinv") && args.length == 2) {
            if (!sender.hasPermission("royalitems.formatinv")) return List.of();
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return filter(names, args[1]);
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("royalitems.give")) return List.of();
            if (args.length == 2) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return filter(names, args[1]);
            }
            if (args.length == 3) {
                List<String> ids = new ArrayList<>();
                for (FormattedItemDefinition def : service.all()) {
                    ids.add(def.id());
                }
                return filter(ids, args[2]);
            }
            if (args.length == 4) {
                return filter(List.of("1", "16", "32", "64"), args[3]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(java.util.Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
