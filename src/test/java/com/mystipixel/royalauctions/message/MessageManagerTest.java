package com.mystipixel.royalauctions.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Placeholder values are text, never formatting or further placeholders.
class MessageManagerTest {
    @TempDir Path folder;

    private MessageManager manager() throws Exception {
        Files.writeString(folder.resolve("messages.yml"), "prefix: '&6[AH] '\n"
                + "sold: '{prefix}&e{item} &7sold for &a{amount}'\n");
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        return new MessageManager(plugin);
    }

    private static boolean anyGreen(Component component) {
        if (NamedTextColor.GREEN.equals(component.color()) && component instanceof net.kyori.adventure.text.TextComponent t
                && t.content().contains("Staff")) return true;
        return component.children().stream().anyMatch(MessageManagerTest::anyGreen);
    }

    @Test void itemNamesCannotInjectFormattingOrPlaceholders() throws Exception {
        Component message = manager().component("sold", MessageManager.map(
                "item", "&a[Staff] {amount}", "amount", "5 coins"));
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        assertEquals("[AH] &a[Staff] {amount} sold for 5 coins", plain);
        assertFalse(anyGreen(message), "the item name must keep the template's colour, not its own codes");
    }

    @Test void unknownPlaceholdersAreLeftAsWritten() throws Exception {
        String plain = PlainTextComponentSerializer.plainText().serialize(
                manager().component("sold", MessageManager.map("amount", "5")));
        assertEquals("[AH] {item} sold for 5", plain);
    }
}
