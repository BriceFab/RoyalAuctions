package com.mystipixel.royalauctions.gui.menu;

import com.mystipixel.royalauctions.hooks.EcoHook;
import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses the EcoMenus inline item syntax used in {@code gui/*.yml}, e.g.
 * <pre>item: oak_sign hide_attributes name:"&amp;eSearch"</pre>
 * The first token is an item lookup (vanilla or {@code ecoitems:...}); the rest are flags
 * ({@code hide_enchants}, {@code hide_attributes}) and {@code key:"value"} modifiers ({@code name}).
 */
public final class ItemSpec {

    private final String lookupId;
    private final String rawName;   // nullable
    private final String texture;   // base64 head texture, nullable
    private final String head;      // player-head owner name/%placeholder%, nullable
    private final boolean hideEnchants;
    private final boolean hideAttributes;

    private ItemSpec(String lookupId, String rawName, String texture, String head,
                     boolean hideEnchants, boolean hideAttributes) {
        this.lookupId = lookupId;
        this.rawName = rawName;
        this.texture = texture;
        this.head = head;
        this.hideEnchants = hideEnchants;
        this.hideAttributes = hideAttributes;
    }

    public static ItemSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ItemSpec("stone", null, null, null, false, false);
        }
        List<String> tokens = tokenize(raw.trim());
        String lookup = tokens.isEmpty() ? "stone" : tokens.get(0);
        String name = null;
        String texture = null;
        String head = null;
        boolean hideEnch = false;
        boolean hideAttr = false;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equalsIgnoreCase("hide_enchants")) {
                hideEnch = true;
            } else if (t.equalsIgnoreCase("hide_attributes")) {
                hideAttr = true;
            } else if (t.regionMatches(true, 0, "name:", 0, 5)) {
                name = stripQuotes(t.substring(5));
            } else if (t.regionMatches(true, 0, "texture:", 0, 8)) {
                texture = stripQuotes(t.substring(8));
            } else if (t.regionMatches(true, 0, "head:", 0, 5)) {
                head = stripQuotes(t.substring(5));
            }
        }
        return new ItemSpec(lookup, name, texture, head, hideEnch, hideAttr);
    }

    public ItemStack build(EcoHook eco, Map<String, String> placeholders, List<String> lore, MenuAction action) {
        return build(eco, placeholders, Map.of(), lore, action, action);
    }

    /**
     * Builds the stack with separate left/right click actions. A lore line that is exactly
     * {@code %key%} for a key in {@code listPlaceholders} becomes one lore line per entry.
     */
    public ItemStack build(EcoHook eco, Map<String, String> placeholders,
                           Map<String, List<String>> listPlaceholders, List<String> lore,
                           MenuAction leftAction, MenuAction rightAction) {
        ItemStack item = eco.resolve(apply(lookupId, placeholders), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (rawName != null) {
                meta.displayName(Text.color(apply(rawName, placeholders)));
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    List<String> expanded = expand(line, listPlaceholders);
                    if (expanded != null) {
                        for (String sub : expanded) {
                            lines.add(Text.color(apply(sub, placeholders)));
                        }
                    } else {
                        lines.add(Text.color(apply(line, placeholders)));
                    }
                }
                meta.lore(lines);
            }
            if (hideEnchants) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(MenuKeys.ACTION, PersistentDataType.STRING, leftAction.name());
            meta.getPersistentDataContainer().set(MenuKeys.ACTION_RIGHT, PersistentDataType.STRING,
                    rightAction.name());
            applyHeadTexture(item, meta, placeholders);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyHeadTexture(ItemStack item, ItemMeta meta, Map<String, String> placeholders) {
        if (item.getType() != Material.PLAYER_HEAD || !(meta instanceof SkullMeta skull)) {
            return;
        }
        try {
            if (texture != null && !texture.isBlank()) {
                com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
            } else if (head != null && !head.isBlank()) {
                // A name-only profile: getOfflinePlayer(String) can block the main thread on a
                // Mojang lookup for a name the server has never seen.
                skull.setPlayerProfile(Bukkit.createProfile(apply(head, placeholders)));
            }
        } catch (Throwable ignored) {
            // a malformed texture must never break the menu
        }
    }

    private static List<String> expand(String line, Map<String, List<String>> listPlaceholders) {
        if (line == null || listPlaceholders.isEmpty()) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.length() < 3 || !trimmed.startsWith("%") || !trimmed.endsWith("%")) {
            return null;
        }
        return listPlaceholders.get(trimmed.substring(1, trimmed.length() - 1));
    }

    // Keeps quoted segments together, so name:"a b c" stays one token.
    private static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                cur.append(ch);
            } else if (ch == ' ' && !inQuotes) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("%" + e.getKey() + "%", e.getValue());
        }
        return result;
    }
}
