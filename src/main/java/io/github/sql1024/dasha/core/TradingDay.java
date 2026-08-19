package io.github.sql1024.dasha.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The server's notion of "today". Daily limits — the stock price band and the ore selling quota —
 * all roll over together at local midnight, so a player never has to work out which clock applies
 * to which limit.
 *
 * <p>The zone comes from {@code general.timezone} and is process-wide rather than passed around:
 * a server has exactly one idea of when the day turns over, and threading it through every caller
 * would only create ways for two of them to disagree.
 */
public final class TradingDay {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static volatile ZoneId zone = ZoneId.of("Asia/Taipei");

    private TradingDay() {
    }

    /** Applies the configured zone. Called on load and on every reload. */
    public static void configure(String zoneId) {
        try {
            zone = ZoneId.of(zoneId);
        } catch (RuntimeException e) {
            zone = ZoneId.of("Asia/Taipei");
        }
    }

    public static ZoneId zone() {
        return zone;
    }

    /** Identifier of the current day, e.g. {@code 2026-08-18}. */
    public static String current() {
        return LocalDate.now(zone).format(FORMAT);
    }
}
