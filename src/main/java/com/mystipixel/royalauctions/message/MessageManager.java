package com.mystipixel.royalauctions.message;

import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads messages.yml and renders '&'-coloured, placeholder-filled chat components. The jar's copy
 * backs every key missing from the server's file; the code carries no text of its own.
 */
public final class MessageManager {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_-]+)}");

    private final JavaPlugin plugin;
    private FileConfiguration messages;
    private String prefix = "";

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        // Keys added in an update work without rewriting the server's file.
        try (var stream = plugin.getResource("messages.yml")) {
            if (stream != null) messages.setDefaults(YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) { plugin.getLogger().warning("Could not load default messages: " + e.getMessage()); }
        this.prefix = messages.getString("prefix", "");
    }

    private String raw(String path) {
        String value = messages.getString(path);
        return value == null ? path : value;
    }

    /**
     * Colours the template first, then inserts values as literal text, so a player-controlled value
     * (an anvil-renamed item) can't inject formatting. One pass: a value containing "{amount}" is not
     * substituted again.
     */
    public Component component(String path, Map<String, String> placeholders) {
        Component message = Text.chat(raw(path).replace("{prefix}", prefix));
        if (placeholders.isEmpty()) {
            return message;
        }
        return message.replaceText(TextReplacementConfig.builder()
                .match(PLACEHOLDER)
                .replacement((match, builder) -> {
                    String value = placeholders.get(match.group(1));
                    return builder.content(value == null ? match.group() : value);
                })
                .build());
    }

    /** Legacy '&' text for menu names and lore, placeholders filled; the menu colours it later. */
    public String text(String path, String... kv) {
        String value = raw(path).replace("{prefix}", prefix);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            value = value.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return value;
    }

    /** A list key as '&' text, one entry per line, placeholders filled as in {@link #text}. */
    public List<String> lines(String path, String... kv) {
        List<String> out = new ArrayList<>();
        for (String line : messages.getStringList(path)) {
            String value = line.replace("{prefix}", prefix);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                value = value.replace("{" + kv[i] + "}", kv[i + 1]);
            }
            out.add(value);
        }
        return out;
    }

    /** Each line of a list key as its own chat message. */
    public void sendLines(CommandSender to, String path, String... kv) {
        for (String line : lines(path, kv)) {
            to.sendMessage(Text.chat(line));
        }
    }

    /**
     * Like {@link #send(CommandSender, String, String...)}, but the values keep their own colour
     * codes. Only for values from config (category and tier names), never for player input.
     */
    public void sendStyled(CommandSender to, String path, String... kv) {
        to.sendMessage(Text.chat(text(path, kv)));
    }

    public void send(CommandSender to, String path) {
        send(to, path, Map.of());
    }

    public void send(CommandSender to, String path, Map<String, String> placeholders) {
        to.sendMessage(component(path, placeholders));
    }

    /** Usage: send(sender, "sell.success", "item", name, "price", price). */
    public void send(CommandSender to, String path, String... kv) {
        send(to, path, map(kv));
    }

    public static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
