package com.mystipixel.royalauctions.data;

import com.mystipixel.royalauctions.service.ExternalEffectRunner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static com.mystipixel.royalauctions.data.AuctionTransactions.State.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC transactions; queued executors let tests stop at each external-effect boundary. */
class AuctionTransactionsTest {
    @TempDir Path folder;
    AuctionDatabase db;
    AuctionTransactions tx;
    final UUID seller = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID();
    final String worker = UUID.randomUUID().toString();
    final byte[] bytes = {1, 2, 3}; // Serialized data is opaque to the transaction layer.

    @BeforeEach void open() throws Exception {
        db = new AuctionDatabase(folder.toFile(), config(), Logger.getAnonymousLogger());
        db.init(); tx = db.transactions();
    }
    YamlConfiguration config() { return new YamlConfiguration(); }
    @AfterEach void close() { db.close(); }
    Connection connection() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("auctions.db")); }
    void sql(String sql, Object... args) throws SQLException {
        try (var c = connection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i] instanceof UUID ? args[i].toString() : args[i]);
            ps.executeUpdate();
        }
    }
    long count(String table) throws SQLException {
        try (var c = connection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) { rs.next(); return rs.getLong(1); }
    }
    Listing make(ListingType type) {
        return new Listing(UUID.randomUUID(), seller, "Seller", bytes, "Test item", "misc", null, type,
                100, System.currentTimeMillis(), System.currentTimeMillis() + 600_000, ListingStatus.ACTIVE, 0, null, null, 0);
    }
    Listing seed(ListingType type) throws SQLException {
        Listing l = make(type);
        try (var c = connection()) { AuctionDatabase.insertListing(c, l, ListingStatus.ACTIVE); }
        return l;
    }
    Listing current(Listing l) throws SQLException { return db.getListing(l.id()).orElseThrow(); }
    AuctionTransactions.Operation bid(Listing l, UUID bidder, double amount) throws SQLException {
        return tx.reserveBid(l.id(), bidder, "Bidder", amount, previous -> 10, 60_000, worker).operation();
    }
    void complete(AuctionTransactions.Operation o, boolean success) throws SQLException {
        assertTrue(tx.begin(o.id(), worker)); tx.acknowledge(o.id(), success); tx.finish(o.id());
    }
    void expireNow(Listing l) throws SQLException { sql("UPDATE ra_listings SET expires_at=0 WHERE id=?", l.id()); }
    AuctionTransactions.Operation capture() throws SQLException {
        var o = tx.reserveCapture(seller, "Seller", bytes, worker).operation(); complete(o, true); return o;
    }
    void restart() throws Exception { db.close(); open(); }
    void failInserts() throws SQLException {
        sql("CREATE TRIGGER fail_delivery BEFORE INSERT ON ra_collection BEGIN SELECT RAISE(ABORT, 'injected'); END");
    }
    void failBegin() throws SQLException {
        sql("CREATE TRIGGER fail_begin BEFORE UPDATE ON ra_operations BEGIN SELECT RAISE(ABORT, 'injected'); END");
    }

    @Test void unpaidBidCannotBecomeLeaderOrRaceExpiryOrCancellation() throws Exception {
        var l = seed(ListingType.AUCTION); var first = bid(l, alice, 100);
        assertFalse(current(l).hasBids());
        assertThrows(AuctionTransactions.Rejected.class, () -> bid(l, bob, 110));
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.cancel(l.id(), seller));
        expireNow(l); assertFalse(tx.expire(l.id()));
        complete(first, true);
        assertEquals(alice, current(l).topBidderId());
        assertFalse(tx.expire(l.id()), "The funded bid's extension invalidates the sweep's old deadline");
        expireNow(l); assertTrue(tx.expire(l.id())); assertFalse(tx.expire(l.id()));
        assertEquals(ListingStatus.SOLD, current(l).status());
        assertEquals(alice, db.collectionItems(alice).getFirst().ownerId());
        assertEquals(1, count("ra_collection"));
        assertEquals(1, tx.pending(50).stream().filter(o -> o.kind() == AuctionTransactions.Kind.PAYOUT).count());
    }

    @Test void declinedBidPreservesPreviousFundedLeaderWithoutRefund() throws Exception {
        var l = seed(ListingType.AUCTION); complete(bid(l, alice, 100), true);
        var second = bid(l, bob, 110); complete(second, false); tx.finish(second.id());
        assertEquals(alice, current(l).topBidderId()); assertEquals(1, current(l).bidCount());
        assertEquals(1, count("ra_bids")); assertTrue(tx.pending(50).isEmpty());
        assertEquals(0, count("ra_operation_locks"));
    }

    @Test void staleMinimumAndInvalidMoneyAreRejectedAgainstFreshState() throws Exception {
        var l = seed(ListingType.AUCTION); complete(bid(l, alice, 100), true);
        for (double amount : new double[]{100, 109, Double.NaN, Double.POSITIVE_INFINITY, -5, 0})
            assertThrows(AuctionTransactions.Rejected.class, () -> bid(l, bob, amount));
        assertThrows(AuctionTransactions.Rejected.class, () -> bid(l, seller, 110));
        assertEquals(0, count("ra_operation_locks"));
        var o = bid(l, bob, 110); complete(o, true); tx.finish(o.id());
        assertEquals(2, count("ra_bids"));
        var payout = tx.pending(50).getFirst();
        assertEquals(alice, payout.player()); assertEquals(100, payout.amount()); assertEquals(READY, payout.state());
    }

    @Test void twoIndependentDatabasePoolsPermitOnlyOneReservation() throws Exception {
        var l = seed(ListingType.BIN);
        var other = new AuctionDatabase(folder.toFile(), config(), Logger.getAnonymousLogger()); other.init();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> first = () -> { start.await(); try { tx.reserveBuy(l.id(), alice, "Alice", 100, worker); return true; } catch (AuctionTransactions.Rejected e) { return false; } };
            Callable<Boolean> second = () -> { start.await(); try { other.transactions().reserveBuy(l.id(), bob, "Bob", 100, "other"); return true; } catch (AuctionTransactions.Rejected e) { return false; } };
            var a = executor.submit(first); var b = executor.submit(second); start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, tx.pending(50).size()); assertEquals(1, count("ra_operation_locks"));
        } finally { other.close(); }
    }

    @Test void settlementFailureRollsBackStatusCollectionPayoutAndLockTogether() throws Exception {
        var l = seed(ListingType.AUCTION); complete(bid(l, alice, 100), true); expireNow(l);
        failInserts();
        assertThrows(SQLException.class, () -> tx.expire(l.id()));
        assertEquals(ListingStatus.ACTIVE, current(l).status()); assertEquals(0, count("ra_collection"));
        assertTrue(tx.pending(50).isEmpty()); assertEquals(0, count("ra_operation_locks"));
        sql("DROP TRIGGER fail_delivery"); assertTrue(tx.expire(l.id())); assertFalse(tx.expire(l.id()));
        assertEquals(1, count("ra_collection")); assertEquals(1, tx.pending(50).size());
    }

    @Test void paidPurchaseCanFinishAfterRestartWithoutChargingAgain() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        assertTrue(tx.begin(o.id(), worker)); tx.acknowledge(o.id(), true);
        restart(); assertTrue(tx.recoverable(50).stream().anyMatch(r -> r.id().equals(o.id())));
        tx.finish(o.id()); tx.finish(o.id());
        assertFalse(tx.begin(o.id(), worker)); assertEquals(ListingStatus.SOLD, current(l).status());
        assertEquals(1, db.collectionItems(alice).size()); assertEquals(1, tx.pending(50).size());
    }

    @Test void unacknowledgedChargeRemainsHeldAfterRestartAndIsNeverRecoverableAutomatically() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        assertTrue(tx.begin(o.id(), worker)); restart();
        assertEquals(APPLYING, tx.get(o.id()).state()); assertTrue(tx.recoverable(50).isEmpty());
        assertFalse(tx.begin(o.id(), worker)); tx.abandon(o.id());
        assertEquals(APPLYING, tx.get(o.id()).state());
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.reserveBuy(l.id(), bob, "Bob", 100, worker));
        expireNow(l); assertFalse(tx.expire(l.id())); assertEquals(0, count("ra_collection"));
    }

    @Test void preparedAbandonPreventsLateQueuedEffect() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        tx.abandon(o.id());
        AtomicInteger calls = new AtomicInteger(); List<ExternalEffectRunner.Result> results = new ArrayList<>();
        new ExternalEffectRunner(tx, Runnable::run, Runnable::run, worker, e -> fail(e))
                .execute(o.id(), () -> { calls.incrementAndGet(); return true; }, results::add);
        assertEquals(0, calls.get()); assertEquals(List.of(ExternalEffectRunner.Result.PENDING), results);
        assertEquals(ListingStatus.ACTIVE, current(l).status()); assertEquals(0, count("ra_operation_locks"));
    }

    @Test void closingRunnerDeclinesEffectsThatHaveNotStarted() throws Exception {
        // Shutdown: an exchange whose intent is durable but whose effect has not run is recorded as
        // not applied, so it finishes cleanly instead of being held as ambiguous.
        var r = tx.reserveCapture(seller, "Seller", bytes, worker);
        AtomicInteger calls = new AtomicInteger(); List<ExternalEffectRunner.Result> results = new ArrayList<>();
        new ExternalEffectRunner(tx, Runnable::run, Runnable::run, worker, e -> fail(e), () -> true)
                .execute(r.operation().id(), () -> { calls.incrementAndGet(); return true; }, results::add);
        assertEquals(0, calls.get()); assertEquals(List.of(ExternalEffectRunner.Result.DECLINED), results);
        assertEquals(DONE, tx.get(r.operation().id()).state());
        assertEquals(0, count("ra_collection")); assertEquals(0, count("ra_operation_locks"));
    }

    @Test void closingRunnerLeavesSellerPayoutOwed() throws Exception {
        var l = seed(ListingType.BIN); complete(tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation(), true);
        var payout = tx.pending(50).getFirst(); assertEquals(READY, payout.state());
        List<ExternalEffectRunner.Result> results = new ArrayList<>();
        new ExternalEffectRunner(tx, Runnable::run, Runnable::run, worker, e -> fail(e), () -> true)
                .execute(payout.id(), () -> { throw new AssertionError("must not pay during shutdown"); }, results::add);
        assertEquals(List.of(ExternalEffectRunner.Result.DECLINED), results);
        assertEquals(READY, tx.get(payout.id()).state());
    }

    @Test void deliveredEventsAreRemovedOnlyAfterDelivery() throws Exception {
        db.addEvent(alice, OfflineEvent.SOLD, "Item", 5, 1); db.addEvent(alice, OfflineEvent.OUTBID, "Item", 6, 2);
        var first = db.peekEvents(alice);
        assertEquals(2, first.size()); assertEquals(2, db.peekEvents(alice).size(), "peeking removes nothing");
        db.addEvent(alice, OfflineEvent.WON, "Item", 7, 3);
        db.deleteEvents(first.keySet());
        var left = db.peekEvents(alice);
        assertEquals(1, left.size()); assertEquals(OfflineEvent.WON, left.values().iterator().next().type());
    }

    @Test void captureAndClaimKeepCustodyUntilEachExternalAcknowledgement() throws Exception {
        var r = tx.reserveCapture(seller, "Seller", bytes, worker); var o = r.operation();
        assertEquals(1, db.collectionItems(seller).size());
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.reserveClaim(o.collection(), seller, "Seller", worker));
        complete(o, true); restart();
        var claim = tx.reserveClaim(o.collection(), seller, "Seller", worker).operation();
        assertTrue(tx.begin(claim.id(), worker)); restart();
        assertEquals(1, db.collectionItems(seller).size()); assertTrue(tx.recoverable(50).isEmpty());
        tx.acknowledge(claim.id(), true); tx.finish(claim.id()); tx.finish(claim.id());
        assertEquals(0, count("ra_collection")); assertEquals(0, count("ra_operation_locks"));
    }

    @Test void failedCaptureDiscardsBackupAndFailedClaimRetainsItem() throws Exception {
        var first = tx.reserveCapture(seller, "Seller", bytes, worker).operation(); tx.abandon(first.id());
        assertEquals(0, count("ra_collection"));
        var captured = capture(); var claim = tx.reserveClaim(captured.collection(), seller, "Seller", worker).operation();
        complete(claim, false); assertEquals(1, count("ra_collection"));
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.reserveClaim(captured.collection(), bob, "Bob", worker));
        assertEquals(0, count("ra_operation_locks"));
    }

    @Test void createKeepsDraftHiddenAndSerializesSellerLimitUntilFeeOutcome() throws Exception {
        var captured = capture(); var l = make(ListingType.BIN);
        var o = tx.reserveCreate(l, captured.collection(), 5, 1, worker).operation();
        assertEquals(ListingStatus.DRAFT, current(l).status()); assertEquals(0, db.countActive());
        assertEquals(1, count("ra_collection"));
        var second = capture();
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.reserveCreate(make(ListingType.BIN), second.collection(), 5, 1, worker));
        complete(o, true); assertEquals(ListingStatus.ACTIVE, current(l).status());
        assertEquals(1, count("ra_collection"));
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.reserveCreate(make(ListingType.BIN), second.collection(), 5, 1, worker));
        assertEquals(0, count("ra_operation_locks"));
    }

    @Test void failedCreationRestoresCollectionWithoutActivatingDraft() throws Exception {
        var captured = capture(); var l = make(ListingType.BIN);
        var o = tx.reserveCreate(l, captured.collection(), 5, 1, worker).operation(); complete(o, false);
        assertTrue(db.getListing(l.id()).isEmpty()); assertEquals(1, count("ra_collection"));
        assertEquals(0, count("ra_operation_locks"));
    }

    @Test void heldSaleEarningsWaitForTheSellerAndPayOnce() throws Exception {
        var l = seed(ListingType.BIN); complete(tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation(), true);
        var payout = tx.pending(50).getFirst();
        assertEquals(1, tx.recoverable(50).size(), "Paid automatically by default");
        assertTrue(tx.recoverable(50, true).isEmpty(), "Held for the seller to collect");
        assertEquals(java.util.List.of(payout.id()), tx.unclaimedEarnings(seller).stream().map(AuctionTransactions.Operation::id).toList());
        assertTrue(tx.unclaimedEarnings(alice).isEmpty());
        complete(payout, true);
        assertFalse(tx.begin(payout.id(), worker), "A second collect cannot pay again");
        assertTrue(tx.unclaimedEarnings(seller).isEmpty());
    }

    @Test void bidRefundsAreNotHeld() throws Exception {
        var l = seed(ListingType.AUCTION);
        complete(bid(l, alice, 100), true); complete(bid(l, bob, 120), true);
        assertEquals(1, tx.recoverable(50, true).size());
        assertTrue(tx.unclaimedEarnings(alice).isEmpty());
    }

    @Test void explicitPayoutFailureRemainsOwedAndRetryIsNotDuplicated() throws Exception {
        var l = seed(ListingType.BIN); complete(tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation(), true);
        var payout = tx.pending(50).getFirst(); complete(payout, false);
        assertEquals(READY, tx.get(payout.id()).state()); assertTrue(tx.recoverable(50).isEmpty(), "Back off known failed payments");
        sql("UPDATE ra_operations SET updated_at=0 WHERE id=?", payout.id());
        assertEquals(1, tx.recoverable(50).size()); complete(payout, true); tx.finish(payout.id());
        assertEquals(DONE, tx.get(payout.id()).state()); assertFalse(tx.begin(payout.id(), worker));
        assertEquals(1, count("ra_events"));
    }

    @Test void recoveryRefusesLiveOrRecentOriginAndRecordsPermanentOperatorDecision() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        assertTrue(tx.begin(o.id(), worker));
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.resolve(o.id(), true, "console"));
        sql("UPDATE ra_operations SET updated_at=0 WHERE id=?", o.id()); tx.heartbeat(worker); tx.heartbeat(worker);
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.resolve(o.id(), true, "console"));
        sql("UPDATE ra_workers SET heartbeat=0"); tx.resolve(o.id(), true, "console");
        assertEquals(DONE, tx.get(o.id()).state()); assertEquals(ListingStatus.SOLD, current(l).status());
        assertEquals(1, count("ra_reconciliations"));
        assertThrows(AuctionTransactions.Rejected.class, () -> tx.resolve(o.id(), false, "console"));
        assertEquals(1, count("ra_reconciliations"));
    }

    @Test void unpaidOperatorDecisionReleasesReservationWithoutSale() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        assertTrue(tx.begin(o.id(), worker)); sql("UPDATE ra_operations SET updated_at=0 WHERE id=?", o.id());
        tx.resolve(o.id(), false, "console");
        assertEquals(ListingStatus.ACTIVE, current(l).status()); assertEquals(0, count("ra_collection"));
        assertEquals(0, count("ra_operation_locks"));
    }

    @Test void effectThrowingAfterMovingMoneyIsNeverRetried() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        AtomicInteger payments = new AtomicInteger(); List<Exception> errors = new ArrayList<>();
        var runner = new ExternalEffectRunner(tx, Runnable::run, Runnable::run, worker, errors::add);
        ExternalEffectRunner.Effect ambiguous = () -> { payments.incrementAndGet(); throw new IllegalStateException("provider failed after debit"); };
        runner.execute(o.id(), ambiguous, result -> assertEquals(ExternalEffectRunner.Result.PENDING, result));
        restart();
        new ExternalEffectRunner(tx, Runnable::run, Runnable::run, worker, errors::add)
                .execute(o.id(), ambiguous, result -> assertEquals(ExternalEffectRunner.Result.PENDING, result));
        assertEquals(1, payments.get()); assertEquals(1, errors.size()); assertEquals(APPLYING, tx.get(o.id()).state());
    }

    @Test void runnerPersistsIntentBeforeEffectAndRetainsAmbiguityIfAcknowledgementIsLost() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        Queue<Runnable> database = new ArrayDeque<>(), main = new ArrayDeque<>(); AtomicInteger calls = new AtomicInteger();
        var runner = new ExternalEffectRunner(tx, database::add, main::add, worker, e -> fail(e));
        runner.execute(o.id(), () -> { assertEquals(APPLYING, tx.get(o.id()).state()); calls.incrementAndGet(); return true; }, r -> fail("Callback must not run before acknowledgement"));
        assertEquals(PREPARED, tx.get(o.id()).state()); assertEquals(0, calls.get());
        database.remove().run(); assertEquals(APPLYING, tx.get(o.id()).state()); assertEquals(0, calls.get());
        main.remove().run(); assertEquals(1, calls.get()); assertEquals(APPLYING, tx.get(o.id()).state());
        database.clear(); restart(); // Simulate process death before queued acknowledgement.
        assertTrue(tx.recoverable(50).isEmpty()); assertEquals(ListingStatus.ACTIVE, current(l).status());
        assertEquals(1, count("ra_operation_locks"));
    }

    @Test void runnerDoesNotCallProviderIfDurableBeginFails() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        failBegin();
        AtomicInteger calls = new AtomicInteger(); List<Exception> errors = new ArrayList<>();
        new ExternalEffectRunner(tx, Runnable::run, Runnable::run, worker, errors::add)
                .execute(o.id(), () -> { calls.incrementAndGet(); return true; }, r -> assertEquals(ExternalEffectRunner.Result.PENDING, r));
        assertEquals(0, calls.get()); assertEquals(1, errors.size()); assertEquals(PREPARED, tx.get(o.id()).state());
    }

    @Test void finalizingPaidPurchaseFailureCanResumeDatabaseWorkOnly() throws Exception {
        var l = seed(ListingType.BIN); var o = tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation();
        assertTrue(tx.begin(o.id(), worker)); tx.acknowledge(o.id(), true); failInserts();
        assertThrows(SQLException.class, () -> tx.finish(o.id()));
        assertEquals(APPLIED, tx.get(o.id()).state()); assertEquals(ListingStatus.ACTIVE, current(l).status());
        assertEquals(0, count("ra_collection")); assertEquals(1, count("ra_operation_locks"));
        sql("DROP TRIGGER fail_delivery"); restart(); tx.finish(o.id());
        assertEquals(ListingStatus.SOLD, current(l).status()); assertEquals(1, count("ra_collection"));
        var context = tx.paymentContext(l.id()); assertEquals(alice, context.buyer()); assertFalse(context.auction());
    }

    @Test void pruningPreservesUnfinishedSellerPaymentAndDraftCustody() throws Exception {
        var l = seed(ListingType.BIN); complete(tx.reserveBuy(l.id(), alice, "Alice", 100, worker).operation(), true);
        var payout = tx.pending(50).getFirst();
        var captured = capture(); var draft = make(ListingType.BIN);
        tx.reserveCreate(draft, captured.collection(), 5, 2, worker);
        assertEquals(0, db.pruneClosed(Long.MAX_VALUE));
        assertTrue(db.getListing(l.id()).isPresent()); assertTrue(db.getListing(draft.id()).isPresent());
        complete(payout, true); assertEquals(1, db.pruneClosed(Long.MAX_VALUE));
        assertTrue(db.getListing(draft.id()).isPresent()); assertEquals(2, count("ra_collection"));
    }

    @Test void twoFinalizersCannotRepeatRefundOrBidHistory() throws Exception {
        var l = seed(ListingType.AUCTION); complete(bid(l, alice, 100), true);
        var o = bid(l, bob, 110); assertTrue(tx.begin(o.id(), worker)); tx.acknowledge(o.id(), true);
        var other = new AuctionDatabase(folder.toFile(), config(), Logger.getAnonymousLogger()); other.init();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); tx.finish(o.id()); return true; });
            var b = executor.submit(() -> { start.await(); other.transactions().finish(o.id()); return true; });
            start.countDown(); assertTrue(a.get(10, TimeUnit.SECONDS)); assertTrue(b.get(10, TimeUnit.SECONDS));
            assertEquals(2, count("ra_bids")); assertEquals(1, tx.pending(50).size());
        } finally { other.close(); }
    }

    @Test void twoExpiryWorkersSettleTheSameWinnerOnce() throws Exception {
        var l = seed(ListingType.AUCTION); complete(bid(l, alice, 100), true); expireNow(l);
        var other = new AuctionDatabase(folder.toFile(), config(), Logger.getAnonymousLogger()); other.init();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return tx.expire(l.id()); });
            var b = executor.submit(() -> { start.await(); return other.transactions().expire(l.id()); });
            start.countDown(); assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, count("ra_collection")); assertEquals(1, tx.pending(50).size());
            assertEquals(alice, tx.paymentContext(l.id()).buyer());
        } finally { other.close(); }
    }

    @Test void concurrentNotificationDrainsDeliverEachSavedEventOnlyOnce() throws Exception {
        for (int i = 0; i < 20; i++) db.addEvent(alice, "SOLD", "Item " + i, i + 1, 1);
        db.addEvent(bob, "SOLD", "Bob's item", 100, 1);
        var other = new AuctionDatabase(folder.toFile(), config(), Logger.getAnonymousLogger()); other.init();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return db.drainEvents(alice); });
            var b = executor.submit(() -> { start.await(); return other.drainEvents(alice); });
            start.countDown(); var delivered = new ArrayList<>(a.get(10, TimeUnit.SECONDS)); delivered.addAll(b.get(10, TimeUnit.SECONDS));
            assertEquals(20, delivered.size()); assertEquals(20, delivered.stream().map(OfflineEvent::item).distinct().count());
            assertTrue(db.drainEvents(alice).isEmpty()); assertEquals(1, db.drainEvents(bob).size());
        } finally { other.close(); }
    }
}
