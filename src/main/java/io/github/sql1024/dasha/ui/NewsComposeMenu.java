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

/**
 * 擬新聞：選方向、選強度、寫標題，然後發布。
 *
 * <p>The confirm button spells out what publishing costs the publisher — the halt everyone else
 * eats and the insider lock they eat themselves — because both are easy to forget until they bite.
 */
public final class NewsComposeMenu extends Gui {

    private static final int SLOT_STOCK = 4;
    private static final int SLOT_BULLISH = 20;
    private static final int SLOT_BEARISH = 24;
    private static final int SLOT_STRENGTH = 31;
    private static final int SLOT_HEADLINE = 22;
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_BACK = 45;

    private final Stock stock;
    private int direction = 1;
    private int strength;
    private String headline = "";

    public NewsComposeMenu(DashaEconomyPlugin plugin, Player player, Stock stock) {
        super(plugin, player);
        this.stock = stock;
        this.strength = plugin.newsSettings().defaultStrength();
        this.inventory = Bukkit.createInventory(this, 54,
                Msg.mm("<yellow>發布新聞 <dark_gray>— " + stock.symbol()));
    }

    @Override
    public void render() {
        inventory.clear();

        inventory.setItem(SLOT_STOCK, icon(stock.icon(),
                stock.displayName() + " <dark_gray>" + stock.symbol(),
                "<gray>現價　<white>" + Fmt.price(stock.price()),
                "<gray>今日　" + Fmt.changeTag(stock.dayChangePercent()),
                "<gray>波動率　<white>" + stock.volatility()));

        inventory.setItem(SLOT_BULLISH, icon(
                direction > 0 ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS_PANE,
                (direction > 0 ? "<green><bold>▶ 利多" : "<gray>利多"),
                "<gray>股價會朝上走。",
                direction > 0 ? "<green>已選擇" : "<yellow>▶ 點擊選擇"));

        inventory.setItem(SLOT_BEARISH, icon(
                direction < 0 ? Material.RED_CONCRETE : Material.RED_STAINED_GLASS_PANE,
                (direction < 0 ? "<red><bold>▶ 利空" : "<gray>利空"),
                "<gray>股價會朝下走。",
                direction < 0 ? "<red>已選擇" : "<yellow>▶ 點擊選擇"));

        inventory.setItem(SLOT_STRENGTH, icon(Material.COMPARATOR,
                "<gold>強度：<white>" + strength + " <dark_gray>/ " + plugin.newsSettings().maxStrength(),
                "<gray>" + "★".repeat(strength) + "<dark_gray>"
                        + "☆".repeat(Math.max(0, plugin.newsSettings().maxStrength() - strength)),
                "",
                "<gray>影響幅度會乘上這檔的波動率，",
                "<gray>持續 <white>" + plugin.newsSettings().durationUpdates() + "</white> 次股價更新。",
                "", "<yellow>左鍵 +1　右鍵 -1"));

        List<String> headlineLore = new ArrayList<>();
        if (headline.isBlank()) {
            headlineLore.add("<gray>目前：<dark_gray>（留空，從新聞池隨機抽）");
        } else {
            headlineLore.add("<gray>目前：");
            headlineLore.add("<white>" + headline);
        }
        headlineLore.add("");
        headlineLore.add("<yellow>▶ 點擊在聊天欄輸入標題");
        org.bukkit.inventory.ItemStack headlineIcon =
                new org.bukkit.inventory.ItemStack(Material.WRITABLE_BOOK);
        applyMeta(headlineIcon, "<aqua>新聞標題", headlineLore);
        inventory.setItem(SLOT_HEADLINE, headlineIcon);

        if (!plugin.news().newsAffectsMarket()) {
            inventory.setItem(SLOT_CONFIRM, icon(Material.BELL, "<gray><bold>發布 <dark_gray>（純廣播）",
                    "<gray>" + (direction > 0 ? "<green>利多" : "<red>利空")
                            + " <white>" + stock.symbol() + "</white>",
                    "",
                    "<red>這則消息不會影響股價。",
                    "<gray>本伺服器的股價跟著真實市場走。",
                    "<dark_gray>只會廣播文字並寫進稽核紀錄。",
                    "", "<yellow>▶ 點擊發布"));
            inventory.setItem(SLOT_BACK, icon(Material.BARRIER, "<red>返回"));
            fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
            return;
        }

        inventory.setItem(SLOT_CONFIRM, icon(Material.BELL, "<gold><bold>發布",
                "<gray>" + (direction > 0 ? "<green>利多" : "<red>利空")
                        + " <white>" + stock.symbol() + "</white> <gray>強度 " + strength,
                "",
                "<red>發布後會立刻發生：",
                "<gray>• 全服廣播預告，該股<red>停牌 "
                        + plugin.newsSettings().haltSeconds() + " 秒",
                "<gray>• 停牌期間股價已經開始走",
                "<gray>• 你自己 <white>" + plugin.newsSettings().insiderLockMinutes()
                        + "</white> 分鐘內不能交易這檔",
                "<gray>• 寫入稽核紀錄",
                "", "<yellow>▶ 點擊發布"));

        inventory.setItem(SLOT_BACK, icon(Material.BARRIER, "<red>返回"));
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
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

        switch (slot) {
            case SLOT_BULLISH -> {
                direction = 1;
                plugin.click(player);
                render();
            }
            case SLOT_BEARISH -> {
                direction = -1;
                plugin.click(player);
                render();
            }
            case SLOT_STRENGTH -> {
                strength = Math.clamp(strength + (event.getClick().isRightClick() ? -1 : 1),
                        1, plugin.newsSettings().maxStrength());
                plugin.click(player);
                render();
            }
            case SLOT_HEADLINE -> {
                plugin.click(player);
                askHeadline();
            }
            case SLOT_CONFIRM -> publish();
            case SLOT_BACK -> {
                plugin.click(player);
                reopen(new StockPickMenu(plugin, player, StockPickMenu.Purpose.NEWS));
            }
            default -> {
            }
        }
    }

    private void askHeadline() {
        player.closeInventory();
        player.sendMessage(Msg.prefixed("<yellow>請輸入新聞標題："));
        player.sendMessage(Msg.prefixed("<gray>輸入 <white>清空</white> 改回隨機抽，<white>取消</white> 放棄。"));
        plugin.promptChat(player, input -> {
            String text = input.trim();
            if (text.equalsIgnoreCase("取消") || text.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            headline = text.equalsIgnoreCase("清空") || text.equalsIgnoreCase("clear") ? "" : text;
            plugin.click(player);
            open();
        });
    }

    private void publish() {
        boolean ok = plugin.news().publishManual(
                player.getUniqueId(), player.getName(), stock, direction, strength, headline);
        if (!ok) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red><white>" + stock.symbol()
                    + "</white> 已經有一則新聞在跑，等它結束再發。"));
            return;
        }
        plugin.success(player);
        if (!plugin.news().newsAffectsMarket()) {
            player.sendMessage(Msg.prefixed("<yellow>已廣播消息，但<red>不會影響股價</red> —— "
                    + "本伺服器的股價跟著真實市場走。"));
            reopen(new AdminMenu(plugin, player));
            return;
        }
        player.sendMessage(Msg.prefixed("<green>已發布新聞，<white>" + stock.symbol()
                + "</white> 停牌 <white>" + plugin.newsSettings().haltSeconds() + "</white> 秒。"));
        if (plugin.newsSettings().insiderLockMinutes() > 0) {
            player.sendMessage(Msg.prefixed("<gray>你在 <white>"
                    + plugin.newsSettings().insiderLockMinutes()
                    + "</white> 分鐘內不能交易這檔股票（防內線）。"));
        }
        reopen(new AdminMenu(plugin, player));
    }
}
