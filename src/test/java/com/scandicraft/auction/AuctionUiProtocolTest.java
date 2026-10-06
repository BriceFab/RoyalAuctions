package com.scandicraft.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuctionUiProtocolTest {
    @Test
    void clientBrowseRoundTrips() {
        var value = new AuctionUiProtocol.BrowseRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new AuctionUiProtocol.Query(2, "weapons", "scandium", AuctionUiProtocol.Sort.PRICE_LOW));
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
                        "scandicraft:scandium_sword",
                        1,
                        "Scandium Sword",
                        "BriceFab",
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
}
