package com.mystipixel.royalauctions.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// No Bukkit involved as long as the item bytes aren't decoded.
class ListingTest {

    private static Listing bin(double price) {
        return new Listing(UUID.randomUUID(), UUID.randomUUID(), "Seller", null, "Item",
                "cat", null, ListingType.BIN, price, 0L, 0L, ListingStatus.ACTIVE,
                0, null, null, 0);
    }

    private static Listing auction(double startPrice, double currentBid, UUID topBidder, int bidCount) {
        return new Listing(UUID.randomUUID(), UUID.randomUUID(), "Seller", null, "Item",
                "cat", null, ListingType.AUCTION, startPrice, 0L, 0L, ListingStatus.ACTIVE,
                currentBid, topBidder, topBidder == null ? null : "Bidder", bidCount);
    }

    @Test
    void binListingUsesFixedPrice() {
        Listing l = bin(1000);
        assertFalse(l.isAuction());
        assertFalse(l.hasBids());
        assertEquals(1000, l.displayPrice(), 1e-9);
        assertEquals(1000, l.nextMinBid(50), 1e-9); // increment is irrelevant to a BIN
    }

    @Test
    void auctionWithoutBidsShowsStartingPrice() {
        Listing l = auction(200, 0, null, 0);
        assertTrue(l.isAuction());
        assertFalse(l.hasBids());
        assertEquals(200, l.displayPrice(), 1e-9);
        assertEquals(200, l.nextMinBid(25), 1e-9); // first bid must at least meet the start price
    }

    @Test
    void auctionWithBidsShowsLeadingBidAndAddsIncrement() {
        Listing l = auction(200, 500, UUID.randomUUID(), 3);
        assertTrue(l.hasBids());
        assertEquals(500, l.displayPrice(), 1e-9);
        assertEquals(550, l.nextMinBid(50), 1e-9);
    }

    @Test
    void bidCountWithoutTopBidderIsNotBids() {
        // Defensive: a positive count with no recorded bidder must not read as "has bids".
        Listing l = auction(200, 500, null, 2);
        assertFalse(l.hasBids());
        assertEquals(200, l.displayPrice(), 1e-9);
    }

    @Test
    void minimumBidDropsFloatingPointNoise() {
        // 0.1 + 0.2 is 0.30000000000000004 in doubles; a bid of exactly 0.3 must still qualify.
        Listing l = auction(0.1, 0.1, UUID.randomUUID(), 1);
        assertEquals(0.3, l.nextMinBid(0.2), 0.0);
    }
}
