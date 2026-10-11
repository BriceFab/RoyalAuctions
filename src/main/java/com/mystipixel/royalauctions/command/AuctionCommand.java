package com.mystipixel.royalauctions.command;

import com.mystipixel.royalauctions.RoyalAuctionsPlugin;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("browse", "bids", "sell", "create", "search", "collect", "listings", "category", "help");

    private final RoyalAuctionsPlugin plugin;
    private final GuiManager gui;
    private final MessageManager messages;

    public AuctionCommand(RoyalAuctionsPlugin plugin, GuiManager gui, MessageManager messages) {
        this.plugin = plugin;
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("royalauctions.admin")) {
                messages.send(sender, "general.no-permission");
                return true;
            }
            plugin.reloadEverything();
            messages.send(sender, "general.reloaded");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("recovery")) {
            if (!sender.hasPermission("royalauctions.admin")) messages.send(sender, "general.no-permission");
            else gui.service().recoveryCommand(sender, args);
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission("royalauctions.use")) {
            messages.send(player, "general.no-permission");
            return true;
        }

        if (args.length == 0) {
            gui.openHub(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "browse" -> gui.openBrowse(player);
            case "bids" -> gui.openBids(player);
            case "sell", "create" -> {
                if (!player.hasPermission("royalauctions.sell")) {
                    messages.send(player, "general.no-permission");
                } else {
                    gui.openCreate(player);
                }
            }
            case "search" -> {
                if (args.length < 2) {
                    gui.openBrowse(player);
                } else {
                    String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    gui.openBrowse(player, null, query, plugin.pluginConfig().defaultSort(), 0);
                }
            }
            case "collect", "collection" -> gui.openCollection(player);
            case "listings", "mylistings" -> gui.openListings(player);
            case "category" -> inspectCategory(player);
            case "help" -> sendHelp(player);
            // Anything else is treated as a player name: /ah <username> shows that seller's auctions.
            default -> openSellerView(player, args[0]);
        }
        return true;
    }

    // /ah category: shows where the held item would be listed and which rule decided it.
    private void inspectCategory(Player player) {
        if (!player.hasPermission("royalauctions.admin")) {
            messages.send(player, "general.no-permission");
            return;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.send(player, "inspect.hold-item");
            return;
        }
        var categories = gui.categories();
        var match = categories.explain(held);
        var category = categories.byId(match.categoryId());
        String tier = gui.tiers().tierOf(held);

        messages.sendStyled(player, "inspect.header");
        messages.sendStyled(player, "inspect.item", "item", categories.keyOf(held));
        messages.sendStyled(player, "inspect.category",
                "category", category == null ? match.categoryId() : category.displayName(), "id", match.categoryId());
        messages.sendStyled(player, "inspect.matched", "reason", match.reason());
        messages.sendStyled(player, "inspect.tier",
                "tier", tier == null ? messages.text("inspect.no-tier") : gui.tiers().displayOf(tier));
        if (match.reason().startsWith("catch-all") || match.reason().startsWith("strict-items")) {
            messages.sendLines(player, "inspect.unmatched");
        }
    }

    // Only players this server already knows are looked up: getOfflinePlayer(String) blocks the
    // main thread on a Mojang request for an unknown name.
    private void openSellerView(Player viewer, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            gui.openSeller(viewer, online.getUniqueId(), online.getName());
            return;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline != null && offline.hasPlayedBefore()) {
            gui.openSeller(viewer, offline.getUniqueId(),
                    offline.getName() != null ? offline.getName() : name);
            return;
        }
        messages.send(viewer, "general.unknown-player", "player", name);
    }

    private void sendHelp(Player player) {
        messages.sendLines(player, "help", "search-help", messages.text("search.help"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            if (sender.hasPermission("royalauctions.admin") && "reload".startsWith(prefix)) {
                out.add("reload");
            }
            if (sender.hasPermission("royalauctions.admin") && "recovery".startsWith(prefix)) out.add("recovery");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(online.getName());
                }
            }
            return out;
        }
        return List.of();
    }
}
