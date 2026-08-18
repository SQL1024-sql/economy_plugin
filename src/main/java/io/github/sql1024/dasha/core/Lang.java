package io.github.sql1024.dasha.core;

import java.util.HashMap;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Loads the {@code messages} section of config.yml and renders it through MiniMessage.
 * Placeholders are written as {@code {name}} in the config and passed as key/value pairs.
 */
public final class Lang {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> values = new HashMap<>();
    private String prefix = "";
    private String currencyName = "";

    public void load(FileConfiguration config) {
        values.clear();
        prefix = "";
        ConfigurationSection section = config.getConfigurationSection("messages");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(true)) {
            if (section.isString(key)) {
                values.put(key, section.getString(key, ""));
            }
        }
        prefix = values.getOrDefault("prefix", "");
    }

    /** The currency name substituted for {@code {currency}} in every message. */
    public void currencyName(String currencyName) {
        this.currencyName = currencyName == null ? "" : currencyName;
    }

    /** Raw MiniMessage string with placeholders already substituted, without the prefix. */
    public String raw(String key, Object... placeholders) {
        String template = values.get(key);
        if (template == null) {
            return "<red>[missing message: " + key + "]";
        }
        return substitute(template, placeholders).replace("{currency}", currencyName);
    }

    /** Rendered component including the configured prefix. */
    public Component msg(String key, Object... placeholders) {
        return MM.deserialize(prefix + raw(key, placeholders));
    }

    /** Rendered component without the prefix — for GUI text and multi-line output. */
    public Component plain(String key, Object... placeholders) {
        return MM.deserialize(raw(key, placeholders));
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(msg(key, placeholders));
    }

    public Component prefix() {
        return MM.deserialize(prefix);
    }

    /** Renders an arbitrary MiniMessage string (used for stock display names from config). */
    public static Component mini(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** Same as {@link #mini(String)} but with italics switched off, for item names and lore. */
    public static Component item(String miniMessage) {
        return MM.deserialize("<!italic>" + miniMessage);
    }

    private static String substitute(String template, Object... placeholders) {
        if (placeholders.length == 0) {
            return template;
        }
        String out = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String needle = "{" + placeholders[i] + "}";
            String value = String.valueOf(placeholders[i + 1]);
            out = out.replace(needle, value);
        }
        return out;
    }
}
