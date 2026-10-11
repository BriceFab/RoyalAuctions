package com.mystipixel.royalauctions.gui.menu;

import java.util.Locale;
import java.util.Map;

/**
 * Click actions a GUI button can carry, authored as effect ids in the menu files:
 * <pre>
 * left-click:
 *   - id: ah_search
 * </pre>
 */
public enum MenuAction {
    NONE,
    OPEN_HUB,
    OPEN_BIDS,
    SEARCH,
    SORT,
    // Filters step through their list: prev = up, next = down (bind to left/right click in config).
    TIER_PREV,
    TIER_NEXT,
    TYPE_PREV,
    TYPE_NEXT,
    CREATE_AUCTION,
    PREV_PAGE,
    NEXT_PAGE,
    CLEAR_FILTERS,
    OPEN_MANAGE,
    OPEN_COLLECTION,
    OPEN_BROWSE,
    CLOSE,
    SET_PRICE,
    SET_DURATION,
    TOGGLE_TYPE,
    REMOVE_ITEM,
    CONTINUE,
    CONFIRM,
    CANCEL,
    BACK,
    CONFIRM_PURCHASE,
    BID_MIN,
    BID_CUSTOM,
    CONFIRM_BID,
    CONFIRM_CANCEL,
    CLAIM_EARNINGS;

    private static final Map<String, MenuAction> BY_EFFECT_ID = Map.ofEntries(
            Map.entry("ah_hub", OPEN_HUB),
            Map.entry("ah_bids", OPEN_BIDS),
            Map.entry("ah_browse", OPEN_BROWSE),
            Map.entry("ah_search", SEARCH),
            Map.entry("ah_sort", SORT),
            Map.entry("ah_tier_prev", TIER_PREV),
            Map.entry("ah_tier_next", TIER_NEXT),
            Map.entry("ah_type_prev", TYPE_PREV),
            Map.entry("ah_type_next", TYPE_NEXT),
            Map.entry("ah_create", CREATE_AUCTION),
            Map.entry("ah_manage", OPEN_MANAGE),
            Map.entry("ah_collection", OPEN_COLLECTION),
            Map.entry("ah_clear_filters", CLEAR_FILTERS),
            Map.entry("ah_prev_page", PREV_PAGE),
            Map.entry("ah_next_page", NEXT_PAGE),
            Map.entry("ah_set_price", SET_PRICE),
            Map.entry("ah_set_duration", SET_DURATION),
            Map.entry("ah_toggle_type", TOGGLE_TYPE),
            Map.entry("ah_remove_item", REMOVE_ITEM),
            Map.entry("ah_continue", CONTINUE),
            Map.entry("ah_confirm", CONFIRM),
            Map.entry("ah_confirm_purchase", CONFIRM_PURCHASE),
            Map.entry("ah_cancel", CANCEL),
            Map.entry("ah_back", BACK),
            Map.entry("ah_bid_min", BID_MIN),
            Map.entry("ah_bid_custom", BID_CUSTOM),
            Map.entry("ah_confirm_bid", CONFIRM_BID),
            Map.entry("ah_confirm_cancel", CONFIRM_CANCEL),
            Map.entry("ah_claim_earnings", CLAIM_EARNINGS),
            Map.entry("close_inventory", CLOSE));

    /** Unknown ids resolve to NONE rather than failing. */
    public static MenuAction fromEffectId(String id) {
        if (id == null) {
            return NONE;
        }
        return BY_EFFECT_ID.getOrDefault(id.toLowerCase(Locale.ROOT), NONE);
    }

    public static MenuAction parse(String raw) {
        if (raw == null) {
            return NONE;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
