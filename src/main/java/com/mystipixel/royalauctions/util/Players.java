package com.mystipixel.royalauctions.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class Players {

    private Players() {
    }

    /**
     * True if this is the Player object the server holds for that account right now. {@code isOnline()}
     * is not enough: it stays true on the old object after a relog, whose inventory is never saved again.
     */
    public static boolean isCurrent(Player player) {
        return player != null && Bukkit.getPlayer(player.getUniqueId()) == player;
    }
}
