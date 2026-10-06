package com.mystipixel.royalauctions.scandicraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mystipixel.royalauctions.data.SortOrder;
import com.scandicraft.auction.AuctionUiProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ScandiAuctionBridgeTest {

    @Test
    void sortMappingIsExplicitAndRoundTrips() {
        for (SortOrder sort : SortOrder.values()) {
            AuctionUiProtocol.Sort protocol = ScandiAuctionBridge.protocolSort(sort);
            assertEquals(sort, ScandiAuctionBridge.serverSort(protocol));
        }
    }

    @Test
    void presentationDataDropsLegacyFormattingAndControlCharacters() {
        assertEquals(
                "Price 100",
                ScandiAuctionBridge.presentation("§aPrice" + (char) 0 + " 100", 64));
        assertEquals(
                "abcdef",
                ScandiAuctionBridge.presentation("abcdefgh", 6));
        assertEquals(
                "Épée ⚔️",
                ScandiAuctionBridge.presentation("Épée ⚔️", 16));
    }

    @Test
    void sessionRejectsDuplicateRequestIds() {
        var session = new ScandiAuctionBridge.Session(UUID.randomUUID(), AuctionUiProtocol.Query.newest());
        UUID request = UUID.randomUUID();

        assertTrue(session.remember(request));
        assertFalse(session.remember(request));
        assertTrue(session.remember(UUID.randomUUID()));
    }
}
