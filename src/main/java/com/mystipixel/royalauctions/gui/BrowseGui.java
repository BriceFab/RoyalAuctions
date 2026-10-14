package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.category.Category;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.data.ListingQuery;
import com.mystipixel.royalauctions.data.ListingPage;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.search.SearchTerms;
import com.mystipixel.royalauctions.tier.Tier;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Auction browser, laid out by gui/browse.yml. Category, tier (eco rarity) and listing type filters
 * stack; tier and type cycle on click.
 */
public final class BrowseGui extends AuctionGui {

    private enum TypeFilter {
        ALL("filter.all"),
        BIN("filter.bin"),
        AUCTION("filter.auction");

        // label key under gui. in messages.yml
        final String key;

        TypeFilter(String key) {
            this.key = key;
        }

        // delta is +1 (down) or -1 (up), wrapping at both ends
        TypeFilter step(int delta) {
            TypeFilter[] all = values();
            return all[Math.floorMod(ordinal() + delta, all.length)];
        }
    }

    // The count is the slow half of a fetch, so page flips and re-sorts reuse it for a while.
    private static final long COUNT_REUSE_MILLIS = 30_000L;

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;

    private String category;
    private String search;
    private String resolvedSearch;
    private SearchTerms.Match searchMatch = SearchTerms.Match.NONE;
    private SortOrder sort;
    private int page;
    // The page on screen; page runs ahead of it while a fetch is in flight.
    private int shownPage;

    // null = no tier filter
    private String tier;
    private TypeFilter typeFilter = TypeFilter.ALL;

    // One fetch at a time: clicks while it runs only change what the next fetch asks for.
    private boolean fetching;
    private boolean refetch;
    private List<Object> countedFilters;
    private int countedTotal;
    private long countedAt;
    private final Map<Integer, Listing> slotToListing = new HashMap<>();
    private final Map<Integer, String> slotToCategory = new HashMap<>();
    private final List<Category> tabs;

    public BrowseGui(GuiManager manager, Player player, String category, String search, SortOrder sort) {
        this.manager = manager;
        this.template = manager.menus().browse();
        this.player = player;
        this.category = category;
        this.search = search;
        this.sort = sort;
        this.tabs = manager.categories().categories();
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
    }

    public void populate(int page) {
        this.page = Math.max(0, page);
        render();
    }

    private ListingQuery query() {
        ListingType typed = switch (typeFilter) {
            case BIN -> ListingType.BIN;
            case AUCTION -> ListingType.AUCTION;
            case ALL -> null;
        };
        if (!Objects.equals(search, resolvedSearch)) {
            searchMatch = manager.searchTerms().resolve(search);
            resolvedSearch = search;
        }
        return new ListingQuery(category, tier, typed, search, sort,
                searchMatch.itemNames(), searchMatch.enchantmentTokens());
    }

    // Only the newest state may draw, so a slow page 1 cannot land after page 2 and rewind the view.
    private void render() {
        if (fetching) {
            refetch = true;
            return;
        }
        fetching = true;
        int perPage = Math.max(1, template.slots("content-slots").size());
        ListingQuery query = query();
        List<Object> filters = Arrays.asList(query.category(), query.tier(), query.type(), query.search());
        boolean reuseCount = filters.equals(countedFilters)
                && System.currentTimeMillis() - countedAt < COUNT_REUSE_MILLIS;
        manager.service().loadBrowsePage(query, page, perPage, reuseCount ? countedTotal : -1, result -> {
            fetching = false;
            if (!stillOpen()) {
                return;
            }
            if (refetch) {
                refetch = false;
                render();
                return;
            }
            if (!reuseCount) {
                countedFilters = filters;
                countedAt = System.currentTimeMillis();
            }
            countedTotal = result.total();
            int pages = result.pageCount(perPage);
            if (page >= pages) {
                // Filters shrank the results below the current page: step back and refetch.
                page = pages - 1;
                render();
                return;
            }
            draw(result, perPage);
        });
    }

    private boolean stillOpen() {
        return player.isOnline() && inventory.equals(player.getOpenInventory().getTopInventory());
    }

    private void draw(ListingPage result, int perPage) {
        shownPage = page;
        inventory.clear();
        slotToListing.clear();
        slotToCategory.clear();

        List<Integer> contentSlots = template.slots("content-slots");
        List<Listing> results = result.rows();
        int pages = result.pageCount(perPage);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("category", category == null ? manager.t("all") : displayNameOf(category));
        placeholders.put("count", String.valueOf(result.total()));
        placeholders.put("page", String.valueOf(page + 1));
        placeholders.put("pages", String.valueOf(pages));
        placeholders.put("search", search == null ? manager.t("none") : search);
        placeholders.put("sort", prettySort());
        placeholders.put("tier", tier == null ? manager.t("format.filter", "label", manager.t("no-filter"))
                : manager.tiers().displayOf(tier));
        placeholders.put("type", manager.t(typeFilter.key));

        Map<String, List<String>> lists = new HashMap<>();
        lists.put("tier_list", tierList());
        lists.put("type_list", typeList());
        template.applyStatic(inventory, placeholders, lists);

        for (int i = 0; i < perPage && i < results.size(); i++) {
            int slot = contentSlots.get(i);
            Listing listing = results.get(i);
            inventory.setItem(slot, listingIcon(listing));
            slotToListing.put(slot, listing);
        }

        renderTabs();
    }

    private ItemStack listingIcon(Listing listing) {
        // Keep the item's own lore and append the auction info below it.
        ItemStack icon = listing.item();
        boolean own = listing.sellerId().equals(player.getUniqueId());

        Map<String, String> ph = new HashMap<>();
        ph.put("seller", listing.sellerName());
        ph.put("price", GuiUtil.comma(listing.displayPrice()));
        ph.put("starting_price", GuiUtil.comma(listing.price()));
        ph.put("bids", String.valueOf(listing.bidCount()));
        ph.put("top_bidder", listing.topBidderName() == null ? manager.t("none") : listing.topBidderName());
        ph.put("ends_in", manager.timeLeft(listing.expiresAt() - System.currentTimeMillis()));
        ph.put("bid_label", manager.t(listing.hasBids() ? "label.current-bid" : "label.starting-bid"));
        ph.put("tier", listing.tier() == null ? manager.t("format.no-tier", "label", manager.t("none"))
                : manager.tiers().displayOf(listing.tier()));
        ph.put("click_hint", manager.t(own ? "own-listing-browse"
                : (listing.isAuction() ? "click-bid-browse" : "click-purchase")));

        String path = listing.isAuction() ? "listing-lore.auction" : "listing-lore.buy-it-now";
        List<String> lines = template.lore(path, ph);
        if (lines.isEmpty()) {
            lines = defaultListingLore(listing, ph);
        }
        return GuiUtil.appendLore(icon, lines);
    }

    // Used when browse.yml has no listing-lore section.
    private List<String> defaultListingLore(Listing listing, Map<String, String> ph) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(manager.t("seller", "seller", ph.get("seller")));
        if (listing.isAuction()) {
            lore.add(manager.t("bid-price-line", "label", ph.get("bid_label"), "price", ph.get("price")));
            lore.add(manager.t("top-bidder", "bidder", ph.get("top_bidder")));
            lore.add(manager.t("bids", "bids", ph.get("bids")));
        } else {
            lore.add(manager.t("bin-price-line", "price", ph.get("price")));
        }
        lore.add("");
        lore.add(manager.t("ends-in", "time", ph.get("ends_in")));
        lore.add("");
        lore.add(ph.get("click_hint"));
        return lore;
    }

    private void renderTabs() {
        List<Integer> catSlots = template.slots("category-slots");
        for (int i = 0; i < tabs.size() && i < catSlots.size(); i++) {
            Category cat = tabs.get(i);
            int slot = catSlots.get(i);
            boolean selected = cat.id().equalsIgnoreCase(category);
            ItemStack tab = GuiUtil.button(cat.icon(), cat.displayName(),
                    manager.t(selected ? "currently-viewing" : "click-view"));
            if (selected) {
                ItemMeta meta = tab.getItemMeta();
                if (meta != null) {
                    meta.setEnchantmentGlintOverride(true);
                    tab.setItemMeta(meta);
                }
            }
            inventory.setItem(slot, tab);
            slotToCategory.put(slot, cat.id());
        }
    }

    // Options are [No Filter, tier1, tier2, ...]; delta +1 moves down, -1 up, wrapping.
    private void stepTier(int delta) {
        List<Tier> tiers = manager.tiers().tiers();
        if (tiers.isEmpty()) {
            return;
        }
        int size = tiers.size() + 1;          // index 0 = "No Filter"
        int current = tierIndex(tiers);
        int next = Math.floorMod(current + delta, size);
        tier = (next == 0) ? null : tiers.get(next - 1).id();
    }

    private int tierIndex(List<Tier> tiers) {
        if (tier == null) {
            return 0;
        }
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).id().equalsIgnoreCase(tier)) {
                return i + 1;
            }
        }
        return 0; // the tier vanished on reload: treat as No Filter
    }

    private List<String> tierList() {
        List<Tier> tiers = manager.tiers().tiers();
        List<String> out = new ArrayList<>();
        if (tiers.isEmpty()) {
            out.add(manager.t("no-tiers"));
            return out;
        }
        int current = tierIndex(tiers);
        out.add(marked(current == 0, manager.t("format.filter", "label", manager.t("no-filter"))));
        for (int i = 0; i < tiers.size(); i++) {
            out.add(marked(current == i + 1, tiers.get(i).displayName()));
        }
        return out;
    }

    private List<String> typeList() {
        List<String> out = new ArrayList<>();
        for (TypeFilter f : TypeFilter.values()) {
            out.add(marked(f == typeFilter, manager.t("format.filter", "label", manager.t(f.key))));
        }
        return out;
    }

    private String marked(boolean selected, String label) {
        return selected ? manager.t("format.selected", "label", label)
                : manager.t("format.unselected", "label", stripColor(label));
    }

    private String stripColor(String s) {
        return s.replaceAll("&[0-9a-fk-or]", "");
    }

    private String prettySort() {
        return switch (sort) {
            case NEWEST -> manager.t("sort.newest");
            case OLDEST -> manager.t("sort.oldest");
            case PRICE_LOW -> manager.t("sort.price-low");
            case PRICE_HIGH -> manager.t("sort.price-high");
            case ENDING_SOON -> manager.t("sort.ending-soon");
        };
    }

    private String displayNameOf(String categoryId) {
        Category c = manager.categories().byId(categoryId);
        return c == null ? categoryId : Text.plain(Text.color(c.displayName()));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        int slot = event.getSlot();

        if (slotToListing.containsKey(slot)) {
            Listing listing = slotToListing.get(slot);
            if (listing.sellerId().equals(player.getUniqueId())) {
                return; // can't buy/bid on your own; the tooltip already says so
            }
            // The slots show shownPage; page may already point at a fetch still in flight.
            if (listing.isAuction()) {
                manager.openBid(player, listing, category, search, sort, shownPage);
            } else {
                manager.openConfirm(player, listing, category, search, sort, shownPage);
            }
            return;
        }
        if (slotToCategory.containsKey(slot)) {
            String clicked = slotToCategory.get(slot);
            category = clicked.equalsIgnoreCase(category) ? null : clicked;
            page = 0;
            render();
            return;
        }

        switch (MenuTemplate.actionOf(event.getCurrentItem(), event.isRightClick())) {
            case SEARCH -> manager.beginSearch(player, category, sort);
            case SORT -> {
                sort = sort.next();
                render();
            }
            case TIER_PREV -> {
                stepTier(-1);
                page = 0;
                render();
            }
            case TIER_NEXT -> {
                stepTier(1);
                page = 0;
                render();
            }
            case TYPE_PREV -> {
                typeFilter = typeFilter.step(-1);
                page = 0;
                render();
            }
            case TYPE_NEXT -> {
                typeFilter = typeFilter.step(1);
                page = 0;
                render();
            }
            case CREATE_AUCTION -> manager.openCreate(player);
            case CLEAR_FILTERS -> {
                category = null;
                search = null;
                tier = null;
                typeFilter = TypeFilter.ALL;
                page = 0;
                render();
            }
            // Step from the page on screen, so spam-clicking cannot run ahead of the results.
            case PREV_PAGE -> {
                if (shownPage > 0) {
                    page = shownPage - 1;
                    render();
                }
            }
            case NEXT_PAGE -> {
                page = shownPage + 1;
                render();
            }
            case OPEN_HUB -> manager.openHub(player);
            case OPEN_BIDS -> manager.openBids(player);
            case OPEN_MANAGE -> manager.openListings(player);
            case OPEN_COLLECTION -> manager.openCollection(player);
            case CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }
}
