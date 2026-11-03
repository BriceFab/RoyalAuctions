package com.mystipixel.royalauctions.data;

import com.mystipixel.royalauctions.api.AuctionCurrency;

/**
 * An event that happened while the player was offline, replayed on their next join. For
 * {@code EXPIRED} events {@code amount} is the listing count, not money. {@code amount} is in
 * {@code currency}; for {@code SOLD} it is what the seller received, after {@code tax}.
 */
public record OfflineEvent(String type, String item, double amount, long createdAt, String currency, double tax) {

    public OfflineEvent(String type, String item, double amount, long createdAt) {
        this(type, item, amount, createdAt, AuctionCurrency.MONEY, 0);
    }

    public static final String SOLD = "SOLD";
    public static final String OUTBID = "OUTBID";
    public static final String WON = "WON";
    public static final String EXPIRED = "EXPIRED";
}
