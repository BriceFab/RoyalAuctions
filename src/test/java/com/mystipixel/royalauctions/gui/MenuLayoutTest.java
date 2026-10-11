package com.mystipixel.royalauctions.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static YamlConfiguration load(String path) throws IOException {
        try (InputStream in = MenuLayoutTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is not bundled");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
