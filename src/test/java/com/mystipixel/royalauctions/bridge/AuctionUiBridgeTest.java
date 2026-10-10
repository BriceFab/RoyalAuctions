package com.mystipixel.royalauctions.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.protocol.AuctionUiProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuctionUiBridgeTest {

    @Test
    void sortMappingIsExplicitAndRoundTrips() {
        for (SortOrder sort : SortOrder.values()) {
            AuctionUiProtocol.Sort protocol = AuctionUiBridge.protocolSort(sort);
            assertEquals(sort, AuctionUiBridge.serverSort(protocol));
        }
    }

    @Test
    void presentationDataDropsLegacyFormattingAndControlCharacters() {
        assertEquals(
                "Price 100",
                AuctionUiBridge.presentation("§aPrice" + (char) 0 + " 100", 64));
        assertEquals(
                "abcdef",
                AuctionUiBridge.presentation("abcdefgh", 6));
        assertEquals(
                "Épée ⚔️",
                AuctionUiBridge.presentation("Épée ⚔️", 16));
    }

    @Test
    void sessionRejectsDuplicateRequestIds() {
        var session = new AuctionUiBridge.Session(UUID.randomUUID(), AuctionUiProtocol.Query.newest());
        UUID request = UUID.randomUUID();

        assertTrue(session.remember(request));
        assertFalse(session.remember(request));
        assertTrue(session.remember(UUID.randomUUID()));
    }
}
