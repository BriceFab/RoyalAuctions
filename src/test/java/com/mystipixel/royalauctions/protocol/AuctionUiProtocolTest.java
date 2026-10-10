package com.mystipixel.royalauctions.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.IntStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuctionUiProtocolTest {
    @Test
    void clientBrowseRoundTrips() {
        var value = new AuctionUiProtocol.BrowseRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new AuctionUiProtocol.Query(2, "weapons", "diamond", AuctionUiProtocol.Sort.PRICE_LOW));
        assertEquals(value, AuctionUiProtocol.decodeClient(AuctionUiProtocol.encodeClient(value)));
    }

    @Test
    void snapshotRoundTrips() {
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var value = new AuctionUiProtocol.Snapshot(
                session,
                true,
                AuctionUiProtocol.Query.newest(),
                12,
                31,
                1234.5,
                "1,234.50",
                List.of(new AuctionUiProtocol.Category("weapons", "Weapons", "minecraft:diamond_sword")),
                List.of(new AuctionUiProtocol.Listing(
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        "minecraft:diamond_sword",
                        1,
                        "Diamond Sword",
                        "Seller",
                        "weapons",
                        "legendary",
                        AuctionUiProtocol.ListingKind.BIN,
                        7500,
                        7500,
                        "7,500.00",
                        1_900_000_000_000L,
                        0)));
        assertEquals(value, AuctionUiProtocol.decodeServer(AuctionUiProtocol.encodeServer(value)));
    }

    @Test
    void malformedAndOversizedInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuctionUiProtocol.decodeClient(new byte[] {1}));
        assertThrows(
                IllegalArgumentException.class,
                () -> AuctionUiProtocol.decodeServer(new byte[AuctionUiProtocol.MAX_BYTES + 1]));
    }

    @Test
    void invalidRegistryKeysAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuctionUiProtocol.Category("weapons", "Weapons", "NOT A KEY"));
    }
    @Test
    void clientGoldenVectorsAndTruncations() {
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID request = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID listing = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var purchase = new AuctionUiProtocol.PurchaseRequest(session, request, listing, 100);
        byte[] bytes = AuctionUiProtocol.encodeClient(purchase);
        assertEquals("01020000000000000000000000000000000100000000000000000000000000000002000000000000000000000000000000034059000000000000",
                HexFormat.of().formatHex(bytes));
        assertEquals(purchase, AuctionUiProtocol.decodeClient(bytes));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> AuctionUiProtocol.decodeClient(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> AuctionUiProtocol.decodeClient(Arrays.copyOf(bytes, bytes.length + 1)));
        var close = new AuctionUiProtocol.CloseRequest(session);
        assertEquals(close, AuctionUiProtocol.decodeClient(AuctionUiProtocol.encodeClient(close)));
        assertThrows(IllegalArgumentException.class, () -> new AuctionUiProtocol.PurchaseRequest(session, request, listing, Double.NaN));
    }

    @Test
    void largeValidSnapshotRoundTripsAboveTheOldPluginLimit() {
        var categories = IntStream.range(0, AuctionUiProtocol.MAX_CATEGORIES)
                .mapToObj(i -> new AuctionUiProtocol.Category("c" + i + "a".repeat(60), "猫".repeat(96), "minecraft:" + "a".repeat(118)))
                .toList();
        var rows = IntStream.range(0, AuctionUiProtocol.MAX_PAGE_SIZE)
                .mapToObj(i -> new AuctionUiProtocol.Listing(UUID.randomUUID(), "minecraft:stone", 64,
                        "猫".repeat(256), "s".repeat(64), "c", "", AuctionUiProtocol.ListingKind.BIN,
                        100, 100, "100", 1000, 0)).toList();
        var snapshot = new AuctionUiProtocol.Snapshot(UUID.randomUUID(), true, AuctionUiProtocol.Query.newest(),
                60, 1000, 100, "100", categories, rows);
        byte[] bytes = AuctionUiProtocol.encodeServer(snapshot);
        org.junit.jupiter.api.Assertions.assertTrue(bytes.length > 32_000);
        assertEquals(snapshot, AuctionUiProtocol.decodeServer(bytes));
    }

}
