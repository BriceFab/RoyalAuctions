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
 * View Bids — every still-active auction the player has bid on, marked TOP BIDDER or OUTBID.
 * Layout from gui/bids.yml. Outbid refunds normally settle immediately; rejected credits remain
 * in the payment journal for retry. The OUTBID label is not proof that a refund was paid.
 */
public final class BidsGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;
    private final List<Listing> listings;
    private final Map<Integer, Listing> slotToListing = new HashMap<>();
    private int page;

    public BidsGui(GuiManager manager, Player player, List<Listing> listings) {
        this.manager = manager;
        this.template = manager.menus().bids();
        this.player = player;
        this.listings = listings;
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
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

        int top = topBidCount();
        template.applyStatic(inventory, Map.of(
                "count", String.valueOf(listings.size()),
                "top", String.valueOf(top),
                "outbid", String.valueOf(Math.max(0, listings.size() - top)),
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages)));

        int from = page * perPage;
        for (int i = 0; i < perPage && from + i < listings.size(); i++) {
            int slot = slots.get(i);
            Listing listing = listings.get(from + i);
            boolean winning = isTopBidder(listing);

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(manager.t(winning ? "top-bidder-badge" : "outbid-badge"));
            lore.add(manager.t("seller", "seller", listing.sellerName()));
            lore.add(manager.t("current-bid", "price", manager.vault().format(listing.displayPrice())));
            lore.add(manager.t("bids", "bids", String.valueOf(listing.bidCount())));
            if (!winning) {
                lore.add(manager.t("leader", "bidder", listing.topBidderName() == null ? "-" : listing.topBidderName()));
            }
            lore.add("");
            lore.add(manager.t("ends-in", "time", GuiUtil.timeLeft(listing.expiresAt() - System.currentTimeMillis())));
            lore.add("");
            lore.add(manager.t(winning ? "winning" : "click-bid-again"));

            ItemStack icon = GuiUtil.appendLore(listing.item(), lore);
            inventory.setItem(slot, icon);
            slotToListing.put(slot, listing);
        }
    }

    private boolean isTopBidder(Listing listing) {
        return listing.topBidderId() != null && listing.topBidderId().equals(player.getUniqueId());
    }

    private int topBidCount() {
        int n = 0;
        for (Listing l : listings) {
            if (isTopBidder(l)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        int slot = event.getSlot();
        if (slotToListing.containsKey(slot)) {
            Listing listing = slotToListing.get(slot);
            manager.openBid(player, listing, null, null, manager.config().defaultSort(), 0);
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
