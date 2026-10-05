package com.mystipixel.royalauctions.gui.menu;

import com.mystipixel.royalauctions.hooks.EcoHook;
import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A menu loaded from a {@code gui/*.yml} file, authored in the EcoMenus dialect:
 *
 * <pre>
 * title: "&amp;8Auction House"
 * rows: 6
 * mask:
 *   items:
 *     - gray_stained_glass_pane
 *   pattern:
 *     - "111111111"
 *     - "000000000"     # 0 / letters = dynamic regions, left empty for the GUI to fill
 *     - "CCCCCCCCC"
 * regions:
 *   content-slots: "0"
 *   category-slots: "C"
 * slots:
 *   - id: search
 *     item: oak_sign name:"&amp;eSearch"
 *     lore: [ "&amp;7Current: &amp;f%search%" ]
 *     location:
 *       row: 1
 *       column: 1
 *     left-click:
 *       - id: ah_search
 * </pre>
 *
 * Positions are 1-based {@code row}/{@code column} (converted to 0-based indices here). Click effects
 * are authored as {@code id:}s and mapped onto {@link MenuAction}, which is stamped on the rendered
 * item's PDC so the GUI classes can switch on it. Placeholders are {@code %percent%} style.
 */
public final class MenuTemplate {

    /**
     * One hand-placed slot from the {@code slots:} list. {@code left}/{@code right} come from the
     * {@code left-click:} / {@code right-click:} effect lists; when a slot declares no right-click
     * effects, right simply mirrors left (so buttons that don't care about the click type are
     * unaffected).
     */
    private record ConfiguredSlot(String id, int index, ItemSpec item, List<String> lore,
                                  MenuAction left, MenuAction right) {
    }

    private final String title;
    private final int rows;
    private final ItemStack[] mask;                      // per-index filler (null = dynamic region / empty)
    private final Map<String, List<Integer>> regions;    // region name -> indices
    private final Map<String, ConfiguredSlot> slots;     // slot id -> slot (unnamed slots get synthetic ids)
    private final FileConfiguration source;

    private MenuTemplate(String title, int rows, ItemStack[] mask, Map<String, List<Integer>> regions,
                         Map<String, ConfiguredSlot> slots, FileConfiguration source) {
        this.title = title;
        this.rows = rows;
        this.mask = mask;
        this.regions = regions;
        this.slots = slots;
        this.source = source;
    }

    // ------------------------------------------------------------------ loading

    public static MenuTemplate load(File file, String defaultTitle, int defaultRows) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String title = cfg.getString("title", defaultTitle);
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", defaultRows)));
        int size = rows * 9;

        // --- mask: filler materials + pattern ---
        ItemStack[] mask = new ItemStack[size];
        List<String> maskItems = cfg.getStringList("mask.items");
        List<String> pattern = cfg.getStringList("mask.pattern");
        Map<Character, List<Integer>> byChar = new LinkedHashMap<>();
        for (int r = 0; r < pattern.size() && r < rows; r++) {
            String line = pattern.get(r);
            for (int c = 0; c < 9 && c < line.length(); c++) {
                int index = r * 9 + c;
                char ch = line.charAt(c);
                byChar.computeIfAbsent(ch, k -> new ArrayList<>()).add(index);
                if (ch >= '1' && ch <= '9') {
                    int matIdx = ch - '1';
                    if (matIdx < maskItems.size()) {
                        mask[index] = filler(maskItems.get(matIdx));
                    }
                }
                // '0' and letters stay null: dynamic regions the GUI fills at render time.
            }
        }

        // --- regions: name -> the mask character that marks it ---
        Map<String, List<Integer>> regions = new LinkedHashMap<>();
        ConfigurationSection regionSec = cfg.getConfigurationSection("regions");
        if (regionSec != null) {
            for (String name : regionSec.getKeys(false)) {
                String marker = regionSec.getString(name, "0");
                if (marker != null && !marker.isEmpty()) {
                    regions.put(name, byChar.getOrDefault(marker.charAt(0), List.of()));
                }
            }
        }

        // --- slots: hand-placed buttons ---
        Map<String, ConfiguredSlot> slots = new LinkedHashMap<>();
        List<Map<?, ?>> rawSlots = cfg.getMapList("slots");
        int synthetic = 0;
        for (Map<?, ?> raw : rawSlots) {
            Object itemObj = raw.get("item");
            if (itemObj == null) {
                continue;
            }
            int index = slotIndex(raw, size);
            if (index < 0) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            if (raw.get("lore") instanceof List<?> list) {
                for (Object o : list) {
                    lore.add(String.valueOf(o));
                }
            }
            MenuAction left = firstAction(raw.get("left-click"));
            MenuAction right = firstAction(raw.get("right-click"));
            if (left == MenuAction.NONE) {
                left = right;                 // right-click-only button
            }
            if (right == MenuAction.NONE) {
                right = left;                 // no separate right action: any click does the same thing
            }
            String id = raw.get("id") != null ? String.valueOf(raw.get("id")) : ("slot" + (synthetic++));
            slots.put(id, new ConfiguredSlot(id, index, ItemSpec.parse(String.valueOf(itemObj)),
                    lore, left, right));
        }

        return new MenuTemplate(title, rows, mask, regions, slots, cfg);
    }

    /** The first recognised effect id in a click list becomes the slot's action. */
    private static MenuAction firstAction(Object clickList) {
        if (!(clickList instanceof List<?> list)) {
            return MenuAction.NONE;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> m && m.get("id") != null) {
                MenuAction action = MenuAction.fromEffectId(String.valueOf(m.get("id")));
                if (action != MenuAction.NONE) {
                    return action;
                }
            }
        }
        return MenuAction.NONE;
    }

    /**
     * Resolve a slot's 1-based {@code row}/{@code column} into a 0-based inventory index. Row/column sit
     * directly on the slot (the eco-menus convention); a legacy nested {@code location: {row, column}}
     * is still accepted as a fallback.
     */
    private static int slotIndex(Map<?, ?> raw, int size) {
        Object rowObj = raw.get("row");
        Object colObj = raw.get("column");
        if (rowObj == null && colObj == null) {
            Object loc = raw.get("location");
            if (loc instanceof ConfigurationSection cs) {
                rowObj = cs.get("row");
                colObj = cs.get("column");
            } else if (loc instanceof Map<?, ?> m) {
                rowObj = m.get("row");
                colObj = m.get("column");
            }
        }
        int index = (intOf(rowObj, 1) - 1) * 9 + (intOf(colObj, 1) - 1);
        return (index >= 0 && index < size) ? index : -1;
    }

    private static ItemStack filler(String lookup) {
        EcoHook eco = EcoHookHolder.get();
        ItemStack item = eco != null ? eco.resolve(lookup, 1) : new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.color(" "));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(MenuKeys.ACTION, PersistentDataType.STRING, MenuAction.NONE.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------------------ rendering (API used by the GUIs)

    public int size() {
        return rows * 9;
    }

    public String title(Map<String, String> placeholders) {
        return ItemSpec.apply(title, placeholders);
    }

    /** Paint the mask filler, then place every configured button (placeholders + action tags applied). */
    public void applyStatic(Inventory inv, Map<String, String> placeholders) {
        applyStatic(inv, placeholders, Map.of());
    }

    /**
     * As {@link #applyStatic(Inventory, Map)}, but any lore line that is exactly {@code %key%} for a
     * key in {@code listPlaceholders} expands into one line per entry — used by the filter buttons to
     * print their full option list with the current selection marked.
     */
    public void applyStatic(Inventory inv, Map<String, String> placeholders,
                            Map<String, List<String>> listPlaceholders) {
        EcoHook eco = EcoHookHolder.get();
        for (int i = 0; i < inv.getSize() && i < mask.length; i++) {
            inv.setItem(i, mask[i] == null ? null : mask[i].clone());
        }
        for (ConfiguredSlot slot : slots.values()) {
            if (slot.index() < 0 || slot.index() >= inv.getSize()) {
                continue;
            }
            inv.setItem(slot.index(), slot.item().build(eco, placeholders, listPlaceholders,
                    slot.lore(), slot.left(), slot.right()));
        }
    }

    /** The mask's filler at {@code index}, or null; used to blank out a slot that does not apply. */
    public ItemStack maskAt(int index) {
        return index < 0 || index >= mask.length || mask[index] == null ? null : mask[index].clone();
    }

    /** 0-based index of a named slot (its {@code id:} in the config), or -1. */
    public int slotOf(String id) {
        ConfiguredSlot slot = slots.get(id);
        return slot == null ? -1 : slot.index();
    }

    public MenuAction actionOf(String id) {
        ConfiguredSlot slot = slots.get(id);
        return slot == null ? MenuAction.NONE : slot.left();
    }

    /** Indices of a named dynamic region from the mask (e.g. {@code content-slots}). */
    public List<Integer> slots(String regionName) {
        return regions.getOrDefault(regionName, List.of());
    }

    /** A configurable list of lore lines at {@code path} (e.g. "listing-lore.auction"), placeholders filled. */
    public List<String> lore(String path, Map<String, String> placeholders) {
        List<String> raw = source.getStringList(path);
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(ItemSpec.apply(line, placeholders));
        }
        return out;
    }

    public void playSound(Player player, String path) {
        if (!source.getBoolean("sounds." + path + ".enabled", false)) {
            return;
        }
        String name = source.getString("sounds." + path + ".name", "UI_BUTTON_CLICK");
        org.bukkit.Sound sound;
        try {
            sound = org.bukkit.Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }
        float volume = (float) source.getDouble("sounds." + path + ".volume", 1.0);
        float pitch = (float) source.getDouble("sounds." + path + ".pitch", 1.0);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    // ------------------------------------------------------------------ static helpers (unchanged API)

    /** Build a simple tagged item (used for dynamically-generated buttons, e.g. duration options). */
    public static ItemStack simpleItem(Material material, String name, List<String> lore, MenuAction action) {
        ItemStack item = new ItemStack(material == null || material.isAir() ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.color(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(Text.color(line));
                }
                meta.lore(lines);
            }
            meta.getPersistentDataContainer().set(MenuKeys.ACTION, PersistentDataType.STRING, action.name());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Stamp an action onto an existing item (e.g. the deposited sell item). */
    public static ItemStack tag(ItemStack item, MenuAction action) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(MenuKeys.ACTION, PersistentDataType.STRING, action.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Read the (left-click) action tagged on a clicked item, or NONE. */
    public static MenuAction actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return MenuAction.NONE;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(MenuKeys.ACTION, PersistentDataType.STRING);
        return MenuAction.parse(raw);
    }

    /**
     * Read the action for a specific click type. Buttons that declare no separate {@code right-click:}
     * carry the same action on both keys, so this is safe for every menu — only the filter buttons
     * actually differ between left and right.
     */
    public static MenuAction actionOf(ItemStack item, boolean rightClick) {
        if (item == null || !item.hasItemMeta()) {
            return MenuAction.NONE;
        }
        if (!rightClick) {
            return actionOf(item);
        }
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(MenuKeys.ACTION_RIGHT, PersistentDataType.STRING);
        MenuAction right = MenuAction.parse(raw);
        return right == MenuAction.NONE ? actionOf(item) : right;
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Holds the plugin's {@link EcoHook} so static template loading can resolve item lookups. */
    public static final class EcoHookHolder {
        private static EcoHook eco;

        private EcoHookHolder() {
        }

        public static void set(EcoHook hook) {
            eco = hook;
        }

        public static EcoHook get() {
            return eco;
        }
    }
}
