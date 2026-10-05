package com.mystipixel.royalauctions.config;

import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.data.SortOrder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/** Typed, reloadable view over config.yml plus the categories/ folder. */
public final class PluginConfig {

    /** Shipped on a fresh install; the file name is the category id. */
    private static final String[] DEFAULT_CATEGORIES = {
            "weapons.yml", "armor.yml", "accessories.yml", "consumables.yml", "blocks.yml", "tools_misc.yml"
    };

    /** One selectable duration on the "Auction Duration" screen. */
    public record DurationOption(String label, int hours, Material icon) {
    }

    private final JavaPlugin plugin;

    private int maxPerPlayer;
    private List<String> sellableCategories;
    private double minPrice;
    private double maxPrice;
    private double feePercent;
    private double feeMinimum;
    private boolean instantDeliver;
    private boolean manualEarnings;

    private final List<DurationOption> durations = new ArrayList<>();
    private int defaultDurationHours;
    private ListingType defaultType;

    private double bidMinIncrement;
    private double bidIncrementPercent;
    private long antiSnipeSeconds;

    private String browseTitle;
    private String collectionTitle;
    private String listingsTitle;
    private String confirmTitle;
    private String createTitle;
    private String durationTitle;
    private String confirmAuctionTitle;
    private String bidTitle;
    private int guiRows;

    private SortOrder defaultSort;
    private long expirySweepTicks;
    private int closedRetentionDays;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        ConfigurationSection c = plugin.getConfig();

        ConfigurationSection listings = section(c, "listings");
        this.maxPerPlayer = listings.getInt("max-per-player", 7);
        // Which categories may be listed at all. Empty = no restriction, which is the shipped
        // default: a fresh install should not silently refuse half the items in the game.
        this.sellableCategories = listings.getStringList("sellable-categories").stream()
                .map(x -> x.toLowerCase(Locale.ROOT).trim())
                .filter(x -> !x.isEmpty())
                .toList();
        this.minPrice = listings.getDouble("min-price", 1.0);
        this.maxPrice = listings.getDouble("max-price", -1);
        this.instantDeliver = listings.getBoolean("instant-deliver-purchases", true);
        this.manualEarnings = listings.getBoolean("manual-earnings-collection", false);
        ConfigurationSection fee = section(listings, "fee");
        this.feePercent = fee.getDouble("percent", 0.02);
        this.feeMinimum = fee.getDouble("minimum", 5.0);

        loadDurations(listings);
        this.defaultDurationHours = listings.getInt("default-duration-hours", 24);
        if (durations.stream().noneMatch(d -> d.hours() == defaultDurationHours)) {
            // The create screen would start on a duration no button offers; use the first one.
            int fallback = durations.get(0).hours();
            plugin.getLogger().warning("Config warning: listings.default-duration-hours (" + defaultDurationHours
                    + ") matches none of listings.durations; using " + fallback + ".");
            this.defaultDurationHours = fallback;
        }
        this.defaultType = ListingType.fromString(listings.getString("default-type"), ListingType.BIN);

        ConfigurationSection bidding = section(c, "bidding");
        this.bidMinIncrement = bidding.getDouble("min-increment", 10.0);
        this.bidIncrementPercent = bidding.getDouble("increment-percent", 0.05);
        this.antiSnipeSeconds = Math.max(0, bidding.getLong("anti-snipe-seconds", 60));

        ConfigurationSection gui = section(c, "gui");
        this.browseTitle = gui.getString("browse-title", "&8Auction House");
        this.collectionTitle = gui.getString("collection-title", "&8Collection");
        this.listingsTitle = gui.getString("listings-title", "&8Manage Auctions");
        this.confirmTitle = gui.getString("confirm-title", "&8Confirm Purchase");
        this.createTitle = gui.getString("create-title", "&8Create Auction");
        this.durationTitle = gui.getString("duration-title", "&8Auction Duration");
        this.confirmAuctionTitle = gui.getString("confirm-auction-title", "&8Confirm Auction");
        this.bidTitle = gui.getString("bid-title", "&8Place Bid");
        this.guiRows = Math.min(6, Math.max(2, gui.getInt("rows", 6)));

        this.defaultSort = SortOrder.fromString(c.getString("default-sort"), SortOrder.NEWEST);
        this.expirySweepTicks = Math.max(1, c.getLong("expiry-sweep-minutes", 5)) * 60L * 20L;
        this.closedRetentionDays = Math.max(0, c.getInt("closed-retention-days", 90));
    }

    private void loadDurations(ConfigurationSection listings) {
        durations.clear();
        List<?> raw = listings.getList("durations");
        if (raw != null) {
            for (Object entry : raw) {
                if (!(entry instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                Object labelObj = map.get("label");
                String label = labelObj == null ? "Duration" : String.valueOf(labelObj);
                int hours = map.get("hours") instanceof Number n ? n.intValue() : 24;
                if (hours <= 0) {
                    // Used to be accepted and silently listed as 1 hour; a typo should not do that.
                    plugin.getLogger().warning("Config warning: listings.durations entry '" + label
                            + "' has hours " + hours + "; it must be at least 1. Skipping it.");
                    continue;
                }
                Object iconObj = map.get("icon");
                Material icon = Material.matchMaterial(iconObj == null ? "CLOCK" : String.valueOf(iconObj));
                durations.add(new DurationOption(label, hours, icon == null ? Material.CLOCK : icon));
            }
        }
        if (durations.isEmpty()) {
            durations.add(new DurationOption("1 Day", 24, Material.CLOCK));
        }
    }

    private ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection s = parent.getConfigurationSection(path);
        return s != null ? s : parent.createSection(path);
    }

    public ConfigurationSection storageSection() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("storage");
        return s != null ? s : plugin.getConfig().createSection("storage");
    }

    /**
     * The categories, assembled from {@code categories/<name>.yml} — one file per category, the file
     * name being the category id. Kept out of config.yml so a server with twenty categories doesn't
     * have a thousand-line config; each file is small, self-contained and easy to diff.
     *
     * <p>On first run (or when upgrading from the old inline {@code categories:} block) the folder is
     * populated automatically — see {@link #migrateCategoriesIfNeeded()} — so nobody loses their
     * existing setup.
     */
    public ConfigurationSection categoriesSection() {
        migrateCategoriesIfNeeded();

        MemoryConfiguration combined = new MemoryConfiguration();
        File dir = new File(plugin.getDataFolder(), "categories");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml") && !n.startsWith("_"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("No categories/*.yml found - items will be uncategorised.");
            return combined;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = combined.createSection(id);
            for (String key : cfg.getKeys(true)) {
                if (!cfg.isConfigurationSection(key)) {
                    section.set(key, cfg.get(key));
                }
            }
        }
        return combined;
    }

    /**
     * Create {@code categories/} on first run. If the user still has the legacy inline
     * {@code categories:} block in config.yml, split it into one file per category rather than
     * silently ignoring it — an upgrade must never quietly drop someone's categories.
     */
    private void migrateCategoriesIfNeeded() {
        File dir = new File(plugin.getDataFolder(), "categories");
        if (dir.isDirectory() && dir.list((d, n) -> n.endsWith(".yml")) != null
                && dir.list((d, n) -> n.endsWith(".yml")).length > 0) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create the categories/ folder.");
            return;
        }

        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("categories");
        if (legacy != null && !legacy.getKeys(false).isEmpty()) {
            int written = 0;
            for (String id : legacy.getKeys(false)) {
                ConfigurationSection cs = legacy.getConfigurationSection(id);
                if (cs == null) {
                    continue;
                }
                YamlConfiguration out = new YamlConfiguration();
                for (String key : cs.getKeys(true)) {
                    if (!cs.isConfigurationSection(key)) {
                        out.set(key, cs.get(key));
                    }
                }
                try {
                    out.save(new File(dir, id.toLowerCase(Locale.ROOT) + ".yml"));
                    written++;
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not write categories/" + id + ".yml", e);
                }
            }
            plugin.getLogger().info("Migrated " + written + " categor(ies) from config.yml into categories/."
                    + " The 'categories:' block in config.yml is now ignored and can be deleted.");
            return;
        }

        // Fresh install: ship the defaults.
        for (String name : DEFAULT_CATEGORIES) {
            plugin.saveResource("categories/" + name, false);
        }
        plugin.getLogger().info("Created categories/ with " + DEFAULT_CATEGORIES.length + " default categories.");
    }

    /**
     * Whether the irreversible actions are gated behind a confirmation screen. Buy-It-Now and Create
     * always had one; bids and cancellations did not, and both move real value on a single click.
     * Servers that prefer fewer clicks can switch any of them off.
     */
    public boolean confirmBid() {
        return plugin.getConfig().getBoolean("confirmations.bid", true);
    }

    public boolean confirmCancel() {
        return plugin.getConfig().getBoolean("confirmations.cancel", true);
    }

    public boolean confirmPurchase() {
        return plugin.getConfig().getBoolean("confirmations.purchase", true);
    }

    /** Curation for the tier filter; the tiers themselves are discovered from eco's rarities. */
    public ConfigurationSection tiersSection() {
        return plugin.getConfig().getConfigurationSection("tiers");
    }

    /**
     * Options for how categories resolve ({@code strict-items}, the startup audit). Kept out of the
     * {@code categories:} map itself, since every key in there is read as a category id.
     */
    public ConfigurationSection categoryOptionsSection() {
        return plugin.getConfig().getConfigurationSection("category-options");
    }

    /** Compute the listing fee for a given sale price. */
    /** Never negative: a negative fee would make every listing fail as an invalid amount. */
    public double feeFor(double price) {
        return Math.max(0, Math.max(feeMinimum, price * feePercent));
    }

    public List<DurationOption> durations() {
        return durations;
    }

    public int defaultDurationHours() {
        return defaultDurationHours;
    }

    public ListingType defaultType() {
        return defaultType;
    }

    public long durationMillisFor(int hours) {
        return Math.max(1, hours) * 3_600_000L;
    }

    public double bidMinIncrement() {
        return bidMinIncrement;
    }

    public double bidIncrementPercent() {
        return bidIncrementPercent;
    }

    /** Minimum raise over the current bid: the larger of the flat and percentage increments. */
    public double bidIncrementFor(double currentBid) {
        return Math.max(bidMinIncrement, currentBid * bidIncrementPercent);
    }

    /** How close to expiry a bid must land to push the end time back out; 0 = anti-snipe off. */
    public long antiSnipeMillis() {
        return antiSnipeSeconds * 1000L;
    }

    /**
     * Categories a player may list into, lower-cased. Empty means everything is listable.
     *
     * <p>Expressed as categories rather than as its own material list so there is one place that
     * decides what an item *is* — the browse category and the sell rule can never disagree, and a
     * new custom item is classified once.
     */
    public List<String> sellableCategories() {
        return sellableCategories;
    }

    public boolean restrictsSelling() {
        return !sellableCategories.isEmpty();
    }

    public boolean canSellCategory(String categoryId) {
        return sellableCategories.isEmpty()
                || sellableCategories.contains(categoryId.toLowerCase(Locale.ROOT));
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    public double feePercent() {
        return feePercent;
    }

    public double feeMinimum() {
        return feeMinimum;
    }

    public double minPrice() {
        return minPrice;
    }

    public double maxPrice() {
        return maxPrice;
    }

    public boolean hasMaxPrice() {
        return maxPrice > 0;
    }

    /** Sale money waits in the Collection menu until the seller collects it, instead of being paid at once. */
    public boolean manualEarnings() {
        return manualEarnings;
    }

    public boolean instantDeliver() {
        return instantDeliver;
    }

    public String browseTitle() {
        return browseTitle;
    }

    public String collectionTitle() {
        return collectionTitle;
    }

    public String listingsTitle() {
        return listingsTitle;
    }

    public String confirmTitle() {
        return confirmTitle;
    }

    public String createTitle() {
        return createTitle;
    }

    public String durationTitle() {
        return durationTitle;
    }

    public String confirmAuctionTitle() {
        return confirmAuctionTitle;
    }

    public String bidTitle() {
        return bidTitle;
    }

    public int guiRows() {
        return guiRows;
    }

    public SortOrder defaultSort() {
        return defaultSort;
    }

    public long expirySweepTicks() {
        return expirySweepTicks;
    }

    /** Days closed listings and their bid history are kept before pruning; 0 = keep forever. */
    public int closedRetentionDays() {
        return closedRetentionDays;
    }
}
