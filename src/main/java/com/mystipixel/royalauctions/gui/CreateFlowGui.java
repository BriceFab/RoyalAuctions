package com.mystipixel.royalauctions.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Shared base for the create-auction screens (create / duration / confirm). Its job is to return
 * the escrowed item whenever the player closes the flow "for real" — i.e. not because we are
 * opening the next screen in the flow ({@code OPEN_NEW}) and not because we closed the menu to
 * collect a typed price in a dialog ({@code awaitingPrice}).
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
            return; // moving to another screen in the flow
        }
        if (session.awaitingPrice()) {
            return; // closed to type the price in the dialog
        }
        manager.endCreateSession(player, true);
    }
}
