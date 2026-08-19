package io.github.sql1024.dasha.stock.gui;

import io.github.sql1024.dasha.core.Fmt;
import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.stock.market.Indicators;
import io.github.sql1024.dasha.stock.market.Stock;
import io.github.sql1024.dasha.stock.news.NewsEvent;
import io.github.sql1024.dasha.stock.portfolio.Holding;
import io.github.sql1024.dasha.core.Lang;
import io.github.sql1024.dasha.stock.util.Report;
import io.github.sql1024.dasha.stock.util.Sparkline;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** The main board: every stock, its price, its chart, and click-to-trade. */
public final class MarketMenu extends StockMenu {

    public MarketMenu(DashaEconomyPlugin plugin, Player viewer) {
        super(plugin, viewer, Lang.mini(plugin.settings().guiTitle()));
    }

    @Override
    public void render() {
        List<Stock> stocks = plugin.market().stockList();
        clampPage(stocks.size());
        slotSymbols.clear();

        long carried = plugin.economy().balance(viewer);
        drawFrame(stocks.size(), "<gold>我的持股", List.of(
                "<gray>餘額：<white>" + Fmt.gold(carried) + "</white> " + plugin.currency().displayName(),
                "<gray>持股市值：<white>" + Fmt.gold(plugin.portfolios().marketValue(viewer.getUniqueId())),
                "<yellow>點一下查看投資組合"));

        int from = page * CONTENT_SLOTS.length;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = from + i;
            if (index >= stocks.size()) {
                break;
            }
            Stock stock = stocks.get(index);
            int slot = CONTENT_SLOTS[i];
            Holding holding = plugin.portfolios().get(viewer.getUniqueId(), stock.symbol());
            inventory.setItem(slot, buildIcon(stock, holding, carried));
            slotSymbols.put(slot, stock.symbol());
        }
    }

    private org.bukkit.inventory.ItemStack buildIcon(Stock stock, Holding holding, long carried) {
        String currency = plugin.currency().displayName();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>代號 <white>" + stock.symbol());
        lore.add("<gray>現價 <white>" + Fmt.price(stock.price()) + "</white> " + currency + "<gray> / 股");
        // In a real-price mode the day change is the number players will cross-check against a
        // real ticker, so it leads; the per-update move is secondary information.
        if (plugin.settings().priceSource().needsFeed() && stock.realSymbol() != null) {
            lore.add("<gray>今日 " + Fmt.changeTag(stock.dayChangePercent())
                    + "<reset><gray>　本次 " + Fmt.changeTag(stock.changePercent()));
        } else {
            lore.add("<gray>漲跌 " + Fmt.changeTag(stock.changePercent())
                    + "<reset><gray>　累計 " + Fmt.changeTag(stock.sessionChangePercent()));
        }
        lore.add("<gray>區間 <white>" + Fmt.price(stock.historyLow())
                + "</white> ~ <white>" + Fmt.price(stock.historyHigh()));
        lore.add("<gray>走勢 " + Sparkline.render(stock.history(), plugin.settings().chartWidth()));
        appendRealMarket(lore, stock);

        if (plugin.indicators().enabled()) {
            Indicators indicators = Indicators.of(stock, plugin.indicators());
            lore.add("<gray>技術面 " + indicators.trendTag());
            lore.add("<dark_gray>  " + indicators.maTag(plugin.indicators()));
        }

        for (NewsEvent news : plugin.news().activeFor(stock.symbol())) {
            lore.add("<gold>📰 " + (news.bullish() ? "<green>利多" : "<red>利空")
                    + " <dark_gray>還剩 " + news.updatesLeft() + " 次更新");
            lore.add("<dark_gray>  " + news.headline());
        }

        lore.add("<dark_gray>");

        long unitCost = plugin.settings().buyCost(stock.price(), 1);
        lore.add("<gray>買 1 股 <white>" + Fmt.gold(unitCost) + "</white> " + currency
                + (carried >= unitCost ? "" : "　<red>(不夠)"));

        if (holding.shares() > 0) {
            long value = (long) Math.floor(stock.price() * holding.shares());
            lore.add("<gray>持有 <white>" + holding.shares() + "</white> 股　均價 <white>"
                    + Fmt.price(holding.averageCost()));
            lore.add("<gray>市值 <white>" + Fmt.gold(value) + "</white> " + currency + "<gray>　損益 "
                    + Fmt.pnlTag(value - holding.invested()));
        }

        lore.add("<dark_gray>");
        lore.add("<yellow>左鍵 <gray>買 1　<yellow>Shift+左鍵 <gray>買 10");
        lore.add("<yellow>右鍵 <gray>賣 1　<yellow>Shift+右鍵 <gray>賣 10");
        lore.add("<yellow>中鍵 <gray>詳細資訊");

        int amount = holding.shares() > 0 ? holding.shares() : 1;
        return Icons.of(stock.icon(), amount, stock.displayName() + " <dark_gray>[" + stock.symbol() + "]", lore);
    }

    /**
     * Adds the live-market line: what this stock tracks and whether that exchange is open.
     *
     * <p>Worth its own line because a frozen price is otherwise indistinguishable from a broken
     * feed — players seeing Toyota sit still for eight hours should be told Tokyo is shut, not
     * left guessing.
     */
    private void appendRealMarket(java.util.List<String> lore, Stock stock) {
        if (!plugin.settings().priceSource().needsFeed() || stock.realSymbol() == null) {
            return;
        }
        var quote = plugin.quotes().quote(stock.realSymbol());
        if (quote == null) {
            lore.add("<dark_gray>連動 " + stock.realSymbol() + " <red>（尚未取得報價）");
            return;
        }
        lore.add("<dark_gray>連動 " + stock.realSymbol() + " <dark_gray>| "
                + Fmt.price(quote.price()) + " " + quote.currency()
                + (quote.open() ? " <green>● 盤中" : " <gray>● 休市"));
    }

    @Override
    public void onClick(int slot, ClickType click) {
        switch (slot) {
            case SLOT_HUB -> {
                click(Sound.UI_BUTTON_CLICK);
                // Bukkit forbids opening an inventory inside a click event, so defer a tick.
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                        () -> new io.github.sql1024.dasha.ui.HubMenu(plugin, viewer).open());
                return;
            }
            case SLOT_PREVIOUS -> {
                page--;
                click(Sound.UI_BUTTON_CLICK);
                render();
                return;
            }
            case SLOT_NEXT -> {
                page++;
                click(Sound.UI_BUTTON_CLICK);
                render();
                return;
            }
            case SLOT_REFRESH -> {
                click(Sound.UI_BUTTON_CLICK);
                render();
                return;
            }
            case SLOT_NEWS -> {
                for (Component line : Report.newsBoard(plugin)) {
                    viewer.sendMessage(line);
                }
                click(Sound.ITEM_BOOK_PAGE_TURN);
                return;
            }
            case SLOT_SWITCH -> {
                plugin.menus().openPortfolio(viewer);
                return;
            }
            default -> {
                // fall through to the stock slots
            }
        }

        String symbol = slotSymbols.get(slot);
        if (symbol == null) {
            return;
        }
        Stock stock = plugin.market().stock(symbol);
        if (stock == null) {
            render();
            return;
        }

        switch (click) {
            case LEFT -> plugin.trades().buy(viewer, stock, plugin.tuning().stockClickShares());
            case SHIFT_LEFT -> plugin.trades().buy(viewer, stock, plugin.tuning().stockShiftShares());
            case RIGHT -> plugin.trades().sell(viewer, stock, plugin.tuning().stockClickShares());
            case SHIFT_RIGHT -> plugin.trades().sell(viewer, stock, plugin.tuning().stockShiftShares());
            case MIDDLE -> {
                for (Component line : Report.stockInfo(plugin, stock, viewer.getUniqueId())) {
                    viewer.sendMessage(line);
                }
                click(Sound.ITEM_BOOK_PAGE_TURN);
            }
            default -> {
                return;
            }
        }
        render();
    }

    private void click(Sound sound) {
        viewer.playSound(viewer.getLocation(), sound, 0.5f, 1.4f);
    }
}
