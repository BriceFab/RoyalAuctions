package com.mystipixel.royalauctions.data;

import com.mystipixel.royalauctions.api.AuctionCurrency;

/**
 * An event that happened while the player was offline, replayed on their next join. For
 * {@code EXPIRED} events {@code amount} is the listing count, not money; otherwise it is in {@code currency}.
 */
public record OfflineEvent(String type, String item, double amount, long createdAt, String currency) {

    public OfflineEvent(String type, String item, double amount, long createdAt) {
        this(type, item, amount, createdAt, AuctionCurrency.MONEY);
    }

    public static final String SOLD = "SOLD";
    public static final String OUTBID = "OUTBID";
    public static final String WON = "WON";
    public static final String EXPIRED = "EXPIRED";
}
