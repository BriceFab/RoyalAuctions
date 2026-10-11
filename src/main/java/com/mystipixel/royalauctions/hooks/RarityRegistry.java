package com.mystipixel.royalauctions.hooks;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The eco suite's rarities, read from {@code plugins/EcoItems/rarities/*.yml}. A rarity's
 * {@code items:} list can claim items from any eco namespace; an EcoItems item may also declare
 * {@code rarity:} itself. If several rarities claim an item, the highest {@code weight} wins, as in
 * EcoItems.
 */
public final class RarityRegistry {

    /** {@code id} is the file name. */
    public record Rarity(String id, String display, int weight) {
    }

    /** The rarity id EcoItems uses to mean "explicitly no rarity". */
    public static final String NONE = "none";

    private final boolean present;
    private final Map<String, Rarity> byId = new HashMap<>();
    private final Map<String, String> rarityByItemKey = new HashMap<>();   // "namespace:id" -> rarity id
    private final Map<String, Integer> weightByItemKey = new HashMap<>();  // for conflict resolution

    public RarityRegistry(File pluginsFolder, Logger logger) {
        File rarityDir = new File(pluginsFolder, "EcoItems/rarities");
        this.present = rarityDir.isDirectory();
        if (!present) {
            return;
        }
        loadRarities(rarityDir);
        loadItemRarityFields(new File(pluginsFolder, "EcoItems/items"));
        logger.info("Rarity registry loaded: " + byId.size() + " rarities, "
                + rarityByItemKey.size() + " items tiered.");
    }

    public boolean isPresent() {
        return present;
    }

    /** Every rarity, excluding {@code none}, ordered by weight then id. */
    public List<Rarity> rarities() {
        List<Rarity> out = new ArrayList<>();
        for (Rarity r : byId.values()) {
            if (!r.id().equals(NONE)) {
                out.add(r);
            }
        }
        out.sort((a, b) -> a.weight() != b.weight()
                ? Integer.compare(a.weight(), b.weight())
                : a.id().compareTo(b.id()));
        return out;
    }

    public Rarity byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** The rarity id for a full eco id ({@code talismans:starter_talisman}); null if untiered or {@code none}. */
    public String rarityOf(String ecoItemKey) {
        if (ecoItemKey == null) {
            return null;
        }
        String rarity = rarityByItemKey.get(ecoItemKey.toLowerCase(Locale.ROOT));
        return NONE.equals(rarity) ? null : rarity;
    }

    private void loadRarities(File dir) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml") && !n.startsWith("_"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            String id = stripExtension(file.getName());
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            int weight = cfg.getInt("weight", 1);

            List<String> lore = cfg.getStringList("lore");
            String display = lore.isEmpty() ? prettify(id) : lore.get(0);

            byId.put(id, new Rarity(id, display, weight));

            for (String itemKey : cfg.getStringList("items")) {
                claim(itemKey, id, weight);
            }
        }
    }

    private void loadItemRarityFields(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                loadItemRarityFields(file); // items may live in subfolders
                continue;
            }
            String name = file.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".yml") || name.startsWith("_")) {
                continue;
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String rarity = cfg.getString("rarity");
            if (rarity == null || rarity.isBlank()) {
                continue;
            }
            rarity = rarity.trim().toLowerCase(Locale.ROOT);
            Rarity r = byId.get(rarity);
            claim("ecoitems:" + stripExtension(name), rarity, r == null ? 1 : r.weight());
        }
    }

    // The highest weight wins if several rarities claim an item.
    private void claim(String itemKey, String rarityId, int weight) {
        if (itemKey == null || itemKey.isBlank()) {
            return;
        }
        String key = itemKey.trim().toLowerCase(Locale.ROOT);
        Integer existing = weightByItemKey.get(key);
        if (existing != null && existing >= weight) {
            return;
        }
        rarityByItemKey.put(key, rarityId);
        weightByItemKey.put(key, weight);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot < 0 ? fileName : fileName.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    private static String prettify(String id) {
        String[] words = id.replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }
}
