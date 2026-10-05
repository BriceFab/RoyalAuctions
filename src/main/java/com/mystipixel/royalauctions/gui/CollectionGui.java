package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.CollectionItem;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collection — layout from gui/collection.yml; items fill content-slots. */
public final class CollectionGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;
    private final List<CollectionItem> items;
    /** Sale money waiting to be collected (manual-earnings-collection), shown on the earnings button. */
    private final double earnings;
    private final Map<Integer, CollectionItem> slotToItem = new HashMap<>();
    private int page;
    /** One claim at a time; the menu reopens with fresh contents when it completes. */
    private boolean claiming;

    public CollectionGui(GuiManager manager, Player player, List<CollectionItem> items, double earnings) {
        this.manager = manager;
        this.earnings = earnings;
        this.template = manager.menus().collection();
        this.player = player;
        this.items = items;
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
    }

    public void populate(int page) {
        this.page = Math.max(0, page);
        render();
    }

    private void render() {
        inventory.clear();
        slotToItem.clear();

        List<Integer> slots = template.slots("content-slots");
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) perPage));
        if (page >= pages) {
            page = pages - 1;
        }

        template.applyStatic(inventory, Map.of(
                "count", String.valueOf(items.size()),
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages),
                "earnings", manager.vault().format(earnings)));
        int earningsSlot = template.slotOf("earnings");
        if (earningsSlot >= 0 && !manager.config().manualEarnings()) {
            inventory.setItem(earningsSlot, template.maskAt(earningsSlot));
        }

        int from = page * perPage;
        for (int i = 0; i < perPage && from + i < items.size(); i++) {
            int slot = slots.get(i);
            CollectionItem ci = items.get(from + i);
            ItemStack icon = GuiUtil.appendLore(ci.item(), List.of(
                    "",
                    manager.t("collection-reason", "reason", prettyReason(ci.reason())),
                    manager.t("click-claim")));
            inventory.setItem(slot, icon);
            slotToItem.put(slot, ci);
        }
    }

    private String prettyReason(CollectionItem.Reason reason) {
        return switch (reason) {
            case PURCHASE -> manager.t("reason.purchase");
            case EXPIRED -> manager.t("reason.expired");
            case CANCELLED -> manager.t("reason.cancelled");
        };
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        int slot = event.getSlot();
        if (slotToItem.containsKey(slot)) {
            if (!claiming) {
                claiming = true;
                manager.service().claim(player, slotToItem.get(slot), () -> manager.openCollection(player, page));
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
            case CLAIM_EARNINGS -> {
                if (!claiming && manager.config().manualEarnings()) {
                    claiming = true;
                    manager.service().claimEarnings(player, () -> manager.openCollection(player, page));
                }
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
