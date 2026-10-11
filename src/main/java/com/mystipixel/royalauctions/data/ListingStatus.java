package com.mystipixel.royalauctions.data;

public enum ListingStatus {
    /** Persisted create operation awaiting its listing fee. Not visible or tradeable. */
    DRAFT,
    ACTIVE,
    SOLD,
    EXPIRED,
    CANCELLED
}
