package com.mystipixel.royalauctions.data;

import java.util.List;

/** One page of browse results plus the total match count, for "page 2 of 7". */
public record ListingPage(List<Listing> rows, int total) {

    public static ListingPage empty() {
        return new ListingPage(List.of(), 0);
    }

    /** Total pages at this page size, never less than one (an empty result still shows page 1 of 1). */
    public int pageCount(int perPage) {
        int size = Math.max(1, perPage);
        return Math.max(1, (int) Math.ceil(total / (double) size));
    }
}
