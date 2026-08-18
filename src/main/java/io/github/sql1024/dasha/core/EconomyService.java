package io.github.sql1024.dasha.core;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The one place 大沙幣 is created, destroyed and moved. Every subsystem — stocks, auction house,
 * server store, ore selling — goes through here, so the money supply has a single source of truth.
 *
 * <p>Balances live in memory and are the authority; the database is a durable mirror written by a
 * background thread. All mutations take one lock, which makes a check-then-debit pair atomic: two
 * players buying the same auction listing on the same tick cannot both succeed, and a player
 * cannot spend the same coin twice by racing two GUIs.
 *
 * <p>Amounts are whole 大沙幣 held in {@code long}. Money is never stored in a floating-point
 * type — rounding drift over a season would silently mint or burn coins.
 */
public final class EconomyService {

    private final DashaEconomyPlugin plugin;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    /** Running totals since boot, so the report works even before the first ledger flush. */
    private volatile long mintedThisSession;
    private volatile long burnedThisSession;

    public EconomyService(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        balances.clear();
        balances.putAll(plugin.database().loadBalances());
        plugin.getLogger().info("載入 " + balances.size() + " 個大沙幣帳戶，流通總量 "
                + Fmt.coin(totalSupply()) + "。");
    }

    /** Writes every balance in one batch. Called on the periodic save and on shutdown. */
    public void flush() {
        lock.lock();
        try {
            plugin.database().saveAllBalances(balances);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ 查詢

    public long balance(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public long balance(OfflinePlayer player) {
        return balance(player.getUniqueId());
    }

    public boolean has(UUID uuid, long amount) {
        return amount <= 0L || balance(uuid) >= amount;
    }

    /** Total 大沙幣 in circulation across every account. */
    public long totalSupply() {
        long total = 0L;
        for (long value : balances.values()) {
            total += value;
        }
        return total;
    }

    public int accountCount() {
        return balances.size();
    }

    /** Live view of every account, for the rich list. Read-only. */
    public Map<UUID, Long> allBalances() {
        return Collections.unmodifiableMap(balances);
    }

    public long mintedThisSession() {
        return mintedThisSession;
    }

    public long burnedThisSession() {
        return burnedThisSession;
    }

    // ------------------------------------------------------------------ 變動

    /**
     * Takes {@code amount} from a player. Returns {@code false} and changes nothing when the
     * balance is short — the check and the debit happen under one lock, so the caller can trust
     * a {@code true} result even if two threads call at once.
     */
    public boolean withdraw(UUID uuid, String name, long amount, TxnType type, String detail) {
        if (amount < 0L) {
            throw new IllegalArgumentException("扣款金額不能是負數：" + amount);
        }
        if (amount == 0L) {
            return true;
        }
        lock.lock();
        try {
            long current = balances.getOrDefault(uuid, 0L);
            if (current < amount) {
                return false;
            }
            long after = current - amount;
            balances.put(uuid, after);
            record(uuid, name, type, -amount, after, detail);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Gives {@code amount} to a player. */
    public void deposit(UUID uuid, String name, long amount, TxnType type, String detail) {
        if (amount < 0L) {
            throw new IllegalArgumentException("入帳金額不能是負數：" + amount);
        }
        if (amount == 0L) {
            return;
        }
        lock.lock();
        try {
            long after = balances.getOrDefault(uuid, 0L) + amount;
            balances.put(uuid, after);
            record(uuid, name, type, amount, after, detail);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Moves money between two players in one atomic step, taking a cut for the server.
     * Used by the auction house: the buyer pays the full price, the seller receives the price
     * minus {@code cut}, and the cut is destroyed.
     *
     * @return {@code false} when the payer is short; nothing changes in that case
     */
    public boolean transfer(UUID from, String fromName, UUID to, String toName,
                            long amount, long cut, String detail) {
        if (amount < 0L || cut < 0L || cut > amount) {
            throw new IllegalArgumentException("轉帳金額不合法：amount=" + amount + " cut=" + cut);
        }
        lock.lock();
        try {
            long payerBalance = balances.getOrDefault(from, 0L);
            if (payerBalance < amount) {
                return false;
            }
            long payerAfter = payerBalance - amount;
            balances.put(from, payerAfter);
            record(from, fromName, TxnType.AUCTION_BUY, -amount, payerAfter, detail);

            long payout = amount - cut;
            long payeeAfter = balances.getOrDefault(to, 0L) + payout;
            balances.put(to, payeeAfter);
            record(to, toName, TxnType.AUCTION_PAYOUT, payout, payeeAfter, detail);

            if (cut > 0L) {
                // The cut never lands in an account — it leaves the economy here.
                record(to, toName, TxnType.AUCTION_CUT, -cut, payeeAfter, detail);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Overwrites a balance outright. Administrative only, and always audited. */
    public void set(UUID uuid, String name, long amount, String detail) {
        lock.lock();
        try {
            long value = Math.max(0L, amount);
            balances.put(uuid, value);
            record(uuid, name, TxnType.ADMIN, value, value, detail);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ 內部

    /**
     * Persists and logs one balance change. Always called with the lock held, so the ledger lines
     * are written in the same order the balances changed.
     */
    private void record(UUID uuid, String name, TxnType type, long delta, long after, String detail) {
        switch (type.flow()) {
            case FAUCET -> mintedThisSession += Math.abs(delta);
            case SINK -> burnedThisSession += Math.abs(delta);
            case TRANSFER, ADMIN -> {
                // Neither creates nor destroys money, so it stays out of the supply counters.
            }
        }
        plugin.database().saveBalance(uuid, after);
        plugin.database().logLedger(uuid, name, type, delta, after, detail);
    }

    // ------------------------------------------------------------------ 便利方法

    public boolean withdraw(Player player, long amount, TxnType type, String detail) {
        return withdraw(player.getUniqueId(), player.getName(), amount, type, detail);
    }

    public void deposit(Player player, long amount, TxnType type, String detail) {
        deposit(player.getUniqueId(), player.getName(), amount, type, detail);
    }

    public long balance(Player player) {
        return balance(player.getUniqueId());
    }

    /** Makes sure a player who has never traded still shows up in the account list. */
    public void ensureAccount(UUID uuid) {
        balances.putIfAbsent(uuid, 0L);
    }
}
