package io.github.sql1024.dasha.auction.gui;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Listing;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class BrowseGui extends Gui {

    public static final int PAGE_SIZE = 45;

    private static final int SLOT_PREV = 45;
    private static final int SLOT_MINE = 47;
    private static final int SLOT_SELL = 49;
    private static final int SLOT_COLLECT = 51;
    private static final int SLOT_NEXT = 53;

    private int page;
    private List<Listing> shown = new ArrayList<>();

    public BrowseGui(DashaEconomyPlugin plugin, Player player, int page) {
        super(plugin, player);
        this.page = Math.max(0, page);
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm("<dark_gray>拍賣行 <gray>— 以物易物"));
    }

    @Override
    public void render() {
        inventory.clear();
        List<Listing> all = plugin.auctions().all();
        int maxPage = Math.max(0, (all.size() - 1) / PAGE_SIZE);
        if (page > maxPage) {
            page = maxPage;
        }

        int from = page * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);
        shown = from >= to ? new ArrayList<>() : new ArrayList<>(all.subList(from, to));

        for (int i = 0; i < shown.size(); i++) {
            inventory.setItem(i, listingIcon(shown.get(i)));
        }

        if (all.isEmpty()) {
            inventory.setItem(22, icon(Material.COBWEB, "<gray>目前沒有任何上架",
                    "", "<yellow>用下面的「上架物品」開張第一筆吧。"));
        }

        inventory.setItem(SLOT_PREV, page > 0
                ? icon(Material.ARROW, "<yellow>◀ 上一頁", "<gray>第 " + page + " 頁")
                : icon(Material.GRAY_DYE, "<dark_gray>已經是第一頁"));
        inventory.setItem(SLOT_NEXT, page < maxPage
                ? icon(Material.ARROW, "<yellow>下一頁 ▶", "<gray>第 " + (page + 2) + " 頁")
                : icon(Material.GRAY_DYE, "<dark_gray>已經是最後一頁"));

        inventory.setItem(SLOT_MINE, icon(Material.BOOKSHELF, "<aqua>我的上架",
                "<gray>目前 <white>" + plugin.auctions().countOf(player.getUniqueId()) + "</white> 筆",
                "", "<yellow>▶ 點擊查看 / 下架"));
        inventory.setItem(SLOT_SELL, icon(Material.EMERALD, "<green>上架物品",
                "<gray>放入要賣的東西，再放入想換到的東西。",
                "", "<yellow>▶ 點擊開始上架"));
        int boxSize = plugin.auctions().collectSize(player.getUniqueId());
        inventory.setItem(SLOT_COLLECT, icon(boxSize > 0 ? Material.CHEST : Material.BARREL, "<gold>取貨箱",
                "<gray>待領取 <white>" + boxSize + "</white> 件",
                "", "<yellow>▶ 點擊領取"));

        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack listingIcon(Listing listing) {
        ItemStack icon = listing.offer();
        boolean mine = listing.seller().equals(player.getUniqueId());
        long balance = plugin.economy().balance(player);

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gray>賣家：<white>" + listing.sellerName());
        lore.add("<gold>價格：<white>" + Fmt.coin(listing.price()) + " 大沙幣");
        lore.add("<dark_gray>單價 " + Fmt.price(listing.unitPrice()) + " /個");
        if (listing.expiresAt() > 0) {
            lore.add("<gray>剩餘：<white>" + Msg.duration(listing.expiresAt() - System.currentTimeMillis()));
        }
        lore.add("");
        if (player.hasPermission("dasha.admin") && !mine) {
            lore.add("<dark_red>Shift+右鍵：強制下架");
        }
        if (mine) {
            lore.add("<yellow>這是你自己的上架");
            lore.add("<gray>要下架請去「我的上架」");
        } else if (balance >= listing.price()) {
            lore.add("<green>✔ 你有 " + Fmt.coin(balance) + " 大沙幣");
            lore.add("<green>▶ 左鍵購買");
        } else {
            lore.add("<red>✖ 還差 " + Fmt.coin(listing.price() - balance) + " 大沙幣");
        }
        appendLore(icon, lore);
        return icon;
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
            case SLOT_MINE -> {
                plugin.click(player);
                reopen(new MyListingsGui(plugin, player, 0));
                return;
            }
            case SLOT_SELL -> {
                plugin.click(player);
                reopen(plugin.sellSession(player));
                return;
            }
            case SLOT_COLLECT -> {
                plugin.click(player);
                reopen(new CollectGui(plugin, player, 0));
                return;
            }
            default -> {
            }
        }

        if (slot >= 0 && slot < shown.size()) {
            Listing listing = shown.get(slot);

            if (event.getClick() == ClickType.SHIFT_RIGHT && player.hasPermission("dasha.admin")
                    && !listing.seller().equals(player.getUniqueId())) {
                if (plugin.auctions().remove(listing.id())) {
                    plugin.auctions().forceRemoveNotify(player, listing);
                    plugin.auctions().save();
                    plugin.success(player);
                } else {
                    plugin.fail(player);
                }
                render();
                return;
            }

            if (listing.seller().equals(player.getUniqueId())) {
                player.sendMessage(Msg.prefixed("<red>不能跟自己交易，去「我的上架」下架吧。"));
                plugin.fail(player);
                return;
            }
            plugin.click(player);
            reopen(new ConfirmGui(plugin, player, listing.id(), page));
        }
    }
}
