package io.github.sql1024.dasha.stock.market;

import io.github.sql1024.dasha.core.TradingDay;
import io.github.sql1024.dasha.core.Fmt;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Lang;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Owns the live price of every stock and advances them on a fixed interval using a
 * clamped geometric random walk: {@code price *= exp(drift + volatility * gauss)}.
 */
public final class MarketManager {

    private final DashaEconomyPlugin plugin;
    private final Random random = new Random();
    private final Map<String, Stock> stocks = new LinkedHashMap<>();

    public MarketManager(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Rebuilds the stock table from config.yml. Prices of surviving symbols are kept. */
    public void loadStocks(FileConfiguration config, MarketSettings settings) {
        Map<String, double[]> carried = new LinkedHashMap<>();
        for (Stock stock : stocks.values()) {
            carried.put(stock.symbol(), new double[] {stock.price(), stock.previousPrice()});
        }
        Map<String, List<Double>> carriedHistory = new LinkedHashMap<>();
        for (Stock stock : stocks.values()) {
            carriedHistory.put(stock.symbol(), new ArrayList<>(stock.history()));
        }

        stocks.clear();
        ConfigurationSection section = config.getConfigurationSection("stocks");
        if (section == null) {
            plugin.getLogger().warning("config.yml 沒有 stocks 區段，股市是空的。");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            String symbol = key.toUpperCase(Locale.ROOT);
            String iconName = node.getString("icon", "PAPER");
            Material icon = Material.matchMaterial(iconName == null ? "PAPER" : iconName);
            if (icon == null || !icon.isItem()) {
                plugin.getLogger().warning("股票 " + symbol + " 的 icon『" + iconName + "』無效，改用 PAPER。");
                icon = Material.PAPER;
            }

            double initial = Math.max(0.01, node.getDouble("initial-price", 100.0));
            double min = Math.max(0.01, node.getDouble("min-price", initial / 10.0));
            double max = Math.max(min, node.getDouble("max-price", initial * 10.0));

            Stock stock = new Stock(
                    symbol,
                    node.getString("display-name", symbol),
                    icon,
                    initial,
                    Math.clamp(node.getDouble("volatility", 0.05), 0.0, 1.0),
                    Math.clamp(node.getDouble("drift", 0.0), -0.1, 0.1),
                    min,
                    max,
                    settings.historyPoints());

            double[] previous = carried.get(symbol);
            if (previous != null) {
                stock.restore(previous[0], previous[1]);
                List<Double> history = carriedHistory.get(symbol);
                if (history != null) {
                    stock.loadHistory(history);
                }
            }
            stocks.put(symbol, stock);
        }
    }

    public Collection<Stock> stocks() {
        return stocks.values();
    }

    public List<Stock> stockList() {
        return new ArrayList<>(stocks.values());
    }

    public int size() {
        return stocks.size();
    }

    public Stock stock(String symbol) {
        if (symbol == null) {
            return null;
        }
        return stocks.get(symbol.toUpperCase(Locale.ROOT));
    }

    public boolean has(String symbol) {
        return stock(symbol) != null;
    }

    /** Advances every price by one step, persists the new state and announces big moves. */
    public void tick() {
        MarketSettings settings = plugin.settings();
        double maxChange = settings.maxTickChangePercent() / 100.0;
        List<Stock> moved = new ArrayList<>(stocks.size());

        String dayKey = TradingDay.current();
        for (Stock stock : stocks.values()) {
            double newsBias = plugin.news().biasFor(stock.symbol());
            double factor = Math.exp(stock.drift() + newsBias + stock.volatility() * random.nextGaussian());
            factor = Math.clamp(factor, 1.0 - maxChange, 1.0 + maxChange);
            double candidate = stock.price() * factor;
            stock.moveTo(stock.clampToDailyBand(candidate, dayKey, settings.dailyLimitPercent()));
            moved.add(stock);
        }

        long now = System.currentTimeMillis();
        for (Stock stock : moved) {
            plugin.database().savePrice(stock.symbol(), stock.price(), stock.previousPrice());
            plugin.database().appendHistory(stock.symbol(), now, stock.price(), settings.historyPoints());
        }

        announce(settings, moved);

        // News is spent by the move it caused, and the next headline is published now so players
        // get one window to react before it starts biasing the price.
        plugin.news().afterUpdate();
        plugin.news().maybePublish();

        plugin.menus().refreshOpenMenus();
    }

    /** Admin override: jumps a price without a random step, but still records history. */
    public void setPrice(Stock stock, double price) {
        stock.moveTo(price);
        plugin.database().savePrice(stock.symbol(), stock.price(), stock.previousPrice());
        plugin.database().appendHistory(
                stock.symbol(), System.currentTimeMillis(), stock.price(), plugin.settings().historyPoints());
        plugin.menus().refreshOpenMenus();
    }

    private void announce(MarketSettings settings, List<Stock> moved) {
        Lang lang = plugin.lang();

        if (settings.announceBigMovesPercent() > 0.0) {
            for (Stock stock : moved) {
                double change = stock.changePercent();
                if (Math.abs(change) < settings.announceBigMovesPercent()) {
                    continue;
                }
                String key = change > 0.0 ? "big-move-up" : "big-move-down";
                plugin.getServer().broadcast(lang.msg(key,
                        "name", stock.displayName(),
                        "symbol", stock.symbol(),
                        "change", Fmt.signedPercent(change),
                        "price", Fmt.price(stock.price())));
            }
        }

        if (settings.broadcastUpdates()) {
            StringBuilder line = new StringBuilder("<gray>股市更新：");
            for (Stock stock : moved) {
                line.append(' ').append("<white>").append(stock.symbol()).append("</white> ")
                        .append(Fmt.changeTag(stock.changePercent())).append("<reset>");
            }
            try {
                plugin.getServer().broadcast(Lang.mini(line.toString()));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "無法廣播股市更新", e);
            }
        }
    }
}
