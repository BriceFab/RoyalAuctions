package com.mystipixel.royalauctions.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Derived search tokens only; the serialized item remains authoritative. */
public final class EnchantmentSearch {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern KEY = Pattern.compile("enchantment\\.([a-z0-9_]+)\\.([a-z0-9_]+)");
    private volatile Map<String, Set<String>> translations = Map.of();

    public static String index(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) return "";
        Set<String> tokens = new TreeSet<>();
        meta.getEnchants().forEach((enchantment, level) ->
                tokens.add("|" + enchantment.getKey() + "=" + level + "|"));
        if (meta instanceof EnchantmentStorageMeta book) {
            book.getStoredEnchants().forEach((enchantment, level) ->
                    tokens.add("|" + enchantment.getKey() + "=" + level + "|"));
        }
        return String.join("", tokens);
    }

    private static String normalize(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT).trim();
    }

    /** Optional Minecraft language JSONs, shared with item-name search on servers using it. */
    public void reload(File dir, Logger logger) {
        List<Map<String, String>> langs = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) for (File file : files) {
            try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Map<String, String> lang = new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
                if (lang != null) langs.add(lang);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not read enchantment names from " + file.getName(), e);
            }
        }
        load(langs);
    }

    void load(Collection<Map<String, String>> langs) {
        Map<String, Set<String>> names = new HashMap<>();
        for (var lang : langs) for (var entry : lang.entrySet()) {
            var key = KEY.matcher(entry.getKey());
            if (key.matches()) names.computeIfAbsent(normalize(entry.getValue()), k -> new HashSet<>())
                    .add(key.group(1) + ":" + key.group(2));
        }
        translations = names;
    }

    /** Literal token fragments; SQL supplies the wildcards and escapes caller-controlled characters. */
    public Set<String> matching(String query) {
        if (query == null || query.isBlank()) return Set.of();
        String name = normalize(query);
        String level = "";
        int space = name.lastIndexOf(' ');
        if (space > 0) {
            String suffix = name.substring(space + 1);
            if (suffix.matches("[0-9]+")) {
                try { level = Integer.toString(Integer.parseInt(suffix)) + "|"; }
                catch (NumberFormatException e) { return Set.of(); }
            } else {
                int roman = List.of("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x").indexOf(suffix);
                if (roman >= 0) level = (roman + 1) + "|";
            }
            if (!level.isEmpty()) name = name.substring(0, space).trim();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(name.replace(' ', '_') + "=" + level);
        for (var entry : translations.entrySet()) {
            if (entry.getKey().contains(name)) for (String key : entry.getValue()) {
                out.add(key + "=" + level);
                if (out.size() >= 200) return Set.copyOf(out);
            }
        }
        return Set.copyOf(out);
    }
}
