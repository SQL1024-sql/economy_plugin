package io.github.sql1024.dasha.stock.portfolio;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.stock.market.Stock;

/**
 * In-memory, main-thread-only holdings for every player, written through to SQLite
 * asynchronously. The whole table is small enough to keep loaded at all times.
 */
public final class PortfolioManager {

    private final DashaEconomyPlugin plugin;
    private final Map<UUID, Map<String, Holding>> holdings = new HashMap<>();

    public PortfolioManager(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Replaces everything in memory — called once at startup with the rows read from storage. */
    public void loadAll(Map<UUID, Map<String, Holding>> loaded) {
        holdings.clear();
        loaded.forEach((uuid, positions) -> holdings.put(uuid, new LinkedHashMap<>(positions)));
    }

    public Holding get(UUID player, String symbol) {
        return holdings.getOrDefault(player, Map.of()).getOrDefault(symbol, Holding.EMPTY);
    }

    public int shares(UUID player, String symbol) {
        return get(player, symbol).shares();
    }

    public Map<String, Holding> all(UUID player) {
        return Collections.unmodifiableMap(holdings.getOrDefault(player, Map.of()));
    }

    public Map<UUID, Map<String, Holding>> everyone() {
        return Collections.unmodifiableMap(holdings);
    }

    public void set(UUID player, String symbol, Holding holding) {
        if (holding.shares() <= 0) {
            Map<String, Holding> positions = holdings.get(player);
            if (positions != null) {
                positions.remove(symbol);
                if (positions.isEmpty()) {
                    holdings.remove(player);
                }
            }
            plugin.database().deleteHolding(player, symbol);
            return;
        }
        holdings.computeIfAbsent(player, key -> new LinkedHashMap<>()).put(symbol, holding);
        plugin.database().saveHolding(player, symbol, holding);
    }

    /** Current market value of a player's whole portfolio, in gold. */
    public long marketValue(UUID player) {
        long total = 0L;
        for (Map.Entry<String, Holding> entry : all(player).entrySet()) {
            Stock stock = plugin.market().stock(entry.getKey());
            if (stock != null) {
                total += (long) Math.floor(stock.price() * entry.getValue().shares());
            }
        }
        return total;
    }

    /** Total gold invested in the positions a player still holds. */
    public long investedTotal(UUID player) {
        long total = 0L;
        for (Holding holding : all(player).values()) {
            total += holding.invested();
        }
        return total;
    }
}
