package io.github.sql1024.dasha.stock.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Material;

/** A single tradable stock: its static configuration plus its live price and history. */
public final class Stock {

    private final String symbol;
    private final String displayName;
    private final Material icon;
    private final double initialPrice;
    private final double volatility;
    private final double drift;
    private final double minPrice;
    private final double maxPrice;
    private final int historyPoints;

    private final List<Double> history = new ArrayList<>();

    private double price;
    private double previousPrice;

    /** Price this stock opened the current day at, and which day that was. */
    private double dayOpen;
    private String dayKey = "";

    public Stock(String symbol,
                 String displayName,
                 Material icon,
                 double initialPrice,
                 double volatility,
                 double drift,
                 double minPrice,
                 double maxPrice,
                 int historyPoints) {
        this.symbol = symbol;
        this.displayName = displayName;
        this.icon = icon;
        this.initialPrice = initialPrice;
        this.volatility = volatility;
        this.drift = drift;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.historyPoints = Math.max(2, historyPoints);
        this.price = clamp(initialPrice);
        this.previousPrice = this.price;
    }

    public String symbol() {
        return symbol;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public double initialPrice() {
        return initialPrice;
    }

    public double volatility() {
        return volatility;
    }

    public double drift() {
        return drift;
    }

    public double minPrice() {
        return minPrice;
    }

    public double maxPrice() {
        return maxPrice;
    }

    public double price() {
        return price;
    }

    public double previousPrice() {
        return previousPrice;
    }

    /** Change since the previous market update, in percent. */
    public double changePercent() {
        if (previousPrice <= 0.0) {
            return 0.0;
        }
        return (price - previousPrice) / previousPrice * 100.0;
    }

    /** Change since the oldest retained history point, in percent. */
    public double sessionChangePercent() {
        if (history.isEmpty()) {
            return 0.0;
        }
        double first = history.get(0);
        if (first <= 0.0) {
            return 0.0;
        }
        return (price - first) / first * 100.0;
    }

    public List<Double> history() {
        return Collections.unmodifiableList(history);
    }

    public double historyLow() {
        double low = price;
        for (double value : history) {
            low = Math.min(low, value);
        }
        return low;
    }

    public double historyHigh() {
        double high = price;
        for (double value : history) {
            high = Math.max(high, value);
        }
        return high;
    }

    /**
     * Confines a candidate price to the day's allowed band and returns what the price may become.
     *
     * <p>News drives this market with no supply and demand pushing back, so a strong headline
     * compounding across several updates can run a price to absurd numbers in an evening. The
     * band caps how far a stock can travel from where it opened, which bounds how much a single
     * lucky position can mint before anybody can react.
     *
     * @param dayKey       identifier of the current trading day; a new one re-anchors the band
     * @param limitPercent how far from the open the price may move, either way
     */
    public double clampToDailyBand(double candidate, String dayKey, double limitPercent) {
        if (!dayKey.equals(this.dayKey)) {
            this.dayKey = dayKey;
            this.dayOpen = price;
        }
        if (dayOpen <= 0.0) {
            dayOpen = price;
        }
        double span = dayOpen * (limitPercent / 100.0);
        return Math.clamp(candidate, dayOpen - span, dayOpen + span);
    }

    /** Change since this trading day opened, in percent. */
    public double dayChangePercent() {
        if (dayOpen <= 0.0) {
            return 0.0;
        }
        return (price - dayOpen) / dayOpen * 100.0;
    }

    public double dayOpen() {
        return dayOpen;
    }

    /** Replaces the live price without recording history — used when loading from storage. */
    public void restore(double price, double previousPrice) {
        this.price = clamp(price);
        this.previousPrice = previousPrice > 0.0 ? clamp(previousPrice) : this.price;
    }

    /** Sets the price as a discrete market move, recording the old price as the previous one. */
    public void moveTo(double newPrice) {
        this.previousPrice = this.price;
        this.price = clamp(newPrice);
        history.add(this.price);
        while (history.size() > historyPoints) {
            history.remove(0);
        }
    }

    public void loadHistory(List<Double> points) {
        history.clear();
        int from = Math.max(0, points.size() - historyPoints);
        history.addAll(points.subList(from, points.size()));
    }

    public double clamp(double value) {
        if (!Double.isFinite(value)) {
            return minPrice;
        }
        return Math.clamp(value, minPrice, maxPrice);
    }
}
