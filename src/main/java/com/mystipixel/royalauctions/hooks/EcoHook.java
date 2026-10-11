package com.mystipixel.royalauctions.hooks;

import com.willfp.eco.core.items.CustomItem;
import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Optional eco integration. eco types are only touched after the {@link #present} guard, so without
 * eco the JVM never links the {@code com.willfp.*} references.
 */
public final class EcoHook {

    private final boolean present;

    public EcoHook() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    public boolean isPresent() {
        return present;
    }

    /**
     * The eco id of a custom item (e.g. {@code enchanted_diamond}), or null for non-eco items.
     * {@link #namespaceOf} gives the plugin it came from.
     */
    public String customItemId(ItemStack item) {
        if (!present || item == null) {
            return null;
        }
        try {
            if (!Items.isCustomItem(item)) {
                return null;
            }
            CustomItem custom = Items.getCustomItem(item);
            if (custom == null || custom.getKey() == null) {
                return null;
            }
            return custom.getKey().getKey();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Every custom item registered with eco, keyed by full id ({@code ecoitems:brazen_sword}). Empty
     * if eco is absent or its API changed, since the startup audit must never break startup.
     */
    public Map<String, ItemStack> allCustomItems() {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        if (!present) {
            return out;
        }
        try {
            for (CustomItem custom : Items.getCustomItems()) {
                if (custom == null || custom.getKey() == null) {
                    continue;
                }
                ItemStack stack = custom.getItem();
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                out.put((custom.getKey().getNamespace() + ":" + custom.getKey().getKey())
                        .toLowerCase(Locale.ROOT), stack);
            }
        } catch (Throwable ignored) {
            // The audit is optional, so report nothing.
        }
        return out;
    }

    /**
     * Resolves a menu item lookup id. Vanilla ids go straight to Bukkit (eco's lookup doesn't reliably
     * handle {@code minecraft:}); others go through eco. Falls back to STONE so a typo can't break a menu.
     */
    public ItemStack resolve(String id, int amount) {
        Material vanilla = vanillaMaterial(id);
        if (vanilla != null) {
            return new ItemStack(vanilla, amount);
        }
        if (present) {
            try {
                TestableItem test = Items.lookup(id);
                ItemStack item = test.getItem();
                if (item != null && !item.getType().isAir()) {
                    item = item.clone();
                    item.setAmount(amount);
                    return item;
                }
            } catch (Throwable ignored) {
                // unknown id: fall through
            }
        }
        return new ItemStack(Material.STONE, amount);
    }

    private Material vanillaMaterial(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String raw = id;
        if (id.contains(":")) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!ns.equalsIgnoreCase("minecraft")) {
                return null;
            }
            raw = id.substring(id.indexOf(':') + 1);
        }
        Material material = Material.matchMaterial(raw);
        return (material != null && !material.isAir()) ? material : null;
    }

    /** The eco namespace of an item (e.g. {@code talismans}, {@code ecoitems}), or null for non-eco items. */
    public String namespaceOf(ItemStack item) {
        if (!present || item == null) {
            return null;
        }
        try {
            if (!Items.isCustomItem(item)) {
                return null;
            }
            CustomItem custom = Items.getCustomItem(item);
            if (custom == null || custom.getKey() == null) {
                return null;
            }
            return custom.getKey().getNamespace();
        } catch (Throwable ignored) {
            // Any eco API hiccup must never block a listing.
            return null;
        }
    }
}
