package com.mystipixel.royalauctions.category;

import com.mystipixel.royalauctions.hooks.EcoHook;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Decides which category an item belongs to: an {@code items:} pin wins, then the priority-ordered
 * rules, then the catch-all. {@code strict-items} turns inference off, so only pinned items match.
 */
public final class CategoryManager {

    /** Where an item landed, and which rule put it there. */
    public record CategoryMatch(String categoryId, String reason) {
    }

    private final EcoHook eco;
    private final Logger logger;

    // Published in one volatile write: listings are categorised off the main thread while
    // /ah reload runs, so shared collections must never be cleared in place.
    private record State(List<Category> ordered, Map<String, Category> byId, String fallbackId, boolean strictItems) {
    }

    private volatile State state = new State(List.of(), Map.of(), "misc", false);

    public CategoryManager(EcoHook eco, Logger logger) {
        this.eco = eco;
        this.logger = logger;
    }

    public void load(ConfigurationSection section, ConfigurationSection options) {
        List<Category> ordered = new ArrayList<>();
        Map<String, Category> byId = new LinkedHashMap<>();
        boolean strictItems = options != null && options.getBoolean("strict-items", false);
        String fallbackId = state.fallbackId();

        if (section == null) {
            logger.warning("No 'categories' section found in config.yml, items will be uncategorised.");
            state = new State(List.of(), Map.of(), fallbackId, strictItems);
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection cs = section.getConfigurationSection(id);
            if (cs == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            String displayName = cs.getString("display-name", id);
            Material icon = Material.matchMaterial(cs.getString("icon", "CHEST"));
            if (icon == null) {
                logger.warning("Category '" + id + "' has an unknown icon; defaulting to CHEST.");
                icon = Material.CHEST;
            }
            int priority = cs.getInt("priority", 100);
            MatchRule rule = MatchRule.fromConfig(cs.getConfigurationSection("match"),
                    msg -> logger.warning("[category " + id + "] " + msg));

            Category category = new Category(key, displayName, icon, priority, rule);
            ordered.add(category);
            byId.put(key, category);
        }

        ordered.sort(Comparator.comparingInt(Category::priority));
        if (!ordered.isEmpty()) {
            fallbackId = ordered.get(ordered.size() - 1).id();
        }
        state = new State(List.copyOf(ordered), java.util.Collections.unmodifiableMap(byId), fallbackId, strictItems);
        logger.info("Loaded " + ordered.size() + " auction categories"
                + (strictItems ? " (strict-items: only pinned items are categorised)." : "."));
    }

    /** Never null once categories are loaded. */
    public String categorize(ItemStack item) {
        return explain(item).categoryId();
    }

    /** An item's category and the rule that chose it, for {@code /ah category}. */
    public CategoryMatch explain(ItemStack item) {
        State state = this.state;
        List<Category> ordered = state.ordered();
        String fallbackId = state.fallbackId();
        String key = keyOf(item);

        // An explicit pin wins whatever the priority order says.
        for (Category category : ordered) {
            if (category.rule().pins(key)) {
                return new CategoryMatch(category.id(), "explicit items: pin (" + key + ")");
            }
        }

        // strict-items: unpinned items go to the catch-all.
        if (state.strictItems()) {
            return new CategoryMatch(fallbackId, "strict-items is on and " + key + " is not pinned");
        }

        String namespace = eco.namespaceOf(item);
        for (Category category : ordered) {
            String reason = category.rule().reason(item, namespace);
            if (reason != null) {
                return new CategoryMatch(category.id(), reason);
            }
        }
        return new CategoryMatch(fallbackId, "no rule matched");
    }

    /** {@code namespace:id} for eco items, else {@code minecraft:<material>}; what {@code items:} pins match. */
    public String keyOf(ItemStack item) {
        if (item == null) {
            return null;
        }
        String namespace = eco.namespaceOf(item);
        String id = eco.customItemId(item);
        if (namespace != null && id != null) {
            return (namespace + ":" + id).toLowerCase(Locale.ROOT);
        }
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }

    /** Logs every eco item that only matched the catch-all, so admins know which items to pin. */
    public void auditCustomItems() {
        if (state.ordered().isEmpty()) {
            return;
        }
        Map<String, ItemStack> customItems = eco.allCustomItems();
        if (customItems.isEmpty()) {
            return;
        }
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, ItemStack> e : customItems.entrySet()) {
            CategoryMatch match = explain(e.getValue());
            if (MatchRule.CATCH_ALL.equals(match.reason()) || match.reason().startsWith("no rule")
                    || match.reason().startsWith("strict-items")) {
                unresolved.add(e.getKey());
            }
        }
        if (unresolved.isEmpty()) {
            logger.info("Category audit: all " + customItems.size() + " eco items resolved to a category.");
            return;
        }
        java.util.Collections.sort(unresolved);
        logger.info("Category audit: " + unresolved.size() + " of " + customItems.size()
                + " eco item(s) fell through to '" + state.fallbackId() + "'. Pin them under a category's"
                + " match.items: if they belong elsewhere:");
        for (String id : unresolved) {
            logger.info("  - " + id);
        }
    }

    /** Where an item that matches nothing lands (the highest-priority catch-all). */
    public String fallbackId() {
        return state.fallbackId();
    }

    public List<Category> categories() {
        return state.ordered();
    }

    public Category byId(String id) {
        return id == null ? null : state.byId().get(id.toLowerCase(Locale.ROOT));
    }

    public boolean isKnown(String categoryId) {
        return categoryId != null && state.byId().containsKey(categoryId.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return state.ordered().isEmpty();
    }
}
