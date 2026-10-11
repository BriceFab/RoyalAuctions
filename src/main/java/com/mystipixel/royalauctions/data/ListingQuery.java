package com.mystipixel.royalauctions.data;

import java.util.Set;

/**
 * The filters behind one browse view. Everything but the sort is optional: null means no filter, and a
 * blank search matches everything. A search also matches listings named exactly one of
 * {@code searchItemNames} (lower case) or whose enchantment index contains one of
 * {@code searchEnchantments}; both come from {@code SearchTerms}.
 */
public record ListingQuery(String category, String tier, ListingType type, String search, SortOrder sort,
                           Set<String> searchItemNames, Set<String> searchEnchantments) {

    public ListingQuery(String category, String tier, ListingType type, String search, SortOrder sort) {
        this(category, tier, type, search, sort, Set.of(), Set.of());
    }

    public ListingQuery {
        searchItemNames = searchItemNames == null ? Set.of() : Set.copyOf(searchItemNames);
        searchEnchantments = searchEnchantments == null ? Set.of() : Set.copyOf(searchEnchantments);
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
