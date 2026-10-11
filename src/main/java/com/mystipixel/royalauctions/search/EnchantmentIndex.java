package com.mystipixel.royalauctions.search;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The enchantments of a listed item as search text, stored in {@code ra_listings.enchantments} so a
 * search never has to decode items. Format: {@code |minecraft:sharpness=5||minecraft:unbreaking=3|},
 * sorted. Applied enchantments and a book's stored ones both count; lore is ignored.
 */
public final class EnchantmentIndex {

    private EnchantmentIndex() {
    }

    public static String of(ItemStack item) {
        if (!item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        Set<String> tokens = new TreeSet<>();
        add(tokens, meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta book) {
            add(tokens, book.getStoredEnchants());
        }
        return String.join("", tokens);
    }

    private static void add(Set<String> tokens, Map<Enchantment, Integer> enchants) {
        enchants.forEach((enchantment, level) -> tokens.add(token(enchantment.getKey().toString(), level)));
    }

    static String token(String key, int level) {
        return "|" + key + "=" + level + "|";
    }
}
