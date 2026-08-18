package io.github.sql1024.dasha.stock.market;

import org.bukkit.configuration.file.FileConfiguration;

/** The {@code indicators} section of config.yml. */
public record IndicatorSettings(
        boolean enabled,
        int shortPeriod,
        int longPeriod,
        double trendThresholdPercent) {

    public static IndicatorSettings from(FileConfiguration config) {
        int shortPeriod = Math.clamp(config.getInt("indicators.short-period", 5), 2, 200);
        int longPeriod = Math.clamp(config.getInt("indicators.long-period", 20), 3, 400);
        if (longPeriod <= shortPeriod) {
            longPeriod = shortPeriod + 1;
        }
        return new IndicatorSettings(
                config.getBoolean("indicators.enabled", true),
                shortPeriod,
                longPeriod,
                Math.clamp(config.getDouble("indicators.trend-threshold-percent", 0.5), 0.0, 50.0));
    }
}
