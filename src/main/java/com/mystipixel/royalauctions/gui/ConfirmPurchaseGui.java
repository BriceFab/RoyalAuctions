package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.Map;

/** Confirm Purchase (Buy It Now) — layout from gui/confirm-purchase.yml. */
public final class ConfirmPurchaseGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Listing listing;
    private final String category;
    private final String search;
    private final SortOrder sort;
    private final int page;
    private boolean submitted;

    public ConfirmPurchaseGui(GuiManager manager, Listing listing, String category, String search,
                              SortOrder sort, int page) {
        this.manager = manager;
        this.template = manager.menus().confirmPurchase();
        this.listing = listing;
        this.category = category;
        this.search = search;
        this.sort = sort;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
        render();
    }

    private void render() {
        template.applyStatic(inventory, Map.of(
                "price", manager.vault().format(listing.price()),
                "seller", listing.sellerName()));
        int itemSlot = template.slotOf("item");
        if (itemSlot >= 0) {
            inventory.setItem(itemSlot, GuiUtil.appendLore(listing.item(), List.of(
                    "",
                    manager.t("price", "price", manager.vault().format(listing.price())),
                    manager.t("seller", "seller", listing.sellerName()))));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case CONFIRM_PURCHASE -> {
                if (!submitted) {
                    submitted = true;
                    manager.service().purchase(player, listing,
                            () -> manager.openBrowse(player, category, search, sort, page));
                }
            }
            case OPEN_BROWSE, CANCEL -> manager.openBrowse(player, category, search, sort, page);
            default -> {
                // decorative
            }
        }
    }
}
