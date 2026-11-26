package com.mystipixel.royalauctions.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Lets another plugin draw the auction house menus. Register an implementation with Bukkit's
 * {@code ServicesManager}; before opening one of its own menus, RoyalAuctions asks it first and opens
 * nothing when it accepts. Its listings, prices, fees and transactions stay RoyalAuctions' own: the
 * provider only presents them and calls the plugin's service.
 */
public interface AuctionMenuProvider {

    /** The menus a provider may take over. */
    enum Menu { HUB, BROWSE, BIDS, SELL, COLLECTION, LISTINGS }

    /**
     * Called on the main thread. {@code search} is the text of {@code /ah search} for {@link Menu#BROWSE},
     * else null. Return true when the provider opened its own screen, false to let RoyalAuctions open
     * its menu.
     */
    boolean open(Player player, Menu menu, @Nullable String search);
}
