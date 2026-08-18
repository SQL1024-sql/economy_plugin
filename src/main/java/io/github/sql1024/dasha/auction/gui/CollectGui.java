package io.github.sql1024.dasha.auction.gui;

import io.github.sql1024.dasha.ui.Gui;
import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class CollectGui extends Gui {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_ALL = 47;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_HUB = 46;

    private int page;
    private int shownCount;

    public CollectGui(DashaEconomyPlugin plugin, Player player, int page) {
        super(plugin, player);
        this.page = Math.max(0, page);
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm("<dark_gray>取貨箱"));
    }

    @Override
    public void render() {
        inventory.clear();
        List<ItemStack> box = plugin.auctions().collectBox(player.getUniqueId());
        int maxPage = Math.max(0, (box.size() - 1) / PAGE_SIZE);
        if (page > maxPage) {
            page = maxPage;
        }
        int from = page * PAGE_SIZE;
        int to = Math.min(box.size(), from + PAGE_SIZE);
        shownCount = Math.max(0, to - from);

        for (int i = 0; i < shownCount; i++) {
            ItemStack icon = box.get(from + i).clone();
            appendLore(icon, List.of("", "<green>▶ 點擊領取"));
            inventory.setItem(i, icon);
        }

        if (box.isEmpty()) {
            inventory.setItem(22, icon(Material.COBWEB, "<gray>取貨箱是空的",
                    "<gray>賣出的所得、過期退回的物品會出現在這。"));
        }

        inventory.setItem(SLOT_PREV, page > 0
                ? icon(Material.ARROW, "<yellow>◀ 上一頁")
                : icon(Material.GRAY_DYE, "<dark_gray>已經是第一頁"));
        inventory.setItem(SLOT_NEXT, page < maxPage
                ? icon(Material.ARROW, "<yellow>下一頁 ▶")
                : icon(Material.GRAY_DYE, "<dark_gray>已經是最後一頁"));
        inventory.setItem(SLOT_ALL, icon(Material.HOPPER, "<green>全部領取",
                "<gray>共 <white>" + box.size() + "</white> 件",
                "<yellow>背包放不下的會掉在腳邊。"));
        inventory.setItem(SLOT_BACK, icon(Material.BARRIER, "<red>返回拍賣行"));

        inventory.setItem(SLOT_HUB, io.github.sql1024.dasha.ui.HubMenu.backButton());
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        switch (slot) {
            case SLOT_PREV -> {
                if (page > 0) {
                    page--;
                    plugin.click(player);
                    render();
                }
                return;
            }
            case SLOT_NEXT -> {
                page++;
                plugin.click(player);
                render();
                return;
            }
            case SLOT_BACK -> {
                plugin.click(player);
                reopen(new BrowseGui(plugin, player, 0));
                return;
            }
            case SLOT_HUB -> {
                plugin.click(player);
                reopen(new io.github.sql1024.dasha.ui.HubMenu(plugin, player));
                return;
            }
            case SLOT_ALL -> {
                int taken = plugin.auctions().claimAll(player);
                if (taken > 0) {
                    plugin.auctions().save();
                    plugin.success(player);
                    player.sendMessage(Msg.prefixed("<green>領取了 <white>" + taken + "</white> 件物品。"));
                } else {
                    plugin.fail(player);
                }
                render();
                return;
            }
            default -> {
            }
        }

        if (slot < 0 || slot >= shownCount) {
            return;
        }
        if (plugin.auctions().claim(player, page * PAGE_SIZE + slot)) {
            plugin.auctions().save();
            plugin.click(player);
        } else {
            plugin.fail(player);
        }
        render();
    }
}
