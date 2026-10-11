package com.mystipixel.royalauctions.gui.menu;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads (and, on first run, writes out) every gui/*.yml menu template. Reloadable. */
public final class MenuManager {

    private final JavaPlugin plugin;

    private MenuTemplate hub;
    private MenuTemplate browse;
    private MenuTemplate bids;
    private MenuTemplate seller;
    private MenuTemplate create;
    private MenuTemplate duration;
    private MenuTemplate confirmPurchase;
    private MenuTemplate confirmAuction;
    private MenuTemplate confirmBid;
    private MenuTemplate confirmCancel;
    private MenuTemplate bid;
    private MenuTemplate collection;
    private MenuTemplate manage;

    public MenuManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.hub = load("hub.yml");
        this.browse = load("browse.yml");
        this.bids = load("bids.yml");
        this.seller = load("seller.yml");
        this.create = load("create.yml");
        this.duration = load("duration.yml");
        this.confirmPurchase = load("confirm-purchase.yml");
        this.confirmAuction = load("confirm-auction.yml");
        this.confirmBid = load("confirm-bid.yml");
        this.confirmCancel = load("confirm-cancel.yml");
        this.bid = load("bid.yml");
        this.collection = load("collection.yml");
        this.manage = load("manage.yml");
    }

    private MenuTemplate load(String fileName) {
        File file = new File(plugin.getDataFolder(), "gui/" + fileName);
        if (!file.exists()) {
            plugin.saveResource("gui/" + fileName, false);
        }
        return MenuTemplate.load(file, bundled("gui/" + fileName));
    }

    private YamlConfiguration bundled(String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled " + path + ": " + e.getMessage());
        }
        return new YamlConfiguration();
    }

    /** A template by its menu id (the file name without .yml), or null. */
    public MenuTemplate get(String id) {
        if (id == null) {
            return null;
        }
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "hub" -> hub;
            case "browse" -> browse;
            case "bids" -> bids;
            case "seller" -> seller;
            case "create" -> create;
            case "duration" -> duration;
            case "confirm-purchase" -> confirmPurchase;
            case "confirm-auction" -> confirmAuction;
            case "confirm-bid" -> confirmBid;
            case "confirm-cancel" -> confirmCancel;
            case "bid" -> bid;
            case "collection" -> collection;
            case "manage" -> manage;
            default -> null;
        };
    }

    public MenuTemplate hub() {
        return hub;
    }

    public MenuTemplate browse() {
        return browse;
    }

    public MenuTemplate bids() {
        return bids;
    }

    public MenuTemplate seller() {
        return seller;
    }

    public MenuTemplate create() {
        return create;
    }

    public MenuTemplate duration() {
        return duration;
    }

    public MenuTemplate confirmPurchase() {
        return confirmPurchase;
    }

    public MenuTemplate confirmAuction() {
        return confirmAuction;
    }

    public MenuTemplate confirmBid() {
        return confirmBid;
    }

    public MenuTemplate confirmCancel() {
        return confirmCancel;
    }

    public MenuTemplate bid() {
        return bid;
    }

    public MenuTemplate collection() {
        return collection;
    }

    public MenuTemplate manage() {
        return manage;
    }
}
