package com.mystipixel.royalauctions.data;

/**
 * An event that happened while the player was offline, replayed on their next join. For
 * {@code EXPIRED} events {@code amount} is the listing count, not money.
 */
public record OfflineEvent(String type, String item, double amount, long createdAt) {

    public static final String SOLD = "SOLD";
    public static final String OUTBID = "OUTBID";
    public static final String WON = "WON";
    public static final String EXPIRED = "EXPIRED";
}
