package io.github.sql1024.dasha.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Writes a single value back into a config file from in-game, without wrecking the file.
 *
 * <p>Bukkit's own {@code YamlConfiguration.save} does keep comments, but it re-serialises
 * everything else: hand-written spacing goes, and a number like {@code 0.00002} comes back as
 * {@code 2.0E-5}. These config files are meant to be read and edited by a person, so a toggle
 * clicked in a menu should not quietly reformat the operator's work.
 *
 * <p>So the normal path is a surgical line edit — find the one line holding the key and rewrite
 * only its value, leaving every other byte of the file alone. If the file has been restructured
 * far enough that the line cannot be found, it falls back to the Bukkit writer, which is uglier
 * but always correct.
 */
public final class ConfigWriter {

    private ConfigWriter() {
    }

    /**
     * Sets {@code section.key} to a boolean.
     *
     * @param file    the YAML file to edit
     * @param section top-level section the key lives under, e.g. {@code news}
     * @param key     the key itself, e.g. {@code enabled}
     * @return whether the file was written
     */
    public static boolean setBoolean(File file, String section, String key,
                                     boolean value, Logger logger) {
        if (!file.exists()) {
            logger.warning("找不到設定檔 " + file.getName() + "，無法寫入。");
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            int index = findKeyLine(lines, section, key);
            if (index >= 0) {
                lines.set(index, rewriteValue(lines.get(index), String.valueOf(value)));
                Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
                return true;
            }
            logger.warning("在 " + file.getName() + " 裡找不到 " + section + "." + key
                    + " 這一行，改用一般寫入（可能會重排格式）。");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            yaml.set(section + "." + key, value);
            yaml.save(file);
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "寫入 " + file.getName() + " 失敗", e);
            return false;
        }
    }

    /**
     * Index of the line holding {@code key} inside {@code section}, or {@code -1}.
     *
     * <p>Walks the file rather than parsing it: a top-level section starts at column zero, and the
     * key is the first line under it at deeper indentation whose name matches. Nested sections
     * deeper in are skipped because only the key's own indentation level is considered.
     */
    private static int findKeyLine(List<String> lines, String section, String key) {
        Pattern sectionStart = Pattern.compile("^" + Pattern.quote(section) + "\\s*:\\s*(#.*)?$");
        Pattern topLevel = Pattern.compile("^[^\\s#].*$");
        Pattern target = Pattern.compile("^(\\s+)" + Pattern.quote(key) + "\\s*:.*$");

        boolean inside = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!inside) {
                if (sectionStart.matcher(line).matches()) {
                    inside = true;
                }
                continue;
            }
            // A new top-level key ends the section we were scanning.
            if (topLevel.matcher(line).matches() && !line.startsWith(" ")) {
                return -1;
            }
            if (target.matcher(line).matches()) {
                return i;
            }
        }
        return -1;
    }

    /** Replaces the value on a {@code key: value  # comment} line, keeping indent and comment. */
    private static String rewriteValue(String line, String value) {
        Matcher matcher = Pattern.compile("^(\\s*[^:]+:\\s*)([^#]*?)(\\s*#.*)?$").matcher(line);
        if (!matcher.matches()) {
            return line;
        }
        String head = matcher.group(1);
        String comment = matcher.group(3) == null ? "" : matcher.group(3);
        return head + value + comment;
    }
}
