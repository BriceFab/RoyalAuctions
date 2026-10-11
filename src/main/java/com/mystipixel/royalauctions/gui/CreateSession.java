package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.ListingType;
import org.bukkit.inventory.ItemStack;

/**
 * Per-player state of the create-auction flow. The item is held in collection storage; this only
 * keeps its id and a display copy.
 */
public final class CreateSession {

    private ItemStack item;
    private java.util.UUID collectionId;
    private boolean busy;
    public java.util.UUID collectionId() { return collectionId; }
    public void collectionId(java.util.UUID value) { collectionId = value; }
    public boolean busy() { return busy; }
    public void busy(boolean value) { busy = value; }
    private double price;
    private int durationHours;
    private ListingType type;
    private boolean awaitingPrice;

    public CreateSession(int durationHours, ListingType type) {
        this.durationHours = durationHours;
        this.type = type;
    }

    /** Returns a copy so GUI rendering (which appends lore) can't mutate the stored item. */
    public ItemStack item() {
        return item == null ? null : item.clone();
    }

    public void item(ItemStack item) {
        this.item = item == null ? null : item.clone();
    }

    public boolean hasItem() {
        return item != null && !item.getType().isAir();
    }

    public double price() {
        return price;
    }

    public void price(double price) {
        this.price = price;
    }

    public boolean hasPrice() {
        return price > 0;
    }

    public int durationHours() {
        return durationHours;
    }

    public void durationHours(int durationHours) {
        this.durationHours = durationHours;
    }

    public ListingType type() {
        return type;
    }

    public void type(ListingType type) {
        this.type = type;
    }

    public void toggleType() {
        this.type = type == ListingType.BIN ? ListingType.AUCTION : ListingType.BIN;
    }

    public boolean awaitingPrice() {
        return awaitingPrice;
    }

    public void awaitingPrice(boolean awaitingPrice) {
        this.awaitingPrice = awaitingPrice;
    }

    public boolean isReady() {
        return hasItem() && collectionId != null && hasPrice() && !busy;
    }
}
