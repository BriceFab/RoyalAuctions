package com.mystipixel.royalauctions.message;

import com.mystipixel.royalauctions.gui.GuiManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The text read from the bundled messages.yml is exactly what the code used to build itself.
class MessageRenderTest {

    @TempDir Path folder;

    private MessageManager bundled() throws Exception {
        try (InputStream in = MessageRenderTest.class.getResourceAsStream("/messages.yml")) {
            Files.write(folder.resolve("messages.yml"), in.readAllBytes());
        }
        return manager();
    }

    private MessageManager manager() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("messages.yml")).thenAnswer(i -> MessageRenderTest.class.getResourceAsStream("/messages.yml"));
        return new MessageManager(plugin);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void helpIsTheOldHelp() throws Exception {
        MessageManager m = bundled();
        assertEquals(List.of(
                "&6&lAuction House",
                "&e/ah &7- open the Auction House menu",
                "&e/ah browse &7- browse all listings",
                "&e/ah bids &7- auctions you've bid on",
                "&e/ah <player> &7- view a player's auctions",
                "&e/ah sell &7- open the Create Auction menu",
                "&e/ah search <query> &7- search by name or enchantment",
                "&e/ah listings &7- manage your listings",
                "&e/ah collect &7- claim purchases & returns"),
                m.lines("help", "search-help", m.text("search.help")));
    }

    @Test
    void categoryInspectIsTheOldOutput() throws Exception {
        MessageManager m = bundled();
        assertEquals("&cHold the item you want to inspect.", m.text("inspect.hold-item"));
        assertEquals("&6&lItem Category", m.text("inspect.header"));
        assertEquals("&7Item:     &f" + "minecraft:diamond_sword", m.text("inspect.item", "item", "minecraft:diamond_sword"));
        assertEquals("&7Category: &f" + "&cWeapons" + " &8(" + "weapons" + ")",
                m.text("inspect.category", "category", "&cWeapons", "id", "weapons"));
        assertEquals("&7Matched:  &f" + "tag WEAPON", m.text("inspect.matched", "reason", "tag WEAPON"));
        assertEquals("&7Tier:     &f" + "&8none", m.text("inspect.tier", "tier", m.text("inspect.no-tier")));
        assertEquals(List.of("&8Nothing matched this item — pin it under a category's",
                "&8match.items: to place it explicitly."), m.lines("inspect.unmatched"));

        CommandSender sender = mock(CommandSender.class);
        m.sendStyled(sender, "inspect.tier", "tier", "&d&lEPIC");
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(sent.capture());
        assertEquals("Tier:     EPIC", plain(sent.getValue()), "a tier's colour codes are applied, not shown");
    }

    @Test
    void unknownPlayerIsTheOldLine() throws Exception {
        CommandSender sender = mock(CommandSender.class);
        bundled().send(sender, "general.unknown-player", "player", "Steve");
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(sent.capture());
        assertEquals("No player named Steve has played here.", plain(sent.getValue()));
    }

    @Test
    void recoveryOutputIsTheOldOutput() throws Exception {
        MessageManager m = bundled();
        assertEquals("Console: /ah recovery resolve <operation-id> <applied|not-applied> confirm", m.text("recovery.resolve-usage"));
        assertEquals("Invalid operation ID.", m.text("recovery.invalid-id"));
        UUID id = UUID.randomUUID();
        assertEquals("Reconciled " + id, m.text("recovery.reconciled", "operation", id.toString()));
        assertEquals("Usage: /ah recovery [page] or /ah recovery resolve <id> <applied|not-applied> confirm", m.text("recovery.usage"));
        assertEquals("Pending auction operations, page " + 2 + " (up to 50): " + 7,
                m.text("recovery.header", "page", "2", "count", "7"));
        UUID player = UUID.randomUUID();
        String old = id + " " + "BUY" + " " + "APPLYING" + " player=" + player + " amount=" + 12.5 + " listing=" + null
                + " collection=" + null + " origin=" + "w1" + " updated=" + java.time.Instant.ofEpochMilli(0);
        assertEquals(old, m.text("recovery.entry", "id", id.toString(), "kind", "BUY", "state", "APPLYING",
                "player", player.toString(), "amount", String.valueOf(12.5), "listing", "null", "collection", "null",
                "origin", "w1", "updated", java.time.Instant.ofEpochMilli(0).toString()));
        assertEquals("Recovery refused: The originating server is still active. Stop that process before reconciliation.",
                m.text("recovery.refused", "reason", m.text("exchange.reasons.origin-active")));
    }

    @Test
    void refusalReasonsAreTheOldSentences() throws Exception {
        MessageManager m = bundled();
        Map<String, String> old = Map.of(
                "payment-listing-gone", "The payment's listing no longer exists.",
                "unknown-operation", "Unknown operation.",
                "not-interrupted", "Only interrupted APPLYING operations older than two minutes can be reconciled.",
                "origin-active", "The originating server is still active. Stop that process before reconciliation.",
                "already-pending", "An operation is already pending for this item or auction.",
                "invalid-amount", "Invalid money amount.");
        old.forEach((key, text) -> assertEquals(text, m.text("exchange.reasons." + key), key));
        assertEquals("The bid must meet the current minimum: " + 110.0,
                m.text("exchange.reasons.bid-too-low", "minimum", String.valueOf(110.0)));
        assertEquals("an item", m.text("away.unknown-item"));
        assertEquals("-", m.text("gui.no-leader"));
    }

    @Test
    void menuLabelsAreStyledAsBefore() throws Exception {
        GuiManager gui = new GuiManager(null, null, null, null, null, bundled(), null, null, null);
        assertEquals("&e> " + "&fNo Filter", gui.t("format.selected", "label", gui.t("format.filter", "label", gui.t("no-filter"))));
        assertEquals("&8  " + "No Filter", gui.t("format.unselected", "label", "No Filter"));
        assertEquals("&7" + "None", gui.t("format.no-tier", "label", gui.t("none")));
        assertEquals("&a" + "1 Day", gui.t("format.duration-selected", "label", "1 Day"));
        assertEquals("&e" + "1 Day", gui.t("format.duration", "label", "1 Day"));
        assertEquals(36 + "h", gui.t("time.duration", "hours", "36"));
    }

    @Test
    void timeLeftIsTheOldFormat() throws Exception {
        GuiManager gui = new GuiManager(null, null, null, null, null, bundled(), null, null, null);
        for (long millis : new long[] {-5, 0, 999, 1_000, 59_999, 60_000, 61_000, 3_599_000, 3_600_000, 3_661_000,
                86_399_000, 86_400_000, 90_061_000, 864_000_000L}) {
            assertEquals(oldTimeLeft(millis), gui.timeLeft(millis), millis + " ms");
        }
    }

    // the format GuiUtil.timeLeft built in code
    private static String oldTimeLeft(long millis) {
        if (millis <= 0) {
            return "expired";
        }
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    @Test
    void anOldServerFileGetsTheNewKeysFromTheJarAndIsNotRewritten() throws Exception {
        String old = "prefix: '&6[AH] '\n"
                + "general:\n"
                + "  no-permission: '{prefix}&cNope.'\n";
        Files.writeString(folder.resolve("messages.yml"), old);
        MessageManager m = manager();
        assertEquals("&6[AH] &cNope.", m.text("general.no-permission"));
        assertEquals("&cHold the item you want to inspect.", m.text("inspect.hold-item"));
        assertEquals(9, m.lines("help").size());
        assertEquals("{seconds}s", m.text("gui.time.seconds"));
        assertEquals("&6[AH] &eAn auction payment is on hold. Staff can check its status; do not repeat the transaction.",
                m.text("payment-pending"));
        assertEquals(old, Files.readString(folder.resolve("messages.yml")));

        CommandSender sender = mock(CommandSender.class);
        m.sendLines(sender, "inspect.unmatched");
        verify(sender, times(2)).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
    }
}
