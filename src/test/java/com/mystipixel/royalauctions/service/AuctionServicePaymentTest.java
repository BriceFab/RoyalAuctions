package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.data.*;
import com.mystipixel.royalauctions.hooks.*;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.tier.TierManager;
import com.mystipixel.royalauctions.message.MessageManager;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

// Public service paths against real SQLite and a rejecting/ambiguous Vault provider.
class AuctionServicePaymentTest {
    @TempDir Path folder;
    JavaPlugin plugin; AuctionDatabase db; VaultHook vault; EconGuardHook guard;
    PluginConfig config; CategoryManager categories; TierManager tiers; MessageManager messages;
    AuctionService service; Player buyer, seller; MockedStatic<Bukkit> bukkit;
    final UUID buyerId = UUID.randomUUID(), sellerId = UUID.randomUUID();
    final byte[] bytes = {1, 2, 3};

    @BeforeEach void setup() throws Exception {
        plugin = mock(JavaPlugin.class); vault = mock(VaultHook.class); guard = mock(EconGuardHook.class);
        config = mock(PluginConfig.class); categories = mock(CategoryManager.class); tiers = mock(TierManager.class); messages = mock(MessageManager.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.isEnabled()).thenReturn(true); when(config.instantDeliver()).thenReturn(false);
        buyer = player(buyerId, "buyer"); seller = player(sellerId, "seller");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getOfflinePlayer(buyerId)).thenReturn(buyer);
        bukkit.when(() -> Bukkit.getOfflinePlayer(sellerId)).thenReturn(seller);
        bukkit.when(() -> Bukkit.getPlayer(sellerId)).thenReturn(seller);
        bukkit.when(() -> Bukkit.getPlayer(buyerId)).thenReturn(buyer);
        BukkitScheduler scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return mock(BukkitTask.class); });
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return mock(BukkitTask.class); });
        when(vault.withdraw(any(), anyDouble())).thenReturn(true); when(config.bidIncrementFor(anyDouble())).thenReturn(10d);
        when(guard.allow(any())).thenReturn(true);
        open();
    }
    void open() throws Exception {
        db = new AuctionDatabase(folder.toFile(), new YamlConfiguration(), Logger.getLogger("test")); db.init();
        service = new AuctionService(plugin, db, vault, config, categories, tiers, messages, guard, new Workers(plugin, Runnable::run));
    }
    @AfterEach void close() { if (db != null) db.close(); if (bukkit != null) bukkit.close(); }
    Player player(UUID id, String name) {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(id); when(p.getName()).thenReturn(name); when(p.isOnline()).thenReturn(true); return p;
    }
    void complete(AuctionTransactions.Operation o) throws Exception {
        assertTrue(db.transactions().begin(o.id(), "fixture")); db.transactions().acknowledge(o.id(), true); db.transactions().finish(o.id());
    }
    Listing seed(ListingType type) throws Exception {
        var captured = db.transactions().reserveCapture(sellerId, "seller", bytes, "fixture").operation(); complete(captured);
        var l = new Listing(UUID.randomUUID(), sellerId, "seller", bytes, "test item", "misc", null, type, 50,
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, ListingStatus.ACTIVE, 0, null, null, 0);
        complete(db.transactions().reserveCreate(l, captured.collection(), 0, -1, "fixture").operation()); return l;
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("auctions.db")); var s = c.createStatement()) { s.executeUpdate(sql); }
    }
    AuctionTransactions.Operation owed() throws Exception { return db.transactions().pending(50).stream().filter(o -> o.kind() == AuctionTransactions.Kind.PAYOUT).findFirst().orElseThrow(); }

    @Test void rejectedSellerCreditRemainsOwedAndIsNotAuditedAsPaid() throws Exception {
        service.purchase(buyer, seed(ListingType.BIN), () -> {}); service.recover();
        assertEquals(AuctionTransactions.State.READY, owed().state()); assertEquals(1, db.collectionItems(buyerId).size());
        verify(guard, never()).report(eq(sellerId), any(), eq("sale"), anyDouble(), eq(true), any(), any(), any());
        verify(vault).withdraw(buyer, 50); verify(vault).deposit(seller, 50);
    }
    @Test void pendingSellerCreditRetriesAfterRestartWithoutChargingBuyerAgain() throws Exception {
        when(vault.deposit(seller, 50)).thenReturn(false, true);
        service.purchase(buyer, seed(ListingType.BIN), () -> {}); service.recover();
        db.close(); open(); sql("UPDATE ra_operations SET updated_at=0 WHERE state='READY'"); service.recover(); service.recover();
        verify(vault).withdraw(buyer, 50); verify(vault, times(2)).deposit(seller, 50);
        assertTrue(db.transactions().pending(50).isEmpty());
        verify(guard).report(eq(sellerId), eq("seller"), eq("sale"), eq(50d), eq(true), eq(buyerId), eq("buyer"), eq("test item"));
    }
    @Test void rejectedOutbidRefundIsPersistedWithoutClaimingRefundSuccess() throws Exception {
        Listing l = seed(ListingType.AUCTION);
        complete(db.transactions().reserveBid(l.id(), buyerId, "buyer", 50, amount -> 10, 0, "fixture").operation());
        Player next = player(UUID.randomUUID(), "next"); service.placeBid(next, l, 60, () -> {}); service.recover();
        assertEquals(buyerId, owed().player()); assertEquals(50, owed().amount()); verify(vault).deposit(buyer, 50);
        verify(guard, never()).report(any(), any(), eq("bid-refund"), anyDouble(), eq(true), any(), any(), any());
    }
    @Test void expiryPayoutRejectionLeavesRecoverableCredit() throws Exception {
        Listing l = seed(ListingType.AUCTION); service.placeBid(buyer, l, 50, () -> {});
        sql("UPDATE ra_listings SET expires_at=0"); service.sweepExpired(); service.recover();
        assertEquals(AuctionTransactions.State.READY, owed().state()); assertEquals(1, db.collectionItems(buyerId).size());
        verify(guard, never()).report(any(), any(), eq("auction-sale"), anyDouble(), eq(true), any(), any(), any());
        when(vault.deposit(seller, 50)).thenReturn(true); sql("UPDATE ra_operations SET updated_at=0 WHERE state='READY'"); service.recover();
        assertTrue(db.transactions().pending(50).isEmpty());
    }
    @Test void listingReservationFailureNeverChargesFeeOrNeedsRefund() throws Exception {
        when(config.maxPerPlayer()).thenReturn(-1); when(config.feeFor(50)).thenReturn(5d); when(config.canSellCategory(any())).thenReturn(true);
        when(categories.categorize(any())).thenReturn("misc"); when(seller.hasPermission("royalauctions.sell")).thenReturn(true);
        ItemStack item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STONE);
        try (var serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.serialize(item)).thenReturn(bytes);
            service.createListing(seller, UUID.randomUUID(), item, 50, ListingType.BIN, 60_000, Assertions::assertFalse);
        }
        verify(vault, never()).withdraw(any(), anyDouble()); verify(vault, never()).deposit(any(), anyDouble());
        assertTrue(db.transactions().pending(50).isEmpty());
    }
    @Test void unknownSellerCreditDoesNotRetryOnTickOrRestart() throws Exception {
        when(vault.deposit(seller, 50)).thenThrow(new IllegalStateException("provider may have paid"));
        service.purchase(buyer, seed(ListingType.BIN), () -> {}); service.recover();
        db.close(); open(); service.recover(); service.retryPayments();
        verify(vault).deposit(seller, 50); assertEquals(AuctionTransactions.State.APPLYING, owed().state());
    }
    @Test void preUpgradeRejectedReceiptStillRetriesWithoutNewDatabaseObligation() throws Exception {
        PaymentJournal journal = new PaymentJournal(folder.resolve("payments")); UUID id = UUID.randomUUID();
        journal.begin(id, sellerId, buyerId, "sale", Map.of("source", id.toString(), "item", "payment:" + id));
        journal.attempt(id, "credit", sellerId, 50, true, () -> false);
        when(vault.deposit(seller, 50)).thenReturn(true);
        db.close(); open(); service.retryPayments(); service.retryPayments(); service.recover();
        verify(vault).deposit(seller, 50); verify(vault, never()).withdraw(any(), anyDouble());
        assertTrue(journal.pending().isEmpty()); assertTrue(db.transactions().pending(50).isEmpty());
    }
    @Test void preUpgradeUnknownReceiptIsRetainedWithoutReplay() throws Exception {
        PaymentJournal journal = new PaymentJournal(folder.resolve("payments")); UUID id = UUID.randomUUID();
        journal.begin(id, sellerId, buyerId, "sale");
        assertThrows(IllegalStateException.class, () -> journal.attempt(id, "credit", sellerId, 50, true, () -> { throw new IllegalStateException("unknown"); }));
        db.close(); open(); service.retryPayments(); service.recover();
        verifyNoInteractions(vault); assertEquals("IN_FLIGHT", journal.read(id).getProperty("leg.credit.status"));
    }
    @Test void listingWithoutSellPermissionIsRefusedBeforeAnyReservation() throws Exception {
        List<Boolean> results = new ArrayList<>();
        service.createListing(seller, UUID.randomUUID(), mock(ItemStack.class), 50, ListingType.BIN, 60_000, results::add);
        assertEquals(List.of(false), results);
        verifyNoInteractions(vault); assertTrue(db.transactions().pending(50).isEmpty());
    }
    @Test void vetoedBuyerCannotBuyOrBid() throws Exception {
        when(guard.allow(buyerId)).thenReturn(false);
        Listing bin = seed(ListingType.BIN), auction = seed(ListingType.AUCTION);
        List<String> done = new ArrayList<>();
        service.purchase(buyer, bin, () -> done.add("buy")); service.placeBid(buyer, auction, 60, () -> done.add("bid"));
        assertEquals(List.of("buy", "bid"), done);
        verify(messages, times(2)).send(buyer, "exchange.restricted-self", new String[0]);
        verify(vault, never()).withdraw(any(), anyDouble()); assertTrue(db.transactions().pending(50).isEmpty());
    }
    @Test void vetoedSellerBlocksTheBuyerWithoutSayingWhy() throws Exception {
        when(guard.allow(sellerId)).thenReturn(false);
        service.purchase(buyer, seed(ListingType.BIN), () -> {});
        verify(messages).send(buyer, "exchange.restricted-other", "player", "seller");
        verify(vault, never()).withdraw(any(), anyDouble()); assertTrue(db.transactions().pending(50).isEmpty());
    }
    org.bukkit.permissions.PermissionAttachmentInfo permission(String name, boolean value) {
        var info = mock(org.bukkit.permissions.PermissionAttachmentInfo.class);
        when(info.getPermission()).thenReturn(name); when(info.getValue()).thenReturn(value); return info;
    }
    @Test void listingLimitFollowsPermissionsThenConfig() {
        when(config.maxPerPlayer()).thenReturn(7);
        assertEquals(7, service.listingLimit(seller));
        // Build the permission mocks before stubbing: creating mocks inside when(...) is unfinished stubbing.
        Set<org.bukkit.permissions.PermissionAttachmentInfo> limits = Set.of(permission("royalauctions.limit.20", true),
                permission("royalauctions.limit.12", true), permission("royalauctions.limit.99", false));
        when(seller.getEffectivePermissions()).thenReturn(limits);
        assertEquals(20, service.listingLimit(seller), "highest granted limit wins; negated ones are ignored");
        Set<org.bukkit.permissions.PermissionAttachmentInfo> unlimited = Set.of(permission("RoyalAuctions.Limit.Unlimited", true));
        when(seller.getEffectivePermissions()).thenReturn(unlimited);
        assertEquals(-1, service.listingLimit(seller));
        when(seller.getEffectivePermissions()).thenReturn(Set.of());
        when(config.maxPerPlayer()).thenReturn(-5);
        assertEquals(-1, service.listingLimit(seller));
        when(seller.hasPermission("royalauctions.admin")).thenReturn(true);
        when(config.maxPerPlayer()).thenReturn(7);
        assertEquals(-1, service.listingLimit(seller));
    }
    @Test void longNamesAreCutToWhatTheDatabaseHolds() {
        assertEquals("short", AuctionService.truncate("short"));
        String longName = "\uD83D\uDE00".repeat(300); // 300 emoji, two chars each
        String cut = AuctionService.truncate(longName);
        assertEquals(AuctionService.MAX_NAME_LENGTH, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("…")); assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 2)));
    }
}
