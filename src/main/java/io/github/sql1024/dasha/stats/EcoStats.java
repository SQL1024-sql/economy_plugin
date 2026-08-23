package io.github.sql1024.dasha.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            // No closing tag: the colour above is what opened, and a stray </white> closing a
            // tag that was never opened renders as literal text.
            lines.add(Lang.mini("  <gray>" + type.label() + "　" + colour + Fmt.coin(value)));
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
    /**
     * One player's total worth and the two forms it is held in.
     *
     * <p>Ranking on cash alone rewarded staying out of the market: a player who put everything
     * into stocks looked poor right up until they sold. Net worth is what the leaderboard is
     * actually trying to say.
     *
     * @param cash   大沙幣 in the account
     * @param stocks holdings valued at the current market price
     */
    public record Wealth(UUID uuid, long cash, long stocks) {

        public long total() {
            return cash + stocks;
        }
    }

    /**
     * Everyone holding anything at all, richest first.
     *
     * <p>Built from both ledgers rather than the account list alone, so a player who has spent
     * every coin on shares still appears.
     */
    public List<Wealth> ranking() {
        Set<UUID> everyone = new HashSet<>(plugin.economy().allBalances().keySet());
        everyone.addAll(plugin.portfolios().everyone().keySet());

        List<Wealth> ranked = new ArrayList<>(everyone.size());
        for (UUID uuid : everyone) {
            long cash = plugin.economy().balance(uuid);
            long stocks = plugin.portfolios().marketValue(uuid);
            if (cash + stocks <= 0L) {
                continue;
            }
            ranked.add(new Wealth(uuid, cash, stocks));
        }
        ranked.sort(Comparator.comparingLong(Wealth::total).reversed());
        return ranked;
    }

    /**
     * Cash in circulation plus the market value of every holding — the denominator the share
     * column is a share of. Using the money supply alone would let a heavily invested server
     * report percentages adding up to far more than 100.
     */
    public long totalWealth() {
        long total = plugin.economy().totalSupply();
        for (UUID uuid : plugin.portfolios().everyone().keySet()) {
            total += plugin.portfolios().marketValue(uuid);
        }
        return total;
    }

    public List<Component> richList(int limit) {
        List<Wealth> ranked = ranking();

        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>━━━━━━ <gold>大沙幣富豪榜</gold> <dark_gray>━━━━━━"));
        lines.add(Lang.mini("<dark_gray>總資產 = 現金 + 持股市值"));

        long total = Math.max(1L, totalWealth());
        int rank = 1;
        for (Wealth wealth : ranked) {
            if (rank > limit) {
                break;
            }
            double share = wealth.total() * 100.0 / total;
            lines.add(Lang.mini("<gray>" + rank + ". <white>" + plugin.playerName(wealth.uuid())
                    + "</white>　<yellow>" + Fmt.coin(wealth.total()) + "</yellow>"
                    + " <dark_gray>(" + Fmt.price(share) + "%)"));
            lines.add(Lang.mini("<dark_gray>    現金 " + Fmt.coin(wealth.cash())
                    + "　持股 " + Fmt.coin(wealth.stocks())));
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
