package io.github.sql1024.dasha.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The server's notion of "today". Daily limits — the stock price band and the ore selling quota —
 * all roll over together at local midnight, so a player never has to work out which clock applies
 * to which limit.
 */
public final class TradingDay {

    /** Taipei time, matching where the server and its players actually are. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TradingDay() {
    }

    /** Identifier of the current day, e.g. {@code 2026-08-18}. */
    public static String current() {
        return LocalDate.now(ZONE).format(FORMAT);
    }
}
