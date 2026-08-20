package io.github.sql1024.dasha.sell;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The buy-back table from sell.yml: what the server purchases, at what base price, and how fast
 * the price sags as the server floods with the stuff.
 *
 * @param enabled       whether ore selling works at all
 * @param dailyQuota    most 大沙幣 one player can earn from selling in a day, 0 for no limit
 * @param priceFloor    fraction of the base price the buy-back can never fall below
 * @param recoveryHours how long an ore priced at the floor takes to climb back to full price
 * @param items         material to its entry, in the order config lists them
 */
public record SellSettings(
        boolean enabled,
        long dailyQuota,
        double priceFloor,
        double recoveryHours,
        Map<Material, Item> items) {

    /**
     * One purchasable ore.
     *
     * @param material  the item the server buys
     * @param basePrice price per unit before the supply discount
     * @param softCap   units that must be sold server-wide to push the price to the floor
     * @param tier      display label describing how farmable this ore is
     */
    public record Item(Material material, long basePrice, double softCap, String tier) {
    }

    public static SellSettings from(FileConfiguration config, Logger logger) {
        Map<Material, Item> items = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection node = section.getConfigurationSection(key);
                if (node == null) {
                    continue;
                }
                Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                if (material == null || !material.isItem()) {
                    logger.warning("sell.yml：『" + key + "』不是有效的物品，已略過。");
                    continue;
                }
                long price = Math.max(0L, node.getLong("price", 0L));
                if (price <= 0L) {
                    logger.warning("sell.yml：" + key + " 的價格是 0，已略過。");
                    continue;
                }
                items.put(material, new Item(
                        material,
                        price,
                        Math.max(1.0, node.getDouble("soft-cap", 5000.0)),
                        node.getString("tier", "")));
            }
        }
        if (items.isEmpty()) {
            logger.warning("sell.yml 沒有任何收購項目，/sell 不會收任何東西。");
        }

        return new SellSettings(
                config.getBoolean("enabled", true),
                Math.max(0L, config.getLong("daily-quota", 8000L)),
                Math.clamp(config.getDouble("price-floor-ratio", 0.3), 0.0, 1.0),
                Math.max(0.01, config.getDouble("recovery-hours", 1.0)),
                Map.copyOf(items));
    }

    public boolean buys(Material material) {
        return items.containsKey(material);
    }

    public Item item(Material material) {
        return items.get(material);
    }
}
