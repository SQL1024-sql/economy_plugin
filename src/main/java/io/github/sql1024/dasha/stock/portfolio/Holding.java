package io.github.sql1024.dasha.stock.portfolio;

/**
 * One player's position in one stock.
 *
 * @param shares   how many shares are held
 * @param invested total gold paid for those shares, fees included — the basis for average cost
 */
public record Holding(int shares, long invested) {

    public static final Holding EMPTY = new Holding(0, 0L);

    public double averageCost() {
        return shares <= 0 ? 0.0 : (double) invested / shares;
    }

    public Holding plus(int addedShares, long addedGold) {
        return new Holding(shares + addedShares, invested + addedGold);
    }

    /** Removes shares, reducing the invested basis proportionally. */
    public Holding minus(int removedShares) {
        if (removedShares >= shares) {
            return EMPTY;
        }
        long remainingInvested = Math.round(invested * (1.0 - (double) removedShares / shares));
        return new Holding(shares - removedShares, Math.max(0L, remainingInvested));
    }

    /** The invested gold attributable to {@code removedShares}, used to compute realised P/L. */
    public long basisFor(int removedShares) {
        if (shares <= 0) {
            return 0L;
        }
        if (removedShares >= shares) {
            return invested;
        }
        return invested - minus(removedShares).invested();
    }
}
