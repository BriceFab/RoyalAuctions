package com.mystipixel.royalauctions.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnchantmentSearchTest {
    @Test void parsesKeysSpacesAndExactLevels() {
        var search = new EnchantmentSearch();
        assertEquals(Set.of("sharpness="), search.matching(" SHARPNESS "));
        assertEquals(Set.of("minecraft:sharpness=5|"), search.matching("minecraft:sharpness V"));
        assertEquals(Set.of("sharpness=5|"), search.matching("sharpness 05"));
        assertEquals(Set.of("silk_touch="), search.matching("silk touch"));
        assertEquals(Set.of(), search.matching(null));
        assertEquals(Set.of(), search.matching("  "));
        assertEquals(Set.of(), search.matching("sharpness 999999999999999999"));
    }

    @Test void translatedNamesIgnoreCaseAndAccentsAndReloadReplacesThem() {
        var search = new EnchantmentSearch();
        search.load(List.of(Map.of("enchantment.minecraft.sharpness", "Tranchant",
                "enchantment.minecraft.unbreaking", "Solidité",
                "enchantment.minecraft.sharpness.desc", "ignore me")));
        assertEquals(Set.of("solidite=3|", "minecraft:unbreaking=3|"), search.matching("SOLIDITE III"));
        assertEquals(Set.of("tranch=5|", "minecraft:sharpness=5|"), search.matching("tranch V"));
        search.load(List.of());
        assertEquals(Set.of("solidite="), search.matching("solidité"));
    }

    @Test void indexesAppliedAndStoredEnchantmentsUsingTheirRealKeys() {
        // Enchantment's static constants resolve through Paper's registry access.
        var access = mock(io.papermc.paper.registry.RegistryAccess.class, RETURNS_DEEP_STUBS);
        try (var registries = mockStatic(io.papermc.paper.registry.RegistryAccess.class)) {
            registries.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
            var registry = access.getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
            doReturn(registry).when(access).getRegistry(any(io.papermc.paper.registry.RegistryKey.class));
            doReturn(null).when(registry).getOrThrow(any(net.kyori.adventure.key.Key.class));
            var sharpness = mock(org.bukkit.enchantments.Enchantment.class);
            when(sharpness.getKey()).thenReturn(new org.bukkit.NamespacedKey("minecraft", "sharpness"));
            var item = mock(ItemStack.class);
            var equipment = mock(ItemMeta.class);
            when(item.getItemMeta()).thenReturn(equipment);
            when(equipment.getEnchants()).thenReturn(Map.of(sharpness, 5));
            assertEquals("|minecraft:sharpness=5|", EnchantmentSearch.index(item));
            var book = mock(EnchantmentStorageMeta.class);
            when(item.getItemMeta()).thenReturn(book);
            when(book.getEnchants()).thenReturn(Map.of());
            when(book.getStoredEnchants()).thenReturn(Map.of(sharpness, 4));
            assertEquals("|minecraft:sharpness=4|", EnchantmentSearch.index(item));
            verify(item, never()).setItemMeta(any());
        }
    }

    @Test void emptyEquipmentAndBooksProduceNoTokensAndDoNotChangeMetadata() {
        var item = mock(ItemStack.class);
        var equipment = mock(ItemMeta.class);
        when(item.getItemMeta()).thenReturn(equipment);
        when(equipment.getEnchants()).thenReturn(Map.of());
        assertEquals("", EnchantmentSearch.index(item));
        var book = mock(EnchantmentStorageMeta.class);
        when(item.getItemMeta()).thenReturn(book);
        when(book.getEnchants()).thenReturn(Map.of());
        when(book.getStoredEnchants()).thenReturn(Map.of());
        assertEquals("", EnchantmentSearch.index(item));
        verify(book).getStoredEnchants();
        verify(item, never()).setItemMeta(any());
    }
}
