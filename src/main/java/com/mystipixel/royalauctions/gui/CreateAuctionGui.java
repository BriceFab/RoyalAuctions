package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.gui.menu.MenuAction;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/** Create Auction menu (gui/create.yml). The item lives in the CreateSession, not the menu. */
public final class CreateAuctionGui extends CreateFlowGui {

    private final MenuTemplate template;

    public CreateAuctionGui(GuiManager manager, Player player) {
        super(manager, player);
        this.template = manager.menus().create();
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
        render();
    }

    private void render() {
        CreateSession s = manager.session(player.getUniqueId());
        boolean auction = s != null && s.type() == ListingType.AUCTION;

        Map<String, String> placeholders = Map.of(
                "price", s != null && s.hasPrice() ? manager.vault().format(s.price()) : manager.t("not-set"),
                "duration", durationLabel(s == null ? manager.config().defaultDurationHours() : s.durationHours()),
                "type_material", template.text(auction ? "type-materials.auction" : "type-materials.bin"),
                "type_name", manager.t(auction ? "type.auction" : "type.bin"),
                "type_desc", manager.t(auction ? "type.auction-desc" : "type.bin-desc"),
                "toggle_hint", manager.t("type-toggle", "type", manager.t(auction ? "type.bin" : "type.auction")));
        template.applyStatic(inventory, placeholders);

        int itemSlot = template.slotOf("item");
        if (itemSlot >= 0 && s != null && s.hasItem()) {
            ItemStack shown = GuiUtil.appendLore(s.item(),
                    List.of("", manager.t("click-remove-item")));
            inventory.setItem(itemSlot, MenuTemplate.tag(shown, MenuAction.REMOVE_ITEM));
        }
    }

    private String durationLabel(int hours) {
        return manager.config().durations().stream()
                .filter(d -> d.hours() == hours)
                .map(PluginConfig.DurationOption::label)
                .findFirst()
                .orElse(manager.t("time.duration", "hours", String.valueOf(hours)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        CreateSession s = manager.session(player.getUniqueId());
        if (s == null || s.busy()) {
            return;
        }

        if (!isTopClick(event)) {
            if (!s.hasItem() && event.getClickedInventory() != null) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir()) {
                    if (event.getClickedInventory() != player.getInventory()) return;
                    manager.captureCreateItem(player, event.getSlot(), clicked.clone());
                }
            }
            return;
        }

        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case REMOVE_ITEM -> {
                if (s.hasItem()) {
                    manager.removeCreateItem(player);
                }
            }
            case SET_PRICE -> manager.beginPriceInput(player);
            case SET_DURATION -> manager.openDuration(player);
            case TOGGLE_TYPE -> {
                s.toggleType();
                render();
            }
            case CONTINUE -> {
                if (!s.hasItem()) {
                    manager.messages().send(player, "create.need-item");
                } else if (!s.hasPrice()) {
                    manager.messages().send(player, "create.need-price");
                } else {
                    manager.openConfirmAuction(player);
                }
            }
            case CANCEL -> manager.cancelCreate(player);
            default -> {
            }
        }
    }
}
