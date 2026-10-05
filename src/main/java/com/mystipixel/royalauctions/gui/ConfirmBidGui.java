package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Confirm Bid — layout from gui/confirm-bid.yml.
 *
 * <p>A bid moves real money and can't be taken back (you're only refunded if someone outbids you), so
 * it gets the same gate Buy-It-Now already had. Skippable via {@code confirmations.bid: false}.
 */
public final class ConfirmBidGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;
    private final Listing listing;
    private final double amount;
    private final String category;
    private final String search;
    private final SortOrder sort;
    private final int page;
    private boolean submitted;

    public ConfirmBidGui(GuiManager manager, Player player, Listing listing, double amount,
                         String category, String search, SortOrder sort, int page) {
        this.manager = manager;
        this.template = manager.menus().confirmBid();
        this.player = player;
        this.listing = listing;
        this.amount = amount;
        this.category = category;
        this.search = search;
        this.sort = sort;
        this.page = page;

        Map<String, String> ph = new HashMap<>();
        ph.put("bid", manager.vault().format(amount));
        ph.put("current_bid", manager.vault().format(listing.displayPrice()));
        ph.put("seller", listing.sellerName());
        ph.put("balance", manager.vault().format(manager.vault().balance(player)));

        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(ph)));
        template.applyStatic(inventory, ph);

        int itemSlot = template.slotOf("item");
        if (itemSlot >= 0) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(manager.t("seller", "seller", listing.sellerName()));
            lore.add(listing.hasBids()
                    ? manager.t("current-bid", "price", manager.vault().format(listing.currentBid()))
                    : manager.t("starting-bid", "price", manager.vault().format(listing.price())));
            lore.add(manager.t("your-bid", "price", manager.vault().format(amount)));
            lore.add("");
            lore.add(manager.t("ends-in", "time", GuiUtil.timeLeft(listing.expiresAt() - System.currentTimeMillis())));
            inventory.setItem(itemSlot, GuiUtil.appendLore(listing.item(), lore));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case CONFIRM_BID -> {
                if (!submitted) {
                    submitted = true;
                    manager.service().placeBid(player, listing, amount,
                            () -> manager.openBrowse(player, category, search, sort, page));
                }
            }
            case BACK, CANCEL -> manager.reopenBid(player, listing, category, search, sort, page);
            case OPEN_BROWSE -> manager.openBrowse(player, category, search, sort, page);
            case CLOSE -> player.closeInventory();
            default -> {
                // decorative
            }
        }
    }
}
