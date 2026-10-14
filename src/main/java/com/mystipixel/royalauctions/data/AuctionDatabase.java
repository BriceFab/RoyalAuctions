package com.mystipixel.royalauctions.data;

import com.mystipixel.royalcommon.storage.Dialect;
import com.mystipixel.royalcommon.storage.Lease;
import com.mystipixel.royalcommon.storage.SchemaVersion;
import com.mystipixel.royalcommon.storage.TxRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All persistence, one JDBC/HikariCP implementation for both backends. Every method blocks and
 * must be called off the main thread ({@code AuctionService} schedules them).
 */
public final class AuctionDatabase {

    public enum Type {
        SQLITE, MYSQL
    }

    static final int SCHEMA_VERSION = 2;
    private static final int CHUNK = 500;

    private final File dataFolder;
    private final ConfigurationSection config;
    private final Logger logger;

    private Type type;
    private HikariDataSource dataSource;
    private TxRunner runner;
    private AuctionTransactions transactions;

    public AuctionTransactions transactions() { return transactions; }

    public Type type() { return type; }

    public AuctionDatabase(File dataFolder, ConfigurationSection storageConfig, Logger logger) {
        this.dataFolder = dataFolder;
        this.config = storageConfig;
        this.logger = logger;
    }

    public void init() throws SQLException {
        String rawType = config.getString("type", "SQLITE").toUpperCase(java.util.Locale.ROOT);
        this.type = "MYSQL".equals(rawType) ? Type.MYSQL : Type.SQLITE;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("RoyalAuctions");

        if (type == Type.MYSQL) {
            ConfigurationSection my = config.getConfigurationSection("mysql");
            String host = my.getString("host", "localhost");
            int port = my.getInt("port", 3306);
            String database = my.getString("database", "royalauctions");
            String props = my.getString("properties", "useSSL=false");
            registerDriver("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props);
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setUsername(my.getString("username", "root"));
            hikari.setPassword(my.getString("password", ""));
            hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
        } else {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warning("Could not create plugin data folder: " + dataFolder);
            }
            File db = new File(dataFolder, config.getString("sqlite-file", "auctions.db"));
            registerDriver("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + db.getAbsolutePath());
            hikari.setDriverClassName("org.sqlite.JDBC");
            // SQLite is a single-writer engine; one pooled connection + WAL avoids SQLITE_BUSY.
            hikari.setMaximumPoolSize(1);
            hikari.setConnectionInitSql("PRAGMA synchronous=FULL");
        }

        this.dataSource = new HikariDataSource(hikari);
        try {
            if (type == Type.SQLITE) {
                try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                }
            }
            runner = new TxRunner(dataSource);
            transactions = new AuctionTransactions(runner);
            migrate();
            transactions.init();
        } catch (SQLException | RuntimeException e) {
            dataSource.close();
            throw e;
        }
        logger.info("Connected to " + type + " storage.");
    }

    // Servers sharing a MySQL database that start together take turns here; the second finds nothing to do.
    private void migrate() throws SQLException {
        int before = new SchemaVersion(runner, "ra").ensure(SCHEMA_VERSION, (c, dialect, from, to) -> {
            if (from < 1) {
                createSchema(c);
                transactions.createTables(c);
            }
            if (from < 2) {
                try (Statement st = c.createStatement()) {
                    // Price sorts and category pages read in index order instead of sorting every match.
                    createIndex(st, "idx_ra_listings_status_price", "ra_listings(status, price)");
                    createIndex(st, "idx_ra_listings_status_category_created", "ra_listings(status, category, created_at)");
                    createIndex(st, "idx_ra_listings_status_category_price", "ra_listings(status, category, price)");
                    // The recovery poll steps over held sale payouts by note; a prefix keeps MySQL's key short.
                    createIndex(st, "idx_ra_operations_state_note", dialect == Dialect.MYSQL
                            ? "ra_operations(state, note(32))" : "ra_operations(state, note)");
                    createIndex(st, "idx_ra_operations_player", "ra_operations(player_id, state)");
                    createIndex(st, "idx_ra_bids_bidder_listing", "ra_bids(bidder_id, listing_id)");
                }
            }
        });
        if (before < SCHEMA_VERSION) {
            logger.info("Database schema migrated from version " + before + " to " + SCHEMA_VERSION + ".");
        }
        lease("").createTable();
    }

    /** A lease on this database, for the jobs only one server should run (expiry and pruning). */
    public Lease lease(String serverId) {
        return new Lease(runner, "ra", serverId);
    }

    private void registerDriver(String driverClass) {
        // Paper's library loader puts the JDBC driver on this plugin's classloader; forcing the
        // class to load here registers it with DriverManager before Hikari asks for a connection.
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, e);
        }
    }

    public int poolSize() {
        return dataSource.getMaximumPoolSize();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private String bigText() {
        return type == Type.MYSQL ? "LONGTEXT" : "TEXT";
    }

    private void createSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ra_listings ("
                    + "id CHAR(36) PRIMARY KEY,"
                    + "seller_id CHAR(36) NOT NULL,"
                    + "seller_name VARCHAR(32) NOT NULL,"
                    + "item_data " + bigText() + " NOT NULL,"
                    + "display_name VARCHAR(256) NOT NULL,"
                    + "category VARCHAR(64) NOT NULL,"
                    + "tier VARCHAR(32),"
                    + "type VARCHAR(16) NOT NULL DEFAULT 'BIN',"
                    + "price DOUBLE PRECISION NOT NULL,"
                    + "current_bid DOUBLE PRECISION,"
                    + "top_bidder_id CHAR(36),"
                    + "top_bidder_name VARCHAR(32),"
                    + "bid_count INT NOT NULL DEFAULT 0,"
                    + "created_at BIGINT NOT NULL,"
                    + "expires_at BIGINT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "buyer_id CHAR(36),"
                    + "sold_at BIGINT)");
            // A NULL tier on older rows means "no rarity".
            addColumn(st, "tier", "VARCHAR(32)");
            addColumn(st, "type", "VARCHAR(16) NOT NULL DEFAULT 'BIN'");
            addColumn(st, "current_bid", "DOUBLE PRECISION");
            addColumn(st, "top_bidder_id", "CHAR(36)");
            addColumn(st, "top_bidder_name", "VARCHAR(32)");
            addColumn(st, "bid_count", "INT NOT NULL DEFAULT 0");
            // Search text for the item's enchantments. NULL = not indexed yet (rows from older versions).
            addColumn(st, "enchantments", bigText());
            // Category and tier ids are compared exactly (so the browse filters can use an index);
            // new rows are stored lowercase, and rows from older versions are brought in line here.
            st.executeUpdate("UPDATE ra_listings SET category=LOWER(category) WHERE category<>LOWER(category)");
            st.executeUpdate("UPDATE ra_listings SET tier=LOWER(tier) WHERE tier IS NOT NULL AND tier<>LOWER(tier)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ra_collection ("
                    + "id CHAR(36) PRIMARY KEY,"
                    + "owner_id CHAR(36) NOT NULL,"
                    + "item_data " + bigText() + " NOT NULL,"
                    + "reason VARCHAR(16) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            // Every bid ever placed: ra_listings only keeps the current top bidder, and "auctions I've
            // bid on" must include ones the player was outbid on.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ra_bids ("
                    + "id " + autoIncrementPk() + ","
                    + "listing_id CHAR(36) NOT NULL,"
                    + "bidder_id CHAR(36) NOT NULL,"
                    + "bidder_name VARCHAR(32) NOT NULL,"
                    + "amount DOUBLE PRECISION NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            createIndex(st, "idx_ra_listings_status", "ra_listings(status)");
            createIndex(st, "idx_ra_listings_seller", "ra_listings(seller_id)");
            createIndex(st, "idx_ra_collection_owner", "ra_collection(owner_id)");
            // Composites matching how the hot queries filter and sort.
            createIndex(st, "idx_ra_listings_status_created", "ra_listings(status, created_at)");
            createIndex(st, "idx_ra_listings_status_seller", "ra_listings(status, seller_id)");
            createIndex(st, "idx_ra_listings_status_expires", "ra_listings(status, expires_at)");
            createIndex(st, "idx_ra_listings_status_category", "ra_listings(status, category)");
            createIndex(st, "idx_ra_listings_status_tier", "ra_listings(status, tier)");
            createIndex(st, "idx_ra_collection_owner_created", "ra_collection(owner_id, created_at)");
            createIndex(st, "idx_ra_bids_bidder", "ra_bids(bidder_id)");
            createIndex(st, "idx_ra_bids_listing", "ra_bids(listing_id)");
            // Events that happened to offline players, drained on their next join.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ra_events ("
                    + "id " + autoIncrementPk() + ","
                    + "player_id CHAR(36) NOT NULL,"
                    + "type VARCHAR(16) NOT NULL,"
                    + "item VARCHAR(256),"
                    + "amount DOUBLE PRECISION NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            createIndex(st, "idx_ra_events_player", "ra_events(player_id)");
        }
    }

    private String autoIncrementPk() {
        return type == Type.MYSQL ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    private void addColumn(Statement st, String column, String definition) throws SQLException {
        // Check the metadata rather than ALTERing and swallowing the error, which hides a failed
        // migration. Scope to this database: a null catalog means "every database" to Connector/J.
        // '_' is a metadata wildcard, so names are compared exactly. Failures stop startup.
        Connection c = st.getConnection();
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, "ra_listings", column)) {
            while (rs.next()) {
                if ("ra_listings".equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        try {
            st.executeUpdate("ALTER TABLE ra_listings ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            throw new SQLException("Migration failed: could not add ra_listings." + column + ": " + e.getMessage(), e);
        }
        logger.info("Migrated ra_listings: added column " + column + ".");
    }

    // A failure stops the migration: the schema version is only stamped once every index exists.
    private void createIndex(Statement st, String name, String target) throws SQLException {
        // MySQL lacks CREATE INDEX IF NOT EXISTS on older versions, so fall back to a plain CREATE.
        try {
            st.executeUpdate("CREATE INDEX IF NOT EXISTS " + name + " ON " + target);
        } catch (SQLException unsupported) {
            try {
                st.executeUpdate("CREATE INDEX " + name + " ON " + target);
            } catch (SQLException e) {
                if (!alreadyExists(e)) {
                    throw e;
                }
            }
        }
    }

    // MySQL error 1061, or SQLite's message.
    private static boolean alreadyExists(SQLException e) {
        if (e.getErrorCode() == 1061) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("already exists");
    }

    static void insertListing(Connection c, Listing l, ListingStatus status) throws SQLException {
        String sql = "INSERT INTO ra_listings "
                + "(id,seller_id,seller_name,item_data,display_name,category,tier,type,price,"
                + "current_bid,top_bidder_id,top_bidder_name,bid_count,created_at,expires_at,status,enchantments) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, l.id().toString());
            ps.setString(2, l.sellerId().toString());
            ps.setString(3, l.sellerName());
            ps.setString(4, ItemSerialization.toBase64(l.itemData()));
            ps.setString(5, l.displayName());
            ps.setString(6, l.category() == null ? null : l.category().toLowerCase(Locale.ROOT));
            ps.setString(7, l.tier() == null ? null : l.tier().toLowerCase(Locale.ROOT));
            ps.setString(8, l.type().name());
            ps.setDouble(9, l.price());
            ps.setDouble(10, l.currentBid());
            ps.setString(11, l.topBidderId() == null ? null : l.topBidderId().toString());
            ps.setString(12, l.topBidderName());
            ps.setInt(13, l.bidCount());
            ps.setLong(14, l.createdAt());
            ps.setLong(15, l.expiresAt());
            ps.setString(16, status.name());
            ps.setString(17, l.enchantmentIndex());
            ps.executeUpdate();
        }
    }

    /** A listing's id and serialized item, as read for indexing. */
    public record StoredItem(String id, byte[] itemData) { }

    /** Active listings with no enchantment index yet, in id order after {@code afterId}. */
    public List<StoredItem> listingsWithoutEnchantmentIndex(String afterId, int limit) throws SQLException {
        List<StoredItem> out = new ArrayList<>();
        String sql = "SELECT id,item_data FROM ra_listings WHERE status='ACTIVE' AND enchantments IS NULL AND id > ? "
                + "ORDER BY id LIMIT ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, afterId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredItem(rs.getString("id"), ItemSerialization.fromBase64(rs.getString("item_data"))));
                }
            }
        }
        return out;
    }

    /** Store enchantment indexes by listing id in one transaction. Rows indexed meanwhile are left alone. */
    public void saveEnchantmentIndexes(java.util.Map<String, String> indexes) throws SQLException {
        if (indexes.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ra_listings SET enchantments=? WHERE id=? AND enchantments IS NULL")) {
                for (var entry : indexes.entrySet()) {
                    ps.setString(1, entry.getValue());
                    ps.setString(2, entry.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    public List<Listing> activeListings() throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_listings WHERE status='ACTIVE' ORDER BY created_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapListing(rs));
            }
        }
        return out;
    }

    /**
     * One page of active listings matching {@code query}, plus the total number of matches. Each
     * ordering ends with the id so listings with equal keys keep a stable position between pages.
     */
    public ListingPage browse(ListingQuery query, int page, int perPage) throws SQLException {
        return browse(query, page, perPage, -1);
    }

    /**
     * As {@link #browse(ListingQuery, int, int)}, but a positive {@code knownTotal} (from an earlier page of
     * the same filters) skips the count. The page itself corrects it when the results end sooner or later.
     */
    public ListingPage browse(ListingQuery query, int page, int perPage, int knownTotal) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params);

        int total = knownTotal;
        if (total <= 0) {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ra_listings " + where)) {
                bindAll(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
        if (total == 0) {
            return ListingPage.empty();
        }

        int size = Math.max(1, perPage);
        int offset = Math.max(0, page) * size;
        List<Listing> rows = new ArrayList<>();
        String sql = "SELECT * FROM ra_listings " + where + " ORDER BY " + orderBy(query.sort())
                + " LIMIT ? OFFSET ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int index = bindAll(ps, params);
            ps.setInt(index++, size);
            ps.setInt(index, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapListing(rs));
                }
            }
        }
        if (knownTotal > 0) {
            if (rows.isEmpty() && offset > 0) {
                return browse(query, page, perPage, -1);
            }
            total = rows.size() < size ? offset + rows.size() : Math.max(total, offset + rows.size());
        }
        return new ListingPage(rows, total);
    }

    private static String buildWhere(ListingQuery query, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE status='ACTIVE'");
        // Ids are stored lowercase; wrapping the column in LOWER() would defeat the index.
        if (query.category() != null) {
            where.append(" AND category = ?");
            params.add(query.category().toLowerCase(Locale.ROOT));
        }
        if (query.tier() != null) {
            where.append(" AND tier = ?");
            params.add(query.tier().toLowerCase(Locale.ROOT));
        }
        if (query.type() != null) {
            where.append(" AND type = ?");
            params.add(query.type().name());
        }
        if (query.search() != null) {
            where.append(" AND (LOWER(display_name) LIKE ? ESCAPE '!'");
            params.add("%" + escapeLike(query.search().toLowerCase(Locale.ROOT)) + "%");
            if (!query.searchItemNames().isEmpty()) {
                where.append(" OR LOWER(display_name) IN (")
                        .append(String.join(",", java.util.Collections.nCopies(query.searchItemNames().size(), "?")))
                        .append(")");
                params.addAll(query.searchItemNames());
            }
            for (String token : query.searchEnchantments()) {
                where.append(" OR enchantments LIKE ? ESCAPE '!'");
                params.add("%" + escapeLike(token) + "%");
            }
            where.append(")");
        }
        return where.toString();
    }

    // '!' escapes LIKE wildcards because a backslash is not portable between MySQL and SQLite literals.
    private static String escapeLike(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (char ch : input.toCharArray()) {
            if (ch == '!' || ch == '%' || ch == '_') {
                out.append('!');
            }
            out.append(ch);
        }
        return out.toString();
    }

    // Fixed whitelist: never interpolates caller input.
    private static String orderBy(SortOrder sort) {
        return switch (sort) {
            case OLDEST -> "created_at ASC, id ASC";
            case PRICE_LOW -> "price ASC, id ASC";
            case PRICE_HIGH -> "price DESC, id ASC";
            case ENDING_SOON -> "expires_at ASC, id ASC";
            case NEWEST -> "created_at DESC, id ASC";
        };
    }

    // Returns the next free index.
    private static int bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            ps.setObject(index++, param);
        }
        return index;
    }

    public List<Listing> activeListingsBySeller(UUID sellerId) throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_listings WHERE status='ACTIVE' AND seller_id=? ORDER BY created_at DESC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sellerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
            }
        }
        return out;
    }

    /** Active auctions this player has bid on, winning or outbid. Only knows bids placed since {@code ra_bids} existed. */
    public List<Listing> activeListingsBidOnBy(UUID bidderId) throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT l.* FROM ra_listings l WHERE l.status='ACTIVE' AND EXISTS ("
                + "SELECT 1 FROM ra_bids b WHERE b.listing_id = l.id AND b.bidder_id = ?) "
                + "ORDER BY l.expires_at ASC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bidderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
            }
        }
        return out;
    }

    /** What the hub shows: active auctions the player bid on, how many of those they lead, and their own active listings. */
    public record HubCounts(int bids, int winning, int selling) { }

    public HubCounts hubCounts(UUID player) throws SQLException {
        String bidOn = "FROM ra_listings l WHERE l.status='ACTIVE' AND l.id IN "
                + "(SELECT b.listing_id FROM ra_bids b WHERE b.bidder_id=?)";
        String sql = "SELECT (SELECT COUNT(*) " + bidOn + "), (SELECT COUNT(*) " + bidOn + " AND l.top_bidder_id=?), "
                + "(SELECT COUNT(*) FROM ra_listings WHERE status='ACTIVE' AND seller_id=?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            String id = player.toString();
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new HubCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
        }
    }

    public void updateCategory(UUID id, String category) throws SQLException {
        String sql = "UPDATE ra_listings SET category=? WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, category.toLowerCase(Locale.ROOT));
            ps.setString(2, id.toString());
            ps.executeUpdate();
        }
    }

    public int countActiveBySeller(UUID sellerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ra_listings WHERE status='ACTIVE' AND seller_id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sellerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int countActive() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ra_listings WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public Optional<Listing> getListing(UUID id) throws SQLException {
        String sql = "SELECT * FROM ra_listings WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapListing(rs)) : Optional.empty();
            }
        }
    }

    public List<Listing> dueExpirations(long now) throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_listings WHERE status='ACTIVE' AND expires_at<=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
            }
        }
        return out;
    }

    /**
     * Prunes closed listings older than {@code cutoff}, orphaned bids and stale offline events.
     * Returns how many listings went. EXPIRED/CANCELLED rows have no closure stamp, so
     * {@code expires_at} stands in for it (at most one listing duration off).
     */
    public int pruneClosed(long cutoff) throws SQLException {
        int removed;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM ra_listings WHERE status IN ('SOLD','EXPIRED','CANCELLED') AND COALESCE(sold_at, expires_at) < ? AND NOT EXISTS (SELECT 1 FROM ra_operations o WHERE o.listing_id=ra_listings.id AND o.state<>'DONE')")) {
            ps.setLong(1, cutoff);
            removed = ps.executeUpdate();
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM ra_bids WHERE NOT EXISTS "
                    + "(SELECT 1 FROM ra_listings l WHERE l.id = ra_bids.listing_id)");
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM ra_events WHERE created_at < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        }
        return removed;
    }

    public void addEvent(UUID player, String type, String item, double amount, long createdAt)
            throws SQLException {
        String sql = "INSERT INTO ra_events (player_id, type, item, amount, created_at) VALUES (?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, type);
            ps.setString(3, item);
            ps.setDouble(4, amount);
            ps.setLong(5, createdAt);
            ps.executeUpdate();
        }
    }

    /** Which of these players have queued events, e.g. ones an exchange on another server left for them. */
    public Set<UUID> playersWithEvents(Collection<UUID> players) throws SQLException {
        Set<UUID> out = new HashSet<>();
        List<UUID> all = new ArrayList<>(players);
        try (Connection c = dataSource.getConnection()) {
            for (int from = 0; from < all.size(); from += CHUNK) {
                List<UUID> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
                String sql = "SELECT DISTINCT player_id FROM ra_events WHERE player_id IN ("
                        + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) {
                        ps.setString(i + 1, chunk.get(i).toString());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(UUID.fromString(rs.getString(1)));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * A player's queued events, oldest first, keyed by row id. Nothing is removed until
     * {@link #deleteEvents}, so a crash in between repeats a notice rather than losing it.
     */
    public java.util.LinkedHashMap<Long, OfflineEvent> peekEvents(UUID player) throws SQLException {
        var out = new java.util.LinkedHashMap<Long, OfflineEvent>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, type, item, amount, created_at FROM ra_events WHERE player_id=? ORDER BY created_at,id")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getLong("id"), new OfflineEvent(rs.getString("type"), rs.getString("item"),
                        rs.getDouble("amount"), rs.getLong("created_at")));
            }
        }
        return out;
    }

    public void deleteEvents(java.util.Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ra_events WHERE id=?")) {
                for (long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    /** Read and delete a player's queued events, oldest first. The caller re-queues on failed delivery. */
    public List<OfflineEvent> drainEvents(UUID player) throws SQLException {
        List<OfflineEvent> out = new ArrayList<>();
        // Delete only the rows actually read. Another transaction may enqueue a payment notice
        // after this snapshot, and another server may try to deliver these same events.
        var selected = new java.util.LinkedHashMap<Long, OfflineEvent>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, type, item, amount, created_at FROM ra_events WHERE player_id=? ORDER BY created_at,id")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) selected.put(rs.getLong("id"), new OfflineEvent(rs.getString("type"), rs.getString("item"),
                            rs.getDouble("amount"), rs.getLong("created_at")));
                }
            }
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ra_events WHERE id=?")) {
                for (var entry : selected.entrySet()) {
                    ps.setLong(1, entry.getKey());
                    if (ps.executeUpdate() == 1) out.add(entry.getValue());
                }
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
        return out;
    }

    public List<CollectionItem> collectionItems(UUID ownerId) throws SQLException {
        List<CollectionItem> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_collection WHERE owner_id=? ORDER BY created_at ASC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ownerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapCollection(rs));
                }
            }
        }
        return out;
    }

    public int countCollectionItems(UUID ownerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ra_collection WHERE owner_id=?")) {
            ps.setString(1, ownerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    static Listing mapListing(ResultSet rs) throws SQLException {
        String topBidder = rs.getString("top_bidder_id");
        String typeStr = rs.getString("type");
        return new Listing(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("seller_id")),
                rs.getString("seller_name"),
                ItemSerialization.fromBase64(rs.getString("item_data")),
                rs.getString("display_name"),
                rs.getString("category"),
                rs.getString("tier"),
                ListingType.fromString(typeStr, ListingType.BIN),
                rs.getDouble("price"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                ListingStatus.valueOf(rs.getString("status")),
                rs.getDouble("current_bid"),
                topBidder == null ? null : UUID.fromString(topBidder),
                rs.getString("top_bidder_name"),
                rs.getInt("bid_count"),
                rs.getString("enchantments"));
    }

    static CollectionItem mapCollection(ResultSet rs) throws SQLException {
        return new CollectionItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("owner_id")),
                ItemSerialization.fromBase64(rs.getString("item_data")),
                CollectionItem.Reason.valueOf(rs.getString("reason")),
                rs.getLong("created_at"));
    }
}
