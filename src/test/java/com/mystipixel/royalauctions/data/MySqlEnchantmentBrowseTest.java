package com.mystipixel.royalauctions.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.UUID;

/** Same contract suite against a disposable loopback MySQL instance. Never point at production. */
@EnabledIfEnvironmentVariable(named = "RA_TEST_MYSQL_PORT", matches = "[0-9]+")
class MySqlEnchantmentBrowseTest extends EnchantmentBrowseTest {
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
    @Override @AfterEach void close() {
        if (db != null) super.close();
        if (database == null) return;
        try (var c = connect(""); var s = c.createStatement()) { s.executeUpdate("DROP DATABASE " + database); }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
}
