package io.github.sql1024.dasha.auction;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import org.bukkit.Material;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * 物品的中文名稱表。內容是從官方 zh_tw.json 擷取出來的 item./block. 名稱，
 * 放在 jar 裡的 lang/zh_tw.lang；如果 plugins/AuctionHouse/lang/zh_tw.lang 存在會蓋上去。
 */
public final class Lang {

    private static final String RESOURCE = "lang/zh_tw.lang";
    private static final Map<Material, String> NAMES = new EnumMap<>(Material.class);

    private Lang() {
    }

    public static void load(DashaEconomyPlugin plugin) {
        NAMES.clear();
        Map<String, String> keys = new HashMap<>();

        try (InputStream in = plugin.getResource(RESOURCE)) {
            if (in != null) {
                parse(new InputStreamReader(in, StandardCharsets.UTF_8), keys);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "讀取內建中文名稱表失敗", ex);
        }

        File override = new File(plugin.getDataFolder(), RESOURCE);
        if (override.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(override.toPath(), StandardCharsets.UTF_8)) {
                parse(reader, keys);
                plugin.getLogger().info("套用自訂中文名稱表：" + override.getName());
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "讀取自訂中文名稱表失敗：" + override, ex);
            }
        }

        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            String id = material.name().toLowerCase(Locale.ROOT);
            String name = keys.get("item.minecraft." + id);
            if (name == null) {
                name = keys.get("block.minecraft." + id);
            }
            if (name != null) {
                NAMES.put(material, name);
            }
        }
        plugin.getLogger().info("中文名稱表：" + NAMES.size() + " 種物品。");
    }

    private static void parse(java.io.Reader source, Map<String, String> into) throws IOException {
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int split = line.indexOf('=');
                if (split > 0 && split < line.length() - 1) {
                    into.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
                }
            }
        }
    }

    /** 中文名稱，沒收錄就回傳 null。 */
    public static String name(Material material) {
        return NAMES.get(material);
    }
}
