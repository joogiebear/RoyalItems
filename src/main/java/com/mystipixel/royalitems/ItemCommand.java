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
 * {@code /royalitems give <player> <id> [amount]}, {@code reload}, and {@code info} (the held item's
 * identity). Each subcommand is permission-gated; tab completion only offers what the sender may run.
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
        String sub = args.length >= 1 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            case "info" -> info(sender);
            default -> usage(sender, label);
        }
        return true;
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
        ItemStack item = service.format(args[2], amount);
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "No formatted item with id '" + args[2] + "'.");
            return;
        }
        for (ItemStack overflow : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), overflow);
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + "x " + args[2].toLowerCase()
                + " to " + target.getName() + ".");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("royalitems.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
            return;
        }
        plugin.reloadFormatting();
        sender.sendMessage(ChatColor.GREEN + "RoyalItems reloaded — " + service.all().size() + " formatted item(s).");
    }

    private void info(CommandSender sender) {
        if (!sender.hasPermission("royalitems.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can inspect a held item.");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            sender.sendMessage(ChatColor.GRAY + "Hold an item to inspect it.");
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + "Item: " + ChatColor.WHITE + held.getType());
        String id = service.getItemId(held);
        if (id == null) {
            sender.sendMessage(ChatColor.GRAY + "Not a RoyalItems item (plain vanilla).");
            FormattedItemDefinition def = service.byMaterial(held.getType());
            if (def != null) {
                sender.sendMessage(ChatColor.DARK_GRAY + "Its material would format as '" + def.id() + "' when dropped.");
            }
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "item_id: " + ChatColor.WHITE + id);
        String fuel = service.getFuelId(held);
        if (fuel != null) {
            sender.sendMessage(ChatColor.GRAY + "fuel_id: " + ChatColor.WHITE + fuel);
        }
        FormattedItemDefinition def = service.byId(id);
        if (def != null) {
            for (Map.Entry<String, String> tag : def.tags().entrySet()) {
                if (tag.getKey().equals("item_id") || tag.getKey().equals("fuel_id")) {
                    continue;
                }
                sender.sendMessage(ChatColor.GRAY + tag.getKey() + ": " + ChatColor.WHITE + tag.getValue());
            }
        }
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GRAY + "Usage: /" + label + " <give|reload|info>");
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
                subs.add("info");
            }
            return filter(subs, args[0]);
        }
        if (args[0].equalsIgnoreCase("give")) {
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
