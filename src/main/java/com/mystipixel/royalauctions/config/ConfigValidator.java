package com.mystipixel.royalauctions.config;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Warns about config values that would silently misbehave, on load and on {@code /ah reload}.
 * Warn-only: {@link PluginConfig} already applies safe fallbacks. Durations are checked while parsed.
 */
public final class ConfigValidator {

    private final JavaPlugin plugin;
    private final PluginConfig config;

    public ConfigValidator(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void validate() {
        if (config.feePercent() < 0) {
            warn("listings.fee.percent is negative; the fee is floored at zero.");
        } else if (config.feePercent() > 1) {
            warn("listings.fee.percent is " + config.feePercent() + "; expected a fraction between 0 and 1 (e.g. 0.02 = 2%).");
        }
        if (config.feeMinimum() < 0) {
            warn("listings.fee.minimum is negative; it will act as no minimum fee.");
        }
        if (config.minPrice() <= 0) {
            warn("listings.min-price is <= 0; players could list items for nothing.");
        }
        if (config.hasMaxPrice() && config.maxPrice() < config.minPrice()) {
            warn("listings.max-price (" + config.maxPrice() + ") is below listings.min-price ("
                    + config.minPrice() + "); no price would ever be valid.");
        }
        if (config.maxPerPlayer() == 0) {
            warn("listings.max-per-player is 0; nobody can create a listing. Use -1 for unlimited.");
        } else if (config.maxPerPlayer() < -1) {
            warn("listings.max-per-player is " + config.maxPerPlayer() + "; any negative value means unlimited."
                    + " Use -1 to say so explicitly.");
        }
        if (config.bidMinIncrement() <= 0) {
            warn("bidding.min-increment is <= 0; bids could be raised by nothing.");
        }
        if (config.bidIncrementPercent() < 0 || config.bidIncrementPercent() > 1) {
            warn("bidding.increment-percent is " + config.bidIncrementPercent()
                    + "; expected a fraction between 0 and 1 (e.g. 0.05 = 5%).");
        }
        if (config.durations().isEmpty()) {
            warn("no valid listings.durations configured; falling back to a single 1-day option.");
        }
    }

    private void warn(String message) {
        plugin.getLogger().warning("Config warning: " + message);
    }
}
