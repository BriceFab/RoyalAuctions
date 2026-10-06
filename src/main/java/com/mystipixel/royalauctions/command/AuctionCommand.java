package com.mystipixel.royalauctions.command;

import com.mystipixel.royalauctions.RoyalAuctionsPlugin;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.scandicraft.ScandiAuctionBridge;
import com.mystipixel.royalauctions.util.Text;
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
    private final ScandiAuctionBridge scandiAuctionBridge;

    public AuctionCommand(RoyalAuctionsPlugin plugin, GuiManager gui, MessageManager messages,
                          ScandiAuctionBridge scandiAuctionBridge) {
        this.plugin = plugin;
        this.gui = gui;
        this.messages = messages;
        this.scandiAuctionBridge = scandiAuctionBridge;
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
            if (!scandiAuctionBridge.open(player, null)) {
                gui.openHub(player);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "browse" -> {
                if (!scandiAuctionBridge.open(player, null)) {
                    gui.openBrowse(player);
                }
            }
            case "bids" -> gui.openBids(player);
            case "sell", "create" -> {
                if (!player.hasPermission("royalauctions.sell")) {
                    messages.send(player, "general.no-permission");
                } else {
                    gui.openCreate(player);
                }
            }
            case "search" -> {
                String query = args.length < 2
                        ? null
                        : String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                if (!scandiAuctionBridge.open(player, query)) {
                    if (query == null) {
                        gui.openBrowse(player);
                    } else {
                        gui.openBrowse(player, null, query, plugin.pluginConfig().defaultSort(), 0);
                    }
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

    /**
     * {@code /ah category} — explain where the held item would be listed and which rule decided it.
     * The category rules infer from base material for anything that isn't explicitly pinned, so this
     * exists to make that inference visible rather than magic.
     */
    private void inspectCategory(Player player) {
        if (!player.hasPermission("royalauctions.admin")) {
            messages.send(player, "general.no-permission");
            return;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(Text.chat("&cHold the item you want to inspect."));
            return;
        }
        var categories = gui.categories();
        var match = categories.explain(held);
        var category = categories.byId(match.categoryId());
        String tier = gui.tiers().tierOf(held);

        player.sendMessage(Text.chat("&6&lItem Category"));
        player.sendMessage(Text.chat("&7Item:     &f" + categories.keyOf(held)));
        player.sendMessage(Text.chat("&7Category: &f"
                + (category == null ? match.categoryId() : category.displayName())
                + " &8(" + match.categoryId() + ")"));
        player.sendMessage(Text.chat("&7Matched:  &f" + match.reason()));
        player.sendMessage(Text.chat("&7Tier:     &f" + (tier == null ? "&8none" : gui.tiers().displayOf(tier))));
        if (match.reason().startsWith("catch-all") || match.reason().startsWith("strict-items")) {
            player.sendMessage(Text.chat("&8Nothing matched this item — pin it under a category's"));
            player.sendMessage(Text.chat("&8match.items: to place it explicitly."));
        }
    }

    /**
     * {@code /ah <username>} — open that seller's active auctions. Only players this server already
     * knows are looked up: {@code getOfflinePlayer(String)} would block the main thread on a Mojang
     * request for any unknown name, and anyone can type {@code /ah <random name>}.
     */
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
        viewer.sendMessage(Text.chat("&cNo player named &f" + name + "&c has played here."));
    }

    private void sendHelp(Player player) {
        player.sendMessage(Text.chat("&6&lAuction House"));
        player.sendMessage(Text.chat("&e/ah &7- open the Auction House menu"));
        player.sendMessage(Text.chat("&e/ah browse &7- browse all listings"));
        player.sendMessage(Text.chat("&e/ah bids &7- auctions you've bid on"));
        player.sendMessage(Text.chat("&e/ah <player> &7- view a player's auctions"));
        player.sendMessage(Text.chat("&e/ah sell &7- open the Create Auction menu"));
        player.sendMessage(Text.chat("&e/ah search <query> &7- search by name"));
        player.sendMessage(Text.chat("&e/ah listings &7- manage your listings"));
        player.sendMessage(Text.chat("&e/ah collect &7- claim purchases & returns"));
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
            // Online player names, so /ah <username> tab-completes.
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
