package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.Listing;
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
 * Confirm Cancel — layout from gui/confirm-cancel.yml.
 *
 * <p>Cancelling used to happen on a bare right-click in Manage Auctions, one misclick away from
 * pulling a listing you meant to keep. Skippable via {@code confirmations.cancel: false}.
 */
public final class ConfirmCancelGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;
    private final Listing listing;
    private final int page;
    private boolean submitted;

    public ConfirmCancelGui(GuiManager manager, Player player, Listing listing, int page) {
        this.manager = manager;
        this.template = manager.menus().confirmCancel();
        this.player = player;
        this.listing = listing;
        this.page = page;

        Map<String, String> ph = new HashMap<>();
        ph.put("price", manager.vault().format(listing.displayPrice()));
        ph.put("bids", String.valueOf(listing.bidCount()));

        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(ph)));
        template.applyStatic(inventory, ph);

        int itemSlot = template.slotOf("item");
        if (itemSlot >= 0) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(manager.t("type-line", "type", manager.t(listing.isAuction() ? "type.auction" : "type.bin")));
            lore.add(manager.t("price", "price", manager.vault().format(listing.displayPrice())));
            lore.add("");
            lore.add(manager.t("cancel-returns"));
            inventory.setItem(itemSlot, GuiUtil.appendLore(listing.item(), lore));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case CONFIRM_CANCEL -> {
                if (!submitted) {
                    submitted = true;
                    manager.service().cancelListing(player, listing, () -> manager.openListings(player, page));
                }
            }
            case BACK, CANCEL, OPEN_MANAGE -> manager.openListings(player, page);
            case CLOSE -> player.closeInventory();
            default -> {
                // decorative
            }
        }
    }
}
