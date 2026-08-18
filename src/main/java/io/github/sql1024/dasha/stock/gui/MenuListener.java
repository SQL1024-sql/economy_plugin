package io.github.sql1024.dasha.stock.gui;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Routes clicks in GoldStock menus and keeps items from being taken out of them. */
public final class MenuListener implements Listener {

    private final DashaEconomyPlugin plugin;

    public MenuListener(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        StockMenu menu = menuOf(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }
        // Nothing in these menus is a real item, so no click may ever move anything.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(event.getView().getTopInventory())) {
            return;
        }
        if (!player.hasPermission("dasha.stock")) {
            plugin.lang().send(player, "no-permission");
            player.closeInventory();
            return;
        }
        menu.onClick(event.getRawSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    private StockMenu menuOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof StockMenu menu ? menu : null;
    }
}
