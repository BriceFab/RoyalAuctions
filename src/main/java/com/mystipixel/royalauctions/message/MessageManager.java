package com.mystipixel.royalauctions.message;

import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Loads messages.yml and renders '&'-coloured, placeholder-filled chat components. */
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
        // New recovery messages work on upgrade without overwriting the server's translations.
        try (var stream = plugin.getResource("messages.yml")) {
            if (stream != null) messages.setDefaults(YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) { plugin.getLogger().warning("Could not load default messages: " + e.getMessage()); }
        this.prefix = messages.getString("prefix", "");
    }

    private String raw(String path) {
        String value = messages.getString(path);
        if (value == null && path.equals("payment-pending")) {
            return "&eAn auction payment is on hold. Staff can check its status; do not repeat the transaction.";
        }
        return value == null ? path : value;
    }

    /**
     * Colour the template first, then drop the values in as literal text. Values include things
     * players control, such as an anvil-renamed item's name; substituted before colouring, a name
     * like "&a[Staff] You won 1,000,000" would be rendered as formatting in other players' chat.
     * One pass, so a value that itself contains "{amount}" is not substituted again. Values keep
     * the colour of the text around their placeholder.
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

    /**
     * Legacy '&' text for menu item names and lore, with {placeholders} filled in. Coloured later by
     * the menu, so it stays a plain string.
     */
    public String text(String path, String... kv) {
        String value = raw(path).replace("{prefix}", prefix);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            value = value.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return value;
    }

    public void send(CommandSender to, String path) {
        send(to, path, Map.of());
    }

    public void send(CommandSender to, String path, Map<String, String> placeholders) {
        to.sendMessage(component(path, placeholders));
    }

    /** Varargs convenience: send(sender, "sell.success", "item", name, "price", price). */
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
