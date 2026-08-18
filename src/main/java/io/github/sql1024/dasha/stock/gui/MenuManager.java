package io.github.sql1024.dasha.stock.gui;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

/** Opens the menus and keeps the ones currently on screen up to date. */
public final class MenuManager {

    private final DashaEconomyPlugin plugin;

    public MenuManager(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public void openMarket(Player player) {
        open(new MarketMenu(plugin, player));
    }

    public void openPortfolio(Player player) {
        open(new PortfolioMenu(plugin, player));
    }

    private void open(StockMenu menu) {
        menu.render();
        // Opening from inside an InventoryClickEvent is not allowed, so always defer a tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (menu.viewer().isOnline()) {
                menu.viewer().openInventory(menu.getInventory());
            }
        });
    }

    /** Re-renders every open GoldStock menu, e.g. after a price update. */
    public void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            StockMenu menu = menuOf(player);
            if (menu != null) {
                menu.render();
                player.updateInventory();
            }
        }
    }

    /** The GoldStock menu the player currently has open, or {@code null}. */
    public StockMenu menuOf(Player player) {
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        return holder instanceof StockMenu menu ? menu : null;
    }
}
