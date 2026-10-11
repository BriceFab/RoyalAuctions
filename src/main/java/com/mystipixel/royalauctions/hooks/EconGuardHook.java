package com.mystipixel.royalauctions.hooks;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional EconGuard integration, by reflection against its {@code EconGuard.record(...)} bridge so
 * there is no build-time dependency. Every call is a no-op when EconGuard is absent or too old. Bids
 * and purchases first pass {@link #allow}, EconGuard's {@code allowTrade} veto.
 */
public final class EconGuardHook {

    private static final String SOURCE_AUCTION = "auction";

    private final Method bridge;
    private final Method veto;
    private final Logger logger;
    private final AtomicBoolean vetoFailureLogged = new AtomicBoolean();

    public EconGuardHook(Logger logger) {
        this.logger = logger;
        Method resolved = null;
        Method resolvedVeto = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            try {
                Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
                resolved = econGuard.getMethod("record",
                        UUID.class, String.class, String.class, String.class,
                        double.class, boolean.class, double.class,
                        UUID.class, String.class, String.class, String.class);
                try {
                    resolvedVeto = econGuard.getMethod("allowTrade", UUID.class);
                } catch (NoSuchMethodException oldEconGuard) {
                    // Predates the veto bridge: reporting still works, allow() permits.
                }
            } catch (Throwable ignored) {
                // EconGuard missing or predates the bridge - stay a no-op.
            }
        }
        this.bridge = resolved;
        this.veto = resolvedVeto;
    }

    public boolean isPresent() {
        return bridge != null;
    }

    /** Whether the installed EconGuard exposes the pre-trade veto (its enforcement decides the rest). */
    public boolean hasVeto() {
        return veto != null;
    }

    /** Any failure to answer permits. */
    public boolean allow(UUID player) {
        if (veto == null) {
            return true;
        }
        try {
            return (boolean) veto.invoke(null, player);
        } catch (Throwable failure) {
            if (vetoFailureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "EconGuard's trade veto failed; auctions are allowed until it answers again."
                        + " Further failures are not logged.", failure);
            }
            return true;
        }
    }

    /**
     * Fire-and-forget report of a money movement. {@code amount} is positive; {@code incoming} is true
     * when the money goes to {@code player}; {@code counterparty} may be null. Never throws.
     */
    public void report(UUID player, String playerName, String action, double amount, boolean incoming,
                       UUID counterparty, String counterpartyName, String item) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.invoke(null, player, playerName, SOURCE_AUCTION, action, amount, incoming,
                    Double.NaN, counterparty, counterpartyName, item, null);
        } catch (Throwable ignored) {
        }
    }
}
