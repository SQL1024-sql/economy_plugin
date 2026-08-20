package io.github.sql1024.dasha.core;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Everything {@code /pay} is allowed to do, read from the {@code pay} section of config.yml.
 *
 * <p>A direct transfer is the one money movement with no goods attached, which makes it the
 * cheapest way around every other sink: two players who want to trade can list on the auction
 * house and pay the cut, or they can hand the coins over for free and settle the item in a chest.
 * The fee here exists so that second route is not strictly cheaper than the first — set it at or
 * near {@code auction.sale-cut-percent} and the auction house stays the sensible way to trade.
 *
 * <p>The rest are damage limiters rather than economics: a cap and a confirmation prompt so a
 * mistyped zero is recoverable, a cooldown so a compromised account cannot be drained in one
 * script, and a daily ceiling so alt-farming has a bound.
 *
 * @param enabled        whether {@code /pay} works at all
 * @param feePercent     percentage of the amount destroyed on the way across
 * @param feeMin         floor on the fee, so tiny transfers are not effectively free
 * @param minAmount      smallest transfer allowed
 * @param maxAmount      largest single transfer allowed
 * @param dailyLimit     most one player may send per day; 0 means unlimited
 * @param cooldownSecs   seconds a player must wait between transfers
 * @param confirmAbove   transfers at or above this need a second confirmation; 0 disables it
 * @param confirmSecs    how long a pending confirmation stays valid
 * @param allowOffline   whether an offline player may be paid
 * @param notifyPayee    whether an online payee is told about the incoming money
 * @param broadcast      whether every transfer is announced to the whole server
 */
public record PaySettings(
        boolean enabled,
        double feePercent,
        long feeMin,
        long minAmount,
        long maxAmount,
        long dailyLimit,
        long cooldownSecs,
        long confirmAbove,
        long confirmSecs,
        boolean allowOffline,
        boolean notifyPayee,
        boolean broadcast) {

    public static PaySettings from(FileConfiguration config) {
        long min = Math.max(1L, config.getLong("pay.min-amount", 1L));
        long max = Math.max(min, config.getLong("pay.max-amount", 1_000_000L));
        return new PaySettings(
                config.getBoolean("pay.enabled", true),
                Math.clamp(config.getDouble("pay.fee-percent", 5.0), 0.0, 100.0),
                Math.max(0L, config.getLong("pay.fee-min", 1L)),
                min,
                max,
                Math.max(0L, config.getLong("pay.daily-limit", 0L)),
                Math.max(0L, config.getLong("pay.cooldown-seconds", 3L)),
                Math.max(0L, config.getLong("pay.confirm-above", 100_000L)),
                Math.max(1L, config.getLong("pay.confirm-seconds", 30L)),
                config.getBoolean("pay.allow-offline", true),
                config.getBoolean("pay.notify-payee", true),
                config.getBoolean("pay.broadcast", false));
    }

    /**
     * Fee charged on {@code amount}, never more than the amount itself — a 100% fee would
     * otherwise let the floor push the payee's share below zero.
     */
    public long feeFor(long amount) {
        long fee = Math.max(Math.round(amount * feePercent / 100.0), feeMin);
        return Math.clamp(fee, 0L, amount);
    }

    /** What the payee actually receives when the payer sends {@code amount}. */
    public long netFor(long amount) {
        return amount - feeFor(amount);
    }

    /**
     * Smallest amount that actually delivers something. Below this the fee floor takes the whole
     * transfer, so {@code /pay} refuses rather than silently burning the coins.
     */
    public long smallestUseful() {
        long floor = Math.max(minAmount(), feeMin + 1L);
        // A percentage of 100 can never leave anything behind, whatever the amount.
        if (feePercent >= 100.0) {
            return Long.MAX_VALUE;
        }
        while (floor < Long.MAX_VALUE && netFor(floor) <= 0L) {
            floor++;
        }
        return floor;
    }
}
