package com.mystipixel.royalauctions.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Startup schema migration on SQLite: upgrading an old database and a fresh install. */
class SchemaMigrationTest {
    /** ra_listings as it was before bidding and tiers: no type, bid columns or tier. */
    static final String PRE_BIDDING_LISTINGS = "CREATE TABLE ra_listings (id CHAR(36) PRIMARY KEY,"
            + " seller_id CHAR(36) NOT NULL, seller_name VARCHAR(32) NOT NULL, item_data TEXT NOT NULL,"
            + " display_name VARCHAR(256) NOT NULL, category VARCHAR(64) NOT NULL, price DOUBLE PRECISION NOT NULL,"
            + " created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
            + " buyer_id CHAR(36), sold_at BIGINT)";

    @TempDir Path folder;

    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("auctions.db"));
    }

    private Set<String> listingColumns() throws SQLException {
        Set<String> out = new TreeSet<>();
        try (var c = connection(); var rs = c.getMetaData().getColumns(null, null, "ra_listings", null)) {
            while (rs.next()) out.add(rs.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    private AuctionDatabase open() throws SQLException {
        var db = new AuctionDatabase(folder.toFile(), new YamlConfiguration(), Logger.getAnonymousLogger());
        db.init();
        return db;
    }

    @Test void freshInstallHasEveryListingColumn() throws Exception {
        open().close();
        assertTrue(listingColumns().containsAll(Set.of("enchantments", "tier", "type", "current_bid", "top_bidder_id", "top_bidder_name", "bid_count")));
    }

    @Test void upgradesPreBiddingDatabaseAndNormalisesStoredIds() throws Exception {
        UUID id = UUID.randomUUID();
        try (var c = connection(); var s = c.createStatement()) {
            s.executeUpdate(PRE_BIDDING_LISTINGS);
            s.executeUpdate("INSERT INTO ra_listings (id,seller_id,seller_name,item_data,display_name,category,price,"
                    + "created_at,expires_at,status) VALUES ('" + id + "','" + UUID.randomUUID() + "','Seller','AQID',"
                    + "'Old item','Weapons',10," + System.currentTimeMillis() + "," + (System.currentTimeMillis() + 600_000)
                    + ",'ACTIVE')");
        }
        var db = open();
        try {
            assertTrue(listingColumns().containsAll(Set.of("enchantments", "tier", "type", "current_bid", "top_bidder_id", "top_bidder_name", "bid_count")));
            Listing upgraded = db.getListing(id).orElseThrow();
            assertEquals("weapons", upgraded.category());
            assertEquals(ListingType.BIN, upgraded.type());
            // The browse filter compares exactly, so casing from the menu must not matter.
            assertEquals(1, db.browse(new ListingQuery("Weapons", null, null, null, SortOrder.NEWEST), 0, 10).total());
        } finally {
            db.close();
        }
    }

    @Test void tierIsStoredLowercaseAndFilteredRegardlessOfCase() throws Exception {
        var db = open();
        try {
            var l = new Listing(UUID.randomUUID(), UUID.randomUUID(), "Seller", new byte[]{1, 2, 3}, "Item", "Tools",
                    "Legendary", ListingType.BIN, 10, System.currentTimeMillis(), System.currentTimeMillis() + 600_000,
                    ListingStatus.ACTIVE, 0, null, null, 0);
            try (var c = connection()) { AuctionDatabase.insertListing(c, l, ListingStatus.ACTIVE); }
            assertEquals("legendary", db.getListing(l.id()).orElseThrow().tier());
            assertEquals(1, db.browse(new ListingQuery("TOOLS", "LEGENDARY", null, null, SortOrder.NEWEST), 0, 10).total());
            assertEquals(0, db.browse(new ListingQuery(null, "common", null, null, SortOrder.NEWEST), 0, 10).total());
        } finally {
            db.close();
        }
    }

    @Test void searchAlsoMatchesTranslatedListingNames() throws Exception {
        var db = open();
        try {
            var l = new Listing(UUID.randomUUID(), UUID.randomUUID(), "Seller", new byte[]{1, 2, 3}, "Diamond helmet", "Armor",
                    null, ListingType.BIN, 10, System.currentTimeMillis(), System.currentTimeMillis() + 600_000,
                    ListingStatus.ACTIVE, 0, null, null, 0);
            try (var c = connection()) { AuctionDatabase.insertListing(c, l, ListingStatus.ACTIVE); }
            assertEquals(0, db.browse(new ListingQuery(null, null, null, "casque", SortOrder.NEWEST), 0, 10).total());
            assertEquals(1, db.browse(new ListingQuery(null, null, null, "casque", SortOrder.NEWEST,
                    Set.of("diamond helmet", "iron helmet")), 0, 10).total());
            assertEquals(1, db.browse(new ListingQuery(null, null, null, "helm", SortOrder.NEWEST), 0, 10).total());
        } finally {
            db.close();
        }
    }

    @Test void searchTextIsNeverSqlOrAWildcard() throws Exception {
        var db = open();
        try {
            var l = new Listing(UUID.randomUUID(), UUID.randomUUID(), "Seller", new byte[]{1, 2, 3}, "Diamond helmet", "Armor",
                    null, ListingType.BIN, 10, System.currentTimeMillis(), System.currentTimeMillis() + 600_000,
                    ListingStatus.ACTIVE, 0, null, null, 0);
            try (var c = connection()) { AuctionDatabase.insertListing(c, l, ListingStatus.ACTIVE); }
            for (String hostile : java.util.List.of("' OR '1'='1", "') OR 1=1 --", "x'; DROP TABLE ra_listings; --",
                    "%", "_", "!", "%%", "helm%", "\\")) {
                assertEquals(0, db.browse(new ListingQuery(null, null, null, hostile, SortOrder.NEWEST), 0, 10).total(), hostile);
                assertEquals(0, db.browse(new ListingQuery(null, null, null, "casque", SortOrder.NEWEST,
                        Set.of(hostile)), 0, 10).total(), hostile);
            }
            assertEquals(1, db.browse(new ListingQuery(null, null, null, "helm", SortOrder.NEWEST), 0, 10).total(),
                    "Table intact after the payloads");
        } finally {
            db.close();
        }
    }

    @Test void onlyKeyClashesCountAsDuplicates() {
        assertTrue(AuctionTransactions.duplicate(new SQLException(
                "[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: ra_operation_locks.resource)", null, 19)));
        assertTrue(AuctionTransactions.duplicate(new SQLException("Duplicate entry 'x' for key 'PRIMARY'", "23000", 1062)));
        assertFalse(AuctionTransactions.duplicate(new SQLException(
                "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: ra_operations.kind)", null, 19)));
        assertFalse(AuctionTransactions.duplicate(new SQLException("[SQLITE_CONSTRAINT_TRIGGER] injected", null, 19)));
    }
}
