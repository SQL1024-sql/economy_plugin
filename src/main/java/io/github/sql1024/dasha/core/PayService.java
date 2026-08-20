package io.github.sql1024.dasha.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import org.bukkit.entity.Player;

/**
 * Player-to-player transfers. Owns every rule that says "no" before the money moves, so the
 * command and the hub GUI enforce exactly the same limits.
 *
 * <p>The transfer itself is one call into {@link EconomyService#transfer}, which means it takes
 * the same lock as a stock purchase or an auction sale — a player cannot pay away 大沙幣 they are
 * spending in another window on the same tick.
 *
 * <p>Everything else here is abuse control. The daily total is mirrored into the database the way
 * the ore quota is, because a limit that resets on restart is not a limit.
 */
public final class PayService {

    /** Outcome of a transfer attempt. {@link #OK} is the only one that moved money. */
    public enum Result {
        OK,
        DISABLED,
        NO_SELF_PAY,
        BELOW_MIN,
        ABOVE_MAX,
        DAILY_LIMIT,
        COOLDOWN,
        NET_ZERO,
        NOT_ENOUGH,
        NEEDS_CONFIRM
    }

    private final DashaEconomyPlugin plugin;

    /** Payer to 大沙幣 sent today, before fees. */
    private final Map<UUID, Long> sentToday = new ConcurrentHashMap<>();
    /** Payer to the epoch millis of their last successful transfer. */
    private final Map<UUID, Long> lastPaid = new ConcurrentHashMap<>();
    /** Payer to a transfer they have been asked to confirm. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private String quotaDay = TradingDay.current();

    /** A large transfer waiting for {@code /pay confirm}. */
    public record Pending(UUID target, String targetName, long amount, long expiresAt) {

        public boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public PayService(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        quotaDay = TradingDay.current();
        sentToday.clear();
        pending.clear();
        plugin.database().prunePayQuota(quotaDay);
    }

    // ------------------------------------------------------------------ 額度

    private void rollDay() {
        String today = TradingDay.current();
        if (!today.equals(quotaDay)) {
            quotaDay = today;
            sentToday.clear();
            plugin.database().prunePayQuota(today);
        }
    }

    /** 大沙幣 this player has already sent today. */
    public long sentToday(UUID uuid) {
        rollDay();
        return sentToday.computeIfAbsent(uuid, key -> plugin.database().paySentToday(key, quotaDay));
    }

    /** 大沙幣 this player may still send today; {@link Long#MAX_VALUE} when unlimited. */
    public long dailyLeft(UUID uuid) {
        long limit = plugin.paySettings().dailyLimit();
        if (limit <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, limit - sentToday(uuid));
    }

    /** Seconds left on this player's cooldown, or 0 when they may pay now. */
    public long cooldownLeft(UUID uuid) {
        long cooldown = plugin.paySettings().cooldownSecs();
        if (cooldown <= 0L) {
            return 0L;
        }
        long last = lastPaid.getOrDefault(uuid, 0L);
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0L, cooldown - elapsed);
    }

    // ------------------------------------------------------------------ 確認

    /** The transfer this player has been asked to confirm, or {@code null} if there is none. */
    public Pending pendingFor(UUID uuid) {
        Pending held = pending.get(uuid);
        if (held != null && held.expired()) {
            pending.remove(uuid, held);
            return null;
        }
        return held;
    }

    public void clearPending(UUID uuid) {
        pending.remove(uuid);
    }

    // ------------------------------------------------------------------ 轉帳

    /**
     * Runs every check and, if they all pass, moves the money.
     *
     * <p>{@code confirmed} skips only the large-amount confirmation — every other limit is
     * re-checked, because a confirmation can sit for half a minute and the balance, the quota and
     * the configured caps can all have moved in that time.
     */
    public Result pay(Player payer, UUID target, String targetName, long amount, boolean confirmed) {
        PaySettings settings = plugin.paySettings();
        if (!settings.enabled()) {
            return Result.DISABLED;
        }
        if (payer.getUniqueId().equals(target)) {
            return Result.NO_SELF_PAY;
        }
        if (amount < settings.minAmount()) {
            return Result.BELOW_MIN;
        }
        if (amount > settings.maxAmount()) {
            return Result.ABOVE_MAX;
        }
        if (settings.netFor(amount) <= 0L) {
            // The fee floor would swallow the whole thing. Taking the coins and delivering
            // nothing is worse than refusing, so refuse and say what the smallest useful
            // transfer actually is.
            return Result.NET_ZERO;
        }
        if (amount > dailyLeft(payer.getUniqueId())) {
            return Result.DAILY_LIMIT;
        }
        if (cooldownLeft(payer.getUniqueId()) > 0L) {
            return Result.COOLDOWN;
        }
        if (plugin.economy().balance(payer) < amount) {
            return Result.NOT_ENOUGH;
        }
        if (!confirmed && settings.confirmAbove() > 0L && amount >= settings.confirmAbove()) {
            pending.put(payer.getUniqueId(), new Pending(target, targetName, amount,
                    System.currentTimeMillis() + settings.confirmSecs() * 1000L));
            return Result.NEEDS_CONFIRM;
        }

        long fee = settings.feeFor(amount);
        String detail = payer.getName() + " → " + targetName;
        boolean moved = plugin.economy().transfer(
                payer.getUniqueId(), payer.getName(), target, targetName, amount, fee, detail,
                TxnType.PAY_SENT, TxnType.PAY_RECEIVED, TxnType.PAY_FEE);
        if (!moved) {
            // Lost a race against another window spending the same coins.
            return Result.NOT_ENOUGH;
        }

        pending.remove(payer.getUniqueId());
        lastPaid.put(payer.getUniqueId(), System.currentTimeMillis());
        // Counted even when no limit is configured, so switching the limit on mid-day starts
        // from what has actually been sent rather than from zero.
        long used = sentToday(payer.getUniqueId()) + amount;
        sentToday.put(payer.getUniqueId(), used);
        plugin.database().savePayQuota(payer.getUniqueId(), quotaDay, used);
        plugin.getLogger().info("[轉帳] " + payer.getName() + " → " + targetName
                + " " + amount + "（手續費 " + fee + "）");
        return Result.OK;
    }

    public void handleQuit(UUID uuid) {
        pending.remove(uuid);
    }
}
