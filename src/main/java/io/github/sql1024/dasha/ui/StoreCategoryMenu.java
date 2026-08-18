package io.github.sql1024.dasha.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.Lang;
import io.github.sql1024.dasha.store.StoreGui;
import io.github.sql1024.dasha.store.StoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** 伺服器商店的分類選擇頁，讓 {@code /store} 不用先知道分類名稱。 */
public final class StoreCategoryMenu extends Gui {

    private static final int[] SLOTS = {10, 12, 14, 16, 28, 30, 32, 34};
    private static final int SLOT_BALANCE = 22;
    private static final int SLOT_BACK = 45;

    private final List<StoreSettings.Category> shown = new ArrayList<>();

    public StoreCategoryMenu(DashaEconomyPlugin plugin, Player player) {
        super(plugin, player);
        this.inventory = Bukkit.createInventory(this, 54,
                Lang.mini(plugin.storeSettings().guiTitle() + " <dark_gray>— 分類"));
    }

    @Override
    public void render() {
        inventory.clear();
        shown.clear();

        int index = 0;
        for (StoreSettings.Category category : plugin.storeSettings().categories()) {
            if (index >= SLOTS.length) {
                break;
            }
            shown.add(category);
            inventory.setItem(SLOTS[index], categoryIcon(category));
            index++;
        }

        inventory.setItem(SLOT_BALANCE, icon(Material.SUNFLOWER,
                "<gold>餘額：<white>" + Fmt.coin(plugin.economy().balance(player)),
                "<gray>" + plugin.currency().displayName(),
                "",
                "<dark_gray>商店只賣不收 —— 這裡是大沙幣",
                "<dark_gray>主要的消耗管道。"));

        inventory.setItem(SLOT_BACK, HubMenu.backButton());
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack categoryIcon(StoreSettings.Category category) {
        long low = Long.MAX_VALUE;
        long high = 0L;
        for (StoreSettings.Entry entry : category.entries()) {
            low = Math.min(low, entry.price());
            high = Math.max(high, entry.price());
        }

        ItemStack item = new ItemStack(category.icon());
        applyMeta(item, category.name(), List.of(
                "<dark_gray>━━━━━━━━━━━━━━━",
                "<gray>共 <white>" + category.entries().size() + "</white> 項商品",
                "<gray>價格 <white>" + Fmt.coin(low) + "</white> ~ <white>" + Fmt.coin(high),
                "",
                "<dark_gray>/store " + category.key(),
                "<yellow>▶ 點擊進入"));
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (slot == SLOT_BACK) {
            plugin.click(player);
            reopen(new HubMenu(plugin, player));
            return;
        }
        for (int i = 0; i < SLOTS.length && i < shown.size(); i++) {
            if (SLOTS[i] == slot) {
                plugin.click(player);
                StoreSettings.Category category = shown.get(i);
                // StoreGui is not a Gui subclass, so it opens itself rather than going through reopen.
                later(() -> new StoreGui(plugin, player, category, 0).open());
                return;
            }
        }
        if (slot == SLOT_BALANCE) {
            player.sendMessage(Msg.prefixed("<gray>你的餘額：<yellow>"
                    + Fmt.coin(plugin.economy().balance(player)) + "</yellow> 大沙幣"));
        }
    }
}
