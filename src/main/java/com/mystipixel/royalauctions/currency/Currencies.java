package com.mystipixel.royalauctions.currency;

import com.mystipixel.royalauctions.api.AuctionCurrency;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * The currencies listings can be priced in: Vault money plus every registered {@link AuctionCurrency}.
 * Refreshed on the main thread; lookups are safe from any thread.
 */
public final class Currencies {

    // Doubles carry about 15 significant digits; more decimals would not survive storage.
    static final int MAX_SCALE = 6;

    private record Entry(AuctionCurrency currency, int scale) {
    }

    private final AuctionCurrency money;
    private final PluginConfig config;
    private final MessageManager messages;
    private final Logger logger;
    private volatile Map<String, Entry> registered = Map.of();

    public Currencies(VaultHook vault, PluginConfig config, MessageManager messages, Logger logger) {
        this.money = new VaultCurrency(vault);
        this.config = config;
        this.messages = messages;
        this.logger = logger;
    }

    /** Main thread. {@code providers} in service priority order: the first one with an id wins. */
    public void refresh(List<AuctionCurrency> providers) {
        Map<String, Entry> next = new LinkedHashMap<>();
        next.put(AuctionCurrency.MONEY, new Entry(money, money.scale()));
        for (AuctionCurrency provider : providers) {
            String id = provider.id();
            int scale = provider.scale();
            if (!AuctionCurrency.validId(id) || id.equals(AuctionCurrency.MONEY) || scale < 0 || scale > MAX_SCALE) {
                logger.warning("Ignoring auction currency '" + id + "' from " + provider.getClass().getName()
                        + ": the id must be lower case [a-z0-9_-]{1,32} and not 'money', the scale 0 to " + MAX_SCALE + ".");
                continue;
            }
            if (next.containsKey(id)) {
                logger.warning("Two auction currency providers use the id '" + id + "'; using "
                        + next.get(id).currency().getClass().getName() + ", the higher-priority one.");
                continue;
            }
            next.put(id, new Entry(provider, scale));
        }
        Map<String, Entry> before = registered;
        registered = next;
        for (String id : next.keySet()) {
            if (!id.equals(AuctionCurrency.MONEY) && !before.containsKey(id)) {
                logger.info("Auction currency '" + id + "' registered" + (config.enabledCurrencies().contains(id)
                        ? "." : "; set currencies." + id + ".enabled: true to offer it."));
            }
        }
        for (String id : before.keySet()) {
            if (!next.containsKey(id)) {
                logger.warning("Auction currency '" + id + "' is gone: its listings can't be bought"
                        + " or bid on, and payouts in it wait, until it registers again.");
            }
        }
    }

    /** The registered currency, or null. Never another currency in its place. */
    public AuctionCurrency get(String id) {
        Entry entry = registered.get(id);
        return entry == null ? null : entry.currency();
    }

    /** The registered currency's scale, empty while it is not registered. */
    public OptionalInt scale(String id) {
        Entry entry = registered.get(id);
        return entry == null ? OptionalInt.empty() : OptionalInt.of(entry.scale());
    }

    /** The currency's label from messages.yml ({@code currencies.<id>}), or the id. */
    public String name(String id) {
        String label = messages.optional("currencies." + id);
        return label == null ? id : label;
    }

    /** An amount in the currency's own format. Main thread. */
    public String format(String id, double amount) {
        Entry entry = registered.get(id);
        if (entry != null) {
            return entry.currency().format(amount);
        }
        return new DecimalFormat("#,##0.######").format(amount) + " " + name(id);
    }

    /** Per-currency totals as one line, e.g. "$5.00, 120 tokens"; money's zero when there are none. Main thread. */
    public String formatTotals(Map<String, Double> totals, String separator) {
        if (totals.isEmpty()) {
            return format(AuctionCurrency.MONEY, 0);
        }
        List<String> parts = new ArrayList<>();
        totals.forEach((id, amount) -> parts.add(format(id, amount)));
        return String.join(separator, parts);
    }

    /** Whether new listings may be priced in the currency right now: enabled and registered. */
    public boolean offers(String id) {
        return config.enabledCurrencies().contains(id) && registered.containsKey(id);
    }

    /** The currencies new listings may be priced in right now, in config order. */
    public List<String> offered() {
        List<String> out = new ArrayList<>();
        for (String id : config.enabledCurrencies()) {
            if (registered.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
