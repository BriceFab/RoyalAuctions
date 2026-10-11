package com.mystipixel.royalauctions.data;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * One auction listing. {@code itemData} is the source of truth; the decoded item is cached lazily.
 * {@code price} is the buy-now price for BIN and the starting bid for AUCTION ({@code bidCount == 0}
 * means no bids yet).
 */
public final class Listing {

    private final UUID id;
    private final UUID sellerId;
    private final String sellerName;
    private final byte[] itemData;
    private final String displayName;
    private String category;        // re-derived if the configured categories are renamed
    private final String tier;      // EcoItems rarity id, or null when the item has no tier
    private final ListingType type;
    private final double price;
    private final long createdAt;
    private final long expiresAt;
    private ListingStatus status;

    private final double currentBid;
    private final UUID topBidderId;
    private final String topBidderName;
    private final int bidCount;
    private final String enchantmentIndex;  // null until indexed, see EnchantmentIndex

    private transient ItemStack cachedItem;

    public Listing(UUID id, UUID sellerId, String sellerName, byte[] itemData, String displayName,
                   String category, String tier, ListingType type, double price, long createdAt, long expiresAt,
                   ListingStatus status, double currentBid, UUID topBidderId, String topBidderName, int bidCount) {
        this(id, sellerId, sellerName, itemData, displayName, category, tier, type, price, createdAt, expiresAt,
                status, currentBid, topBidderId, topBidderName, bidCount, null);
    }

    public Listing(UUID id, UUID sellerId, String sellerName, byte[] itemData, String displayName,
                   String category, String tier, ListingType type, double price, long createdAt, long expiresAt,
                   ListingStatus status, double currentBid, UUID topBidderId, String topBidderName, int bidCount,
                   String enchantmentIndex) {
        this.id = id;
        this.sellerId = sellerId;
        this.sellerName = sellerName;
        this.itemData = itemData;
        this.displayName = displayName;
        this.category = category;
        this.tier = tier;
        this.type = type;
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = status;
        this.currentBid = currentBid;
        this.topBidderId = topBidderId;
        this.topBidderName = topBidderName;
        this.bidCount = bidCount;
        this.enchantmentIndex = enchantmentIndex;
    }

    public UUID id() {
        return id;
    }

    public UUID sellerId() {
        return sellerId;
    }

    public String sellerName() {
        return sellerName;
    }

    public byte[] itemData() {
        return itemData;
    }

    public String displayName() {
        return displayName;
    }

    public String category() {
        return category;
    }

    public void category(String category) {
        this.category = category;
    }

    public String tier() {
        return tier;
    }

    public ListingType type() {
        return type;
    }

    public double price() {
        return price;
    }

    public long createdAt() {
        return createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public ListingStatus status() {
        return status;
    }

    public void status(ListingStatus status) {
        this.status = status;
    }

    public double currentBid() {
        return currentBid;
    }

    public UUID topBidderId() {
        return topBidderId;
    }

    public String topBidderName() {
        return topBidderName;
    }

    public int bidCount() {
        return bidCount;
    }

    public String enchantmentIndex() {
        return enchantmentIndex;
    }

    public boolean isAuction() {
        return type == ListingType.AUCTION;
    }

    public boolean hasBids() {
        return bidCount > 0 && topBidderId != null;
    }

    /** The amount shown to buyers: the leading bid for a live auction, otherwise the base price. */
    public double displayPrice() {
        return isAuction() && hasBids() ? currentBid : price;
    }

    /**
     * Minimum acceptable next bid. Rounded to six places so floating-point noise (110.00000000000001)
     * cannot reject a bid of exactly the "110.00" the menu shows.
     */
    public double nextMinBid(double increment) {
        double minimum = hasBids() ? currentBid + increment : price;
        if (!Double.isFinite(minimum)) {
            return minimum;
        }
        return java.math.BigDecimal.valueOf(minimum).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    public ItemStack item() {
        if (cachedItem == null) {
            cachedItem = ItemSerialization.deserialize(itemData);
        }
        return cachedItem.clone();
    }
}
