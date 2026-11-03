package com.mystipixel.royalauctions.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A currency listings can be priced in, besides the built-in Vault money. Register an implementation
 * with Bukkit's {@code ServicesManager} (several are allowed, one per id); RoyalAuctions picks it up at
 * once and offers it when {@code currencies.<id>.enabled} is true in its config.yml.
 *
 * <p>Every withdrawal and deposit carries the ID of the auction journal operation behind it. After
 * applying an ID successfully, return {@code true} for repeats without moving value again. A deposit
 * that returned {@code false} may be retried with the same ID.
 */
public interface AuctionCurrency {

    /** Id of the built-in Vault currency; no provider may use it. */
    String MONEY = "money";

    /** Lower-case {@code [a-z0-9_-]{1,32}}, stored with every listing priced in this currency. */
    String id();

    /** Plain text, e.g. {@code "1,250 tokens"}. Called on the main thread. */
    String format(double amount);

    /**
     * Digits after the decimal point, 0 (whole amounts only) to 6; a currency outside that range is not
     * registered. Read when the currency registers; keep it the same for an id. New prices, bids and fees
     * use the current scale, but amounts already recorded (bids held, payouts and refunds owed) are paid
     * as recorded, so after lowering the scale the provider must still accept those finer amounts.
     */
    int scale();

    /**
     * Takes {@code amount} from the player, once per {@code operation}. Called on the main thread; the
     * future may complete on any thread. {@code false}: nothing was taken (not enough funds, account
     * locked). Completing exceptionally means the outcome is unknown: the operation is held for staff
     * review and never retried automatically.
     */
    CompletableFuture<Boolean> withdraw(UUID player, double amount, UUID operation);

    /**
     * Gives {@code amount} to the player, once per {@code operation}, whether or not they are online.
     * Same threading and outcome rules as {@link #withdraw}; {@code false} is retried later.
     */
    CompletableFuture<Boolean> deposit(UUID player, double amount, UUID operation);

    /** Whether {@code id} is a valid currency id. */
    static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,32}");
    }
}
