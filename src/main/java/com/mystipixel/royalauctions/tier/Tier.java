package com.mystipixel.royalauctions.tier;

import org.bukkit.Material;

/** A tier the browser can filter by. {@code id} is the EcoItems rarity id (its file name), e.g. {@code epic}. */
public record Tier(String id, String displayName, Material icon, boolean enabled) {
}
