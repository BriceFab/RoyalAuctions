package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single seller's active auctions — the {@code /ah <username>} view. Read-only browsing of someone
 * else's listings: click to buy (BIN) or bid (auction), exactly like the main browser.
 * Layout from gui/seller.yml.
 */
public final class SellerGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;
    private final String sellerName;
    private final List<Listing> listings;
    private final Map<Integer, Listing> slotToListing = new HashMap<>();
    private int page;

    public SellerGui(GuiManager manager, Player player, String sellerName, List<Listing> listings) {
        this.manager = manager;
        this.template = manager.menus().seller();
        this.player = player;
        this.sellerName = sellerName;
        this.listings = listings;
        this.inventory = Bukkit.createInventory(this, template.size(),
                Text.color(template.title(Map.of("seller", sellerName))));
    }

    public void populate(int page) {
        this.page = Math.max(0, page);
        render();
    }

    private void render() {
        inventory.clear();
        slotToListing.clear();

        List<Integer> slots = template.slots("content-slots");
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (int) Math.ceil(listings.size() / (double) perPage));
        if (page >= pages) {
            page = pages - 1;
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("seller", sellerName);
        ph.put("count", String.valueOf(listings.size()));
        ph.put("page", String.valueOf(page + 1));
        ph.put("pages", String.valueOf(pages));
        template.applyStatic(inventory, ph);

        int from = page * perPage;
        for (int i = 0; i < perPage && from + i < listings.size(); i++) {
            int slot = slots.get(i);
            Listing listing = listings.get(from + i);

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(manager.t("seller", "seller", listing.sellerName()));
            if (listing.isAuction()) {
                lore.add(manager.t("type-line", "type", manager.t("type.auction")));
                lore.add(manager.t("bid-price-line", "label", manager.t(listing.hasBids() ? "label.current-bid" : "label.starting-bid"),
                        "price", manager.vault().format(listing.displayPrice())));
                lore.add(manager.t("bids", "bids", String.valueOf(listing.bidCount())));
                lore.add(manager.t("top-bidder", "bidder",
                        listing.topBidderName() == null ? manager.t("none") : listing.topBidderName()));
            } else {
                lore.add(manager.t("type-line", "type", manager.t("type.bin")));
                lore.add(manager.t("bin-price-line", "price", manager.vault().format(listing.price())));
            }
            lore.add("");
            lore.add(manager.t("ends-in", "time", GuiUtil.timeLeft(listing.expiresAt() - System.currentTimeMillis())));
            lore.add("");
            lore.add(listing.sellerId().equals(player.getUniqueId())
                    ? manager.t("own-listing")
                    : manager.t(listing.isAuction() ? "click-bid" : "click-buy"));

            ItemStack icon = GuiUtil.appendLore(listing.item(), lore);
            inventory.setItem(slot, icon);
            slotToListing.put(slot, listing);
        }
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
                return; // can't buy/bid on your own
            }
            if (listing.isAuction()) {
                manager.openBid(player, listing, null, null, manager.config().defaultSort(), 0);
            } else {
                manager.openConfirm(player, listing, null, null, manager.config().defaultSort(), 0);
            }
            return;
        }
        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case PREV_PAGE -> {
                if (page > 0) {
                    page--;
                    render();
                }
            }
            case NEXT_PAGE -> {
                page++;
                render();
            }
            case OPEN_HUB -> manager.openHub(player);
            case OPEN_BROWSE -> manager.openBrowse(player);
            case CLOSE -> player.closeInventory();
            default -> {
                // decorative
            }
        }
    }
}
