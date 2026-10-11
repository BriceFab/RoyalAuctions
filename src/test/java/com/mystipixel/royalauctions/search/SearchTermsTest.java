package com.mystipixel.royalauctions.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SearchTermsTest {

    static final List<String> REGISTRY = List.of("minecraft:sharpness", "minecraft:smite", "minecraft:silk_touch",
            "minecraft:unbreaking", "minecraft:lure", "minecraft:flame", "minecraft:sweeping_edge",
            "minecraft:fire_protection", "minecraft:protection", "ecoenchants:telekinesis");

    static final Map<String, String> FRENCH = Map.of(
            "item.minecraft.diamond_helmet", "Casque en diamant",
            "item.minecraft.iron_helmet", "Casque en fer",
            "item.minecraft.diamond_sword", "Épée en diamant",
            "block.minecraft.oak_planks", "Planches de chêne",
            "item.minecraft.diamond_sword.desc", "casque",
            "enchantment.minecraft.sharpness", "Tranchant",
            "enchantment.minecraft.unbreaking", "Solidité",
            "enchantment.minecraft.sharpness.desc", "ignore me");

    static SearchTerms loaded() {
        SearchTerms terms = new SearchTerms();
        terms.load(List.of(FRENCH), REGISTRY);
        return terms;
    }

    @Test void translatedItemNamesIgnoreCaseAndAccents() {
        SearchTerms terms = loaded();
        assertEquals(Set.of("diamond helmet", "iron helmet"), terms.resolve("CASQUE").itemNames());
        assertEquals(Set.of("diamond sword"), terms.resolve("epee").itemNames());
        assertEquals(Set.of("oak planks"), terms.resolve("chêne").itemNames());
        assertEquals(Set.of(), terms.resolve("helmet").itemNames(), "English is matched by the listing name itself");
    }

    @Test void enchantmentsMatchByEnglishNameWithoutLanguageFiles() {
        SearchTerms terms = new SearchTerms();
        terms.load(List.of(), REGISTRY);
        assertEquals(Set.of(":sharp=", "|minecraft:sharpness="), terms.resolve("sharp").enchantmentTokens());
        assertEquals(Set.of(":silk_touch=", "|minecraft:silk_touch="), terms.resolve("Silk Touch").enchantmentTokens());
        assertTrue(terms.resolve("telekinesis").enchantmentTokens().contains("|ecoenchants:telekinesis="));
    }

    @Test void translatedEnchantmentNames() {
        SearchTerms terms = loaded();
        assertTrue(terms.resolve("tranchant").enchantmentTokens().contains("|minecraft:sharpness="));
        assertTrue(terms.resolve("SOLIDITE III").enchantmentTokens().contains("|minecraft:unbreaking=3|"));
    }

    @Test void levelsAsNumbersOrRomanNumerals() {
        SearchTerms terms = loaded();
        assertTrue(terms.resolve("sharpness 5").enchantmentTokens().contains("|minecraft:sharpness=5|"));
        assertTrue(terms.resolve("sharpness V").enchantmentTokens().contains("|minecraft:sharpness=5|"));
        assertTrue(terms.resolve("sharpness 05").enchantmentTokens().contains("|minecraft:sharpness=5|"));
        assertEquals(Set.of("|minecraft:sharpness=5|"), terms.resolve("minecraft:sharpness V").enchantmentTokens());
        assertEquals(0, SearchTerms.level("0"));
        assertEquals(0, SearchTerms.level("xi"));
        assertEquals(10, SearchTerms.level("x"));
        assertEquals(0, SearchTerms.level("1234"));
    }

    @Test void noFalsePositives() {
        SearchTerms terms = loaded();
        // short text only matches an exact id, never the end of another one (flame, sweeping_edge)
        assertEquals(Set.of(":e="), terms.resolve("e").enchantmentTokens());
        assertEquals(Set.of(":lure=", "|minecraft:lure="), terms.resolve("lure").enchantmentTokens());
        assertFalse(terms.resolve("sharp").enchantmentTokens().contains("|minecraft:smite="));
        assertEquals(Set.of(":fire_protection=", "|minecraft:fire_protection="),
                terms.resolve("fire protection").enchantmentTokens());
        assertEquals(Set.of(), terms.resolve("zzz").itemNames());
        assertEquals(SearchTerms.Match.NONE, terms.resolve("   "));
        assertEquals(SearchTerms.Match.NONE, terms.resolve(null));
    }

    @Test void resultsAreCapped() {
        Map<String, String> many = new java.util.HashMap<>();
        for (int i = 0; i < 500; i++) many.put("item.minecraft.thing_" + i, "Thing " + i);
        SearchTerms terms = new SearchTerms();
        terms.load(List.of(many), List.of());
        assertEquals(SearchTerms.MAX_ITEM_NAMES, terms.resolve("thing").itemNames().size());
    }

    @Test void readsLanguageFilesAndSkipsBrokenOnes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fr_fr.json"), "{\"item.minecraft.diamond_helmet\": \"Casque en diamant\"}");
        Files.writeString(dir.resolve("broken.json"), "{not json");
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        var langs = SearchTerms.readLanguageFiles(dir.toFile(), Logger.getAnonymousLogger());
        assertEquals(1, langs.size());
        assertEquals(List.of(), SearchTerms.readLanguageFiles(dir.resolve("missing").toFile(), Logger.getAnonymousLogger()));
    }

    @Test void reloadReplacesNames() {
        SearchTerms terms = loaded();
        terms.load(List.of(), List.of());
        assertEquals(Set.of(), terms.resolve("casque").itemNames());
        assertEquals(Set.of(":tranchant="), terms.resolve("tranchant").enchantmentTokens());
    }
}
