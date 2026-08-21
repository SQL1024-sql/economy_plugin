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
                    settings.historyPoints(),
                    node.getString("real-symbol", null));

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
        List<Stock> moved = new ArrayList<>(stocks.size());

        String dayKey = TradingDay.current();
        for (Stock stock : stocks.values()) {
            Step step = nextStep(stock, settings, dayKey);
            if (Double.isNaN(step.price())) {
                // Real market shut and freezing is on: leave the price exactly where it is.
                continue;
            }
            if (step.real()) {
                stock.moveToReal(step.price());
            } else {
                stock.moveTo(step.price());
            }
            moved.add(stock);
        }

        long now = System.currentTimeMillis();
        for (Stock stock : moved) {
            plugin.database().savePrice(stock.symbol(), stock.price(), stock.previousPrice(),
                    regimeKey(stock, settings));
            plugin.database().appendHistory(stock.symbol(), now, stock.price(), settings.historyPoints());
        }

        announce(settings, moved);

        // News is spent by the move it caused, and the next headline is published now so players
        // get one window to react before it starts biasing the price.
        plugin.news().afterUpdate();
        plugin.news().maybePublish();

        plugin.menus().refreshOpenMenus();
    }

    /**
     * The price this stock should move to, or {@link Double#NaN} to leave it untouched.
     *
     * <p>Three modes meet here. Simulation is the original random walk. Real mode copies the
     * exchange, converted into 大沙幣. Hybrid takes the real price and applies a decaying premium
     * that in-game news builds up, so a headline still moves the board without the price drifting
     * away from reality for good.
     */
    private Step nextStep(Stock stock, MarketSettings settings, String dayKey) {
        PriceSource source = settings.priceSource();

        if (source.needsFeed() && stock.realSymbol() != null) {
            var quote = plugin.quotes().quote(stock.realSymbol());
            if (quote != null) {
                stock.setMarketOpen(quote.open());
                stock.setRealPrice(quote.price());

                double rate = settings.rateFor(quote.currency());
                // Anchor the day change to the exchange's own previous close, so the percentage
                // shown in game is the one the player also sees on a real ticker. Set before any
                // early return: a shut market still has a day change to report.
                stock.setDayAnchor(quote.previousClose() * rate);

                double converted = quote.price() * rate;

                if (!quote.open() && source == PriceSource.REAL) {
                    if (settings.closedDriftPercent() > 0.0) {
                        // A frozen board is dull; a whisper of drift keeps night play alive
                        // without pretending the real market moved.
                        double drift = settings.closedDriftPercent() / 100.0;
                        return Step.real(converted * (1.0 + random.nextGaussian() * drift));
                    }
                    if (settings.freezeWhenClosed()) {
                        // Still sync to the real close once — a server booted outside trading
                        // hours would otherwise sit on config placeholder prices until the
                        // exchange reopens. Once matched, stop recording flat ticks.
                        return sameAsNow(converted, stock.price()) ? Step.NONE : Step.real(converted);
                    }
                }

                if (source == PriceSource.HYBRID) {
                    return Step.real(converted * (1.0 + advanceOverlay(stock, settings)));
                }
                return Step.real(converted);
            }
            if (!settings.fallbackToSimulated()) {
                // No reading yet and no fallback allowed: better a stale price than a made-up one.
                return Step.NONE;
            }
        }

        return new Step(simulatedPrice(stock, settings, dayKey), false);
    }

    /**
     * One computed price step. {@code real} marks a price copied from the exchange, which is
     * exempt from the configured price band and which the stored-history régime check treats
     * as a different world from a simulated price.
     */
    private record Step(double price, boolean real) {

        static final Step NONE = new Step(Double.NaN, false);

        static Step real(double price) {
            return new Step(price, true);
        }
    }

    /**
     * Identifies the pricing régime a stored price and its history were produced under.
     *
     * <p>Price history is only meaningful when every point in it came out of the same machinery.
     * Switching {@code price-source}, repointing a stock at a different ticker, editing a currency
     * rate or moving the configured band all rescale the numbers — keeping the old points would
     * splice two unrelated series together, and the chart, the high/low range and the moving
     * averages would all describe a crash that never happened. When this key changes, the old
     * points are thrown away rather than drawn.
     */
    public String regimeKey(Stock stock, MarketSettings settings) {
        StringBuilder key = new StringBuilder(settings.priceSource().name());
        key.append('|').append(stock.realSymbol() == null ? "-" : stock.realSymbol());
        if (settings.priceSource().needsFeed() && stock.realSymbol() != null) {
            // Real prices come from the exchange, so only the conversion rate can rescale them.
            key.append("|fx").append(settings.ratesFingerprint());
        } else {
            // A simulated walk lives entirely inside its configured band.
            key.append("|sim").append(Math.round(stock.initialPrice() * 100.0))
                    .append('/').append(Math.round(stock.minPrice() * 100.0))
                    .append('/').append(Math.round(stock.maxPrice() * 100.0));
        }
        return key.toString();
    }

    /** Whether two prices are the same to within rounding, so no history point is worth adding. */
    private static boolean sameAsNow(double candidate, double current) {
        return Math.abs(candidate - current) <= Math.max(1.0e-9, Math.abs(candidate) * 1.0e-6);
    }

    /** The original random walk, also used as the fallback when a live quote is missing. */
    private double simulatedPrice(Stock stock, MarketSettings settings, String dayKey) {
        double maxChange = settings.maxTickChangePercent() / 100.0;
        double newsBias = settings.priceSource().newsMovesPrice()
                ? plugin.news().biasFor(stock.symbol())
                : 0.0;
        double factor = Math.exp(stock.drift() + newsBias + stock.volatility() * random.nextGaussian());
        factor = Math.clamp(factor, 1.0 - maxChange, 1.0 + maxChange);
        return stock.clampToDailyBand(stock.price() * factor, dayKey, settings.dailyLimitPercent());
    }

    /**
     * Moves the hybrid news premium one step and returns it.
     *
     * <p>News adds to the premium while it is running and the premium decays back towards zero
     * once it stops, so a stock always returns to tracking its real price instead of drifting
     * away from it permanently.
     */
    private double advanceOverlay(Stock stock, MarketSettings settings) {
        double overlay = stock.newsOverlay() * settings.overlayDecay();
        overlay += plugin.news().biasFor(stock.symbol());
        overlay = Math.clamp(overlay, -0.9, 9.0);
        stock.setNewsOverlay(overlay);
        return overlay;
    }

    /** Admin override: jumps a price without a random step, but still records history. */
    public void setPrice(Stock stock, double price) {
        stock.moveTo(price);
        plugin.database().savePrice(stock.symbol(), stock.price(), stock.previousPrice(),
                regimeKey(stock, plugin.settings()));
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
