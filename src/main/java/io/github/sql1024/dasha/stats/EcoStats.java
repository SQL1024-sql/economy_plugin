package io.github.sql1024.dasha.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.Lang;
import io.github.sql1024.dasha.core.TxnType;
import io.github.sql1024.dasha.sell.SellSettings;
import io.github.sql1024.dasha.stock.market.Stock;
import net.kyori.adventure.text.Component;

/**
 * The economy dashboard. Prices in config are guesses until these numbers say otherwise, so this
 * exists to make the guesses correctable.
 *
 * <p>The line worth watching is the stock market's net flow. Ore selling is a faucet by design and
 * its rate is capped; the stock exchange is a faucet only when players are collectively beating
 * the market maker, and nothing caps that but the guards. If stock net flow runs positive and
 * large next to ore income, the market is minting money and its fees or limits need tightening.
 */
public final class EcoStats {

    private static final long DAY_MILLIS = 86_400_000L;

    private final DashaEconomyPlugin plugin;

    public EcoStats(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Full report, one component per line. */
    public List<Component> report(int days) {
        long since = System.currentTimeMillis() - (long) Math.max(1, days) * DAY_MILLIS;
        Map<TxnType, Long> totals = plugin.database().ledgerTotals(since);

        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>━━━━━━ <gradient:#f6d365:#fda085>大沙幣經濟報告</gradient> <dark_gray>━━━━━━"));

        long supply = plugin.economy().totalSupply();
        lines.add(Lang.mini("<gray>流通總量：<white>" + Fmt.coin(supply) + "</white> "
                + plugin.currency().displayName()));
        lines.add(Lang.mini("<gray>持有帳戶：<white>" + plugin.economy().accountCount() + "</white> 個"
                + "　<dark_gray>|</dark_gray> <gray>人均 <white>"
                + Fmt.coin(plugin.economy().accountCount() == 0
                        ? 0L : supply / plugin.economy().accountCount()) + "</white>"));
        lines.add(Component.empty());

        // ---- 產出 / 消耗 ----
        long faucet = 0L;
        long sink = 0L;
        for (Map.Entry<TxnType, Long> entry : totals.entrySet()) {
            switch (entry.getKey().flow()) {
                case FAUCET -> faucet += entry.getValue();
                case SINK -> sink += entry.getValue();
                case TRANSFER, ADMIN -> {
                    // Neither side changes the supply, so they stay out of this total.
                }
            }
        }
        long net = faucet - sink;

        lines.add(Lang.mini("<yellow>近 <white>" + days + "</white> 天"));
        lines.add(Lang.mini("  <green>產出　<white>" + Fmt.coin(faucet) + "</white>"));
        lines.add(Lang.mini("  <red>消耗　<white>" + Fmt.coin(sink) + "</white>"));
        lines.add(Lang.mini("  <gray>淨變動 " + Fmt.pnlTag(net)
                + (net > 0 ? " <dark_gray>(通膨中)" : net < 0 ? " <dark_gray>(通縮中)" : "")));
        lines.add(Component.empty());

        // ---- 分管道 ----
        lines.add(Lang.mini("<yellow>各管道"));
        for (TxnType type : TxnType.values()) {
            long value = totals.getOrDefault(type, 0L);
            if (value == 0L) {
                continue;
            }
            String colour = switch (type.flow()) {
                case FAUCET -> "<green>+";
                case SINK -> "<red>-";
                case TRANSFER -> "<aqua>~";
                case ADMIN -> "<light_purple>*";
            };
            lines.add(Lang.mini("  <gray>" + type.label() + "　" + colour + Fmt.coin(value) + "</white>"));
        }
        lines.add(Component.empty());

        // ---- 股市淨資金流 ----
        long stockIn = totals.getOrDefault(TxnType.STOCK_SELL, 0L);
        long stockOut = totals.getOrDefault(TxnType.STOCK_BUY, 0L);
        long stockFee = totals.getOrDefault(TxnType.STOCK_FEE, 0L);
        long stockNet = stockIn - stockOut - stockFee;
        long oreNet = totals.getOrDefault(TxnType.ORE_SELL, 0L);

        lines.add(Lang.mini("<yellow>股市淨資金流　" + Fmt.pnlTag(stockNet)));
        lines.add(Lang.mini("  <dark_gray>玩家從做市商手上淨賺的錢 = 系統憑空印出的錢"));
        if (stockNet > 0L && stockNet > oreNet) {
            lines.add(Lang.mini("  <red>⚠ 股市印出的錢比挖礦還多，建議調高手續費或收緊持股上限。"));
        }
        lines.add(Component.empty());

        // ---- 礦物供給池 ----
        lines.add(Lang.mini("<yellow>礦物收購水位"));
        for (SellSettings.Item item : plugin.sellSettings().items().values()) {
            double factor = plugin.oreSell().discountFactor(item);
            String bar = bar(factor);
            lines.add(Lang.mini("  <gray>" + item.material().name() + "　" + bar
                    + " <white>" + Fmt.coin(plugin.oreSell().unitPrice(item)) + "</white>"
                    + "<dark_gray>/" + Fmt.coin(item.basePrice())
                    + "　(" + Math.round(factor * 100) + "%)"));
        }
        lines.add(Component.empty());

        // ---- 股價 ----
        lines.add(Lang.mini("<yellow>股價"));
        for (Stock stock : plugin.market().stocks()) {
            lines.add(Lang.mini("  <gray>" + stock.symbol() + "　<white>"
                    + Fmt.price(stock.price()) + "</white>　" + Fmt.changeTag(stock.dayChangePercent())));
        }

        return lines;
    }

    /** Top balances, newest snapshot of who holds the money. */
    public List<Component> richList(int limit) {
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(plugin.economy().allBalances().entrySet());
        sorted.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));

        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>━━━━━━ <gold>大沙幣富豪榜</gold> <dark_gray>━━━━━━"));
        long supply = Math.max(1L, plugin.economy().totalSupply());
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : sorted) {
            if (rank > limit) {
                break;
            }
            if (entry.getValue() <= 0L) {
                break;
            }
            double share = entry.getValue() * 100.0 / supply;
            lines.add(Lang.mini("<gray>" + rank + ". <white>" + plugin.playerName(entry.getKey())
                    + "</white>　<yellow>" + Fmt.coin(entry.getValue()) + "</yellow>"
                    + " <dark_gray>(" + Fmt.price(share) + "%)"));
            rank++;
        }
        if (rank == 1) {
            lines.add(Lang.mini("<gray>還沒有人持有大沙幣。"));
        }
        return lines;
    }

    private String bar(double factor) {
        int width = plugin.tuning().barWidth();
        int filled = (int) Math.round(Math.clamp(factor, 0.0, 1.0) * width);
        String colour = factor > 0.75 ? "<green>" : factor > 0.45 ? "<yellow>" : "<red>";
        return colour + "▉".repeat(filled) + "<dark_gray>" + "▉".repeat(width - filled);
    }
}
