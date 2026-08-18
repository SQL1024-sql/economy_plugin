package io.github.sql1024.dasha.store;

import java.util.Map;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.TxnType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Sells building material for 大沙幣. Buying only — the store never takes anything back. */
public final class StoreService {

    private final DashaEconomyPlugin plugin;

    public StoreService(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Why a purchase did not happen, or {@link #OK} when it did. */
    public enum Result {
        OK,
        NOT_SOLD,
        NOT_ENOUGH_COIN,
        NO_ROOM
    }

    /**
     * Outcome of one purchase.
     *
     * @param result what happened
     * @param bought units actually received, which can be fewer than asked when space ran out
     * @param cost   大沙幣 actually charged
     */
    public record Purchase(Result result, int bought, long cost) {

        static Purchase failed(Result result) {
            return new Purchase(result, 0, 0L);
        }
    }

    /**
     * Buys {@code amount} of one material, or as much of it as will fit.
     *
     * <p>Space is checked before any money moves, so a full inventory can never end with the
     * player paying for goods they do not receive.
     */
    public Purchase buy(Player player, Material material, int amount) {
        StoreSettings.Entry entry = plugin.storeSettings().entry(material);
        if (entry == null || amount <= 0) {
            return Purchase.failed(Result.NOT_SOLD);
        }

        int room = roomFor(player, material);
        if (room <= 0) {
            return Purchase.failed(Result.NO_ROOM);
        }
        int buying = Math.min(amount, room);
        long cost = entry.price() * buying;

        if (!plugin.economy().withdraw(player, cost, TxnType.STORE_BUY,
                material.name() + " x" + buying)) {
            return Purchase.failed(Result.NOT_ENOUGH_COIN);
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stacks(material, buying));
        if (!leftover.isEmpty()) {
            // The room check said this would fit. Something changed underneath us, so drop the
            // remainder at the player's feet rather than deleting paid-for goods.
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        return new Purchase(Result.OK, buying, cost);
    }

    /** How many units of {@code material} the player's main inventory can still take. */
    public int roomFor(Player player, Material material) {
        int maxStack = material.getMaxStackSize();
        int room = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                room += maxStack;
            } else if (stack.getType() == material && stack.getAmount() < maxStack) {
                room += maxStack - stack.getAmount();
            }
        }
        return room;
    }

    private static ItemStack[] stacks(Material material, int amount) {
        int maxStack = material.getMaxStackSize();
        int full = amount / maxStack;
        int rest = amount % maxStack;
        ItemStack[] out = new ItemStack[full + (rest > 0 ? 1 : 0)];
        for (int i = 0; i < full; i++) {
            out[i] = new ItemStack(material, maxStack);
        }
        if (rest > 0) {
            out[out.length - 1] = new ItemStack(material, rest);
        }
        return out;
    }
}
