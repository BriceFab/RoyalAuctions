package com.mystipixel.royalauctions.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Base for the create-auction screens. Returns the escrowed item when the player really leaves the
 * flow, not when moving to the next screen or closing to type a price.
 */
public abstract class CreateFlowGui extends AuctionGui {

    protected final GuiManager manager;
    protected final Player player;

    protected CreateFlowGui(GuiManager manager, Player player) {
        this.manager = manager;
        this.player = player;
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        CreateSession session = manager.session(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (event.getReason() == InventoryCloseEvent.Reason.OPEN_NEW) {
            return;
        }
        if (session.awaitingPrice()) {
            return; // closed to type the price in the dialog
        }
        manager.endCreateSession(player, true);
    }
}
