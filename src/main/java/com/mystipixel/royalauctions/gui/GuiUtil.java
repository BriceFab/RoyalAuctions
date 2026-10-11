package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public final class GuiUtil {

    // GUI rendering is main-thread only, so a shared formatter is fine.
    private static final DecimalFormat COMMA = new DecimalFormat("#,##0.##");

    private GuiUtil() {
    }

    /** Comma-grouped amount ("1,650,000,000"), trimming needless decimals. */
    public static String comma(double amount) {
        return COMMA.format(amount);
    }

    public static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.color(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> components = new ArrayList<>(lore.size());
                for (String line : lore) {
                    components.add(Text.color(line));
                }
                meta.lore(components);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack button(Material material, String name, String... lore) {
        return button(material, name, List.of(lore));
    }

    public static ItemStack filler(Material material) {
        return button(material, " ", List.of());
    }

    public static ItemStack appendLore(ItemStack item, List<String> extra) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        for (String line : extra) {
            lore.add(Text.color(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Remaining time, e.g. "1d 3h", "45m", "12s". */
    public static String timeLeft(long millis) {
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
}
