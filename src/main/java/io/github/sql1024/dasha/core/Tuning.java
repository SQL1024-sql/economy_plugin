package io.github.sql1024.dasha.core;

import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Every tunable number that does not belong to one specific subsystem: how much a click buys, how
 * long the day is, how far back reports look, how much history the database keeps.
 *
 * <p>These used to be constants scattered through the code. They live here so an operator can
 * retune the server without a rebuild — the whole point being that config values are the ones you
 * are allowed to be wrong about, because {@code /eco reload} fixes them.
 *
 * <p>Screen layout — which inventory slot a button sits in — deliberately stays in code. Those are
 * geometry, not policy: moving them to config would let a typo produce an unclickable menu with no
 * error, and nobody tunes a server by shuffling slot numbers.
 *
 * @param timezone           zone that decides when "today" rolls over for daily limits
 * @param autosaveMinutes    how often balances and listings are flushed to disk
 * @param barWidth           number of cells in the ▉▉▉ progress bars
 * @param storeLeftClick     units bought by a left click in the server store
 * @param storeRightClick    units bought by a right click
 * @param storeShiftClick    units bought by a shift-left click
 * @param stockClickShares   shares traded by a plain click in the market menu
 * @param stockShiftShares   shares traded by a shift click
 * @param reportDefaultDays  window {@code /eco stats} covers with no argument
 * @param reportLongDays     window the admin panel's right click covers
 * @param reportMaxDays      furthest back a report may look
 * @param topDefault         entries {@code /eco top} shows with no argument
 * @param topMax             most entries {@code /eco top} will ever show
 * @param newsAuditEntries   rows shown by the news audit trail
 * @param guardSafetyFactor  how much dearer a store item must be than its scrap value
 * @param txnLogLimit        stock transactions kept in the database before old ones are pruned
 * @param queryLimit         hard ceiling on rows any single history query returns
 * @param writeTimeoutSecs   how long a database read may block before giving up
 * @param shutdownWaitSecs   how long shutdown waits for queued writes to land
 */
public record Tuning(
        String timezone,
        long autosaveMinutes,
        int barWidth,
        int storeLeftClick,
        int storeRightClick,
        int storeShiftClick,
        int stockClickShares,
        int stockShiftShares,
        int reportDefaultDays,
        int reportLongDays,
        int reportMaxDays,
        int topDefault,
        int topMax,
        int newsAuditEntries,
        double guardSafetyFactor,
        int txnLogLimit,
        int queryLimit,
        long writeTimeoutSecs,
        long shutdownWaitSecs) {

    public static Tuning from(FileConfiguration config, Logger logger) {
        String zone = config.getString("general.timezone", "Asia/Taipei");
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Taipei";
        }
        try {
            java.time.ZoneId.of(zone);
        } catch (RuntimeException e) {
            logger.warning("general.timezone『" + zone + "』不是有效的時區，改用 Asia/Taipei。");
            zone = "Asia/Taipei";
        }

        return new Tuning(
                zone,
                Math.max(1L, config.getLong("general.autosave-minutes", 1L)),
                Math.clamp(config.getInt("gui.bar-width", 10), 4, 32),
                Math.max(1, config.getInt("gui.store.left-click", 1)),
                Math.max(1, config.getInt("gui.store.right-click", 16)),
                Math.max(1, config.getInt("gui.store.shift-left-click", 64)),
                Math.max(1, config.getInt("gui.stock.click-shares", 1)),
                Math.max(1, config.getInt("gui.stock.shift-click-shares", 10)),
                Math.max(1, config.getInt("report.default-days", 7)),
                Math.max(1, config.getInt("report.long-days", 30)),
                Math.max(1, config.getInt("report.max-days", 365)),
                Math.max(1, config.getInt("report.top-default", 10)),
                Math.max(1, config.getInt("report.top-max", 50)),
                Math.max(1, config.getInt("report.news-audit-entries", 20)),
                Math.max(1.0, config.getDouble("guard.safety-factor", 1.5)),
                Math.max(100, config.getInt("storage.transaction-log-limit", 20_000)),
                Math.clamp(config.getInt("storage.max-query-entries", 100), 10, 10_000),
                Math.max(1L, config.getLong("storage.read-timeout-seconds", 20L)),
                Math.max(1L, config.getLong("storage.shutdown-wait-seconds", 15L)));
    }

    /** Autosave period expressed in server ticks. */
    public long autosaveTicks() {
        return autosaveMinutes * 60L * 20L;
    }
}
