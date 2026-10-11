package com.mystipixel.royalauctions.search;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EnchantmentIndexTest {

    @Test void indexesAppliedAndStoredEnchantmentsSorted() {
        // Enchantment's static constants resolve through Paper's registry, which needs a server.
        var access = mock(RegistryAccess.class, RETURNS_DEEP_STUBS);
        try (var registries = mockStatic(RegistryAccess.class)) {
            registries.when(RegistryAccess::registryAccess).thenReturn(access);
            var registry = access.getRegistry(RegistryKey.ENCHANTMENT);
            doReturn(registry).when(access).getRegistry(any(RegistryKey.class));
            doReturn(null).when(registry).getOrThrow(any(Key.class));
            Enchantment sharpness = enchantment("sharpness");
            Enchantment unbreaking = enchantment("unbreaking");

            ItemStack item = mock(ItemStack.class);
            ItemMeta sword = mock(ItemMeta.class);
            when(item.hasItemMeta()).thenReturn(true);
            when(item.getItemMeta()).thenReturn(sword);
            when(sword.getEnchants()).thenReturn(Map.of(unbreaking, 3, sharpness, 5));
            assertEquals("|minecraft:sharpness=5||minecraft:unbreaking=3|", EnchantmentIndex.of(item));

            EnchantmentStorageMeta book = mock(EnchantmentStorageMeta.class);
            when(item.getItemMeta()).thenReturn(book);
            when(book.getEnchants()).thenReturn(Map.of());
            when(book.getStoredEnchants()).thenReturn(Map.of(sharpness, 4));
            assertEquals("|minecraft:sharpness=4|", EnchantmentIndex.of(item));
            verify(item, never()).setItemMeta(any());
        }
    }

    @Test void plainItemsHaveAnEmptyIndex() {
        ItemStack item = mock(ItemStack.class);
        when(item.hasItemMeta()).thenReturn(false);
        assertEquals("", EnchantmentIndex.of(item));

        ItemMeta meta = mock(ItemMeta.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getEnchants()).thenReturn(Map.of());
        assertEquals("", EnchantmentIndex.of(item));
    }

    private static Enchantment enchantment(String key) {
        Enchantment enchantment = mock(Enchantment.class);
        when(enchantment.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return enchantment;
    }
}
