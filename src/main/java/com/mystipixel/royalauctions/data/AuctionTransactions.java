package com.mystipixel.royalauctions.data;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;

/**
 * Durable reservations and an outbox for external effects. No Bukkit or Vault calls belong here.
 * Resource locks are rows, not expiring JVM locks: another server cannot settle an unpaid bid.
 * PREPARED has never attempted an effect; APPLYING is ambiguous after interruption and is NEVER
 * replayed automatically. APPLIED/FAILED can finish entirely inside the database, on any server.
 */
public final class AuctionTransactions {
    public enum Kind { BID, BUY, CREATE, CAPTURE, CLAIM, PAYOUT }
    public enum State { PREPARED, APPLYING, APPLIED, FAILED, READY, DONE }
    public record Operation(UUID id, Kind kind, State state, UUID listing, UUID collection,
                            UUID player, String playerName, double amount, long expiry,
                            long created, long updated, String worker, String note) { }
    public record Reservation(Operation operation, Listing listing, CollectionItem item) { }
    public record PaymentContext(UUID buyer, String itemName, boolean auction) { }
    public static final class Rejected extends SQLException {
        /** Key under {@code exchange.reasons.} for a player-facing reason, or null for staff-only ones. */
        public final String key;
        /** Placeholder name/value pairs for the reason's text. */
        public final String[] values;
        public Rejected(String message) { this(null, message); }
        public Rejected(String key, String message, String... values) { super(message); this.key = key; this.values = values; }
    }
    private final DataSource source;
    public AuctionTransactions(DataSource source) { this.source = source; }

    public void init() throws SQLException {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
            String engine = mysql ? " ENGINE=InnoDB" : "";
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ra_operations (id CHAR(36) PRIMARY KEY, "
                    + "kind VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL, listing_id CHAR(36), "
                    + "collection_id CHAR(36), player_id CHAR(36) NOT NULL, player_name VARCHAR(32), "
                    + "amount DOUBLE PRECISION NOT NULL, expiry BIGINT NOT NULL, created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL, worker VARCHAR(36), note VARCHAR(512))" + engine);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ra_operation_locks (resource VARCHAR(90) PRIMARY KEY, "
                    + "operation_id CHAR(36) NOT NULL)" + engine);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ra_workers (id VARCHAR(36) PRIMARY KEY, heartbeat BIGINT NOT NULL)" + engine);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ra_reconciliations (id CHAR(36) PRIMARY KEY, "
                    + "operation_id CHAR(36) NOT NULL, decision VARCHAR(16) NOT NULL, operator VARCHAR(128) NOT NULL, created_at BIGINT NOT NULL)" + engine);
            createIndex(s, "idx_ra_operations_recovery", "ra_operations(state,updated_at)");
            createIndex(s, "idx_ra_operations_listing", "ra_operations(listing_id,state)");
            createIndex(s, "idx_ra_locks_operation", "ra_operation_locks(operation_id)");
            if (mysql) {
                try (ResultSet rs = s.executeQuery("SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME IN ('ra_listings','ra_collection','ra_bids','ra_events','ra_operations','ra_operation_locks','ra_workers','ra_reconciliations')")) {
                    while (rs.next()) if (!"InnoDB".equalsIgnoreCase(rs.getString("ENGINE")))
                        throw new SQLException("RoyalAuctions requires InnoDB transactions: convert " + rs.getString("TABLE_NAME") + " before starting.");
                }
            }
        }
    }

    private static void createIndex(Statement s, String name, String target) throws SQLException {
        try { s.executeUpdate("CREATE INDEX " + name + " ON " + target); }
        catch (SQLException e) {
            if (e.getErrorCode() != 1061 && !(e.getErrorCode() == 1 && e.getMessage().contains("already exists"))) throw e;
        }
    }

    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try { T result = work.run(c); c.commit(); return result; }
            catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }
    private static PreparedStatement statement(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < values.length; i++)
            ps.setObject(i + 1, values[i] instanceof UUID || values[i] instanceof Enum<?> ? values[i].toString() : values[i]);
        return ps;
    }
    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = statement(c, sql, args)) { return ps.executeUpdate(); }
    }
    private static UUID uuid(String s) { return s == null ? null : UUID.fromString(s); }
    private static UUID key(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)); }
    /**
     * A primary-key or unique clash. SQLite reports every constraint failure (NOT NULL, CHECK, a
     * trigger's abort, ...) as code 19, so only its key-clash messages count; anything else is a
     * real error and must not be mistaken for "already locked".
     */
    static boolean duplicate(SQLException e) {
        if (e.getErrorCode() == 1062 || "23505".equals(e.getSQLState())) return true;
        String message = e.getMessage();
        return e.getErrorCode() == 19 && message != null && (message.contains("UNIQUE constraint failed")
                || message.contains("SQLITE_CONSTRAINT_PRIMARYKEY") || message.contains("SQLITE_CONSTRAINT_UNIQUE"));
    }
    private static void lock(Connection c, String resource, UUID operation) throws SQLException {
        try { update(c, "INSERT INTO ra_operation_locks(resource,operation_id) VALUES (?,?)", resource, operation); }
        catch (SQLException e) { if (duplicate(e)) throw new Rejected("already-pending", "An operation is already pending for this item or auction."); throw e; }
    }
    private static void release(Connection c, UUID operation) throws SQLException {
        update(c, "DELETE FROM ra_operation_locks WHERE operation_id=?", operation);
    }
    private static void insert(Connection c, Operation o) throws SQLException {
        update(c, "INSERT INTO ra_operations(id,kind,state,listing_id,collection_id,player_id,player_name,"
                        + "amount,expiry,created_at,updated_at,worker,note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                o.id, o.kind, o.state, o.listing, o.collection, o.player, o.playerName,
                o.amount, o.expiry, o.created, o.updated, o.worker, o.note);
    }
    private static Operation operation(ResultSet rs) throws SQLException {
        return new Operation(uuid(rs.getString("id")), Kind.valueOf(rs.getString("kind")),
                State.valueOf(rs.getString("state")), uuid(rs.getString("listing_id")), uuid(rs.getString("collection_id")),
                uuid(rs.getString("player_id")), rs.getString("player_name"), rs.getDouble("amount"),
                rs.getLong("expiry"), rs.getLong("created_at"), rs.getLong("updated_at"),
                rs.getString("worker"), rs.getString("note"));
    }
    public Operation get(UUID id) throws SQLException {
        try (Connection c = source.getConnection()) { return get(c, id); }
    }
    public PaymentContext paymentContext(UUID listingId) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement ps = statement(c,
                "SELECT buyer_id,display_name,type FROM ra_listings WHERE id=?", listingId); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new Rejected("The payment's listing no longer exists.");
            return new PaymentContext(uuid(rs.getString("buyer_id")), rs.getString("display_name"), "AUCTION".equals(rs.getString("type")));
        }
    }
    private static Operation get(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = statement(c, "SELECT * FROM ra_operations WHERE id=?", id); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new Rejected("Unknown operation.");
            return operation(rs);
        }
    }
    private static Listing listing(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = statement(c, "SELECT * FROM ra_listings WHERE id=?", id); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new Rejected("listing-gone", "This listing no longer exists.");
            return AuctionDatabase.mapListing(rs);
        }
    }
    private static CollectionItem item(Connection c, UUID id, UUID owner) throws SQLException {
        try (PreparedStatement ps = statement(c, "SELECT * FROM ra_collection WHERE id=? AND owner_id=?", id, owner);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new Rejected("collection-gone", "This collection item is no longer available.");
            return AuctionDatabase.mapCollection(rs);
        }
    }
    private static void addItem(Connection c, UUID id, UUID owner, byte[] bytes, CollectionItem.Reason reason, long now) throws SQLException {
        update(c, "INSERT INTO ra_collection(id,owner_id,item_data,reason,created_at) VALUES (?,?,?,?,?)",
                id, owner, ItemSerialization.toBase64(bytes), reason, now);
    }
    private static void validMoney(double amount, boolean zeroAllowed) throws Rejected {
        if (!Double.isFinite(amount) || amount < 0 || (!zeroAllowed && amount == 0)) throw new Rejected("invalid-amount", "Invalid money amount.");
    }
    private Operation prepared(Kind kind, UUID listing, UUID collection, UUID player, String name,
                               double amount, long expiry, String worker) {
        long now = System.currentTimeMillis();
        return new Operation(UUID.randomUUID(), kind, State.PREPARED, listing, collection, player, name, amount, expiry, now, now, worker, "");
    }

    public Reservation reserveBid(UUID listingId, UUID player, String name, double amount,
                                  DoubleUnaryOperator increment, long antiSnipeMillis, String worker) throws SQLException {
        validMoney(amount, false);
        return transaction(c -> {
            Operation token = prepared(Kind.BID, listingId, null, player, name, amount, 0, worker);
            lock(c, "listing/" + listingId, token.id);
            Listing l = listing(c, listingId);
            long now = System.currentTimeMillis();
            if (l.status() != ListingStatus.ACTIVE || !l.isAuction() || l.expiresAt() <= now || l.sellerId().equals(player))
                throw new Rejected("bid-closed", "This auction is no longer accepting your bid.");
            double minimum = l.nextMinBid(increment.applyAsDouble(l.currentBid()));
            if (!Double.isFinite(minimum) || amount < minimum || (l.hasBids() && amount <= l.currentBid()))
                throw new Rejected("bid-too-low", "The bid must meet the current minimum: " + minimum, "minimum", String.valueOf(minimum));
            long expiry = antiSnipeMillis > 0 ? Math.max(l.expiresAt(), Math.addExact(now, antiSnipeMillis)) : l.expiresAt();
            Operation o = new Operation(token.id, token.kind, token.state, listingId, null, player, name,
                    amount, expiry, now, now, worker, "");
            insert(c, o);
            return new Reservation(o, l, null);
        });
    }
    public Reservation reserveBuy(UUID listingId, UUID player, String name, double expectedPrice, String worker) throws SQLException {
        return transaction(c -> {
            Operation o = prepared(Kind.BUY, listingId, null, player, name, expectedPrice, 0, worker);
            lock(c, "listing/" + listingId, o.id);
            Listing l = listing(c, listingId);
            validMoney(l.price(), false);
            if (l.status() != ListingStatus.ACTIVE || l.isAuction() || l.expiresAt() <= System.currentTimeMillis()
                    || l.sellerId().equals(player) || Double.compare(expectedPrice, l.price()) != 0)
                throw new Rejected("price-changed", "This purchase is no longer available at that price.");
            insert(c, o);
            return new Reservation(o, l, null);
        });
    }
    public Reservation reserveCapture(UUID owner, String name, byte[] bytes, String worker) throws SQLException {
        UUID collection = UUID.randomUUID();
        Operation o = prepared(Kind.CAPTURE, null, collection, owner, name, 0, 0, worker);
        return transaction(c -> {
            lock(c, "collection/" + collection, o.id);
            addItem(c, collection, owner, bytes, CollectionItem.Reason.CANCELLED, o.created);
            insert(c, o);
            return new Reservation(o, null, item(c, collection, owner));
        });
    }
    public Reservation reserveClaim(UUID collection, UUID owner, String name, String worker) throws SQLException {
        Operation o = prepared(Kind.CLAIM, null, collection, owner, name, 0, 0, worker);
        return transaction(c -> {
            lock(c, "collection/" + collection, o.id);
            CollectionItem ci = item(c, collection, owner);
            insert(c, o);
            return new Reservation(o, null, ci);
        });
    }
    public Reservation reserveCreate(Listing l, UUID collection, double fee, int limit, String worker) throws SQLException {
        validMoney(l.price(), false); validMoney(fee, true);
        if (l.expiresAt() <= System.currentTimeMillis()) throw new Rejected("invalid-duration", "Invalid listing duration.");
        Operation o = prepared(Kind.CREATE, l.id(), collection, l.sellerId(), l.sellerName(), fee, 0, worker);
        return transaction(c -> {
            lock(c, "seller/" + l.sellerId(), o.id);
            lock(c, "collection/" + collection, o.id);
            lock(c, "listing/" + l.id(), o.id);
            CollectionItem ci = item(c, collection, l.sellerId());
            if (!Arrays.equals(ci.itemData(), l.itemData())) throw new Rejected("item-changed", "The selected item changed.");
            if (limit >= 0) {
                try (PreparedStatement ps = statement(c, "SELECT COUNT(*) FROM ra_listings WHERE seller_id=? AND status IN ('ACTIVE','DRAFT')", l.sellerId());
                     ResultSet rs = ps.executeQuery()) {
                    rs.next(); if (rs.getInt(1) >= limit) throw new Rejected("listing-limit", "You have reached your listing limit.");
                }
            }
            AuctionDatabase.insertListing(c, l, ListingStatus.DRAFT);
            insert(c, o);
            return new Reservation(o, l, ci);
        });
    }

    /** Must commit before the external call starts. Failed CAS means the caller MUST NOT call it. */
    public boolean begin(UUID id, String worker) throws SQLException {
        try (Connection c = source.getConnection()) {
            return update(c, "UPDATE ra_operations SET state='APPLYING',worker=?,updated_at=? "
                    + "WHERE id=? AND (state='PREPARED' OR state='READY')", worker, System.currentTimeMillis(), id) == 1;
        }
    }
    /** A known failure has not moved value. Exceptions/unknown outcomes must remain APPLYING. */
    public void acknowledge(UUID id, boolean success) throws SQLException {
        try (Connection c = source.getConnection()) {
            update(c, "UPDATE ra_operations SET state=?,updated_at=? WHERE id=? AND state='APPLYING'",
                    success ? State.APPLIED : State.FAILED, System.currentTimeMillis(), id);
        }
    }
    public void abandon(UUID id) throws SQLException {
        try (Connection c = source.getConnection()) {
            update(c, "UPDATE ra_operations SET state='FAILED',updated_at=? WHERE id=? AND state='PREPARED'", System.currentTimeMillis(), id);
        }
        finish(id);
    }

    /** Claim the terminal state inside the same transaction as all database effects. Idempotent. */
    public void finish(UUID id) throws SQLException {
        transaction(c -> {
            // Acquire the write lock before reading, including on SQLite and MySQL REPEATABLE READ.
            int changed = update(c, "UPDATE ra_operations SET updated_at=updated_at+1 WHERE id=? AND state IN ('APPLIED','FAILED')", id);
            if (changed == 0) return null;
            Operation o = get(c, id);
            if (o.state == State.FAILED) {
                if (o.kind == Kind.PAYOUT) {
                    update(c, "UPDATE ra_operations SET state='READY',updated_at=? WHERE id=?", System.currentTimeMillis(), id);
                    return null;
                }
                if (o.kind == Kind.CREATE) update(c, "DELETE FROM ra_listings WHERE id=? AND status='DRAFT'", o.listing);
                if (o.kind == Kind.CAPTURE) update(c, "DELETE FROM ra_collection WHERE id=?", o.collection);
            } else switch (o.kind) {
                case BID -> {
                    Listing l = listing(c, o.listing);
                    update(c, "UPDATE ra_listings SET current_bid=?,top_bidder_id=?,top_bidder_name=?,bid_count=bid_count+1,expires_at=? WHERE id=?",
                            o.amount, o.player, o.playerName, o.expiry, o.listing);
                    update(c, "INSERT INTO ra_bids(listing_id,bidder_id,bidder_name,amount,created_at) VALUES (?,?,?,?,?)",
                            o.listing, o.player, o.playerName, o.amount, o.created);
                    if (l.hasBids()) payout(c, key("refund/" + o.id), l, l.topBidderId(), l.topBidderName(), l.currentBid(), "OUTBID");
                }
                case BUY -> settle(c, listing(c, o.listing), o.player, o.playerName, o.amount);
                case CREATE -> {
                    update(c, "UPDATE ra_listings SET status='ACTIVE' WHERE id=? AND status='DRAFT'", o.listing);
                    update(c, "DELETE FROM ra_collection WHERE id=? AND owner_id=?", o.collection, o.player);
                }
                case CLAIM -> update(c, "DELETE FROM ra_collection WHERE id=? AND owner_id=?", o.collection, o.player);
                case PAYOUT -> {
                    Listing l = listing(c, o.listing);
                    event(c, o.player, o.note, l.displayName(), o.amount);
                }
                case CAPTURE -> { /* The captured item is now available in collection. */ }
            }
            update(c, "UPDATE ra_operations SET state='DONE',updated_at=? WHERE id=?", System.currentTimeMillis(), id);
            release(c, id);
            return null;
        });
    }
    private static void event(Connection c, UUID player, String type, String name, double amount) throws SQLException {
        update(c, "INSERT INTO ra_events(player_id,type,item,amount,created_at) VALUES (?,?,?,?,?)", player, type, name, amount, System.currentTimeMillis());
    }
    private static void payout(Connection c, UUID id, Listing l, UUID player, String name, double amount, String reason) throws SQLException {
        validMoney(amount, true);
        if (amount == 0) return;
        insert(c, new Operation(id, Kind.PAYOUT, State.READY, l.id(), null, player, name,
                amount, 0, System.currentTimeMillis(), 0, null, reason));
    }
    private static void settle(Connection c, Listing l, UUID buyer, String name, double price) throws SQLException {
        update(c, "UPDATE ra_listings SET status='SOLD',buyer_id=?,sold_at=? WHERE id=?", buyer, System.currentTimeMillis(), l.id());
        addItem(c, key("settlement/" + l.id()), buyer, l.itemData(), CollectionItem.Reason.PURCHASE, System.currentTimeMillis());
        payout(c, key("sale/" + l.id()), l, l.sellerId(), l.sellerName(), price, "SOLD");
        if (l.isAuction()) event(c, buyer, "WON", l.displayName(), price);
    }

    /** Ignore the sweep's snapshot; lock and re-read both the deadline and funded bid state. */
    public boolean expire(UUID listingId) throws SQLException {
        try { return transaction(c -> {
            UUID id = UUID.randomUUID(); lock(c, "listing/" + listingId, id);
            Listing l = listing(c, listingId);
            if (l.status() != ListingStatus.ACTIVE || l.expiresAt() > System.currentTimeMillis()) { release(c, id); return false; }
            if (l.isAuction() && l.hasBids()) settle(c, l, l.topBidderId(), l.topBidderName(), l.currentBid());
            else {
                update(c, "UPDATE ra_listings SET status='EXPIRED' WHERE id=?", l.id());
                addItem(c, key("settlement/" + l.id()), l.sellerId(), l.itemData(), CollectionItem.Reason.EXPIRED, System.currentTimeMillis());
                event(c, l.sellerId(), "EXPIRED", null, 1);
            }
            release(c, id); return true;
        }); } catch (Rejected busy) { return false; }
    }
    public boolean cancel(UUID listingId, UUID seller) throws SQLException {
        return transaction(c -> {
            UUID id = UUID.randomUUID(); lock(c, "listing/" + listingId, id);
            Listing l = listing(c, listingId);
            if (l.status() != ListingStatus.ACTIVE || !l.sellerId().equals(seller) || l.hasBids()) throw new Rejected("cannot-cancel", "This listing cannot be cancelled.");
            update(c, "UPDATE ra_listings SET status='CANCELLED' WHERE id=?", l.id());
            addItem(c, key("settlement/" + l.id()), seller, l.itemData(), CollectionItem.Reason.CANCELLED, System.currentTimeMillis());
            release(c, id); return true;
        });
    }

    public List<Operation> pending(int limit) throws SQLException {
        return pending(limit, 0);
    }
    public List<Operation> pending(int limit, int offset) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement ps = statement(c,
                // Listed explicitly rather than state<>'DONE', so the (state,...) index skips the
                // ever-growing history of finished operations instead of scanning it.
                "SELECT * FROM ra_operations WHERE state IN ('PREPARED','APPLYING','APPLIED','FAILED','READY') "
                        + "ORDER BY created_at,id LIMIT ? OFFSET ?", limit, offset); ResultSet rs = ps.executeQuery()) {
            List<Operation> out = new ArrayList<>(); while (rs.next()) out.add(operation(rs)); return out;
        }
    }
    public int heldCount() throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement ps = statement(c,
                "SELECT COUNT(*) FROM ra_operations WHERE state='APPLYING' AND updated_at<?", System.currentTimeMillis() - 120_000);
             ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
    }
    public List<Operation> recoverable(int limit) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement ps = statement(c,
                "SELECT * FROM ra_operations WHERE state IN ('APPLIED','FAILED') OR (state='READY' AND updated_at<?) "
                        + "OR (state='PREPARED' AND updated_at<?) ORDER BY updated_at,id LIMIT ?",
                System.currentTimeMillis() - 60_000, System.currentTimeMillis() - 120_000, limit); ResultSet rs = ps.executeQuery()) {
            List<Operation> out = new ArrayList<>(); while (rs.next()) out.add(operation(rs)); return out;
        }
    }
    public void heartbeat(String worker) throws SQLException {
        transaction(c -> {
            // An UPDATE can report zero changed rows if the millisecond value is identical.
            try { update(c, "INSERT INTO ra_workers(id,heartbeat) VALUES (?,?)", worker, System.currentTimeMillis()); }
            catch (SQLException e) { if (!duplicate(e)) throw e; }
            update(c, "UPDATE ra_workers SET heartbeat=? WHERE id=?", System.currentTimeMillis(), worker);
            return null;
        });
    }
    /** Operator must stop the originating process and verify the external account/inventory first. */
    public void resolve(UUID id, boolean applied, String operator) throws SQLException {
        transaction(c -> {
            if (update(c, "UPDATE ra_operations SET updated_at=updated_at+1 WHERE id=? AND state='APPLYING' AND updated_at<?",
                    id, System.currentTimeMillis() - 120_000) != 1) throw new Rejected("Only interrupted APPLYING operations older than two minutes can be reconciled.");
            Operation o = get(c, id);
            try (PreparedStatement ps = statement(c, "SELECT heartbeat FROM ra_workers WHERE id=?", o.worker); ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(1) >= System.currentTimeMillis() - 120_000)
                    throw new Rejected("The originating server is still active. Stop that process before reconciliation.");
            }
            // Keep the payout reason in note; the separate immutable audit records the operator decision.
            update(c, "UPDATE ra_operations SET state=?,updated_at=? WHERE id=?", applied ? State.APPLIED : State.FAILED, System.currentTimeMillis(), id);
            update(c, "INSERT INTO ra_reconciliations(id,operation_id,decision,operator,created_at) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), id, applied ? "applied" : "not-applied", operator, System.currentTimeMillis());
            return null;
        });
        finish(id);
    }
}
