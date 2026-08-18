package io.github.sql1024.dasha.auction.gui;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public abstract class Gui implements InventoryHolder {

    protected final DashaEconomyPlugin plugin;
    protected final Player player;
    protected Inventory inventory;

    protected Gui(DashaEconomyPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        render();
        player.openInventory(inventory);
    }

    /** 重新畫一次內容。 */
    public abstract void render();

    public abstract void onClick(InventoryClickEvent event);

    public void onDrag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw < inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public void onClose(InventoryCloseEvent event) {
    }

    // ------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------

    protected void later(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    protected void reopen(Gui gui) {
        later(gui::open);
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        applyMeta(item, name, List.of(lore));
        return item;
    }

    public static void applyMeta(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        if (name != null) {
            meta.displayName(Msg.plain(name));
        }
        if (!lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(Msg.plain(line));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
    }

    /** 在原本的 lore 後面接上幾行（保留物品原本的說明）。 */
    public static void appendLore(ItemStack item, List<String> extra) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<net.kyori.adventure.text.Component> lines =
                meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String line : extra) {
            lines.add(Msg.plain(line));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
    }

    protected void fill(Material material) {
        ItemStack filler = icon(material, "<gray>");
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    protected void fillEmpty(Material material) {
        ItemStack filler = icon(material, "<gray>");
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }
}
