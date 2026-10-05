package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.category.Category;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.data.ListingQuery;
import com.mystipixel.royalauctions.data.ListingPage;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.tier.Tier;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auction Browser — layout from gui/browse.yml. Categories fill the {@code category-slots} region
 * (column 1), listings fill {@code content-slots}, and the bottom bar carries search / sort / tier /
 * type filters.
 *
 * <p>Three independent filters stack: category, item tier (eco rarity), and listing type (BIN vs
 * auction). Tier and type both cycle through their states on click.
 */
public final class BrowseGui extends AuctionGui {

    /** The listing-type filter states, in list order. */
    private enum TypeFilter {
        ALL("Show All"),
        BIN("Buy It Now only"),
        AUCTION("Auctions only");

        final String display;

        TypeFilter(String display) {
            this.display = display;
        }

        /** Step through the list; {@code delta} is +1 (down) or -1 (up), wrapping at both ends. */
        TypeFilter step(int delta) {
            TypeFilter[] all = values();
            return all[Math.floorMod(ordinal() + delta, all.length)];
        }
    }

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;

    private String category;
    private String search;
    private SortOrder sort;
    private int page;
    /** The page the slots currently show. {@code page} runs ahead of it while a fetch is in flight. */
    private int shownPage;

    /** null = no tier filter (show all tiers). */
    private String tier;
    private TypeFilter typeFilter = TypeFilter.ALL;

    /** Bumped per fetch so a late response for an older view is discarded instead of drawn. */
    private int latestRequest;
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

    /** The current filter state as a database query. */
    private ListingQuery query() {
        ListingType typed = switch (typeFilter) {
            case BIN -> ListingType.BIN;
            case AUCTION -> ListingType.AUCTION;
            case ALL -> null;
        };
        return new ListingQuery(category, tier, typed, search, sort,
                manager.itemNames().listingNamesMatching(search));
    }

    /**
     * Fetch the current page and draw it. Filtering and sorting happen in the database now, so every
     * filter change and page turn goes through here.
     *
     * <p>The result arrives later, by which time the player may have clicked something else or closed
     * the menu, so each request carries a sequence number and only the newest one is allowed to draw.
     * Without that, a slow response for page 1 could land after page 2 and silently rewind the view.
     */
    private void render() {
        int requestId = ++latestRequest;
        int perPage = Math.max(1, template.slots("content-slots").size());
        manager.service().loadBrowsePage(query(), page, perPage, result -> {
            if (requestId != latestRequest || !stillOpen()) {
                return;
            }
            int pages = result.pageCount(perPage);
            if (page >= pages) {
                // Filters shrank the result set out from under the current page — step back and refetch.
                page = pages - 1;
                render();
                return;
            }
            draw(result, perPage);
        });
    }

    /** True while this menu is still the one the player has open. */
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
        placeholders.put("category", category == null ? "All" : displayNameOf(category));
        placeholders.put("count", String.valueOf(result.total()));
        placeholders.put("page", String.valueOf(page + 1));
        placeholders.put("pages", String.valueOf(pages));
        placeholders.put("search", search == null ? "None" : search);
        placeholders.put("sort", prettySort());
        placeholders.put("tier", tier == null ? "&fNo Filter" : manager.tiers().displayOf(tier));
        placeholders.put("type", typeFilter.display);

        // Filter buttons print their whole option list, current selection marked.
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
        // Keep the item's own lore (Hypixel-style) and append the auction info below it.
        ItemStack icon = listing.item();
        boolean own = listing.sellerId().equals(player.getUniqueId());

        Map<String, String> ph = new HashMap<>();
        ph.put("seller", listing.sellerName());
        ph.put("price", GuiUtil.comma(listing.displayPrice()));
        ph.put("starting_price", GuiUtil.comma(listing.price()));
        ph.put("bids", String.valueOf(listing.bidCount()));
        ph.put("top_bidder", listing.topBidderName() == null ? "None" : listing.topBidderName());
        ph.put("ends_in", GuiUtil.timeLeft(listing.expiresAt() - System.currentTimeMillis()));
        ph.put("bid_label", listing.hasBids() ? "Current bid" : "Starting bid");
        ph.put("tier", listing.tier() == null ? "&7None" : manager.tiers().displayOf(listing.tier()));
        ph.put("click_hint", own ? "&cThis is your own listing"
                : (listing.isAuction() ? "&eClick to bid!" : "&eClick to purchase!"));

        String path = listing.isAuction() ? "listing-lore.auction" : "listing-lore.buy-it-now";
        List<String> lines = template.lore(path, ph);
        if (lines.isEmpty()) {
            lines = defaultListingLore(listing, ph);
        }
        return GuiUtil.appendLore(icon, lines);
    }

    /** Fallback tooltip used when browse.yml has no listing-lore section. */
    private List<String> defaultListingLore(Listing listing, Map<String, String> ph) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Seller: &f" + ph.get("seller"));
        if (listing.isAuction()) {
            lore.add("&6" + ph.get("bid_label") + ": &e" + ph.get("price") + " coins");
            lore.add("&7Top bidder: &f" + ph.get("top_bidder"));
            lore.add("&7Bids: &f" + ph.get("bids"));
        } else {
            lore.add("&6Buy it now: &e" + ph.get("price") + " coins");
        }
        lore.add("");
        lore.add("&7Ends in: &f" + ph.get("ends_in"));
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
                    selected ? "&aCurrently viewing" : "&7Click to view");
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

    /**
     * Step the tier filter through the list. The options are [No Filter, tier1, tier2, ...], so
     * {@code delta} +1 moves down the list and -1 moves up, wrapping at both ends.
     */
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

    /** Index of the current tier in the option list; 0 = No Filter. */
    private int tierIndex(List<Tier> tiers) {
        if (tier == null) {
            return 0;
        }
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).id().equalsIgnoreCase(tier)) {
                return i + 1;
            }
        }
        return 0; // the tier vanished (config reload) — treat as No Filter
    }

    /** The tier options as lore lines, with the selected one marked. */
    private List<String> tierList() {
        List<Tier> tiers = manager.tiers().tiers();
        List<String> out = new ArrayList<>();
        if (tiers.isEmpty()) {
            out.add("&8No tiers configured");
            return out;
        }
        int current = tierIndex(tiers);
        out.add(marked(current == 0, "&fNo Filter"));
        for (int i = 0; i < tiers.size(); i++) {
            out.add(marked(current == i + 1, tiers.get(i).displayName()));
        }
        return out;
    }

    /** The listing-type options as lore lines, with the selected one marked. */
    private List<String> typeList() {
        List<String> out = new ArrayList<>();
        for (TypeFilter f : TypeFilter.values()) {
            out.add(marked(f == typeFilter, "&f" + f.display));
        }
        return out;
    }

    private String marked(boolean selected, String label) {
        return selected ? "&e> " + label : "&8  " + stripColor(label);
    }

    /** Unselected options are dimmed, so the current one stands out at a glance. */
    private String stripColor(String s) {
        return s.replaceAll("&[0-9a-fk-or]", "");
    }

    private String prettySort() {
        return switch (sort) {
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case PRICE_LOW -> "Price ↑";
            case PRICE_HIGH -> "Price ↓";
            case ENDING_SOON -> "Ending Soon";
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
                // decorative / filler
            }
        }
    }
}
