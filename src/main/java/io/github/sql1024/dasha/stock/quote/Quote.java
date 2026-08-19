package io.github.sql1024.dasha.stock.quote;

/**
 * One live reading from a real exchange.
 *
 * @param symbol        provider's ticker, e.g. {@code AAPL} or {@code 2330.TW}
 * @param price         last traded price, in {@link #currency}
 * @param previousClose previous session's close, used to show a day change while a market is shut
 * @param currency      ISO code the price is quoted in, e.g. {@code USD}, {@code TWD}
 * @param name          the exchange's own short name for the instrument
 * @param open          whether the market is trading right now
 * @param fetchedAt     epoch millis this reading was taken
 */
public record Quote(
        String symbol,
        double price,
        double previousClose,
        String currency,
        String name,
        boolean open,
        long fetchedAt) {

    /** Change since the previous close, in percent. */
    public double changePercent() {
        if (previousClose <= 0.0) {
            return 0.0;
        }
        return (price - previousClose) / previousClose * 100.0;
    }

    /** How stale this reading is, in seconds. */
    public long ageSeconds() {
        return Math.max(0L, (System.currentTimeMillis() - fetchedAt) / 1000L);
    }
}
