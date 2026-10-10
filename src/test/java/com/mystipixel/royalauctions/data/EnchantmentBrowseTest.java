package com.mystipixel.royalauctions.data;

import com.mystipixel.royalauctions.util.EnchantmentSearch;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses real SQL; the transaction contract remains unchanged. */
class EnchantmentBrowseTest extends AuctionTransactionsTest {
    private Listing indexed(String index, String name, ListingType type, ListingStatus status) throws Exception {
        Listing l = new Listing(UUID.randomUUID(), seller, "Seller", bytes, name, "weapons", "rare", type,
                100, System.currentTimeMillis(), System.currentTimeMillis() + 600_000, status, 0, null, null, 0);
        l.enchantments(index);
        try (var c = connection()) { AuctionDatabase.insertListing(c, l, status); }
        return l;
    }
    private ListingQuery query(String text) {
        return new ListingQuery(null, null, null, text, SortOrder.NEWEST, new EnchantmentSearch().matching(text));
    }
    @Test void filtersBeforeCountingAndPagingAndMatchesExactLevels() throws Exception {
        indexed("|minecraft:sharpness=5||minecraft:unbreaking=3|", "Sword", ListingType.BIN, ListingStatus.ACTIVE);
        indexed("|minecraft:sharpness=5|", "Book", ListingType.AUCTION, ListingStatus.ACTIVE);
        indexed("|minecraft:sharpness=4|", "Sword", ListingType.BIN, ListingStatus.ACTIVE);
        indexed("|minecraft:sharpness=50|", "Sword", ListingType.BIN, ListingStatus.ACTIVE);
        indexed("|minecraft:sharpness=5|", "Sword", ListingType.BIN, ListingStatus.SOLD);
        indexed("", "Sword", ListingType.BIN, ListingStatus.ACTIVE);
        assertEquals(4, db.browse(query("sharpness"), 0, 10).total());
        var first = db.browse(query("sharpness V"), 0, 1);
        var second = db.browse(query("sharpness 5"), 1, 1);
        assertEquals(2, first.total()); assertEquals(2, second.total());
        assertEquals(1, first.rows().size()); assertEquals(1, second.rows().size());
        assertNotEquals(first.rows().getFirst().id(), second.rows().getFirst().id());
        assertEquals(1, db.browse(new ListingQuery("WEAPONS", "RARE", ListingType.BIN, "sharpness 5",
                SortOrder.PRICE_LOW, new EnchantmentSearch().matching("sharpness 5")), 0, 10).total());
        assertEquals(5, db.browse(query("sword"), 0, 10).total() + db.browse(query("book"), 0, 10).total());
        assertEquals(1, db.browse(query("minecraft:unbreaking III"), 0, 10).total());
        for (String hostile : List.of("%", "_", "!", "' OR 1=1 --", "sharpness% 5"))
            assertEquals(0, db.browse(query(hostile), 0, 10).total(), hostile);
        assertEquals(0, db.browse(new ListingQuery(null, null, null, "tranchant", SortOrder.NEWEST,
                Set.of("minecraft:smite=")), 0, 10).total());
        assertEquals(4, db.browse(new ListingQuery(null, null, null, "tranchant", SortOrder.NEWEST,
                Set.of("minecraft:sharpness=")), 0, 10).total());
    }

    @Test void unreadableItemsRemainRetryableWithoutBlockingOtherRows() throws Exception {
        var q = query("sharpness 5");
        var broken = indexed(null, "Broken", ListingType.BIN, ListingStatus.ACTIVE);
        var good = indexed(null, "Good", ListingType.BIN, ListingStatus.ACTIVE);
        sql("UPDATE ra_listings SET item_data=? WHERE id=?", "BAUG", broken.id());
        try (var search = mockStatic(EnchantmentSearch.class);
             var serialization = mockStatic(ItemSerialization.class, CALLS_REAL_METHODS)) {
            var item = mock(org.bukkit.inventory.ItemStack.class);
            when(item.clone()).thenReturn(item);
            serialization.when(() -> ItemSerialization.deserialize(any())).thenAnswer(invocation -> {
                byte[] data = invocation.getArgument(0);
                if (data[0] == 4) throw new IllegalArgumentException("Unreadable test item");
                return item;
            });
            search.when(() -> EnchantmentSearch.index(item)).thenReturn("|minecraft:sharpness=5|");
            db.indexMissingEnchantments();
            assertEquals(1, db.browse(q, 0, 10).total());
            try (var c = connection(); var ps = c.prepareStatement("SELECT enchantments FROM ra_listings WHERE id=?")) {
                ps.setString(1, broken.id().toString());
                try (var rs = ps.executeQuery()) { assertTrue(rs.next()); assertNull(rs.getString(1)); }
            }
            serialization.when(() -> ItemSerialization.deserialize(any())).thenReturn(item);
            db.indexMissingEnchantments();
            assertEquals(2, db.browse(q, 0, 10).total());
            assertArrayEquals(new byte[]{4, 5, 6}, db.getListing(broken.id()).orElseThrow().itemData());
            assertArrayEquals(bytes, db.getListing(good.id()).orElseThrow().itemData());
        }
    }

    @Test void backfillIsBoundedRetryableAndLeavesItemsAndStatesUntouched() throws Exception {
        // More than one batch, including a held draft that can become active during recovery.
        for (int i = 0; i < 102; i++) indexed(null, "Old item", ListingType.BIN,
                i == 0 ? ListingStatus.DRAFT : ListingStatus.ACTIVE);
        indexed("|minecraft:sharpness=4|", "Already indexed", ListingType.BIN, ListingStatus.ACTIVE);
        try (var search = mockStatic(EnchantmentSearch.class)) {
            search.when(() -> EnchantmentSearch.index(any())).thenReturn("|minecraft:sharpness=5|");
            // Avoid needing a running Minecraft server for decoding.
            try (var serialization = mockStatic(ItemSerialization.class, CALLS_REAL_METHODS)) {
                var item = mock(org.bukkit.inventory.ItemStack.class);
                when(item.clone()).thenReturn(item);
                serialization.when(() -> ItemSerialization.deserialize(any())).thenReturn(item);
                db.indexMissingEnchantments();
                db.indexMissingEnchantments();
                search.verify(() -> EnchantmentSearch.index(any()), times(102));
            }
        }
        assertEquals(101, db.browse(query("sharpness 5"), 0, 1).total());
        assertEquals(1, db.browse(query("sharpness 4"), 0, 10).total());
        try (var c = connection(); var s = c.createStatement(); var rs = s.executeQuery(
                "SELECT COUNT(*) FROM ra_listings WHERE item_data='AQID' AND enchantments IS NOT NULL")) {
            rs.next(); assertEquals(103, rs.getInt(1));
        }
    }
}
