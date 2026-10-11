package com.mystipixel.royalauctions.search;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns search text into extra matches besides the listing name: vanilla items whose translated name
 * contains it, and enchantments it names, with an optional level ({@code sharpness 5}, {@code sharpness V}).
 * Translations come from language files in {@code lang/}. Thread-safe.
 */
public final class SearchTerms {

    static final int MAX_ITEM_NAMES = 200;
    static final int MAX_ENCHANTMENT_TOKENS = 100;
    // shorter text only matches an exact enchantment id, so "e" doesn't pull in every enchanted item
    static final int MIN_PARTIAL_LENGTH = 3;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern ITEM_KEY = Pattern.compile("(?:item|block)\\.minecraft\\.([a-z0-9_]+)");
    private static final Pattern ENCHANTMENT_KEY = Pattern.compile("enchantment\\.([a-z0-9_.-]+)\\.([a-z0-9_/.-]+)");
    private static final Pattern ARABIC_LEVEL = Pattern.compile("[0-9]{1,3}");
    private static final List<String> ROMAN_LEVELS = List.of("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x");
    private static final Type LANG_FILE = new TypeToken<Map<String, String>>() { }.getType();

    /** What a search matches besides the listing name. Both sets are empty when there is nothing extra. */
    public record Match(Set<String> itemNames, Set<String> enchantmentTokens) {
        public static final Match NONE = new Match(Set.of(), Set.of());

        public Match {
            itemNames = Set.copyOf(itemNames);
            enchantmentTokens = Set.copyOf(enchantmentTokens);
        }
    }

    // item id (diamond_helmet) and enchantment key (minecraft:sharpness) to their normalised names
    private record Names(Map<String, List<String>> items, Map<String, List<String>> enchantments) { }

    private volatile Names names = new Names(Map.of(), Map.of());

    /** Lower case, accents removed, trimmed: the form both sides of a match are compared in. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String stripped = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT).trim();
    }

    /** Reads every {@code *.json} language file in {@code dir}. Does file I/O: call it off the main thread. */
    public static List<Map<String, String>> readLanguageFiles(File dir, Logger logger) {
        List<Map<String, String>> langs = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return langs;
        }
        Gson gson = new Gson();
        for (File file : files) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Map<String, String> lang = gson.fromJson(reader, LANG_FILE);
                if (lang != null) {
                    langs.add(lang);
                }
            } catch (IOException | JsonParseException e) {
                logger.log(Level.WARNING, "Could not read language file " + file.getName(), e);
            }
        }
        if (!langs.isEmpty()) {
            logger.info("Search: loaded item and enchantment names from " + langs.size() + " language file(s).");
        }
        return langs;
    }

    /**
     * Replaces the known names. {@code enchantmentKeys} are the registered enchantments
     * ({@code minecraft:silk_touch}), which also match by their id read as words ("silk touch").
     */
    public void load(Collection<Map<String, String>> langs, Collection<String> enchantmentKeys) {
        Map<String, List<String>> items = new HashMap<>();
        Map<String, List<String>> enchantments = new HashMap<>();
        for (String key : enchantmentKeys) {
            String path = key.substring(key.indexOf(':') + 1);
            enchantments.computeIfAbsent(key, k -> new ArrayList<>()).add(path.replace('_', ' '));
        }
        for (Map<String, String> lang : langs) {
            for (Map.Entry<String, String> entry : lang.entrySet()) {
                Matcher item = ITEM_KEY.matcher(entry.getKey());
                if (item.matches()) {
                    items.computeIfAbsent(item.group(1), k -> new ArrayList<>()).add(normalize(entry.getValue()));
                    continue;
                }
                Matcher enchantment = ENCHANTMENT_KEY.matcher(entry.getKey());
                if (enchantment.matches()) {
                    enchantments.computeIfAbsent(enchantment.group(1) + ":" + enchantment.group(2), k -> new ArrayList<>())
                            .add(normalize(entry.getValue()));
                }
            }
        }
        names = new Names(items, enchantments);
    }

    public Match resolve(String search) {
        String text = normalize(search);
        if (text.isEmpty()) {
            return Match.NONE;
        }
        Names current = names;
        return new Match(itemNames(current, text), enchantmentTokens(current, text));
    }

    // Unnamed vanilla items are listed under their material name in sentence case ("Diamond helmet").
    private static Set<String> itemNames(Names current, String text) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : current.items().entrySet()) {
            if (containsAny(entry.getValue(), text)) {
                out.add(entry.getKey().replace('_', ' '));
                if (out.size() >= MAX_ITEM_NAMES) {
                    break;
                }
            }
        }
        return out;
    }

    private static Set<String> enchantmentTokens(Names current, String text) {
        String name = text;
        int level = 0;
        int space = text.lastIndexOf(' ');
        if (space > 0) {
            level = level(text.substring(space + 1));
            if (level > 0) {
                name = text.substring(0, space).trim();
            }
        }
        String suffix = "=" + (level > 0 ? level + "|" : "");
        Set<String> out = new LinkedHashSet<>();
        if (name.indexOf(':') > 0) {
            out.add("|" + name.replace(' ', '_') + suffix);
            return out;
        }
        // exact id in any namespace; the ':' anchor stops "lure" from matching inside another id
        out.add(":" + name.replace(' ', '_') + suffix);
        if (name.length() < MIN_PARTIAL_LENGTH) {
            return out;
        }
        for (Map.Entry<String, List<String>> entry : current.enchantments().entrySet()) {
            if (containsAny(entry.getValue(), name)) {
                out.add("|" + entry.getKey() + suffix);
                if (out.size() >= MAX_ENCHANTMENT_TOKENS) {
                    break;
                }
            }
        }
        return out;
    }

    // "5" or "v" -> 5; anything else -> 0 (not a level)
    static int level(String word) {
        if (ARABIC_LEVEL.matcher(word).matches()) {
            return Integer.parseInt(word);
        }
        return ROMAN_LEVELS.indexOf(word) + 1;
    }

    private static boolean containsAny(List<String> names, String text) {
        for (String name : names) {
            if (name.contains(text)) {
                return true;
            }
        }
        return false;
    }
}
