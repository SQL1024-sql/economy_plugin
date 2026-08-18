package io.github.sql1024.dasha.stock.news;

/**
 * One published headline about one stock.
 *
 * <p>The headline states {@link #publishedDirection()}, but the price actually leans towards
 * {@link #actualDirection()} — the two differ whenever the news turns out to be wrong, which is
 * what keeps the market from being solvable.
 */
public final class NewsEvent {

    private final String symbol;
    private final String headline;
    private final int publishedDirection;
    private final int actualDirection;
    private final double biasPerUpdate;
    private final long createdAt;

    private int updatesLeft;

    public NewsEvent(String symbol,
                     String headline,
                     int publishedDirection,
                     int actualDirection,
                     double biasPerUpdate,
                     int updatesLeft,
                     long createdAt) {
        this.symbol = symbol;
        this.headline = headline;
        this.publishedDirection = publishedDirection;
        this.actualDirection = actualDirection;
        this.biasPerUpdate = biasPerUpdate;
        this.updatesLeft = updatesLeft;
        this.createdAt = createdAt;
    }

    public String symbol() {
        return symbol;
    }

    public String headline() {
        return headline;
    }

    /** {@code +1} when the headline reads as good news, {@code -1} when it reads as bad news. */
    public int publishedDirection() {
        return publishedDirection;
    }

    /** {@code +1} or {@code -1}: the direction the price is actually nudged towards. */
    public int actualDirection() {
        return actualDirection;
    }

    /** Signed log-return added to the stock on every market update while this news is active. */
    public double biasPerUpdate() {
        return biasPerUpdate;
    }

    public int updatesLeft() {
        return updatesLeft;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean bullish() {
        return publishedDirection > 0;
    }

    public boolean expired() {
        return updatesLeft <= 0;
    }

    /** Whether the headline pointed the same way the price was actually nudged. */
    public boolean wasTrue() {
        return publishedDirection == actualDirection;
    }

    void countDown() {
        updatesLeft--;
    }
}
