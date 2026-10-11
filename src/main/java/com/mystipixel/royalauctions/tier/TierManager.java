package com.mystipixel.royalauctions.tier;

import com.mystipixel.royalauctions.hooks.EcoHook;
import com.mystipixel.royalauctions.hooks.RarityRegistry;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The tiers the browser filter cycles through, and an item's tier. Tiers are eco's rarities from
 * {@link RarityRegistry}; the {@code tiers:} section only sets order, icons and which to hide.
 */
public final class TierManager {

    private final EcoHook eco;
    private final Logger logger;
    private RarityRegistry rarities;

    private final List<Tier> ordered = new ArrayList<>();
    private boolean enabled = true;

    public TierManager(EcoHook eco, RarityRegistry rarities, Logger logger) {
        this.eco = eco;
        this.rarities = rarities;
        this.logger = logger;
    }

    public void rebind(RarityRegistry rarities) {
        this.rarities = rarities;
    }

    public void load(ConfigurationSection section) {
        ordered.clear();
        this.enabled = section == null || section.getBoolean("enabled", true);
        if (!enabled) {
            logger.info("Tier filter disabled in config.");
            return;
        }

        List<String> order = section == null ? List.of() : section.getStringList("order");
        List<String> hidden = section == null ? List.of() : lower(section.getStringList("hide"));
        ConfigurationSection icons = section == null ? null : section.getConfigurationSection("icons");

        Map<String, RarityRegistry.Rarity> discovered = new LinkedHashMap<>();
        for (RarityRegistry.Rarity r : rarities.rarities()) {
            discovered.put(r.id(), r);
        }

        // Configured order first (existing rarities only), then anything left over.
        List<String> ids = new ArrayList<>();
        for (String id : lower(order)) {
            if (discovered.containsKey(id) && !ids.contains(id)) {
                ids.add(id);
            }
        }
        for (String id : discovered.keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }

        for (String id : ids) {
            if (hidden.contains(id)) {
                continue;
            }
            RarityRegistry.Rarity r = discovered.get(id);
            Material icon = icons == null ? null : Material.matchMaterial(icons.getString(id, ""));
            ordered.add(new Tier(
                    id,
                    r.display(),                                   // the rarity's own lore line, e.g. "&d&lEPIC"
                    icon == null ? Material.PAPER : icon,
                    true));
        }
        logger.info("Tier filter: " + ordered.size() + " tiers (" + summary() + ").");
    }

    public List<Tier> tiers() {
        return List.copyOf(ordered);
    }

    public boolean isEmpty() {
        return !enabled || ordered.isEmpty();
    }

    public Tier byId(String id) {
        if (id == null) {
            return null;
        }
        for (Tier t : ordered) {
            if (t.id().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    /** Falls back to the raw id for tiers no longer configured. */
    public String displayOf(String id) {
        Tier t = byId(id);
        return t == null ? id : t.displayName();
    }

    /** An item's eco rarity, or null for vanilla and untiered items. Resolved once, at listing time. */
    public String tierOf(ItemStack item) {
        if (item == null || !rarities.isPresent()) {
            return null;
        }
        String namespace = eco.namespaceOf(item);
        String id = eco.customItemId(item);
        if (namespace == null || id == null) {
            return null; // vanilla / not an eco item
        }
        return rarities.rarityOf(namespace + ":" + id);
    }

    private String summary() {
        List<String> names = new ArrayList<>();
        for (Tier t : ordered) {
            names.add(Text.plain(Text.color(t.displayName())));
        }
        return String.join(", ", names);
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
