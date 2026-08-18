package io.github.sql1024.dasha.stock.util;

import io.github.sql1024.dasha.core.Lang;
import io.github.sql1024.dasha.core.Fmt;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.stock.market.Indicators;
import io.github.sql1024.dasha.stock.market.Stock;
import io.github.sql1024.dasha.stock.news.NewsEvent;
import io.github.sql1024.dasha.stock.portfolio.Holding;
import net.kyori.adventure.text.Component;

/** Chat renderings shared by the commands and the GUI. */
public final class Report {

    private Report() {
    }

    /** Detailed view of one stock, including the viewer's own position when they hold any. */
    public static List<Component> stockInfo(DashaEconomyPlugin plugin, Stock stock, UUID viewer) {
        int width = plugin.settings().chartWidth();
        String currency = plugin.currency().displayName();
        Indicators indicators = Indicators.of(stock, plugin.indicators());

        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>▬▬▬▬▬▬ " + stock.displayName()
                + " <gray>[" + stock.symbol() + "]<dark_gray> ▬▬▬▬▬▬"));
        lines.add(Lang.mini("<gray>現價：<white>" + Fmt.price(stock.price()) + "</white> " + currency
                + "<gray> / 股　" + Fmt.changeTag(stock.changePercent())));
        lines.add(Lang.mini("<gray>區間：<white>" + Fmt.price(stock.historyLow())
                + "</white> ~ <white>" + Fmt.price(stock.historyHigh())
                + "</white>　<gray>累計 " + Fmt.changeTag(stock.sessionChangePercent())));
        lines.add(Lang.mini("<gray>走勢：" + Sparkline.render(stock.history(), width)));

        if (plugin.indicators().enabled()) {
            lines.add(Lang.mini("<gray>技術面：" + indicators.trendTag()
                    + "<reset>　" + indicators.maTag(plugin.indicators())));
            if (indicators.available()) {
                lines.add(Lang.mini("<gray>動能：" + Fmt.changeTag(indicators.momentumPercent())
                        + "<reset> <dark_gray>（近 " + plugin.indicators().longPeriod() + " 次更新）"));
            }
        }

        for (NewsEvent news : plugin.news().activeFor(stock.symbol())) {
            lines.add(Lang.mini("<gold>📰 " + (news.bullish() ? "<green>利多" : "<red>利空")
                    + " <gray>" + news.headline() + " <dark_gray>(還剩 " + news.updatesLeft() + " 次更新)"));
        }

        lines.add(Lang.mini("<gray>手續費：<white>" + Fmt.price(plugin.settings().feePercent()) + "%</white>"
                + "　<gray>買 1 股需 <white>" + Fmt.gold(plugin.settings().buyCost(stock.price(), 1))
                + "</white> " + currency));

        if (viewer != null) {
            Holding holding = plugin.portfolios().get(viewer, stock.symbol());
            if (holding.shares() > 0) {
                long value = (long) Math.floor(stock.price() * holding.shares());
                lines.add(Lang.mini("<gray>你的持股：<white>" + holding.shares()
                        + "</white> 股　均價 <white>" + Fmt.price(holding.averageCost())
                        + "</white>　市值 <white>" + Fmt.gold(value)
                        + "</white>　未實現 " + Fmt.pnlTag(value - holding.invested())));
            }
        }
        return lines;
    }

    /** One line per stock, for {@code /stock list}. */
    public static List<Component> marketList(DashaEconomyPlugin plugin, UUID viewer) {
        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>▬▬▬▬▬ <aqua>股市<dark_gray> ▬▬▬▬▬"));
        for (Stock stock : plugin.market().stocks()) {
            int held = viewer == null ? 0 : plugin.portfolios().shares(viewer, stock.symbol());
            String heldPart = held > 0 ? "　<dark_gray>(持有 <white>" + held + "</white> 股)" : "";
            String newsPart = plugin.news().hasNews(stock.symbol()) ? " <gold>📰" : "";
            lines.add(Lang.mini("<gray>" + stock.symbol() + " <dark_gray>· " + stock.displayName()
                    + "<gray>　<white>" + Fmt.price(stock.price()) + "</white>　"
                    + Fmt.changeTag(stock.changePercent()) + "<reset>　"
                    + Sparkline.render(stock.history(), Math.min(12, plugin.settings().chartWidth()))
                    + newsPart + heldPart));
        }
        lines.add(Lang.mini("<dark_gray>用 <yellow>/stock <dark_gray>開啟介面，"
                + "<yellow>/stock info 代號<dark_gray> 看詳細，"
                + "<yellow>/stock news<dark_gray> 看新聞。"));
        return lines;
    }

    /** Active and recent headlines, for {@code /stock news} and the GUI news button. */
    public static List<Component> newsBoard(DashaEconomyPlugin plugin) {
        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>▬▬▬ <gold>📰 財經新聞<dark_gray> ▬▬▬"));

        List<NewsEvent> active = plugin.news().active();
        if (active.isEmpty()) {
            lines.add(plugin.lang().plain("no-news"));
        }
        for (NewsEvent news : active) {
            // The headline already names the company, so only the ticker is added here.
            lines.add(Lang.mini((news.bullish() ? "<green>▲ 利多" : "<red>▼ 利空")
                    + " <white>" + news.symbol() + "</white>　<gray>" + news.headline()
                    + " <dark_gray>(還剩 " + news.updatesLeft() + " 次更新)"));
        }

        List<NewsEvent> recent = plugin.news().recent();
        if (recent.size() > active.size()) {
            lines.add(Lang.mini("<dark_gray>— 已結束 —"));
            for (int i = active.size(); i < recent.size(); i++) {
                NewsEvent news = recent.get(i);
                lines.add(Lang.mini("<dark_gray>" + (news.bullish() ? "利多 " : "利空 ")
                        + news.symbol() + " · " + news.headline()));
            }
        }
        lines.add(Lang.mini("<dark_gray>新聞只是「傾向」，準確率 <gray>"
                + Fmt.price(plugin.newsSettings().accuracyPercent()) + "%<dark_gray>，還是會有假消息。"));
        return lines;
    }
}
