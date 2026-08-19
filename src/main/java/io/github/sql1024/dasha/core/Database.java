package io.github.sql1024.dasha.core;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.stock.news.NewsEvent;
import io.github.sql1024.dasha.stock.portfolio.Holding;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite storage. Everything goes through one connection owned by a single writer thread, so
 * gameplay code never blocks on disk; the few reads (startup, reload, log lookups) submit a task
 * and wait for it.
 */
public final class Database {

    private final DashaEconomyPlugin plugin;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "DashaEconomy-DB");
                thread.setDaemon(true);
                return thread;
            });

    private Connection connection;
    private volatile boolean closed;

    public Database(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    public void open() throws SQLException {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new SQLException("無法建立資料夾 " + folder.getAbsolutePath());
        }
        File file = new File(folder, "dasha.db");

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);

        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + file.getAbsolutePath());
        connection = source.getConnection();

        createTables();
        prune();
    }

    private void createTables() throws SQLException {
        String[] ddl = {
            """
            CREATE TABLE IF NOT EXISTS players (
                uuid TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS holdings (
                uuid     TEXT    NOT NULL,
                symbol   TEXT    NOT NULL,
                shares   INTEGER NOT NULL,
                invested INTEGER NOT NULL,
                PRIMARY KEY (uuid, symbol)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS prices (
                symbol         TEXT PRIMARY KEY NOT NULL,
                price          REAL NOT NULL,
                previous_price REAL NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS price_history (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT    NOT NULL,
                ts     INTEGER NOT NULL,
                price  REAL    NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS transactions (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid       TEXT    NOT NULL,
                name       TEXT    NOT NULL,
                symbol     TEXT    NOT NULL,
                type       TEXT    NOT NULL,
                shares     INTEGER NOT NULL,
                unit_price REAL    NOT NULL,
                gold       INTEGER NOT NULL,
                ts         INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS news (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol        TEXT    NOT NULL,
                headline      TEXT    NOT NULL,
                published_dir INTEGER NOT NULL,
                actual_dir    INTEGER NOT NULL,
                bias          REAL    NOT NULL,
                updates_left  INTEGER NOT NULL,
                created_ts    INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS accounts (
                uuid    TEXT PRIMARY KEY NOT NULL,
                balance INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS ledger (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid          TEXT    NOT NULL,
                name          TEXT    NOT NULL,
                type          TEXT    NOT NULL,
                flow          TEXT    NOT NULL,
                amount        INTEGER NOT NULL,
                balance_after INTEGER NOT NULL,
                detail        TEXT    NOT NULL,
                ts            INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sell_quota (
                uuid  TEXT    NOT NULL,
                day   TEXT    NOT NULL,
                spent INTEGER NOT NULL,
                PRIMARY KEY (uuid, day)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS supply_pool (
                material TEXT PRIMARY KEY NOT NULL,
                pool     REAL    NOT NULL,
                updated  INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS news_audit (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                admin_uuid TEXT    NOT NULL,
                admin_name TEXT    NOT NULL,
                symbol     TEXT    NOT NULL,
                direction  INTEGER NOT NULL,
                strength   INTEGER NOT NULL,
                headline   TEXT    NOT NULL,
                ts         INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS auction_listings (
                id      TEXT PRIMARY KEY NOT NULL,
                seller  TEXT    NOT NULL,
                name    TEXT    NOT NULL,
                offer   TEXT    NOT NULL,
                price   INTEGER NOT NULL,
                created INTEGER NOT NULL,
                expires INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS auction_collect (
                id    INTEGER PRIMARY KEY AUTOINCREMENT,
                owner TEXT NOT NULL,
                item  TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_history_symbol ON price_history (symbol, id)",
            "CREATE INDEX IF NOT EXISTS idx_txn_uuid ON transactions (uuid, id)",
            "CREATE INDEX IF NOT EXISTS idx_ledger_ts ON ledger (ts)",
            "CREATE INDEX IF NOT EXISTS idx_ledger_uuid ON ledger (uuid, id)",
            "CREATE INDEX IF NOT EXISTS idx_collect_owner ON auction_collect (owner)",
        };
        try (Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                statement.executeUpdate(sql);
            }
        }
    }

    private void prune() {
        String sql = """
                DELETE FROM transactions
                WHERE id NOT IN (SELECT id FROM transactions ORDER BY id DESC LIMIT ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, plugin.tuning().txnLogLimit());
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "整理交易紀錄時發生錯誤", e);
        }
    }

    /** Waits for queued writes, then closes the connection. */
    public void close() {
        long shutdownWaitSecs = plugin.tuning().shutdownWaitSecs();
        closed = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(shutdownWaitSecs, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("資料庫寫入未在 " + shutdownWaitSecs + " 秒內完成，可能有資料未寫入。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "關閉資料庫時發生錯誤", e);
            }
        }
    }

    // ------------------------------------------------------------------ writes

    public void savePlayerName(UUID uuid, String name) {
        submit("記錄玩家名稱", () -> {
            String sql = """
                    INSERT INTO players (uuid, name) VALUES (?, ?)
                    ON CONFLICT (uuid) DO UPDATE SET name = excluded.name
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.executeUpdate();
            }
        });
    }

    public void saveHolding(UUID uuid, String symbol, Holding holding) {
        int shares = holding.shares();
        long invested = holding.invested();
        submit("儲存持股", () -> {
            String sql = """
                    INSERT INTO holdings (uuid, symbol, shares, invested) VALUES (?, ?, ?, ?)
                    ON CONFLICT (uuid, symbol) DO UPDATE SET
                        shares = excluded.shares, invested = excluded.invested
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, symbol);
                statement.setInt(3, shares);
                statement.setLong(4, invested);
                statement.executeUpdate();
            }
        });
    }

    public void deleteHolding(UUID uuid, String symbol) {
        submit("刪除持股", () -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM holdings WHERE uuid = ? AND symbol = ?")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, symbol);
                statement.executeUpdate();
            }
        });
    }

    public void savePrice(String symbol, double price, double previousPrice) {
        submit("儲存股價", () -> {
            String sql = """
                    INSERT INTO prices (symbol, price, previous_price) VALUES (?, ?, ?)
                    ON CONFLICT (symbol) DO UPDATE SET
                        price = excluded.price, previous_price = excluded.previous_price
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, symbol);
                statement.setDouble(2, price);
                statement.setDouble(3, previousPrice);
                statement.executeUpdate();
            }
        });
    }

    public void appendHistory(String symbol, long timestamp, double price, int keepPoints) {
        submit("寫入歷史股價", () -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO price_history (symbol, ts, price) VALUES (?, ?, ?)")) {
                insert.setString(1, symbol);
                insert.setLong(2, timestamp);
                insert.setDouble(3, price);
                insert.executeUpdate();
            }
            String sql = """
                    DELETE FROM price_history
                    WHERE symbol = ? AND id NOT IN (
                        SELECT id FROM price_history WHERE symbol = ? ORDER BY id DESC LIMIT ?
                    )
                    """;
            try (PreparedStatement delete = connection.prepareStatement(sql)) {
                delete.setString(1, symbol);
                delete.setString(2, symbol);
                delete.setInt(3, Math.max(2, keepPoints));
                delete.executeUpdate();
            }
        });
    }

    public void logTransaction(UUID uuid,
                               String name,
                               String symbol,
                               String type,
                               int shares,
                               double unitPrice,
                               long gold) {
        long now = System.currentTimeMillis();
        submit("寫入交易紀錄", () -> {
            String sql = """
                    INSERT INTO transactions (uuid, name, symbol, type, shares, unit_price, gold, ts)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setString(3, symbol);
                statement.setString(4, type);
                statement.setInt(5, shares);
                statement.setDouble(6, unitPrice);
                statement.setLong(7, gold);
                statement.setLong(8, now);
                statement.executeUpdate();
            }
        });
    }

    /** Replaces the stored set of running news with the current one. */
    public void saveActiveNews(List<NewsEvent> events) {
        submit("儲存新聞", () -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (Statement clear = connection.createStatement()) {
                    clear.executeUpdate("DELETE FROM news");
                }
                String sql = """
                        INSERT INTO news
                            (symbol, headline, published_dir, actual_dir, bias, updates_left, created_ts)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (NewsEvent event : events) {
                        statement.setString(1, event.symbol());
                        statement.setString(2, event.headline());
                        statement.setInt(3, event.publishedDirection());
                        statement.setInt(4, event.actualDirection());
                        statement.setDouble(5, event.biasPerUpdate());
                        statement.setInt(6, event.updatesLeft());
                        statement.setLong(7, event.createdAt());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    // ------------------------------------------------------------------ reads

    public Map<UUID, Map<String, Holding>> loadHoldings() {
        return read("讀取持股", Map.of(), () -> {
            Map<UUID, Map<String, Holding>> result = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT uuid, symbol, shares, invested FROM holdings WHERE shares > 0")) {
                while (rows.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rows.getString("uuid"));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    result.computeIfAbsent(uuid, key -> new LinkedHashMap<>())
                            .put(rows.getString("symbol"),
                                    new Holding(rows.getInt("shares"), rows.getLong("invested")));
                }
            }
            return result;
        });
    }

    public Map<String, double[]> loadPrices() {
        return read("讀取股價", Map.of(), () -> {
            Map<String, double[]> result = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT symbol, price, previous_price FROM prices")) {
                while (rows.next()) {
                    result.put(rows.getString("symbol"),
                            new double[] {rows.getDouble("price"), rows.getDouble("previous_price")});
                }
            }
            return result;
        });
    }

    public Map<String, List<Double>> loadHistory(int keepPoints) {
        return read("讀取歷史股價", Map.of(), () -> {
            Map<String, List<Double>> result = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT symbol, price FROM price_history ORDER BY id ASC")) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getString("symbol"), key -> new ArrayList<>())
                            .add(rows.getDouble("price"));
                }
            }
            for (List<Double> points : result.values()) {
                while (points.size() > Math.max(2, keepPoints)) {
                    points.remove(0);
                }
            }
            return result;
        });
    }

    public List<TxnRecord> recentTransactions(UUID uuid, int limit) {
        return read("讀取交易紀錄", List.of(), () -> {
            List<TxnRecord> result = new ArrayList<>();
            String sql = """
                    SELECT name, symbol, type, shares, unit_price, gold, ts FROM transactions
                    WHERE uuid = ? ORDER BY id DESC LIMIT ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, Math.clamp(limit, 1, plugin.tuning().queryLimit()));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new TxnRecord(
                                rows.getString("name"),
                                rows.getString("symbol"),
                                rows.getString("type"),
                                rows.getInt("shares"),
                                rows.getDouble("unit_price"),
                                rows.getLong("gold"),
                                rows.getLong("ts")));
                    }
                }
            }
            return result;
        });
    }

    public List<NewsEvent> loadActiveNews() {
        return read("讀取新聞", List.of(), () -> {
            List<NewsEvent> result = new ArrayList<>();
            String sql = """
                    SELECT symbol, headline, published_dir, actual_dir, bias, updates_left, created_ts
                    FROM news WHERE updates_left > 0 ORDER BY id ASC
                    """;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    result.add(new NewsEvent(
                            rows.getString("symbol"),
                            rows.getString("headline"),
                            rows.getInt("published_dir"),
                            rows.getInt("actual_dir"),
                            rows.getDouble("bias"),
                            rows.getInt("updates_left"),
                            rows.getLong("created_ts")));
                }
            }
            return result;
        });
    }

    public Map<UUID, String> loadPlayerNames() {
        return read("讀取玩家名稱", Map.of(), () -> {
            Map<UUID, String> result = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT uuid, name FROM players")) {
                while (rows.next()) {
                    try {
                        result.put(UUID.fromString(rows.getString("uuid")), rows.getString("name"));
                    } catch (IllegalArgumentException ignored) {
                        // Skip malformed rows rather than failing the whole load.
                    }
                }
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ 大沙幣 帳戶與帳本

    /** Writes one account balance. Called after the in-memory balance has already changed. */
    public void saveBalance(UUID uuid, long balance) {
        submit("儲存餘額", () -> {
            String sql = """
                    INSERT INTO accounts (uuid, balance) VALUES (?, ?)
                    ON CONFLICT (uuid) DO UPDATE SET balance = excluded.balance
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, balance);
                statement.executeUpdate();
            }
        });
    }

    /** Flushes every balance in one transaction; used on shutdown and on the periodic save. */
    public void saveAllBalances(Map<UUID, Long> balances) {
        Map<UUID, Long> copy = new HashMap<>(balances);
        submit("批次儲存餘額", () -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String sql = """
                        INSERT INTO accounts (uuid, balance) VALUES (?, ?)
                        ON CONFLICT (uuid) DO UPDATE SET balance = excluded.balance
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Map.Entry<UUID, Long> entry : copy.entrySet()) {
                        statement.setString(1, entry.getKey().toString());
                        statement.setLong(2, entry.getValue());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    public Map<UUID, Long> loadBalances() {
        return read("讀取餘額", Map.of(), () -> {
            Map<UUID, Long> result = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT uuid, balance FROM accounts")) {
                while (rows.next()) {
                    try {
                        result.put(UUID.fromString(rows.getString("uuid")), rows.getLong("balance"));
                    } catch (IllegalArgumentException ignored) {
                        // Skip malformed rows rather than failing the whole load.
                    }
                }
            }
            return result;
        });
    }

    /** Appends one line to the money ledger. This is what {@code /eco stats} reads back. */
    public void logLedger(UUID uuid, String name, TxnType type, long amount,
                          long balanceAfter, String detail) {
        long now = System.currentTimeMillis();
        submit("寫入帳本", () -> {
            String sql = """
                    INSERT INTO ledger (uuid, name, type, flow, amount, balance_after, detail, ts)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setString(3, type.name());
                statement.setString(4, type.flow().name());
                statement.setLong(5, amount);
                statement.setLong(6, balanceAfter);
                statement.setString(7, detail);
                statement.setLong(8, now);
                statement.executeUpdate();
            }
        });
    }

    /** Total 大沙幣 moved per transaction type since {@code since}, for the economy report. */
    public Map<TxnType, Long> ledgerTotals(long since) {
        return read("統計帳本", Map.of(), () -> {
            Map<TxnType, Long> result = new LinkedHashMap<>();
            String sql = "SELECT type, SUM(ABS(amount)) AS total FROM ledger WHERE ts >= ? GROUP BY type";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, since);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        try {
                            result.put(TxnType.valueOf(rows.getString("type")), rows.getLong("total"));
                        } catch (IllegalArgumentException ignored) {
                            // A type removed in a later version; leave it out of the report.
                        }
                    }
                }
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ 收購額度與供給池

    public long quotaSpent(UUID uuid, String day) {
        return read("讀取收購額度", 0L, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT spent FROM sell_quota WHERE uuid = ? AND day = ?")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, day);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong("spent") : 0L;
                }
            }
        });
    }

    public void saveQuota(UUID uuid, String day, long spent) {
        submit("儲存收購額度", () -> {
            String sql = """
                    INSERT INTO sell_quota (uuid, day, spent) VALUES (?, ?, ?)
                    ON CONFLICT (uuid, day) DO UPDATE SET spent = excluded.spent
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, day);
                statement.setLong(3, spent);
                statement.executeUpdate();
            }
        });
    }

    /** Drops quota rows for days already past, so the table cannot grow without bound. */
    public void pruneQuota(String today) {
        submit("整理收購額度", () -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM sell_quota WHERE day <> ?")) {
                statement.setString(1, today);
                statement.executeUpdate();
            }
        });
    }

    public Map<String, double[]> loadSupplyPools() {
        return read("讀取供給池", Map.of(), () -> {
            Map<String, double[]> result = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT material, pool, updated FROM supply_pool")) {
                while (rows.next()) {
                    result.put(rows.getString("material"),
                            new double[] {rows.getDouble("pool"), rows.getLong("updated")});
                }
            }
            return result;
        });
    }

    public void saveSupplyPool(String material, double pool, long updated) {
        submit("儲存供給池", () -> {
            String sql = """
                    INSERT INTO supply_pool (material, pool, updated) VALUES (?, ?, ?)
                    ON CONFLICT (material) DO UPDATE SET
                        pool = excluded.pool, updated = excluded.updated
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, material);
                statement.setDouble(2, pool);
                statement.setLong(3, updated);
                statement.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ 新聞稽核

    public void logNewsAudit(UUID adminUuid, String adminName, String symbol,
                             int direction, int strength, String headline) {
        long now = System.currentTimeMillis();
        submit("寫入新聞稽核", () -> {
            String sql = """
                    INSERT INTO news_audit
                        (admin_uuid, admin_name, symbol, direction, strength, headline, ts)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, adminUuid == null ? "CONSOLE" : adminUuid.toString());
                statement.setString(2, adminName);
                statement.setString(3, symbol);
                statement.setInt(4, direction);
                statement.setInt(5, strength);
                statement.setString(6, headline);
                statement.setLong(7, now);
                statement.executeUpdate();
            }
        });
    }

    /** Most recent manual news publications, newest first. Each row is {@code [name, symbol, dir, strength, ts]}. */
    public List<Object[]> recentNewsAudit(int limit) {
        return read("讀取新聞稽核", List.of(), () -> {
            List<Object[]> result = new ArrayList<>();
            String sql = """
                    SELECT admin_name, symbol, direction, strength, headline, ts
                    FROM news_audit ORDER BY id DESC LIMIT ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, Math.clamp(limit, 1, plugin.tuning().queryLimit()));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new Object[] {
                            rows.getString("admin_name"),
                            rows.getString("symbol"),
                            rows.getInt("direction"),
                            rows.getInt("strength"),
                            rows.getString("headline"),
                            rows.getLong("ts")});
                    }
                }
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ 拍賣行

    /** Replaces the stored listings and collect boxes with the current in-memory state. */
    public void saveAuction(List<Object[]> listings, List<Object[]> collect) {
        List<Object[]> listingCopy = new ArrayList<>(listings);
        List<Object[]> collectCopy = new ArrayList<>(collect);
        submit("儲存拍賣行", () -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (Statement clear = connection.createStatement()) {
                    clear.executeUpdate("DELETE FROM auction_listings");
                    clear.executeUpdate("DELETE FROM auction_collect");
                }
                String listingSql = """
                        INSERT INTO auction_listings (id, seller, name, offer, price, created, expires)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(listingSql)) {
                    for (Object[] row : listingCopy) {
                        statement.setString(1, (String) row[0]);
                        statement.setString(2, (String) row[1]);
                        statement.setString(3, (String) row[2]);
                        statement.setString(4, (String) row[3]);
                        statement.setLong(5, (Long) row[4]);
                        statement.setLong(6, (Long) row[5]);
                        statement.setLong(7, (Long) row[6]);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO auction_collect (owner, item) VALUES (?, ?)")) {
                    for (Object[] row : collectCopy) {
                        statement.setString(1, (String) row[0]);
                        statement.setString(2, (String) row[1]);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /** Stored listings as {@code [id, seller, name, offer, price, created, expires]}. */
    public List<Object[]> loadAuctionListings() {
        return read("讀取拍賣上架", List.of(), () -> {
            List<Object[]> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT id, seller, name, offer, price, created, expires FROM auction_listings")) {
                while (rows.next()) {
                    result.add(new Object[] {
                        rows.getString("id"),
                        rows.getString("seller"),
                        rows.getString("name"),
                        rows.getString("offer"),
                        rows.getLong("price"),
                        rows.getLong("created"),
                        rows.getLong("expires")});
                }
            }
            return result;
        });
    }

    /** Stored collect-box entries as {@code [owner, encodedItem]}. */
    public List<Object[]> loadAuctionCollect() {
        return read("讀取取貨箱", List.of(), () -> {
            List<Object[]> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT owner, item FROM auction_collect ORDER BY id ASC")) {
                while (rows.next()) {
                    result.add(new Object[] {rows.getString("owner"), rows.getString("item")});
                }
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ plumbing

    private interface SqlTask {
        void run() throws SQLException;
    }

    private void submit(String what, SqlTask task) {
        if (closed) {
            return;
        }
        worker.execute(() -> {
            try {
                task.run();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, what + "失敗", e);
            }
        });
    }

    private <T> T read(String what, T fallback, Callable<T> task) {
        try {
            return worker.submit(task).get(plugin.tuning().writeTimeoutSecs(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, what + "失敗", e);
            return fallback;
        }
    }
}
