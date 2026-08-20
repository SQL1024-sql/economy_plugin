package io.github.sql1024.dasha.sell;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.TradingDay;
import io.github.sql1024.dasha.core.TxnType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Buys ore from players. This is the only faucet in the economy — every 大沙幣 in circulation
 * entered here or was won from the market maker on the stock exchange.
 *
 * <p>Two things stop it running away. Each ore has a <em>supply pool</em> that fills as the server
 * sells and drains at a steady rate; while it is full the buy-back price sags towards a floor, so a
 * gold rush prices itself down and recovers on its own without anybody editing config. On top of
 * that each player has a daily earnings quota, which caps what an alt army or an AFK farm can
 * extract no matter how much material it produces.
 *
 * <p>The pool drains linearly rather than on a half-life, because a half-life never actually
 * reaches zero: operators want to say "the price is back to normal an hour after the rush", and
 * only a straight line gives a finite answer to that. The rate is set so an ore pinned at the
 * price floor climbs back to full price in exactly {@code recovery-hours}.
 *
 * <p>Selling is one-way on purpose: the server never sells ore back. That closes every arbitrage
 * loop through this service — there is no round trip to profit from.
 */
public final class OreSellService {

    private final DashaEconomyPlugin plugin;

    /** Material name to {@code [pool, lastUpdatedMillis]}. */
    private final Map<String, double[]> pools = new ConcurrentHashMap<>();
    /** Player to 大沙幣 earned from selling today. */
    private final Map<UUID, Long> spentToday = new ConcurrentHashMap<>();
    private String quotaDay = TradingDay.current();

    public OreSellService(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        pools.clear();
        pools.putAll(plugin.database().loadSupplyPools());
        quotaDay = TradingDay.current();
        spentToday.clear();
        plugin.database().pruneQuota(quotaDay);
    }

    // ------------------------------------------------------------------ 價格

    /** Current pool level for one ore, after applying the drain owed since it was last touched. */
    public double pool(Material material) {
        double[] entry = pools.get(material.name());
        if (entry == null) {
            return 0.0;
        }
        return drained(material, entry[0], (long) entry[1]);
    }

    private double drained(Material material, double pool, long updated) {
        if (pool <= 0.0) {
            return 0.0;
        }
        double hours = (System.currentTimeMillis() - updated) / 3_600_000.0;
        if (hours <= 0.0) {
            return pool;
        }
        return Math.max(0.0, pool - hours * drainPerHour(material));
    }

    /**
     * Pool units that evaporate per hour. Scaled per ore so every ore recovers on the same clock:
     * the pool level that pins the price at the floor, divided by the configured recovery time.
     *
     * <p>A pool pushed past that level — the server dumped far more than it took to bottom the
     * price out — takes proportionally longer, which is the intended punishment for a real flood.
     */
    private double drainPerHour(Material material) {
        SellSettings settings = plugin.sellSettings();
        SellSettings.Item item = settings.item(material);
        if (item == null) {
            // No longer purchased; let the leftover pool disappear rather than linger in the file.
            return Double.MAX_VALUE;
        }
        return item.softCap() * (1.0 - settings.priceFloor()) / settings.recoveryHours();
    }

    /**
     * Empties every supply pool, putting all buy-back prices straight back to their base price.
     * Administrative: for undoing a test, a duped-item incident, or a rush the operator has
     * decided not to make everyone wait out.
     *
     * @return how many ores actually had a pool to clear
     */
    public int resetPools() {
        int cleared = 0;
        for (Map.Entry<String, double[]> entry : pools.entrySet()) {
            if (entry.getValue()[0] > 0.0) {
                cleared++;
            }
        }
        pools.clear();
        plugin.database().clearSupplyPools();
        return cleared;
    }

    /** Seconds until this ore is back at its base price, or 0 when it already is. */
    public long secondsToFullPrice(SellSettings.Item item) {
        double current = pool(item.material());
        if (current <= 0.0) {
            return 0L;
        }
        double rate = drainPerHour(item.material());
        if (rate <= 0.0 || rate == Double.MAX_VALUE) {
            return 0L;
        }
        return Math.max(0L, Math.round(current / rate * 3600.0));
    }

    /** Multiplier currently applied to an ore's base price, between the floor and 1.0. */
    public double discountFactor(SellSettings.Item item) {
        double floor = plugin.sellSettings().priceFloor();
        double factor = 1.0 - pool(item.material()) / item.softCap();
        return Math.clamp(factor, floor, 1.0);
    }

    /** What the server pays for one more unit right now. */
    public long unitPrice(SellSettings.Item item) {
        return Math.max(1L, Math.round(item.basePrice() * discountFactor(item)));
    }

    /**
     * Total paid for {@code amount} units sold in one go.
     *
     * <p>Priced as an integral rather than {@code amount × currentPrice}: each unit sold pushes
     * the pool up and the next unit's price down, so a single bulk sale must earn exactly what
     * the same units would earn sold one at a time. Otherwise splitting or combining stacks
     * would be worth money.
     */
    public long payoutFor(SellSettings.Item item, int amount) {
        if (amount <= 0) {
            return 0L;
        }
        double floor = plugin.sellSettings().priceFloor();
        double softCap = item.softCap();
        double start = pool(item.material());

        // Pool level at which the discount bottoms out and the price stops falling.
        double floorPoint = softCap * (1.0 - floor);

        double factorUnits;
        if (start >= floorPoint) {
            factorUnits = amount * floor;
        } else {
            double slopeUnits = Math.min(amount, floorPoint - start);
            // ∫ (1 − x/softCap) dx from start to start+slopeUnits
            factorUnits = slopeUnits
                    - (2.0 * start * slopeUnits + slopeUnits * slopeUnits) / (2.0 * softCap);
            factorUnits += (amount - slopeUnits) * floor;
        }
        return Math.max(0L, Math.round(item.basePrice() * factorUnits));
    }

    /** Largest number of units whose payout still fits inside {@code budget}. */
    private int unitsWithin(SellSettings.Item item, int available, long budget) {
        if (budget <= 0L) {
            return 0;
        }
        if (payoutFor(item, available) <= budget) {
            return available;
        }
        int low = 0;
        int high = available;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (payoutFor(item, mid) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    // ------------------------------------------------------------------ 每日額度

    private void rollDayIfNeeded() {
        String today = TradingDay.current();
        if (!today.equals(quotaDay)) {
            quotaDay = today;
            spentToday.clear();
            plugin.database().pruneQuota(today);
        }
    }

    public long quotaUsed(UUID uuid) {
        rollDayIfNeeded();
        return spentToday.computeIfAbsent(uuid, key -> plugin.database().quotaSpent(key, quotaDay));
    }

    /** 大沙幣 this player may still earn from selling today; {@link Long#MAX_VALUE} when unlimited. */
    public long quotaLeft(UUID uuid) {
        long quota = plugin.sellSettings().dailyQuota();
        if (quota <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, quota - quotaUsed(uuid));
    }

    // ------------------------------------------------------------------ 賣出

    /** What one sell attempt did. */
    public record Result(int sold, long paid, boolean quotaHit) {

        public boolean anything() {
            return sold > 0;
        }
    }

    /** Sells one specific stack the player is holding. */
    public Result sellStack(Player player, ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return new Result(0, 0L, false);
        }
        SellSettings.Item item = plugin.sellSettings().item(stack.getType());
        if (item == null || !plain(stack)) {
            return new Result(0, 0L, false);
        }

        int held = stack.getAmount();
        long budget = quotaLeft(player.getUniqueId());
        int sellable = unitsWithin(item, held, budget);
        if (sellable <= 0) {
            return new Result(0, 0L, true);
        }

        long paid = payoutFor(item, sellable);
        stack.setAmount(held - sellable);
        creditAndRecord(player, item, sellable, paid);
        return new Result(sellable, paid, sellable < held);
    }

    /** Sells every purchasable ore in the player's main inventory. */
    public Result sellAll(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();

        int totalSold = 0;
        long totalPaid = 0L;
        boolean quotaHit = false;

        // Group by material first so the integral pricing sees one sale per ore rather than one
        // per stack — otherwise the order slots happen to be in would change the payout.
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack != null && plugin.sellSettings().buys(stack.getType()) && plain(stack)) {
                counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }

        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            SellSettings.Item item = plugin.sellSettings().item(entry.getKey());
            long budget = quotaLeft(player.getUniqueId());
            int sellable = unitsWithin(item, entry.getValue(), budget);
            if (sellable <= 0) {
                quotaHit = true;
                continue;
            }
            if (sellable < entry.getValue()) {
                quotaHit = true;
            }
            long paid = payoutFor(item, sellable);
            removeExactly(contents, entry.getKey(), sellable);
            creditAndRecord(player, item, sellable, paid);
            totalSold += sellable;
            totalPaid += paid;
        }

        if (totalSold > 0) {
            inventory.setStorageContents(contents);
        }
        return new Result(totalSold, totalPaid, quotaHit);
    }

    private void creditAndRecord(Player player, SellSettings.Item item, int amount, long paid) {
        String detail = item.material().name() + " x" + amount;
        plugin.economy().deposit(player, paid, TxnType.ORE_SELL, detail);

        double[] entry = pools.computeIfAbsent(item.material().name(),
                key -> new double[] {0.0, System.currentTimeMillis()});
        double current = drained(item.material(), entry[0], (long) entry[1]);
        entry[0] = current + amount;
        entry[1] = System.currentTimeMillis();
        plugin.database().saveSupplyPool(item.material().name(), entry[0], (long) entry[1]);

        long used = quotaUsed(player.getUniqueId()) + paid;
        spentToday.put(player.getUniqueId(), used);
        plugin.database().saveQuota(player.getUniqueId(), quotaDay, used);
    }

    private static void removeExactly(ItemStack[] contents, Material material, int amount) {
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material || !plain(stack)) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            left -= take;
            if (take >= stack.getAmount()) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }
    }

    /**
     * Only ordinary items are bought. A renamed, enchanted or otherwise special stack is somebody's
     * keepsake or another plugin's custom item, and paying ore rates for it would both rob the
     * player and open a hole for custom items priced far above their base material.
     */
    private static boolean plain(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return true;
        }
        var meta = stack.getItemMeta();
        return meta == null
                || (!meta.hasDisplayName()
                && !meta.hasLore()
                && !meta.hasCustomModelDataComponent()
                && !meta.hasItemModel()
                && meta.getEnchants().isEmpty());
    }
}
