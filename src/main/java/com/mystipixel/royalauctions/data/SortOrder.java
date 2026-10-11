package com.mystipixel.royalauctions.data;

import java.util.Comparator;

public enum SortOrder {
    NEWEST(Comparator.comparingLong(Listing::createdAt).reversed()),
    OLDEST(Comparator.comparingLong(Listing::createdAt)),
    PRICE_LOW(Comparator.comparingDouble(Listing::price)),
    PRICE_HIGH(Comparator.comparingDouble(Listing::price).reversed()),
    /** Soonest to expire first. */
    ENDING_SOON(Comparator.comparingLong(Listing::expiresAt));

    private final Comparator<Listing> comparator;

    SortOrder(Comparator<Listing> comparator) {
        this.comparator = comparator;
    }

    public Comparator<Listing> comparator() {
        return comparator;
    }

    public SortOrder next() {
        SortOrder[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static SortOrder fromString(String name, SortOrder fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
