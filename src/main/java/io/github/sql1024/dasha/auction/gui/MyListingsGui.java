package io.github.sql1024.dasha.auction.gui;

import io.github.sql1024.dasha.ui.Gui;
import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Listing;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MyListingsGui extends Gui {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_HUB = 46;

    private final UUID target;
    private final String targetName;
    private final boolean self;
    private int page;
    private List<Listing> shown = new ArrayList<>();

    /** 看自己的上架。 */
    public MyListingsGui(DashaEconomyPlugin plugin, Player player, int page) {
        this(plugin, player, player.getUniqueId(), player.getName(), page);
    }

    /** 管理員看別人的上架。 */
    public MyListingsGui(DashaEconomyPlugin plugin, Player viewer, UUID target, String targetName, int page) {
        super(plugin, viewer);
        this.target = target;
        this.targetName = targetName;
        this.self = target.equals(viewer.getUniqueId());
        this.page = Math.max(0, page);
        this.inventory = Bukkit.createInventory(this, 54, self
                ? Msg.mm("<dark_gray>我的上架")
                : Msg.mm("<dark_red>管理 <dark_gray>— " + targetName + " 的上架"));
    }

    @Override
    public void render() {
        inventory.clear();
        List<Listing> mine = plugin.auctions().of(target);
        int maxPage = Math.max(0, (mine.size() - 1) / PAGE_SIZE);
        if (page > maxPage) {
            page = maxPage;
        }
        int from = page * PAGE_SIZE;
        int to = Math.min(mine.size(), from + PAGE_SIZE);
        shown = from >= to ? new ArrayList<>() : new ArrayList<>(mine.subList(from, to));

        for (int i = 0; i < shown.size(); i++) {
            Listing listing = shown.get(i);
            ItemStack icon = listing.offer();
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>━━━━━━━━━━━━━━━");
            lore.add("<gold>開價：<white>" + Fmt.coin(listing.price()) + " 大沙幣");
            lore.add("<dark_gray>賣掉可拿 "
                    + Fmt.coin(plugin.auctionSettings().sellerPayout(listing.price()))
                    + "（已扣成交抽成）");
            if (listing.expiresAt() > 0) {
                lore.add("<gray>剩餘：<white>" + Msg.duration(listing.expiresAt() - System.currentTimeMillis()));
            }
            lore.add("");
            lore.add(self ? "<red>▶ 點擊下架並取回物品"
                    : "<red>▶ 點擊強制下架（物品退回 " + targetName + " 的取貨箱）");
            appendLore(icon, lore);
            inventory.setItem(i, icon);
        }

        if (mine.isEmpty()) {
            inventory.setItem(22, icon(Material.COBWEB,
                    self ? "<gray>你還沒有任何上架" : "<gray>" + targetName + " 沒有任何上架"));
        }

        inventory.setItem(SLOT_PREV, page > 0
                ? icon(Material.ARROW, "<yellow>◀ 上一頁")
                : icon(Material.GRAY_DYE, "<dark_gray>已經是第一頁"));
        inventory.setItem(SLOT_NEXT, page < maxPage
                ? icon(Material.ARROW, "<yellow>下一頁 ▶")
                : icon(Material.GRAY_DYE, "<dark_gray>已經是最後一頁"));
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
            case SLOT_HUB -> {
                plugin.click(player);
                reopen(new io.github.sql1024.dasha.ui.HubMenu(plugin, player));
                return;
            }
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
            default -> {
            }
        }

        if (slot >= shown.size()) {
            return;
        }
        Listing listing = shown.get(slot);
        if (!self && !player.hasPermission("dasha.admin")) {
            plugin.fail(player);
            return;
        }
        if (!plugin.auctions().remove(listing.id())) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red>這筆上架已經不在了。"));
            render();
            return;
        }

        if (self) {
            plugin.auctions().giveOrDrop(player, listing.offer());
            player.sendMessage(Msg.prefixed("<yellow>已下架 <white>" + Msg.itemName(listing.offer())
                    + "</white>，物品還你了。"));
        } else {
            plugin.auctions().forceRemoveNotify(player, listing);
        }
        plugin.auctions().save();
        plugin.success(player);
        render();
    }
}
