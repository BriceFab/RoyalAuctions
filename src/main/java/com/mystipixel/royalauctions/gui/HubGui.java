package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.HashMap;
import java.util.Map;

/** The {@code /ah} landing menu, laid out by gui/hub.yml. */
public final class HubGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;

    public HubGui(GuiManager manager, Player player, int totalBids, int topBids, int myListings, int activeTotal) {
        this.manager = manager;
        this.template = manager.menus().hub();
        this.player = player;

        Map<String, String> ph = new HashMap<>();
        ph.put("total_bids", String.valueOf(totalBids));
        ph.put("top_bids", String.valueOf(topBids));
        ph.put("outbid", String.valueOf(Math.max(0, totalBids - topBids)));
        ph.put("my_listings", String.valueOf(myListings));
        ph.put("active", String.valueOf(activeTotal));
        ph.put("player", player.getName());

        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(ph)));
        template.applyStatic(inventory, ph);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case OPEN_BROWSE -> manager.openBrowse(player);
            case OPEN_BIDS -> manager.openBids(player);
            case OPEN_MANAGE -> manager.openListings(player);
            case OPEN_COLLECTION -> manager.openCollection(player);
            case CREATE_AUCTION -> manager.openCreate(player);
            case CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }
}
