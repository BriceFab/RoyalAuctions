package com.mystipixel.royalauctions.category;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchRuleTest {

    @Test void matchesItemsByTheNamespaceOfTheirOwnId() {
        var config = new YamlConfiguration();
        config.set("item-namespaces", java.util.List.of("Minecraft"));
        var rule = MatchRule.fromConfig(config, warning -> {});

        assertEquals("item-namespace 'minecraft'", rule.reason(item(Material.STICK), null));
    }

    @Test void ignoresItemsFromOtherNamespaces() {
        var config = new YamlConfiguration();
        config.set("item-namespaces", java.util.List.of("scandicraft"));
        var rule = MatchRule.fromConfig(config, warning -> {});

        assertNull(rule.reason(item(Material.STICK), null));
    }

    private static ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }
}
