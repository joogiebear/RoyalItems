package com.mystipixel.royalitems;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

/**
 * {@code /royalitems give <player> <id> [amount]}, {@code reload}, {@code inspect} (everything known
 * about the held item; {@code info} is its older name), {@code formatinv} and {@code export}. Each
 * subcommand is permission-gated; tab completion only offers what the sender may run.
 */
public final class ItemCommand implements CommandExecutor, TabCompleter {

    private final RoyalItemsPlugin plugin;
    private final FormattedItemService service;

    public ItemCommand(RoyalItemsPlugin plugin, FormattedItemService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
        switch (sub) {
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            case "info" -> inspect(sender);   // the older, shorter name for the same inspection
            case "formatinv" -> formatInv(sender, args);
            case "export" -> export(sender);
            case "inspect" -> inspect(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void formatInv(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalitems.formatinv")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(ChatColor.GRAY + "Usage: /royalitems formatinv <player>");
            return;
        }
        int changed = service.formatInventory(target);
        sender.sendMessage(ChatColor.GREEN + "Dressed " + changed + " stack(s) in "
                + target.getName() + "'s inventory.");
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalitems.give")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.GRAY + "Usage: /royalitems give <player> <id> [amount]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                // leave at 1
            }
        }
        ItemStack item = service.format(args[2], 1);
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "No formatted item with id '" + args[2] + "'.");
            return;
        }
        // Hand out stacks no bigger than the item allows, so 16 swords are 16 swords, not one stack of 16.
        int perStack = Math.max(1, item.getMaxStackSize());
        for (int left = amount; left > 0; left -= perStack) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(left, perStack));
            for (ItemStack overflow : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + "x " + args[2].toLowerCase()
                + " to " + target.getName() + ".");
    }

    private void export(CommandSender sender) {
        if (!sender.hasPermission("royalitems.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
            return;
        }
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "catalog-export");
        try {
            int total = CatalogExporter.export(service, dir);
            sender.sendMessage(ChatColor.GREEN + "Exported " + total + " item(s) to "
                    + ChatColor.WHITE + "catalog-export/" + ChatColor.GREEN
                    + " — review, then move the files into items/.");
        } catch (java.io.IOException ex) {
            sender.sendMessage(ChatColor.RED + "Export failed: " + ex.getMessage());
        }
    }

    /**
     * {@code /royalitems inspect} — everything RoyalItems knows about the held item: its identity,
     * its dress state (including staleness), and the border detector's verdict with its reasoning.
     * The border is a documented heuristic, so "why does this item have no border" deserves an
     * answer that isn't reading the code — the {@code /ah category} principle applied here.
     */
    private void inspect(CommandSender sender) {
        if (!sender.hasPermission("royalitems.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
            return;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can inspect a held item.");
            return;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "Hold the item you want to inspect.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Item Inspection");
        sender.sendMessage(ChatColor.GRAY + "Material: " + ChatColor.WHITE + held.getType());

        String id = service.getItemId(held);
        if (id != null) {
            String fuel = service.getFuelId(held);
            sender.sendMessage(ChatColor.GRAY + "item_id:  " + ChatColor.WHITE + id
                    + (fuel == null ? "" : ChatColor.GRAY + "   fuel_id: " + ChatColor.WHITE + fuel));
            FormattedItemDefinition def = service.byId(id);
            if (def != null) {
                for (Map.Entry<String, String> tag : def.tags().entrySet()) {
                    if (!tag.getKey().equals(FormattedItemService.ITEM_ID)
                            && !tag.getKey().equals(FormattedItemService.FUEL_ID)) {
                        String value = service.getTag(held, tag.getKey());
                        sender.sendMessage(ChatColor.GRAY + tag.getKey() + ": " + (value == null
                                ? ChatColor.YELLOW + "(not on this item yet)" : ChatColor.WHITE + value));
                    }
                }
            }
            String state;
            if (def == null) {
                state = ChatColor.YELLOW + "dressed, but its definition no longer exists — left as-is";
            } else if (def.material() != held.getType()) {
                state = ChatColor.YELLOW + "dressed, but the definition now uses " + def.material()
                        + " — left as-is";
            } else if (def.hash().equals(service.getTag(held, FormattedItemService.DEF_HASH))) {
                state = ChatColor.GREEN + "dressed, up to date";
            } else {
                state = ChatColor.YELLOW + "dressed with an older definition — refreshes on the next"
                        + " pickup, container close or craft";
            }
            sender.sendMessage(ChatColor.GRAY + "State:    " + state);
        } else {
            String reason = service.skipReason(held);
            sender.sendMessage(ChatColor.GRAY + "State:    " + (reason == null
                    ? ChatColor.GREEN + "plain and supported — dresses as '"
                    + service.byMaterial(held.getType()).id() + "' on the next pickup, container close or craft"
                    : ChatColor.YELLOW + "not dressed: " + reason));
        }
        inspectBorder(sender, held);
    }

    /** The border half of {@link #inspect}: which rarity the packet layer would see, and why. */
    private void inspectBorder(CommandSender sender, org.bukkit.inventory.ItemStack held) {
        net.kyori.adventure.key.Key explicit = held.getItemMeta().getTooltipStyle();
        if (explicit != null) {
            sender.sendMessage(ChatColor.GRAY + "Border:   " + ChatColor.WHITE + explicit
                    + ChatColor.GRAY + " (explicit tooltip_style — the packet layer leaves it alone)");
            return;
        }
        BorderSettings settings = BorderSettings.read(plugin.getConfig(), null);
        if (!settings.active()) {
            sender.sendMessage(ChatColor.GRAY + "Border:   "
                    + ChatColor.YELLOW + "vanilla — custom packet borders are not opted in (rarity lore still works)");
            return;
        }

        java.util.List<net.kyori.adventure.text.Component> lore =
                held.hasItemMeta() ? held.getItemMeta().lore() : null;
        if (lore == null || lore.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Border:   "
                    + ChatColor.YELLOW + "none — the item has no lore to read a rarity from");
            return;
        }
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        java.util.List<String> lines = new java.util.ArrayList<>(lore.size());
        for (var line : lore) {
            lines.add(RarityDetect.sanitize(plain.serialize(line)));
        }
        RarityDetect.Match match = RarityDetect.detect(lines, settings.context(), settings.styles().keySet());
        if (match == null) {
            sender.sendMessage(ChatColor.GRAY + "Border:   " + ChatColor.YELLOW
                    + "none — no rarity token stands alone on a lore line or shares one with a"
                    + " context keyword");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "Border:   " + ChatColor.WHITE
                + match.rarity().toLowerCase(java.util.Locale.ROOT)
                + ChatColor.GRAY + " → " + ChatColor.WHITE + settings.styles().get(match.rarity())
                + ChatColor.GRAY + " (matched '" + match.line() + "', "
                + (match.standalone() ? "standalone line" : "context keyword") + ")");
        if (plugin.getServer().getPluginManager().getPlugin("packetevents") == null) {
            sender.sendMessage(ChatColor.GRAY + "          " + ChatColor.YELLOW
                    + "PacketEvents is not installed — only RoyalItems-dressed items get borders.");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("royalitems.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
            return;
        }
        try {
            plugin.reloadFormatting();
        } catch (RuntimeException ex) {
            sender.sendMessage(ChatColor.RED + "Reload failed; the previous item catalog remains active: " + ex.getMessage());
            plugin.getLogger().log(java.util.logging.Level.WARNING, "RoyalItems reload failed", ex);
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "RoyalItems reloaded — " + service.all().size() + " formatted item(s).");
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GRAY + "Usage: /" + label + " <give|reload|info|formatinv|export|inspect>");
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
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
