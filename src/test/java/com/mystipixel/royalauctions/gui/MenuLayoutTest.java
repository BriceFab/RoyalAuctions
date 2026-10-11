package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuLayoutTest {

    private static final List<String> MENUS = List.of("bid", "bids", "browse", "collection", "confirm-auction",
            "confirm-bid", "confirm-cancel", "confirm-purchase", "create", "duration", "hub", "manage", "seller");

    // two fixed buttons on one slot: the later one hides the earlier
    @Test
    void shippedMenusNeverPutTwoButtonsOnOneSlot() throws IOException {
        List<String> clashes = new ArrayList<>();
        for (String menu : MENUS) {
            YamlConfiguration yaml = load("gui/" + menu + ".yml");
            Map<String, String> taken = new HashMap<>();
            for (Map<?, ?> slot : yaml.getMapList("slots")) {
                if (!(slot.get("row") instanceof Number row) || !(slot.get("column") instanceof Number column)) {
                    continue;
                }
                String at = row.intValue() + "," + column.intValue();
                String id = String.valueOf(slot.get("id"));
                String previous = taken.putIfAbsent(at, id);
                if (previous != null) {
                    clashes.add(menu + ".yml row " + row + " column " + column + ": " + previous + " and " + id);
                }
            }
        }
        assertTrue(clashes.isEmpty(), "buttons sharing a slot: " + clashes);
    }

    @Test
    void everyShippedMenuHasATitleAndRows() throws IOException {
        for (String menu : MENUS) {
            YamlConfiguration yaml = load("gui/" + menu + ".yml");
            assertTrue(yaml.isString("title"), menu + ".yml title");
            assertTrue(yaml.isInt("rows"), menu + ".yml rows");
            assertFalse(yaml.getMapList("slots").isEmpty(), menu + ".yml slots");
        }
    }

    @Test
    void createMenuShipsTheTypeMaterialsTheCodeUsedToPick() throws IOException {
        YamlConfiguration create = load("gui/create.yml");
        assertEquals("GOLD_NUGGET", create.getString("type-materials.auction"));
        assertEquals("EMERALD", create.getString("type-materials.bin"));
    }

    // an older server file falls back to the jar for its title, rows and text, never for buttons
    @Test
    void aMenuFileWithoutTitleOrTextUsesTheBundledOnes(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("create.yml");
        Files.writeString(file, "# emptied by the owner\n");
        MenuTemplate template = MenuTemplate.load(file.toFile(), load("gui/create.yml"));
        assertEquals("&8Create Auction", template.title(java.util.Map.of()));
        assertEquals(45, template.size());
        assertEquals("EMERALD", template.text("type-materials.bin"));
        assertEquals(-1, template.slotOf("type"), "buttons are not copied from the jar");

        Path hub = folder.resolve("hub.yml");
        Files.writeString(hub, "title: '&8Mine'\n");
        MenuTemplate own = MenuTemplate.load(hub.toFile(), load("gui/hub.yml"));
        assertEquals("&8Mine", own.title(java.util.Map.of()));
        assertEquals(36, own.size(), "rows come from the shipped hub.yml");
    }

    private static YamlConfiguration load(String path) throws IOException {
        try (InputStream in = MenuLayoutTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is not bundled");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
