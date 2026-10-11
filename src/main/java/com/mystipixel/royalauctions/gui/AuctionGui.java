package com.mystipixel.royalauctions.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Base for every menu. The GUI object is the {@link InventoryHolder}, so one listener can recognise
 * our menus without tracking open inventories.
 */
public abstract class AuctionGui implements InventoryHolder {

    protected Inventory inventory;

    @NotNull
    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The event is already cancelled; most menus only act on top-inventory clicks. */
    public abstract void onClick(InventoryClickEvent event);

    public void onClose(InventoryCloseEvent event) {
    }

    protected boolean isTopClick(InventoryClickEvent event) {
        return inventory.equals(event.getClickedInventory());
    }
}
