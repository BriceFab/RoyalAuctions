package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.api.AuctionCurrency;
import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.currency.Currencies;
import com.mystipixel.royalauctions.data.*;
import com.mystipixel.royalauctions.hooks.EconGuardHook;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.tier.TierManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Listings priced in a registered currency other than Vault money, against real SQLite.
class CurrencyExchangeTest {
    @TempDir Path folder;
    JavaPlugin plugin; AuctionDatabase db; VaultHook vault; EconGuardHook guard; PluginConfig config;
    MessageManager messages; Currencies currencies; CategoryManager categories; AuctionService service; MockedStatic<Bukkit> bukkit;
    Player buyer, seller;
    final UUID buyerId = UUID.randomUUID(), sellerId = UUID.randomUUID();
    final byte[] bytes = {1, 2, 3};
    final Tokens tokens = new Tokens();

    // Whole tokens. Answers with the next queued future, or at once with true. Like a real provider, a
    // repeat of an operation it applied answers true without moving anything; a refused one may be retried.
    static final class Tokens implements AuctionCurrency {
        final List<String> calls = new ArrayList<>();
        final List<String> attempts = new ArrayList<>();
        final Set<UUID> applied = new HashSet<>();
        final Deque<CompletableFuture<Boolean>> answers = new ArrayDeque<>();
        @Override public String id() { return "tokens"; }
        @Override public String format(double amount) { return (long) amount + " tokens"; }
        @Override public int scale() { return 0; }
        @Override public CompletableFuture<Boolean> withdraw(UUID player, double amount, UUID operation) { return call("withdraw", player, amount, operation); }
        @Override public CompletableFuture<Boolean> deposit(UUID player, double amount, UUID operation) { return call("deposit", player, amount, operation); }
        private CompletableFuture<Boolean> call(String what, UUID player, double amount, UUID operation) {
            attempts.add(what + " " + operation);
            if (applied.contains(operation)) return CompletableFuture.completedFuture(true);
            var answer = answers.isEmpty() ? CompletableFuture.completedFuture(true) : answers.poll();
            return answer.thenApply(moved -> {
                if (moved) { applied.add(operation); calls.add(what + " " + player + " " + amount); }
                return moved;
            });
        }
    }

    @BeforeEach void setup() throws Exception {
        plugin = mock(JavaPlugin.class); vault = mock(VaultHook.class); guard = mock(EconGuardHook.class);
        config = mock(PluginConfig.class); messages = mock(MessageManager.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.isEnabled()).thenReturn(true);
        when(config.bidMinIncrement()).thenReturn(10d);
        when(config.enabledCurrencies()).thenReturn(List.of("money", "tokens"));
        when(vault.fractionalDigits()).thenReturn(2);
        when(vault.withdraw(any(), anyDouble())).thenReturn(true); when(vault.deposit(any(), anyDouble())).thenReturn(true);
        when(guard.allow(any())).thenReturn(true);
        when(messages.text("gui.amount-separator")).thenReturn(", ");
        buyer = player(buyerId, "buyer"); seller = player(sellerId, "seller");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getOfflinePlayer(buyerId)).thenReturn(buyer);
        bukkit.when(() -> Bukkit.getOfflinePlayer(sellerId)).thenReturn(seller);
        bukkit.when(() -> Bukkit.getPlayer(buyerId)).thenReturn(buyer);
        bukkit.when(() -> Bukkit.getPlayer(sellerId)).thenReturn(seller);
        BukkitScheduler scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return mock(BukkitTask.class); });
        db = new AuctionDatabase(folder.toFile(), new YamlConfiguration(), Logger.getLogger("test")); db.init();
        currencies = new Currencies(vault, config, messages, Logger.getLogger("test")); currencies.refresh(List.of(tokens));
        categories = mock(CategoryManager.class); when(categories.categorize(any())).thenReturn("misc");
        service = new AuctionService(plugin, db, vault, currencies, config, categories, mock(TierManager.class),
                messages, guard, new Workers(plugin, Runnable::run));
    }
    @AfterEach void close() { db.close(); bukkit.close(); }

    Player player(UUID id, String name) {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(id); when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true); when(p.hasPermission(anyString())).thenReturn(true); return p;
    }
    void complete(AuctionTransactions.Operation o) throws Exception {
        assertTrue(db.transactions().begin(o.id(), "fixture")); db.transactions().acknowledge(o.id(), true); db.transactions().finish(o.id());
    }
    Listing seed(ListingType type, String currency, double price) throws Exception {
        var captured = db.transactions().reserveCapture(sellerId, "seller", bytes, "fixture").operation(); complete(captured);
        var l = new Listing(UUID.randomUUID(), sellerId, "seller", bytes, "test item", "misc", null, type, price,
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, ListingStatus.ACTIVE, 0, null, null, 0, null, currency);
        complete(db.transactions().reserveCreate(l, captured.collection(), 0, -1, "fixture").operation()); return l;
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("auctions.db")); var s = c.createStatement()) { s.executeUpdate(sql); }
    }
    AuctionTransactions.Operation sale() throws Exception {
        return db.transactions().pending(50).stream().filter(o -> "SOLD".equals(o.note())).findFirst().orElseThrow();
    }
    void noMoney() { verify(vault, never()).withdraw(any(), anyDouble()); verify(vault, never()).deposit(any(), anyDouble()); }

    @Test void tokenListingIsChargedAndPaidInTokens() throws Exception {
        service.purchase(buyer, seed(ListingType.BIN, "tokens", 15), () -> {});
        var sale = sale();
        assertEquals("tokens", sale.currency()); assertEquals(15, sale.amount());
        sql("UPDATE ra_operations SET updated_at=0 WHERE state='READY'"); service.recover(); service.recover();
        assertEquals(List.of("withdraw " + buyerId + " 15.0", "deposit " + sellerId + " 15.0"), tokens.calls);
        assertTrue(db.transactions().pending(50).isEmpty());
        noMoney();
        verify(guard, never()).report(any(), any(), any(), anyDouble(), anyBoolean(), any(), any(), any());
    }

    @Test void slowCurrencyHoldsTheExchangeUntilItAnswers() throws Exception {
        var answer = new CompletableFuture<Boolean>(); tokens.answers.add(answer);
        Listing l = seed(ListingType.BIN, "tokens", 15);
        List<String> done = new ArrayList<>();
        service.purchase(buyer, l, () -> done.add("reopen"));
        assertEquals(AuctionTransactions.State.APPLYING, db.transactions().pending(50).getFirst().state());
        assertEquals(ListingStatus.ACTIVE, db.getListing(l.id()).orElseThrow().status()); assertTrue(done.isEmpty());
        answer.complete(true);
        assertEquals(ListingStatus.SOLD, db.getListing(l.id()).orElseThrow().status());
        assertEquals(1, db.collectionItems(buyerId).size()); assertEquals(List.of("reopen"), done);
    }

    @Test void currencyRefusingTheDebitDeclinesTheBid() throws Exception {
        tokens.answers.add(CompletableFuture.completedFuture(false));
        Listing l = seed(ListingType.AUCTION, "tokens", 50);
        service.placeBid(buyer, l, 50, () -> {});
        verify(messages).send(buyer, "exchange.declined", new String[0]);
        assertFalse(db.getListing(l.id()).orElseThrow().hasBids()); assertTrue(db.transactions().pending(50).isEmpty());
    }

    @Test void refusedDepositIsRetriedWithTheSameOperationAndCreditedOnce() throws Exception {
        service.purchase(buyer, seed(ListingType.BIN, "tokens", 15), () -> {});
        var sale = sale();
        tokens.answers.add(CompletableFuture.completedFuture(false));
        sql("UPDATE ra_operations SET updated_at=0 WHERE state='READY'"); service.recover();
        assertEquals(AuctionTransactions.State.READY, db.transactions().get(sale.id()).state(), "a refused deposit waits");
        sql("UPDATE ra_operations SET updated_at=0 WHERE state='READY'"); service.recover();
        assertEquals(AuctionTransactions.State.DONE, db.transactions().get(sale.id()).state());
        assertEquals(List.of("deposit " + sale.id(), "deposit " + sale.id()),
                tokens.attempts.stream().filter(a -> a.startsWith("deposit")).toList());
        assertEquals(List.of("withdraw " + buyerId + " 15.0", "deposit " + sellerId + " 15.0"), tokens.calls);
    }

    @Test void unregisteredCurrencyRefusesExchangesAndKeepsPayoutsOwedUntilItReturns() throws Exception {
        Listing bin = seed(ListingType.BIN, "tokens", 15), auction = seed(ListingType.AUCTION, "tokens", 50);
        service.purchase(buyer, bin, () -> {});
        currencies.refresh(List.of());
        List<String> done = new ArrayList<>();
        service.purchase(buyer, seed(ListingType.BIN, "tokens", 20), () -> done.add("buy"));
        service.placeBid(buyer, auction, 50, () -> done.add("bid"));
        assertEquals(List.of("buy", "bid"), done);
        verify(messages, times(2)).send(buyer, "exchange.currency-unavailable", new String[0]);
        assertEquals(1, db.transactions().pending(50).size(), "only the owed payout, no new reservation");

        sql("UPDATE ra_operations SET updated_at=0 WHERE state='READY'"); service.recover();
        assertEquals(AuctionTransactions.State.READY, sale().state());
        assertEquals(List.of("withdraw " + buyerId + " 15.0"), tokens.calls, "no deposit while tokens is gone");
        noMoney();

        currencies.refresh(List.of(tokens));
        sql("UPDATE ra_operations SET updated_at=0 WHERE state='READY'"); service.recover();
        assertEquals(List.of("withdraw " + buyerId + " 15.0", "deposit " + sellerId + " 15.0"), tokens.calls);
        assertTrue(db.transactions().pending(50).isEmpty());
    }

    // A purchase paid in tokens whose settlement was interrupted, then tokens left, behind a full batch of rows that keep failing.
    @Test void rowsThatKeepFailingDoNotStarveRecovery() throws Exception {
        Listing l = seed(ListingType.BIN, "tokens", 15);
        var buy = db.transactions().reserveBuy(l.id(), buyerId, "buyer", 15, "fixture").operation();
        assertTrue(db.transactions().begin(buy.id(), "fixture")); db.transactions().acknowledge(buy.id(), true);
        sql("UPDATE ra_operations SET updated_at=1000 WHERE id='" + buy.id() + "'");
        for (int i = 0; i < 50; i++)
            sql("INSERT INTO ra_operations(id,kind,state,listing_id,player_id,amount,expiry,created_at,updated_at,note,currency) "
                    + "VALUES ('" + UUID.randomUUID() + "','BID','APPLIED','" + UUID.randomUUID() + "','" + buyerId + "',5,0,0," + i + ",'','tokens')");
        currencies.refresh(List.of());

        service.recover();
        assertEquals(ListingStatus.ACTIVE, db.getListing(l.id()).orElseThrow().status(), "the first batch is all broken rows");
        service.recover();
        assertEquals(ListingStatus.SOLD, db.getListing(l.id()).orElseThrow().status());
        var sale = db.transactions().pending(100).stream().filter(o -> "SOLD".equals(o.note())).findFirst().orElseThrow();
        assertEquals(15, sale.amount());
        assertEquals(50, db.transactions().pending(100).stream().filter(o -> o.state() == AuctionTransactions.State.APPLIED).count(),
                "the broken rows are set aside, never changed");
        assertTrue(tokens.calls.isEmpty(), "settling never calls the currency");
    }

    @Test void earningsAreTotalledAndPaidPerCurrency() throws Exception {
        when(config.manualEarnings()).thenReturn(true);
        service.purchase(buyer, seed(ListingType.BIN, "money", 50), () -> {});
        service.purchase(buyer, seed(ListingType.BIN, "tokens", 15), () -> {});
        service.purchase(buyer, seed(ListingType.BIN, "tokens", 30), () -> {});
        List<Map<String, Double>> loaded = new ArrayList<>();
        service.loadEarnings(sellerId, loaded::add);
        assertEquals(Map.of("money", 50.0, "tokens", 45.0), loaded.getFirst());
        assertEquals("$50.00, 45 tokens", currenciesFormatted(loaded.getFirst()));

        service.claimEarnings(seller, () -> {});
        verify(vault).deposit(seller, 50);
        assertEquals(List.of("deposit " + sellerId + " 15.0", "deposit " + sellerId + " 30.0"),
                tokens.calls.stream().filter(c -> c.startsWith("deposit")).toList());
        verify(messages).send(seller, "collection.earnings-claimed", "amount", "15 tokens", "item", "test item");
        assertTrue(db.transactions().unclaimedEarnings(sellerId).isEmpty());
    }

    String currenciesFormatted(Map<String, Double> totals) {
        when(vault.format(anyDouble())).thenAnswer(i -> String.format(Locale.ROOT, "$%.2f", (double) i.getArgument(0)));
        return service.formatTotals(totals);
    }

    @Test void amountsFinerThanTheCurrencyAreRefused() throws Exception {
        Listing auction = seed(ListingType.AUCTION, "tokens", 50);
        service.placeBid(buyer, auction, 60.5, () -> {});
        verify(messages).send(buyer, "general.whole-number", new String[0]);
        ItemStack item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STONE);
        List<Boolean> listed = new ArrayList<>();
        service.createListing(seller, UUID.randomUUID(), item, 10.5, ListingType.BIN, 60_000, "tokens", listed::add);
        assertEquals(List.of(false), listed);
        verify(messages).send(seller, "general.whole-number", new String[0]);
        assertTrue(tokens.calls.isEmpty()); assertTrue(db.transactions().pending(50).isEmpty());
    }

    @Test void bidIncrementsAreRoundedUpToTheCurrency() {
        when(config.bidIncrementPercent()).thenReturn(0.05);
        assertEquals(62, service.bidIncrement("tokens", 1234));
        assertEquals(61.71, service.bidIncrement("money", 1234.1));
        assertEquals(10, service.bidIncrement("tokens", 100), "the flat increment when it is larger");
    }

    // 100 * 0.07 is 7.000000000000001 in double, which rounded up to whole tokens became 8.
    @Test void percentagesOfRoundAmountsAreNotRoundedUpAnExtraUnit() throws Exception {
        when(config.bidIncrementPercent()).thenReturn(0.07);
        when(config.bidMinIncrement()).thenReturn(1d);
        assertEquals(7, service.bidIncrement("money", 100), "money keeps 7.00, not 7.01");
        Listing auction = seed(ListingType.AUCTION, "tokens", 100);
        service.placeBid(buyer, auction, 100, () -> {});
        assertEquals(107, service.minimumBid(db.getListing(auction.id()).orElseThrow()));
        Player rival = player(UUID.randomUUID(), "rival");
        service.placeBid(rival, db.getListing(auction.id()).orElseThrow(), 107, () -> {});
        assertEquals(107, db.getListing(auction.id()).orElseThrow().currentBid(), "107 is accepted as the minimum raise");

        when(config.feePercent()).thenReturn(0.07); when(config.canSellCategory(any())).thenReturn(true);
        assertEquals(7, service.listingFee("money", 100));
        assertEquals(0.71, service.listingFee("money", 10.1), "a real fraction of a cent still rounds up");
        var captured = db.transactions().reserveCapture(sellerId, "seller", bytes, "fixture").operation(); complete(captured);
        ItemStack item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STONE);
        List<Boolean> listed = new ArrayList<>();
        // byte serialization needs a running server; the Base64 helpers stay real
        try (var serialization = mockStatic(ItemSerialization.class,
                call -> call.getMethod().getName().equals("serialize") ? bytes : call.callRealMethod())) {
            service.createListing(seller, captured.collection(), item, 100, ListingType.BIN, 60_000, "tokens", listed::add);
        }
        assertEquals(List.of(true), listed);
        assertEquals("withdraw " + sellerId + " 7.0", tokens.calls.getLast());
    }

    // Vault's fractionalDigits() is -1 when the economy does not round: nothing is refused or rounded, and still no binary noise.
    @Test void moneyFromAnEconomyThatDoesNotRoundKeepsEveryDigit() throws Exception {
        when(vault.fractionalDigits()).thenReturn(-1); currencies.refresh(List.of(tokens));
        when(config.bidIncrementPercent()).thenReturn(0.05); when(config.bidMinIncrement()).thenReturn(0d);
        assertEquals(61.705, service.bidIncrement("money", 1234.1));
        when(config.feePercent()).thenReturn(0.07); when(config.canSellCategory(any())).thenReturn(true);
        assertEquals(7, service.listingFee("money", 100), "7% of 100 is 7, not 7.000000000000001");
        assertEquals(0.707, service.listingFee("money", 10.1));
        var captured = db.transactions().reserveCapture(sellerId, "seller", bytes, "fixture").operation(); complete(captured);
        ItemStack item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STONE);
        List<Boolean> listed = new ArrayList<>();
        try (var serialization = mockStatic(ItemSerialization.class,
                call -> call.getMethod().getName().equals("serialize") ? bytes : call.callRealMethod())) {
            service.createListing(seller, captured.collection(), item, 1.001, ListingType.BIN, 60_000, "money", listed::add);
        }
        assertEquals(List.of(true), listed, "a price finer than cents is accepted");
        verify(vault).withdraw(seller, 0.07007);
        verify(messages, never()).send(eq(seller), eq("general.too-many-decimals"), any(String[].class));
    }

    @Test void econGuardOnlyWatchesMoney() throws Exception {
        when(guard.allow(buyerId)).thenReturn(false);
        Listing l = seed(ListingType.BIN, "tokens", 15);
        service.purchase(buyer, l, () -> {});
        assertEquals(ListingStatus.SOLD, db.getListing(l.id()).orElseThrow().status());
        verify(messages, never()).send(buyer, "exchange.restricted-self", new String[0]);
        verify(guard, never()).report(any(), any(), any(), anyDouble(), anyBoolean(), any(), any(), any());
    }
}
