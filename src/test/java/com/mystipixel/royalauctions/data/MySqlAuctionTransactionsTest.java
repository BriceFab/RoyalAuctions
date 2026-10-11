package com.mystipixel.royalauctions.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.UUID;

// Same contract suite against a disposable loopback MySQL instance. Never point at production.
@EnabledIfEnvironmentVariable(named = "RA_TEST_MYSQL_PORT", matches = "[0-9]+")
class MySqlAuctionTransactionsTest extends AuctionTransactionsTest {
    private String database;
    private String rootUrl() { return "jdbc:mysql://127.0.0.1:" + System.getenv("RA_TEST_MYSQL_PORT") + "/"; }
    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(rootUrl() + database + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
    }
    @Override @BeforeEach void open() throws Exception {
        if (database == null) {
            database = "ra_tx_test_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = connect(""); var s = c.createStatement()) { s.executeUpdate("CREATE DATABASE " + database); }
        }
        super.open();
    }
    @Override YamlConfiguration config() {
        var config = new YamlConfiguration(); config.set("type", "MYSQL");
        config.set("mysql.host", "127.0.0.1"); config.set("mysql.port", Integer.parseInt(System.getenv("RA_TEST_MYSQL_PORT")));
        config.set("mysql.database", database); config.set("mysql.username", "root"); config.set("mysql.password", "");
        config.set("mysql.properties", "useSSL=false&allowPublicKeyRetrieval=true");
        return config;
    }
    @Override Connection connection() throws SQLException { return connect(database); }
    @Override void failInserts() throws SQLException {
        sql("CREATE TRIGGER fail_delivery BEFORE INSERT ON ra_collection FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'");
    }
    @Override void failBegin() throws SQLException {
        sql("CREATE TRIGGER fail_begin BEFORE UPDATE ON ra_operations FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'");
    }
    @Test void refusesNontransactionalAuctionTables() throws Exception {
        sql("ALTER TABLE ra_operations ENGINE=MyISAM");
        SQLException error = Assertions.assertThrows(SQLException.class, () -> tx.init());
        Assertions.assertTrue(error.getMessage().contains("requires InnoDB"));
    }
    @Test void columnCheckIgnoresOtherDatabasesOnTheSameServer() throws Exception {
        // Another RoyalAuctions schema that already has the tier column must not stop ours getting it.
        String other = database + "_other", upgraded = database + "_old";
        try (var c = connect(""); var s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE " + other); s.executeUpdate("CREATE DATABASE " + upgraded);
        }
        try {
            try (var c = connect(other); var s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE ra_listings (id CHAR(36) PRIMARY KEY, tier VARCHAR(32))");
            }
            try (var c = connect(upgraded); var s = c.createStatement()) { s.executeUpdate(SchemaMigrationTest.PRE_BIDDING_LISTINGS); }
            var config = config(); config.set("mysql.database", upgraded);
            var upgradedDb = new AuctionDatabase(folder.toFile(), config, java.util.logging.Logger.getAnonymousLogger());
            try { upgradedDb.init(); } finally { upgradedDb.close(); }
            try (var c = connect(upgraded); var rs = c.getMetaData().getColumns(upgraded, null, "ra_listings", "tier")) {
                Assertions.assertTrue(rs.next(), "tier must be added to the upgraded database");
            }
        } finally {
            try (var c = connect(""); var s = c.createStatement()) {
                s.executeUpdate("DROP DATABASE IF EXISTS " + other); s.executeUpdate("DROP DATABASE IF EXISTS " + upgraded);
            }
        }
    }
    @Override @AfterEach void close() {
        if (db != null) super.close();
        if (database == null) return;
        try (var c = connect(""); var s = c.createStatement()) { s.executeUpdate("DROP DATABASE " + database); }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
}
