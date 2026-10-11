package com.mystipixel.econguard.api;

import java.util.UUID;
import java.util.function.Predicate;

/** Stand-in for EconGuard's reflective bridge, so EconGuardHook can be tested without the plugin. */
public final class EconGuard {
    public static Predicate<UUID> veto = player -> true;

    private EconGuard() {
    }

    public static void record(UUID player, String playerName, String source, String action,
                              double amount, boolean incoming, double balanceAfter,
                              UUID counterparty, String counterpartyName, String item, String note) {
    }

    public static boolean allowTrade(UUID player) {
        return veto.test(player);
    }
}
