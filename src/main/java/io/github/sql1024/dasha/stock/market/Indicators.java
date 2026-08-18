package io.github.sql1024.dasha.stock.market;

import io.github.sql1024.dasha.core.Fmt;
import java.util.List;


/**
 * Moving averages computed from the stock's own price history. These carry no information about
 * the future — they only make the shape of the past easier to read at a glance.
 *
 * @param available false when there is not enough history yet
 */
public record Indicators(
        boolean available,
        double shortMa,
        double longMa,
        double spreadPercent,
        double momentumPercent,
        Trend trend) {

    public enum Trend {
        BULLISH, BEARISH, FLAT
    }

    public static final Indicators UNAVAILABLE =
            new Indicators(false, 0.0, 0.0, 0.0, 0.0, Trend.FLAT);

    public static Indicators of(Stock stock, IndicatorSettings settings) {
        List<Double> history = stock.history();
        if (!settings.enabled() || history.size() < settings.longPeriod()) {
            return UNAVAILABLE;
        }

        double shortMa = average(history, settings.shortPeriod());
        double longMa = average(history, settings.longPeriod());
        if (longMa <= 0.0) {
            return UNAVAILABLE;
        }

        double spread = (shortMa - longMa) / longMa * 100.0;
        Trend trend = spread > settings.trendThresholdPercent() ? Trend.BULLISH
                : spread < -settings.trendThresholdPercent() ? Trend.BEARISH
                : Trend.FLAT;

        double windowStart = history.get(history.size() - settings.longPeriod());
        double momentum = windowStart > 0.0
                ? (stock.price() - windowStart) / windowStart * 100.0
                : 0.0;

        return new Indicators(true, shortMa, longMa, spread, momentum, trend);
    }

    /** MiniMessage label for the trend, e.g. {@code <green>多頭排列}. */
    public String trendTag() {
        if (!available) {
            return "<dark_gray>資料不足";
        }
        return switch (trend) {
            case BULLISH -> "<green>多頭排列";
            case BEARISH -> "<red>空頭排列";
            case FLAT -> "<gray>區間盤整";
        };
    }

    /** MiniMessage line showing both moving averages, or a placeholder when unavailable. */
    public String maTag(IndicatorSettings settings) {
        if (!available) {
            return "<dark_gray>均線資料累積中";
        }
        return "<gray>MA" + settings.shortPeriod() + " <white>" + Fmt.price(shortMa)
                + "</white> <dark_gray>/</dark_gray> <gray>MA" + settings.longPeriod()
                + " <white>" + Fmt.price(longMa) + "</white> <dark_gray>("
                + Fmt.signedPercent(spreadPercent) + "%)";
    }

    private static double average(List<Double> history, int period) {
        int from = history.size() - period;
        double sum = 0.0;
        for (int i = from; i < history.size(); i++) {
            sum += history.get(i);
        }
        return sum / period;
    }
}
