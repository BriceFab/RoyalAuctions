package com.mystipixel.royalauctions.search;

import com.mystipixel.royalauctions.data.AuctionDatabase;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Indexes the enchantments of active listings created before enchantment search existed. Blocking:
 * run it on a database worker. Works in batches, one transaction each, and stops between batches once
 * {@code stop} says so; whatever is left is picked up on the next start.
 */
public final class EnchantmentBackfill {

    public static final int BATCH_SIZE = 100;

    private final AuctionDatabase db;
    private final Function<byte[], String> indexer;
    private final BooleanSupplier stop;
    private final Logger logger;

    public EnchantmentBackfill(AuctionDatabase db, Function<byte[], String> indexer, BooleanSupplier stop, Logger logger) {
        this.db = db;
        this.indexer = indexer;
        this.stop = stop;
        this.logger = logger;
    }

    /** Returns how many listings were indexed, unreadable ones included. */
    public int run() throws SQLException {
        String after = "";
        int indexed = 0;
        int unreadable = 0;
        while (!stop.getAsBoolean()) {
            List<AuctionDatabase.StoredItem> batch = db.listingsWithoutEnchantmentIndex(after, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            Map<String, String> indexes = new LinkedHashMap<>();
            for (AuctionDatabase.StoredItem listing : batch) {
                after = listing.id();
                String index;
                try {
                    index = indexer.apply(listing.itemData());
                } catch (RuntimeException e) {
                    // stored as "no enchantments" so it isn't retried on every start; still found by name
                    logger.log(Level.FINE, "Could not read the item of listing " + listing.id(), e);
                    index = "";
                    unreadable++;
                }
                indexes.put(listing.id(), index);
            }
            db.saveEnchantmentIndexes(indexes);
            indexed += indexes.size();
        }
        if (indexed > 0) {
            logger.info("Search: indexed the enchantments of " + indexed + " existing listing(s).");
        }
        if (unreadable > 0) {
            logger.warning(unreadable + " listing item(s) could not be read for enchantment search; they can"
                    + " still be found by name.");
        }
        return indexed;
    }
}
