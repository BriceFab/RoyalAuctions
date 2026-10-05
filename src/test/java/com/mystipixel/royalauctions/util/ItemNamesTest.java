package com.mystipixel.royalauctions.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemNamesTest {

    @Test
    void mapsTranslatedNamesToListingNames() {
        ItemNames names = new ItemNames();
        names.load(List.of(Map.of(
                "item.minecraft.diamond_helmet", "Casque en diamant",
                "item.minecraft.iron_helmet", "Casque en fer",
                "item.minecraft.diamond_sword", "Épée en diamant",
                "item.minecraft.diamond_sword.desc", "casque")));

        assertEquals(Set.of("diamond helmet", "iron helmet"), names.listingNamesMatching("CASQUE"));
        assertEquals(Set.of("diamond sword"), names.listingNamesMatching("epee"));
        assertEquals(Set.of(), names.listingNamesMatching(" "));
    }
}
