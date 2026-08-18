package io.github.sql1024.dasha.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The server store's catalogue from store.yml: categories of building material, each with a price.
 *
 * <p>The store only sells. Nothing here is ever bought back, which is what makes it a pure money
 * sink rather than another trading venue.
 */
public record StoreSettings(boolean enabled, String guiTitle, List<Category> categories) {

    /**
     * One tab of the store.
     *
     * @param key     config key, used for {@code /store <分類>}
     * @param name    MiniMessage title shown on the tab
     * @param icon    material of the tab button
     * @param entries what the tab sells, in config order
     */
    public record Category(String key, String name, Material icon, List<Entry> entries) {
    }

    /**
     * One purchasable item.
     *
     * @param material what the player receives
     * @param price    大沙幣 per unit
     * @param note     optional line of lore, e.g. why the price is set where it is
     */
    public record Entry(Material material, long price, String note) {
    }

    public static StoreSettings from(FileConfiguration config, Logger logger) {
        List<Category> categories = new ArrayList<>();
        ConfigurationSection root = config.getConfigurationSection("categories");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection node = root.getConfigurationSection(key);
                if (node == null) {
                    continue;
                }
                Material icon = Material.matchMaterial(
                        node.getString("icon", "STONE").toUpperCase(Locale.ROOT));
                if (icon == null || !icon.isItem()) {
                    icon = Material.STONE;
                }

                Map<Material, Entry> entries = new LinkedHashMap<>();
                ConfigurationSection itemSection = node.getConfigurationSection("items");
                if (itemSection != null) {
                    for (String itemKey : itemSection.getKeys(false)) {
                        Material material = Material.matchMaterial(itemKey.toUpperCase(Locale.ROOT));
                        if (material == null || !material.isItem()) {
                            logger.warning("store.yml：『" + itemKey + "』不是有效的物品，已略過。");
                            continue;
                        }
                        long price;
                        String note = "";
                        if (itemSection.isConfigurationSection(itemKey)) {
                            ConfigurationSection entryNode = itemSection.getConfigurationSection(itemKey);
                            price = entryNode == null ? 0L : entryNode.getLong("price", 0L);
                            note = entryNode == null ? "" : entryNode.getString("note", "");
                        } else {
                            price = itemSection.getLong(itemKey, 0L);
                        }
                        if (price <= 0L) {
                            logger.warning("store.yml：" + itemKey + " 的價格是 0，已略過。");
                            continue;
                        }
                        entries.put(material, new Entry(material, price, note == null ? "" : note));
                    }
                }
                if (entries.isEmpty()) {
                    continue;
                }
                categories.add(new Category(
                        key,
                        node.getString("name", key),
                        icon,
                        List.copyOf(entries.values())));
            }
        }
        if (categories.isEmpty()) {
            logger.warning("store.yml 沒有任何販售項目，/store 是空的。");
        }

        return new StoreSettings(
                config.getBoolean("enabled", true),
                config.getString("gui-title", "<gradient:#a1c4fd:#c2e9fb>伺服器商店</gradient>"),
                List.copyOf(categories));
    }

    /** Every entry across every category, for the arbitrage check and for lookups. */
    public List<Entry> allEntries() {
        List<Entry> out = new ArrayList<>();
        for (Category category : categories) {
            out.addAll(category.entries());
        }
        return out;
    }

    public Entry entry(Material material) {
        for (Category category : categories) {
            for (Entry entry : category.entries()) {
                if (entry.material() == material) {
                    return entry;
                }
            }
        }
        return null;
    }

    public Category category(String key) {
        for (Category category : categories) {
            if (category.key().equalsIgnoreCase(key)) {
                return category;
            }
        }
        return null;
    }
}
