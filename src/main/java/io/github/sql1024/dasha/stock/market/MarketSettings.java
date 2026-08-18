package io.github.sql1024.dasha.stock.market;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Snapshot of the non-stock parts of stocks.yml, re-read on every reload.
 *
 * <p>The market maker on the other side of every trade is the server itself, with unlimited coins
 * and unlimited shares. That makes a profitable trader a money printer rather than someone else's
 * counterparty, so the guards here are not decoration — the fee, the slippage, the position caps
 * and the daily band are what keep the stock market from out-minting the mines.
 */
public record MarketSettings(
        long updateIntervalSeconds,
        double maxTickChangePercent,
        int historyPoints,
        double buyFeePercent,
        double sellFeePercent,
        double slippagePerShare,
        double dailyLimitPercent,
        boolean broadcastUpdates,
        double announceBigMovesPercent,
        int maxSharesPerTrade,
        int maxSharesPerStock,
        long maxCoinPerTransaction,
        String guiTitle,
        int chartWidth) {

    public static MarketSettings from(FileConfiguration config) {
        return new MarketSettings(
                Math.max(5L, config.getLong("market.update-interval-seconds", 300L)),
                Math.clamp(config.getDouble("market.max-tick-change-percent", 12.0), 0.1, 100.0),
                Math.clamp(config.getInt("market.history-points", 120), 2, 4096),
                Math.clamp(config.getDouble("market.buy-fee-percent", 1.0), 0.0, 90.0),
                Math.clamp(config.getDouble("market.sell-fee-percent", 1.0), 0.0, 90.0),
                Math.clamp(config.getDouble("market.slippage-per-share", 0.00002), 0.0, 0.01),
                Math.clamp(config.getDouble("market.daily-limit-percent", 20.0), 1.0, 500.0),
                config.getBoolean("market.broadcast-updates", false),
                Math.max(0.0, config.getDouble("market.announce-big-moves-percent", 10.0)),
                Math.max(1, config.getInt("limits.max-shares-per-trade", 200)),
                Math.max(1, config.getInt("limits.max-shares-per-stock", 1000)),
                Math.max(1L, config.getLong("limits.max-coin-per-transaction", 500_000L)),
                config.getString("gui.title", "<gradient:#f6d365:#fda085>大沙幣股市</gradient>"),
                Math.clamp(config.getInt("gui.chart-width", 24), 4, 64));
    }

    public long updateIntervalTicks() {
        return updateIntervalSeconds * 20L;
    }

    /** Average fee across both sides, for display where only one number fits. */
    public double feePercent() {
        return (buyFeePercent + sellFeePercent) / 2.0;
    }

    // ------------------------------------------------------------------ 滑價

    /**
     * Price actually paid per share. Buying pushes the fill price up in proportion to order size,
     * so one huge order costs strictly more than the same shares bought in pieces — a whale
     * cannot drain the market maker at the quoted price.
     */
    public double buyFillPrice(double unitPrice, int shares) {
        return unitPrice * (1.0 + slippagePerShare * shares);
    }

    /** Price actually received per share; large sells fill worse, mirroring {@link #buyFillPrice}. */
    public double sellFillPrice(double unitPrice, int shares) {
        return unitPrice * Math.max(0.1, 1.0 - slippagePerShare * shares);
    }

    // ------------------------------------------------------------------ 金額

    /** 大沙幣 owed when buying {@code shares}, slippage and fee included, rounded up. */
    public long buyCost(double unitPrice, int shares) {
        double gross = buyFillPrice(unitPrice, shares) * shares;
        return (long) Math.ceil(gross * (1.0 + buyFeePercent / 100.0));
    }

    /** 大沙幣 paid out when selling {@code shares}, slippage and fee deducted, rounded down. */
    public long sellProceeds(double unitPrice, int shares) {
        double gross = sellFillPrice(unitPrice, shares) * shares;
        return Math.max(0L, (long) Math.floor(gross * (1.0 - sellFeePercent / 100.0)));
    }

    /** The fee part of a buy, in 大沙幣. */
    public long buyFee(double unitPrice, int shares) {
        return buyCost(unitPrice, shares) - (long) Math.ceil(buyFillPrice(unitPrice, shares) * shares);
    }

    /** The fee part of a sell, in 大沙幣. */
    public long sellFee(double unitPrice, int shares) {
        return (long) Math.floor(sellFillPrice(unitPrice, shares) * shares) - sellProceeds(unitPrice, shares);
    }
}
