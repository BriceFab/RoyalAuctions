package com.mystipixel.royalauctions.data;

import java.util.Set;

/**
 * The filters behind one browse view. Every field is optional except the sort — a null category, tier
 * or type means "no filter on that", and a null/blank search matches everything. {@code searchNames}
 * are extra lower-case listing names a search also matches exactly: the vanilla items whose name in
 * another language contains the search text.
 *
 * <p>This is what the browse menu hands to the database so filtering, sorting and paging all happen in
 * SQL. The menu used to load every active listing and do the work in memory, which meant a full table
 * read (including each listing's serialized item) every time anyone opened the auction house, changed a
 * filter, or turned a page.
 */
public record ListingQuery(String category, String tier, ListingType type, String search, SortOrder sort,
                           Set<String> searchNames) {

    public ListingQuery(String category, String tier, ListingType type, String search, SortOrder sort) {
        this(category, tier, type, search, sort, Set.of());
    }

    public ListingQuery {
        searchNames = searchNames == null ? Set.of() : Set.copyOf(searchNames);
        if (sort == null) {
            sort = SortOrder.NEWEST;
        }
        if (search != null && search.isBlank()) {
            search = null;
        }
    }

    public static ListingQuery newest() {
        return new ListingQuery(null, null, null, null, SortOrder.NEWEST);
    }
}
