package com.mystipixel.royalauctions.bridge;

import com.mystipixel.royalauctions.RoyalAuctionsPlugin;
import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.*;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.protocol.AuctionUiProtocol;
import com.mystipixel.royalauctions.service.AuctionService;
import com.mystipixel.royalauctions.util.ItemNames;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class AuctionUiBridgeFlowTest {
    RoyalAuctionsPlugin plugin;
    GuiManager gui;
    AuctionService service;
    Player player;
    AuctionUiBridge bridge;
    final List<AuctionUiProtocol.ServerMessage> sent = new ArrayList<>();
    Consumer<ListingPage> pageCallback;
    Consumer<Optional<Listing>> listingCallback;
    UUID session;
    final UUID listingId = UUID.randomUUID();

    @BeforeEach void setup() {
        plugin = mock(RoyalAuctionsPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(mock(Messenger.class));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("bridge-test"));
        gui = mock(GuiManager.class); service = mock(AuctionService.class);
        when(gui.service()).thenReturn(service);
        PluginConfig config = mock(PluginConfig.class); when(gui.config()).thenReturn(config);
        when(config.defaultSort()).thenReturn(SortOrder.NEWEST);
        when(gui.itemNames()).thenReturn(mock(ItemNames.class));
        CategoryManager categories = mock(CategoryManager.class); when(gui.categories()).thenReturn(categories);
        when(categories.categories()).thenReturn(List.of());
        VaultHook vault = mock(VaultHook.class); when(gui.vault()).thenReturn(vault);
        when(vault.format(anyDouble())).thenReturn("100");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("royalauctions.use")).thenReturn(true);
        when(player.getListeningPluginChannels()).thenReturn(Set.of(AuctionUiProtocol.CHANNEL));
        when(server.getPlayer(player.getUniqueId())).thenReturn(player);
        doAnswer(i -> { sent.add(AuctionUiProtocol.decodeServer(i.getArgument(2))); return null; })
                .when(player).sendPluginMessage(eq(plugin), eq(AuctionUiProtocol.CHANNEL), any(byte[].class));
        doAnswer(i -> { pageCallback = i.getArgument(3); return null; })
                .when(service).loadBrowsePage(any(), anyInt(), anyInt(), any());
        doAnswer(i -> { listingCallback = i.getArgument(1); return null; })
                .when(service).loadListing(any(), any());
        bridge = new AuctionUiBridge(plugin, gui); bridge.start();
    }

    void open() {
        assertTrue(bridge.open(player, null));
        pageCallback.accept(ListingPage.empty());
        session = sent.getLast().session();
        sent.clear();
    }
    void receive(AuctionUiProtocol.ClientMessage request) {
        bridge.onPluginMessageReceived(AuctionUiProtocol.CHANNEL, player, AuctionUiProtocol.encodeClient(request));
    }
    // Seed a visible card through the same session snapshot mapping without needing a live item registry.
    void visible() throws Exception {
        var field = AuctionUiBridge.class.getDeclaredField("sessions"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var sessions = (Map<UUID, AuctionUiBridge.Session>) field.get(bridge);
        sessions.get(player.getUniqueId()).visibleListings = Set.of(listingId);
    }
    AuctionUiProtocol.PurchaseRequest purchase() {
        return new AuctionUiProtocol.PurchaseRequest(session, UUID.randomUUID(), listingId, 100);
    }
    Listing fresh() {
        Listing row = mock(Listing.class);
        when(row.status()).thenReturn(ListingStatus.ACTIVE);
        when(row.expiresAt()).thenReturn(System.currentTimeMillis() + 60_000);
        when(row.type()).thenReturn(ListingType.BIN);
        when(row.sellerId()).thenReturn(UUID.randomUUID());
        when(row.price()).thenReturn(100d);
        return row;
    }
    @Test void unsupportedClientFallsBackWithoutLoading() {
        when(player.getListeningPluginChannels()).thenReturn(Set.of());
        assertFalse(bridge.open(player, null)); verifyNoInteractions(service);
    }
    @Test void oldSessionMalformedAndOversizedRequestsDoNoWork() {
        open(); clearInvocations(service);
        receive(new AuctionUiProtocol.BrowseRequest(UUID.randomUUID(), UUID.randomUUID(), AuctionUiProtocol.Query.newest()));
        bridge.onPluginMessageReceived(AuctionUiProtocol.CHANNEL, player, new byte[]{1, 2});
        bridge.onPluginMessageReceived(AuctionUiProtocol.CHANNEL, player, new byte[AuctionUiProtocol.MAX_BYTES + 1]);
        verifyNoInteractions(service);
    }
    @Test void closeDiscardsPendingBrowseCallback() {
        assertTrue(bridge.open(player, null));
        // Obtain session from a first response, then start a second load and close it.
        pageCallback.accept(ListingPage.empty()); session = sent.getLast().session(); sent.clear();
        receive(new AuctionUiProtocol.BrowseRequest(session, UUID.randomUUID(), AuctionUiProtocol.Query.newest()));
        receive(new AuctionUiProtocol.CloseRequest(session));
        pageCallback.accept(ListingPage.empty()); assertTrue(sent.isEmpty());
    }
    @Test void duplicatePurchaseReachesLoaderOnce() throws Exception {
        open(); visible(); var request = purchase(); receive(request); receive(request);
        verify(service).loadListing(eq(listingId), any());
        assertEquals("rejected", ((AuctionUiProtocol.Result) sent.getLast()).status());
    }
    @Test void pageChangeDuringListingLoadPreventsPurchase() throws Exception {
        open(); visible(); receive(purchase());
        receive(new AuctionUiProtocol.BrowseRequest(session, UUID.randomUUID(), new AuctionUiProtocol.Query(1, "", "", AuctionUiProtocol.Sort.NEWEST)));
        listingCallback.accept(Optional.of(fresh())); verify(service, never()).purchase(any(), any(), any());
    }
    @Test void permissionRevokedDuringListingLoadPreventsPurchase() throws Exception {
        open(); visible(); receive(purchase()); when(player.hasPermission("royalauctions.use")).thenReturn(false);
        listingCallback.accept(Optional.of(fresh())); verify(service, never()).purchase(any(), any(), any());
    }
    @Test void stalePriceOwnNonBinExpiredAndMissingNeverPurchase() throws Exception {
        for (String condition : List.of("price", "own", "auction", "expired", "missing")) {
            // A new session isolates rate/replay state for each rejected case.
            bridge.closeScreens(); open(); visible(); receive(purchase());
            Listing row = fresh();
            UUID playerId = player.getUniqueId();
            switch (condition) {
                case "price" -> when(row.price()).thenReturn(101d);
                case "own" -> when(row.sellerId()).thenReturn(playerId);
                case "auction" -> when(row.type()).thenReturn(ListingType.AUCTION);
                case "expired" -> when(row.expiresAt()).thenReturn(1L);
            }
            listingCallback.accept(condition.equals("missing") ? Optional.empty() : Optional.of(row));
        }
        verify(service, never()).purchase(any(), any(), any());
    }
    @Test void validatedPurchaseDelegatesAndCompletionRefreshes() throws Exception {
        open(); visible(); receive(purchase()); Listing row = fresh();
        doAnswer(i -> { ((Runnable) i.getArgument(2)).run(); return null; }).when(service).purchase(eq(player), eq(row), any());
        listingCallback.accept(Optional.of(row)); verify(service).purchase(eq(player), eq(row), any());
        verify(service, times(2)).loadBrowsePage(any(), anyInt(), anyInt(), any());
    }
}
