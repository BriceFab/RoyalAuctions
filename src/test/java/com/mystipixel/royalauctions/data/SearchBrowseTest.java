package com.mystipixel.royalauctions.data;

import com.mystipixel.royalauctions.search.EnchantmentBackfill;
import com.mystipixel.royalauctions.search.SearchTerms;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

// Search by translated item name and by enchantment against real SQL, and the enchantment backfill.
class SearchBrowseTest {
    @TempDir Path folder;
    AuctionDatabase db;
    final SearchTerms terms = new SearchTerms();

    @BeforeEach void open() throws Exception {
        db = new AuctionDatabase(folder.toFile(), new YamlConfiguration(), Logger.getAnonymousLogger());
        db.init();
        terms.load(List.of(Map.of(
                "item.minecraft.diamond_helmet", "Casque en diamant",
                "item.minecraft.iron_helmet", "Casque en fer",
                "enchantment.minecraft.sharpness", "Tranchant")),
                List.of("minecraft:sharpness", "minecraft:smite", "minecraft:unbreaking", "minecraft:lure",
                        "minecraft:flame", "minecraft:sweeping_edge"));
    }

    @AfterEach void close() { db.close(); }

    Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("auctions.db"));
    }

    Listing seed(String name, String index, ListingStatus status, byte[] data) throws SQLException {
        Listing l = new Listing(UUID.randomUUID(), UUID.randomUUID(), "Seller", data, name, "weapons", null,
                ListingType.BIN, 100, System.currentTimeMillis(), System.currentTimeMillis() + 600_000, status,
                0, null, null, 0, index);
        try (var c = connection()) { AuctionDatabase.insertListing(c, l, status); }
        return l;
    }

    Listing seed(String name, String index) throws SQLException {
        return seed(name, index, ListingStatus.ACTIVE, new byte[]{1, 2, 3});
    }

    int found(String search) throws SQLException {
        var match = terms.resolve(search);
        return db.browse(new ListingQuery(null, null, null, search, SortOrder.NEWEST,
                match.itemNames(), match.enchantmentTokens()), 0, 50).total();
    }

    String storedIndex(Listing l) throws SQLException {
        return db.getListing(l.id()).orElseThrow().enchantmentIndex();
    }

    @Test void translatedItemNames() throws Exception {
        seed("Diamond helmet", "");
        seed("Iron helmet", "");
        seed("Casque du roi", "");     // renamed by the seller: matched on its own name
        seed("Diamond sword", "");
        assertEquals(3, found("casque"));
        assertEquals(1, found("CASQUE EN DIAMANT"));
        assertEquals(2, found("helmet"));
        assertEquals(0, found("épée"));
    }

    @Test void enchantmentNameAndLevel() throws Exception {
        seed("Sword", "|minecraft:sharpness=5||minecraft:unbreaking=3|");
        seed("Book", "|minecraft:sharpness=5|");
        seed("Old sword", "|minecraft:sharpness=4|");
        seed("Odd sword", "|minecraft:sharpness=50|");
        seed("Sold sword", "|minecraft:sharpness=5|", ListingStatus.SOLD, new byte[]{1});
        seed("Plain sword", "");
        assertEquals(4, found("sharpness"));
        assertEquals(4, found("sharp"));
        assertEquals(4, found("Tranchant"));
        assertEquals(2, found("sharpness 5"));
        assertEquals(2, found("sharpness V"));
        assertEquals(2, found("tranchant v"));
        assertEquals(1, found("sharpness IV"));
        assertEquals(1, found("minecraft:unbreaking III"));
        assertEquals(4, found("sword"), "name matches still apply");
    }

    @Test void noFalsePositives() throws Exception {
        seed("Bow", "|minecraft:flame=1|");
        seed("Axt", "|minecraft:sweeping_edge=3|");
        seed("Rod", "|minecraft:lure=3|");
        seed("Club", "|minecraft:smite=5|");
        assertEquals(0, found("e"), "a one-letter search no longer matches the end of an enchantment id");
        assertEquals(0, found("sharp"));
        assertEquals(1, found("lure"));
        assertEquals(0, found("lure 2"));
        for (String hostile : List.of("%", "_", "!", "' OR 1=1 --", "sharp% 5", "|", "=")) {
            assertEquals(0, found(hostile), hostile);
        }
        assertEquals(1, found("smite 5"), "table intact after the payloads");
    }

    @Test void backfillIndexesOnlyUnindexedActiveListingsInBatches() throws Exception {
        List<Listing> old = new ArrayList<>();
        for (int i = 0; i < EnchantmentBackfill.BATCH_SIZE + 5; i++) old.add(seed("Old item", null));
        Listing sold = seed("Sold item", null, ListingStatus.SOLD, new byte[]{1, 2, 3});
        Listing indexed = seed("New item", "|minecraft:smite=1|");
        AtomicInteger reads = new AtomicInteger();
        var backfill = new EnchantmentBackfill(db, data -> {
            reads.incrementAndGet();
            return "|minecraft:sharpness=5|";
        }, () -> false, Logger.getAnonymousLogger());

        assertEquals(old.size(), backfill.run());
        assertEquals(old.size(), reads.get());
        assertEquals(old.size(), found("sharpness 5"));
        assertNull(storedIndex(sold), "closed listings are never searched, so they are not indexed");
        assertEquals("|minecraft:smite=1|", storedIndex(indexed));
        assertArrayEquals(new byte[]{1, 2, 3}, db.getListing(old.getFirst().id()).orElseThrow().itemData());

        assertEquals(0, backfill.run(), "a second start finds nothing left to do");
        assertEquals(old.size(), reads.get());
    }

    @Test void unreadableItemsAreMarkedAndNotRetried() throws Exception {
        Listing broken = seed("Broken", null, ListingStatus.ACTIVE, new byte[]{4});
        Listing good = seed("Good", null);
        AtomicInteger reads = new AtomicInteger();
        var backfill = new EnchantmentBackfill(db, data -> {
            reads.incrementAndGet();
            if (data[0] == 4) throw new IllegalArgumentException("unreadable test item");
            return "|minecraft:sharpness=5|";
        }, () -> false, Logger.getAnonymousLogger());

        assertEquals(2, backfill.run());
        assertEquals("", storedIndex(broken));
        assertEquals("|minecraft:sharpness=5|", storedIndex(good));
        assertEquals(1, found("broken"), "still found by name");
        backfill.run();
        assertEquals(2, reads.get());
    }

    @Test void backfillStopsBetweenBatchesAndResumes() throws Exception {
        for (int i = 0; i < EnchantmentBackfill.BATCH_SIZE * 2 + 1; i++) seed("Old item", null);
        AtomicInteger batches = new AtomicInteger();
        var stopping = new EnchantmentBackfill(db, data -> "", () -> batches.getAndIncrement() >= 1,
                Logger.getAnonymousLogger());
        assertEquals(EnchantmentBackfill.BATCH_SIZE, stopping.run());
        var resumed = new EnchantmentBackfill(db, data -> "", () -> false, Logger.getAnonymousLogger());
        assertEquals(EnchantmentBackfill.BATCH_SIZE + 1, resumed.run());
        assertTrue(db.listingsWithoutEnchantmentIndex("", 10).isEmpty());
    }

    @Test void aRememberedCountIsCorrectedByThePage() throws Exception {
        for (int i = 0; i < 5; i++) seed("Item " + i, "");
        var query = ListingQuery.newest();
        assertEquals(5, db.browse(query, 0, 2).total());
        assertEquals(9, db.browse(query, 0, 2, 9).total(), "a full page cannot tell, so the count is kept");
        assertEquals(5, db.browse(query, 2, 2, 9).total(), "a short page shows where the results end");
        assertEquals(5, db.browse(query, 4, 2, 9).total(), "an empty page counts again");
        assertEquals(2, db.browse(query, 0, 2, 9).rows().size());
    }

    @Test void listingsIndexedMeanwhileAreNotOverwritten() throws Exception {
        Listing l = seed("Item", null);
        db.saveEnchantmentIndexes(Map.of(l.id().toString(), "|minecraft:smite=2|"));
        db.saveEnchantmentIndexes(Map.of(l.id().toString(), "|minecraft:sharpness=5|"));
        assertEquals("|minecraft:smite=2|", storedIndex(l));
    }
}
