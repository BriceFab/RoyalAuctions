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

/** Manage Auctions — layout from gui/manage.yml; right-click a listing to cancel (blocked once bid on). */
public final class ListingsGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;
    private final List<Listing> listings;
    private final Map<Integer, Listing> slotToListing = new HashMap<>();
    private int page;

    public ListingsGui(GuiManager manager, Player player, List<Listing> listings) {
        this.manager = manager;
        this.template = manager.menus().manage();
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

        template.applyStatic(inventory, Map.of(
                "count", String.valueOf(listings.size()),
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages)));

        int from = page * perPage;
        for (int i = 0; i < perPage && from + i < listings.size(); i++) {
            int slot = slots.get(i);
            Listing listing = listings.get(from + i);
            long remaining = listing.expiresAt() - System.currentTimeMillis();
            List<String> lore = new ArrayList<>();
            lore.add("");
            if (listing.isAuction()) {
                lore.add(manager.t("type-line", "type", manager.t("type.auction")));
                lore.add(manager.t("current-bid", "price", manager.vault().format(listing.displayPrice())));
                lore.add(manager.t("bids", "bids", String.valueOf(listing.bidCount())));
            } else {
                lore.add(manager.t("type-line", "type", manager.t("type.bin")));
                lore.add(manager.t("price", "price", manager.vault().format(listing.price())));
            }
            lore.add(manager.t("ends-in", "time", GuiUtil.timeLeft(remaining)));
            lore.add("");
            lore.add(manager.t(listing.hasBids() ? "cannot-cancel" : "right-click-cancel"));
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
            if (!event.isRightClick()) {
                return;
            }
            Listing listing = slotToListing.get(slot);
            if (listing.hasBids()) {
                manager.messages().send(player, "cancel.has-bids");
                return;
            }
            manager.confirmCancel(player, listing, page);
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
