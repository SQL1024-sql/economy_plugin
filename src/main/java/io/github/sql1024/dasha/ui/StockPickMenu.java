package io.github.sql1024.dasha.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.stock.market.Stock;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** 挑一檔股票，然後做管理動作。發新聞與手動改價共用這一頁。 */
public final class StockPickMenu extends Gui {

    /** What happens once a stock is chosen. */
    public enum Purpose {
        NEWS("<yellow>選擇要發布新聞的股票"),
        SET_PRICE("<yellow>選擇要改價的股票");

        private final String title;

        Purpose(String title) {
            this.title = title;
        }
    }

    private static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
    };
    private static final int SLOT_BACK = 45;

    private final Purpose purpose;
    private final List<Stock> shown = new ArrayList<>();

    public StockPickMenu(DashaEconomyPlugin plugin, Player player, Purpose purpose) {
        super(plugin, player);
        this.purpose = purpose;
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm(purpose.title));
    }

    @Override
    public void render() {
        inventory.clear();
        shown.clear();

        int index = 0;
        for (Stock stock : plugin.market().stocks()) {
            if (index >= SLOTS.length) {
                break;
            }
            shown.add(stock);
            inventory.setItem(SLOTS[index], stockIcon(stock));
            index++;
        }

        inventory.setItem(SLOT_BACK, icon(Material.BARRIER, "<red>返回管理面板"));
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack stockIcon(Stock stock) {
        boolean busy = plugin.news().newsAffectsMarket() && plugin.news().hasNews(stock.symbol());
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gray>現價　<white>" + Fmt.price(stock.price()));
        lore.add("<gray>今日　" + Fmt.changeTag(stock.dayChangePercent()));
        lore.add("<gray>波動率　<white>" + stock.volatility());
        lore.add("");
        if (purpose == Purpose.NEWS && busy) {
            lore.add("<red>✖ 這檔已經有新聞在跑");
            lore.add("<dark_gray>等它結束才能再發");
        } else {
            lore.add("<yellow>▶ 點擊選擇");
        }

        ItemStack item = new ItemStack(busy && purpose == Purpose.NEWS
                ? Material.GRAY_DYE : stock.icon());
        applyMeta(item, stock.displayName() + " <dark_gray>" + stock.symbol(), lore);
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (!player.hasPermission("dasha.admin")) {
            plugin.lang().send(player, "no-permission");
            later(player::closeInventory);
            return;
        }
        if (slot == SLOT_BACK) {
            plugin.click(player);
            reopen(new AdminMenu(plugin, player));
            return;
        }

        for (int i = 0; i < SLOTS.length && i < shown.size(); i++) {
            if (SLOTS[i] != slot) {
                continue;
            }
            Stock stock = shown.get(i);
            plugin.click(player);
            switch (purpose) {
                case NEWS -> {
                    if (plugin.news().hasNews(stock.symbol())) {
                        plugin.fail(player);
                        player.sendMessage(Msg.prefixed("<red><white>" + stock.symbol()
                                + "</white> 已經有一則新聞在跑。"));
                        return;
                    }
                    reopen(new NewsComposeMenu(plugin, player, stock));
                }
                case SET_PRICE -> askPrice(stock);
            }
            return;
        }
    }

    private void askPrice(Stock stock) {
        player.closeInventory();
        player.sendMessage(Msg.prefixed("<yellow>請輸入 <white>" + stock.symbol()
                + "</white> 的新股價（目前 <white>" + Fmt.price(stock.price()) + "</white>）："));
        player.sendMessage(Msg.prefixed("<gray>範圍 " + Fmt.price(stock.minPrice()) + " ~ "
                + Fmt.price(stock.maxPrice()) + "，輸入 <white>取消</white> 放棄。"));
        plugin.promptChat(player, input -> {
            String text = input.trim();
            if (text.equalsIgnoreCase("取消") || text.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            double price;
            try {
                price = Double.parseDouble(text.replace(",", ""));
            } catch (NumberFormatException e) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red><white>" + text + "</white> 不是有效的數字。"));
                open();
                return;
            }
            if (price <= 0.0) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>股價要大於 0。"));
                open();
                return;
            }
            plugin.market().setPrice(stock, price);
            plugin.success(player);
            player.sendMessage(Msg.prefixed("<green>已將 <white>" + stock.symbol()
                    + "</white> 的股價設為 <white>" + Fmt.price(stock.price()) + "</white>。"));
            new AdminMenu(plugin, player).open();
        });
    }
}
