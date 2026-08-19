package io.github.sql1024.dasha.stock.trade;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.TxnType;
import io.github.sql1024.dasha.stock.market.MarketSettings;
import io.github.sql1024.dasha.stock.market.Stock;
import io.github.sql1024.dasha.stock.portfolio.Holding;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Validates and executes trades. Every method runs on the main thread, sends its own feedback,
 * and returns whether the trade went through.
 *
 * <p>Money moves through {@link io.github.sql1024.dasha.core.EconomyService}, so a stock trade and
 * an auction purchase contend for the same balance under the same lock — a player cannot spend the
 * same 大沙幣 in two windows at once.
 */
public final class TradeService {

    private final DashaEconomyPlugin plugin;

    public TradeService(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean buy(Player player, Stock stock, int shares) {
        MarketSettings settings = plugin.settings();
        var lang = plugin.lang();

        if (shares <= 0) {
            lang.send(player, "invalid-number", "input", shares);
            return fail(player);
        }
        if (shares > settings.maxSharesPerTrade()) {
            lang.send(player, "shares-too-many", "max", settings.maxSharesPerTrade());
            return fail(player);
        }
        if (!tradable(player, stock)) {
            return fail(player);
        }

        Holding holding = plugin.portfolios().get(player.getUniqueId(), stock.symbol());
        if (holding.shares() + shares > settings.maxSharesPerStock()) {
            lang.send(player, "hold-limit",
                    "max", settings.maxSharesPerStock(),
                    "symbol", stock.symbol(),
                    "held", holding.shares());
            return fail(player);
        }

        double unitPrice = stock.price();
        long cost = settings.buyCost(unitPrice, shares);
        long fee = settings.buyFee(unitPrice, shares);
        if (cost > settings.maxCoinPerTransaction()) {
            lang.send(player, "coin-limit",
                    "max", Fmt.coin(settings.maxCoinPerTransaction()),
                    "coin", Fmt.coin(cost));
            return fail(player);
        }

        long balance = plugin.economy().balance(player);
        if (balance < cost) {
            lang.send(player, "not-enough-coin", "need", Fmt.coin(cost), "have", Fmt.coin(balance));
            return fail(player);
        }

        // The fee is booked separately from the principal so /eco stats can tell the sink
        // (the fee, destroyed) apart from the money that merely changed form into shares.
        long principal = cost - fee;
        String detail = stock.symbol() + " x" + shares;
        if (!plugin.economy().withdraw(player, principal, TxnType.STOCK_BUY, detail)) {
            lang.send(player, "not-enough-coin", "need", Fmt.coin(cost), "have", Fmt.coin(balance));
            return fail(player);
        }
        if (fee > 0L && !plugin.economy().withdraw(player, fee, TxnType.STOCK_FEE, detail)) {
            // Principal already left the account; put it back rather than charging a partial trade.
            plugin.economy().deposit(player, principal, TxnType.STOCK_SELL, detail + " (回退)");
            lang.send(player, "not-enough-coin", "need", Fmt.coin(cost), "have", Fmt.coin(balance));
            return fail(player);
        }

        plugin.portfolios().set(player.getUniqueId(), stock.symbol(), holding.plus(shares, cost));
        plugin.database().logTransaction(
                player.getUniqueId(), player.getName(), stock.symbol(), "BUY", shares, unitPrice, cost);

        lang.send(player, "buy-success",
                "shares", shares,
                "name", stock.displayName(),
                "symbol", stock.symbol(),
                "price", Fmt.price(unitPrice),
                "coin", Fmt.coin(cost),
                "fee", Fmt.coin(fee));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        return true;
    }

    public boolean sell(Player player, Stock stock, int shares) {
        MarketSettings settings = plugin.settings();
        var lang = plugin.lang();

        Holding holding = plugin.portfolios().get(player.getUniqueId(), stock.symbol());
        if (shares <= 0) {
            lang.send(player, "invalid-number", "input", shares);
            return fail(player);
        }
        if (holding.shares() <= 0 || holding.shares() < shares) {
            lang.send(player, "not-enough-shares", "held", holding.shares(), "symbol", stock.symbol());
            return fail(player);
        }
        if (shares > settings.maxSharesPerTrade()) {
            lang.send(player, "shares-too-many", "max", settings.maxSharesPerTrade());
            return fail(player);
        }
        if (!tradable(player, stock)) {
            return fail(player);
        }

        double unitPrice = stock.price();
        long proceeds = settings.sellProceeds(unitPrice, shares);
        long fee = settings.sellFee(unitPrice, shares);
        if (proceeds > settings.maxCoinPerTransaction()) {
            lang.send(player, "coin-limit",
                    "max", Fmt.coin(settings.maxCoinPerTransaction()),
                    "coin", Fmt.coin(proceeds));
            return fail(player);
        }

        long basis = holding.basisFor(shares);
        plugin.portfolios().set(player.getUniqueId(), stock.symbol(), holding.minus(shares));

        String detail = stock.symbol() + " x" + shares;
        plugin.economy().deposit(player, proceeds, TxnType.STOCK_SELL, detail);
        if (fee > 0L) {
            // Booked as a sink even though it never entered the account, so the fee shows up in
            // the report as money the economy destroyed rather than money nobody ever had.
            plugin.database().logLedger(player.getUniqueId(), player.getName(), TxnType.STOCK_FEE,
                    -fee, plugin.economy().balance(player), detail);
        }
        plugin.database().logTransaction(
                player.getUniqueId(), player.getName(), stock.symbol(), "SELL", shares, unitPrice, proceeds);

        lang.send(player, "sell-success",
                "shares", shares,
                "name", stock.displayName(),
                "symbol", stock.symbol(),
                "price", Fmt.price(unitPrice),
                "coin", Fmt.coin(proceeds),
                "fee", Fmt.coin(fee),
                "pnl", Fmt.pnlTag(proceeds - basis));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
        return true;
    }

    /** Sells every share the player holds of one stock. */
    public boolean sellAll(Player player, Stock stock) {
        int held = plugin.portfolios().shares(player.getUniqueId(), stock.symbol());
        if (held <= 0) {
            plugin.lang().send(player, "not-enough-shares", "held", 0, "symbol", stock.symbol());
            return fail(player);
        }
        return sell(player, stock, Math.min(held, plugin.settings().maxSharesPerTrade()));
    }

    /**
     * Whether this player may trade this stock right now. Two things can say no: the stock is
     * halted because news is about to break, or the player published that news themselves and is
     * still inside the insider cool-down.
     */
    private boolean tradable(Player player, Stock stock) {
        if (!plugin.news().newsAffectsMarket()) {
            // News cannot move a real quote, so neither of the guards below is protecting
            // anything — leaving them on would just block trades for no visible reason.
            return true;
        }
        if (plugin.news().halted(stock.symbol())) {
            plugin.lang().send(player, "stock-halted",
                    "symbol", stock.symbol(),
                    "seconds", plugin.news().haltSecondsLeft(stock.symbol()));
            return false;
        }
        if (plugin.news().insiderLocked(player.getUniqueId(), stock.symbol())) {
            plugin.lang().send(player, "insider-locked",
                    "symbol", stock.symbol(),
                    "minutes", plugin.news().insiderMinutesLeft(player.getUniqueId(), stock.symbol()));
            return false;
        }
        return true;
    }

    private boolean fail(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
        return false;
    }
}
