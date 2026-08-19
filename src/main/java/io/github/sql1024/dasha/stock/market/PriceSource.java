package io.github.sql1024.dasha.stock.market;

import java.util.Locale;

/** Where a stock's price comes from. */
public enum PriceSource {

    /**
     * The original engine: a random walk nudged by in-game news, with no outside input.
     * News is the only thing that moves a price, which is what the halt, the accuracy roll and
     * the daily band were all designed around.
     */
    SIMULATED,

    /**
     * Live prices from a real exchange. In-game news no longer moves anything — a fake headline
     * cannot budge Apple's real quote — so the news system becomes decoration here.
     */
    REAL,

    /**
     * Real prices as the anchor, with in-game news layered on top as a temporary premium or
     * discount. Keeps both the reality and the mechanic: the market tracks the world, but a
     * headline still means something, and the extra wobble makes pure "read the real ticker,
     * buy before the plugin updates" arbitrage less of a sure thing.
     */
    HYBRID;

    public static PriceSource parse(String raw, PriceSource fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Whether this mode needs the live price feed running. */
    public boolean needsFeed() {
        return this == REAL || this == HYBRID;
    }

    /** Whether in-game news changes the price in this mode. */
    public boolean newsMovesPrice() {
        return this == SIMULATED || this == HYBRID;
    }
}
