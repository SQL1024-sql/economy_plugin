package io.github.sql1024.dasha;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import io.github.sql1024.dasha.auction.AhCommand;
import io.github.sql1024.dasha.auction.AuctionManager;
import io.github.sql1024.dasha.auction.AuctionSettings;
import io.github.sql1024.dasha.auction.GuiListener;
import io.github.sql1024.dasha.auction.gui.SellGui;
import io.github.sql1024.dasha.core.ConfigWriter;
import io.github.sql1024.dasha.core.Currency;
import io.github.sql1024.dasha.core.Database;
import io.github.sql1024.dasha.core.EcoCommand;
import io.github.sql1024.dasha.core.EconomyService;
import io.github.sql1024.dasha.core.Lang;
import io.github.sql1024.dasha.core.PayCommand;
import io.github.sql1024.dasha.core.PayService;
import io.github.sql1024.dasha.core.PaySettings;
import io.github.sql1024.dasha.core.TradingDay;
import io.github.sql1024.dasha.core.Tuning;
import io.github.sql1024.dasha.guard.RecipeGuard;
import io.github.sql1024.dasha.sell.OreSellService;
import io.github.sql1024.dasha.sell.SellCommand;
import io.github.sql1024.dasha.sell.SellSettings;
import io.github.sql1024.dasha.stats.EcoStats;
import io.github.sql1024.dasha.stock.command.StockCommand;
import io.github.sql1024.dasha.stock.gui.MenuListener;
import io.github.sql1024.dasha.stock.gui.MenuManager;
import io.github.sql1024.dasha.stock.listener.PlayerListener;
import io.github.sql1024.dasha.stock.market.IndicatorSettings;
import io.github.sql1024.dasha.stock.market.MarketManager;
import io.github.sql1024.dasha.stock.market.MarketSettings;
import io.github.sql1024.dasha.stock.market.Stock;
import io.github.sql1024.dasha.stock.news.NewsManager;
import io.github.sql1024.dasha.stock.news.NewsSettings;
import io.github.sql1024.dasha.stock.portfolio.PortfolioManager;
import io.github.sql1024.dasha.stock.quote.QuoteService;
import io.github.sql1024.dasha.stock.trade.TradeService;
import io.github.sql1024.dasha.store.StoreCommand;
import io.github.sql1024.dasha.store.StoreService;
import io.github.sql1024.dasha.store.StoreSettings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 大沙幣經濟系統 — one currency, four ways to touch it.
 *
 * <p>Everything lives in a single plugin on purpose. The stock market, the auction house, the
 * server store and ore selling all mutate the same balance through {@link EconomyService}, so a
 * purchase in one cannot race a purchase in another, the fee sinks land in one ledger, and
 * {@code /eco stats} can state the money supply as a fact rather than a guess.
 *
 * <p>The whole economy is closed: ore selling is the only faucet, the stock market's market maker
 * is the only other place coins can appear, and the store, the fees and the cuts are where they
 * go to die.
 */
public final class DashaEconomyPlugin extends JavaPlugin {

    private final Lang lang = new Lang();
    private final Map<UUID, String> names = new HashMap<>();

    // ---- 設定 ----
    private Currency currency;
    private Tuning tuning;
    private MarketSettings settings;
    private NewsSettings newsSettings;
    private IndicatorSettings indicatorSettings;
    private AuctionSettings auctionSettings;
    private PaySettings paySettings;
    private SellSettings sellSettings;
    private StoreSettings storeSettings;

    private FileConfiguration stocksConfig;
    private FileConfiguration sellConfig;
    private FileConfiguration storeConfig;
    private FileConfiguration messagesConfig;

    // ---- 服務 ----
    private Database database;
    private EconomyService economy;
    private PayService pay;
    private MarketManager market;
    private QuoteService quotes;
    private NewsManager news;
    private PortfolioManager portfolios;
    private TradeService trades;
    private MenuManager menus;
    private AuctionManager auctions;
    private OreSellService oreSell;
    private StoreService store;
    private RecipeGuard guard;
    private EcoStats stats;

    private BukkitTask tickTask;
    private BukkitTask expireTask;
    private BukkitTask saveTask;
    private BukkitTask quoteTask;

    /** 玩家的上架流程狀態，去輸入價格再回來時要保住已選好的物品。 */
    private final Map<UUID, SellGui> sellSessions = new ConcurrentHashMap<>();
    /** 等待玩家在聊天輸入文字的處理器。 */
    private final Map<UUID, Consumer<String>> chatPrompts = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("stocks.yml");
        saveResourceIfMissing("sell.yml");
        saveResourceIfMissing("store.yml");
        saveResourceIfMissing("messages.yml");
        io.github.sql1024.dasha.auction.Lang.load(this);

        readSettings();

        database = new Database(this);
        try {
            database.open();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "無法開啟資料庫，插件停用。", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = new EconomyService(this);
        pay = new PayService(this);
        market = new MarketManager(this);
        quotes = new QuoteService(this);
        news = new NewsManager(this);
        portfolios = new PortfolioManager(this);
        trades = new TradeService(this);
        menus = new MenuManager(this);
        auctions = new AuctionManager(this);
        oreSell = new OreSellService(this);
        store = new StoreService(this);
        guard = new RecipeGuard(this);
        stats = new EcoStats(this);

        economy.load();
        market.loadStocks(stocksConfig, settings);
        restoreMarketState();
        news.load(database.loadActiveNews());
        portfolios.loadAll(database.loadHoldings());
        names.putAll(database.loadPlayerNames());
        auctions.load();
        oreSell.load();
        pay.load();

        register("stock", new StockCommand(this));
        register("ah", new AhCommand(this));
        register("store", new StoreCommand(this));
        register("sell", new SellCommand(this));
        register("eco", new EcoCommand(this));
        register("pay", new PayCommand(this));

        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        startTicking();
        startQuoteFeed();
        restartExpireTask();
        long autosave = tuning.autosaveTicks();
        saveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            auctions.saveIfDirty();
            economy.flush();
        }, autosave, autosave);

        // Both of these need every other plugin to have finished loading, so they wait a tick.
        getServer().getScheduler().runTask(this, () -> {
            guard.run();
            warnAboutCommandConflicts();
        });

        getLogger().info("大沙幣經濟系統已啟動：" + market.size() + " 檔股票、"
                + sellSettings.items().size() + " 種收購礦物、"
                + storeSettings.allEntries().size() + " 項商店商品。");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : new BukkitTask[] {tickTask, expireTask, saveTask, quoteTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        tickTask = null;
        expireTask = null;
        saveTask = null;
        quoteTask = null;

        sellSessions.clear();
        chatPrompts.clear();
        getServer().getOnlinePlayers().forEach(Player::closeInventory);

        if (market != null && database != null) {
            for (Stock stock : market.stocks()) {
                database.savePrice(stock.symbol(), stock.price(), stock.previousPrice(),
                        market.regimeKey(stock, settings));
            }
        }
        if (auctions != null) {
            auctions.save();
        }
        if (economy != null) {
            economy.flush();
        }
        if (database != null) {
            database.close();
        }
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("plugin.yml 沒有註冊 /" + name + " 指令。");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /**
     * Warns when another plugin has taken one of our command labels.
     *
     * <p>Bukkit hands a contested label to whichever plugin registered it first and silently sends
     * the loser's command to the {@code plugin:command} form. From in-game that looks exactly like
     * a broken permission — {@code /eco} answers, but with somebody else's help text, and even an
     * operator appears unable to use it. {@code /eco}, {@code /money}, {@code /bal}, {@code /sell}
     * and {@code /shop} all collide with EssentialsX, which most servers run, so this is the
     * likeliest way the plugin looks broken while working perfectly.
     *
     * <p>Printing who won the label turns half an hour of confusion into one console line.
     */
    private void warnAboutCommandConflicts() {
        List<String> problems = new ArrayList<>();
        for (String name : List.of("eco", "stock", "ah", "store", "sell", "pay")) {
            PluginCommand mine = getCommand(name);
            if (mine == null) {
                continue;
            }

            List<String> labels = new ArrayList<>();
            labels.add(name);
            labels.addAll(mine.getAliases());

            List<String> stolen = new ArrayList<>();
            List<String> survivors = new ArrayList<>();
            String thief = null;
            for (String label : labels) {
                PluginCommand owner = getServer().getPluginCommand(label);
                if (owner != null && owner != mine) {
                    stolen.add("/" + label);
                    thief = owner.getPlugin().getName();
                } else if (owner == mine) {
                    survivors.add("/" + label);
                }
            }
            if (stolen.isEmpty()) {
                continue;
            }
            problems.add(String.join("、", stolen) + " 被 " + thief + " 佔用"
                    + (survivors.isEmpty()
                            ? "，沒有可用的替代名稱，請改用 /" + getName().toLowerCase(Locale.ROOT) + ":" + name
                            : "，請改打 " + survivors.get(0)));
        }

        if (problems.isEmpty()) {
            return;
        }
        getLogger().warning("========================================================");
        getLogger().warning("⚠ 有指令名稱被其他插件搶走了：");
        for (String line : problems) {
            getLogger().warning("  • " + line);
        }
        getLogger().warning("被搶走的指令打下去會跑到對方插件，看起來就像「沒權限」或「指令壞了」，");
        getLogger().warning("連 OP 也一樣 —— 那不是權限問題，是名字撞到了。");
        getLogger().warning("想拿回原本的名字，就在 plugin.yml 或伺服器的 commands.yml 調整別名。");
        getLogger().warning("========================================================");
    }

    /** Writes a bundled config file out on first run, without clobbering an edited one. */
    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    private FileConfiguration loadConfigFile(String name) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), name));
    }

    /** Re-reads every config file and re-applies it to the live subsystems. */
    public void reloadEverything() {
        reloadConfig();
        io.github.sql1024.dasha.auction.Lang.load(this);
        readSettings();
        market.loadStocks(stocksConfig, settings);
        restoreMarketState();
        news.pruneUnknownStocks();
        quotes.pruneUnknown();
        startTicking();
        startQuoteFeed();
        restartExpireTask();
        menus.refreshOpenMenus();
        guard.run();
    }

    private void readSettings() {
        stocksConfig = loadConfigFile("stocks.yml");
        sellConfig = loadConfigFile("sell.yml");
        storeConfig = loadConfigFile("store.yml");
        messagesConfig = loadConfigFile("messages.yml");

        tuning = Tuning.from(getConfig(), getLogger());
        TradingDay.configure(tuning.timezone());
        currency = Currency.from(getConfig());
        settings = MarketSettings.from(stocksConfig);
        newsSettings = NewsSettings.from(stocksConfig);
        indicatorSettings = IndicatorSettings.from(stocksConfig);
        auctionSettings = AuctionSettings.from(getConfig());
        paySettings = PaySettings.from(getConfig());
        sellSettings = SellSettings.from(sellConfig, getLogger());
        storeSettings = StoreSettings.from(storeConfig, getLogger());

        lang.load(messagesConfig);
        lang.currencyName(currency.displayName());
    }

    /**
     * Applies the stored price and history to any stock that does not have a live price yet.
     *
     * <p>Stored points are only replayed when they were produced by the pricing régime that is
     * running now. A server that switched {@code price-source}, repointed a ticker or changed a
     * currency rate has a database full of numbers on a different scale; splicing those onto the
     * current price makes the chart show a crash that never happened and poisons the high/low
     * range and the moving averages with it. Those rows are dropped instead.
     */
    private void restoreMarketState() {
        Map<String, Database.StoredPrice> storedPrices = database.loadPrices();
        Map<String, List<Double>> storedHistory = database.loadHistory(settings.historyPoints());
        int reset = 0;

        for (Stock stock : market.stocks()) {
            Database.StoredPrice stored = storedPrices.get(stock.symbol());
            String regime = market.regimeKey(stock, settings);

            if (stored != null && !regime.equals(stored.source())) {
                // Different world. Forget everything we recorded about this stock.
                database.clearHistory(stock.symbol());
                stock.loadHistory(List.of());
                reset++;
                continue;
            }

            if (stored != null && stored.price() > 0.0) {
                stock.restore(stored.price(), stored.previousPrice());
            }
            List<Double> history = storedHistory.get(stock.symbol());
            if (history != null && !history.isEmpty() && stock.history().isEmpty()) {
                stock.loadHistory(history);
            }
        }

        if (reset > 0) {
            getLogger().info("定價方式變了，" + reset + " 檔股票的舊走勢圖已清空 —— "
                    + "那些價格跟現在不是同一個尺度，留著會畫出一根不存在的崩盤。");
        }
    }

    /**
     * Keeps the live price feed refreshing while a real price source is selected.
     *
     * <p>Runs on its own timer rather than inside the market tick: fetching sixty tickers takes
     * seconds, and the market must be free to advance on schedule regardless of how slow the
     * provider is being. The first pass fires almost immediately so the board is not showing
     * config placeholder prices when the first player logs in.
     */
    public void startQuoteFeed() {
        if (quoteTask != null) {
            quoteTask.cancel();
            quoteTask = null;
        }
        if (!settings.priceSource().needsFeed()) {
            return;
        }
        quotes.rebuildProvider();

        long period = Math.max(20L, settings.quoteIntervalTicks());
        quoteTask = getServer().getScheduler().runTaskTimer(this,
                () -> quotes.refreshAsync(() -> menus.refreshOpenMenus()), 40L, period);

        getLogger().info("即時股價已啟用（來源：" + quotes.provider().name()
                + "，每 " + settings.quoteIntervalSeconds() + " 秒更新一次）。");

        // With real prices a headline has nothing to push, so the whole news mechanic — the
        // automatic roll, the trading halt and the insider lock — stays switched off rather
        // than running as visible noise.
        if (!settings.priceSource().newsMovesPrice()) {
            getLogger().info("價格來源是 " + settings.priceSource()
                    + "，財經新聞已停用（不自動發布、不停牌、不鎖內線）。");
            getLogger().info("想讓新聞真的會推動股價，把 stocks.yml 的 price-source 改成 hybrid。");
        }
    }

    private void startTicking() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        long period = Math.max(20L, settings.updateIntervalTicks());
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                market.tick();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "更新股價時發生錯誤", e);
            }
        }, period, period);
    }

    public void restartExpireTask() {
        if (expireTask != null) {
            expireTask.cancel();
        }
        long ticks = auctionSettings.expireCheckMinutes() * 60L * 20L;
        expireTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (auctions.purgeExpired() > 0) {
                auctions.save();
            }
        }, ticks, ticks);
    }

    // ------------------------------------------------------------------ accessors

    public Currency currency() {
        return currency;
    }

    public Tuning tuning() {
        return tuning;
    }

    /**
     * Turns automatic financial news on or off and writes the choice to stocks.yml, so a toggle
     * flipped in the admin panel survives a restart.
     *
     * <p>This only gates the automatic roll. An administrator can still publish a headline by hand
     * with {@code /stock news} or the admin panel — which is the point: an operator running a
     * scripted event wants the market quiet except for the news they choose.
     *
     * @return whether the setting was written
     */
    public boolean setNewsEnabled(boolean enabled) {
        File file = new File(getDataFolder(), "stocks.yml");
        if (!ConfigWriter.setBoolean(file, "news", "enabled", enabled, getLogger())) {
            return false;
        }
        readSettings();
        getLogger().info("自動財經新聞已" + (enabled ? "開啟" : "關閉") + "。");
        return true;
    }

    public MarketSettings settings() {
        return settings;
    }

    public NewsSettings newsSettings() {
        return newsSettings;
    }

    public IndicatorSettings indicators() {
        return indicatorSettings;
    }

    public PaySettings paySettings() {
        return paySettings;
    }

    public AuctionSettings auctionSettings() {
        return auctionSettings;
    }

    public SellSettings sellSettings() {
        return sellSettings;
    }

    public StoreSettings storeSettings() {
        return storeSettings;
    }

    public Lang lang() {
        return lang;
    }

    public Database database() {
        return database;
    }

    public EconomyService economy() {
        return economy;
    }

    public MarketManager market() {
        return market;
    }

    public QuoteService quotes() {
        return quotes;
    }

    public NewsManager news() {
        return news;
    }

    public PortfolioManager portfolios() {
        return portfolios;
    }

    public TradeService trades() {
        return trades;
    }

    public MenuManager menus() {
        return menus;
    }

    public AuctionManager auctions() {
        return auctions;
    }

    public PayService pay() {
        return pay;
    }

    public OreSellService oreSell() {
        return oreSell;
    }

    public StoreService store() {
        return store;
    }

    public RecipeGuard guard() {
        return guard;
    }

    public EcoStats stats() {
        return stats;
    }

    // ------------------------------------------------------------------ 上架流程

    public SellGui sellSession(Player player) {
        return sellSessions.computeIfAbsent(player.getUniqueId(), key -> new SellGui(this, player));
    }

    public void endSellSession(Player player) {
        sellSessions.remove(player.getUniqueId());
    }

    public void handleQuit(Player player) {
        chatPrompts.remove(player.getUniqueId());
        sellSessions.remove(player.getUniqueId());
        pay.handleQuit(player.getUniqueId());
    }

    // ------------------------------------------------------------------ 聊天輸入

    public void promptChat(Player player, Consumer<String> handler) {
        chatPrompts.put(player.getUniqueId(), handler);
    }

    /** 聊天事件用；有在等輸入就吃掉這則訊息並回傳 true。 */
    public boolean consumeChat(Player player, String message) {
        Consumer<String> handler = chatPrompts.remove(player.getUniqueId());
        if (handler == null) {
            return false;
        }
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                handler.accept(message);
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ 音效

    public void click(Player player) {
        playConfigured(player, "sound.click", 1.0f);
    }

    public void success(Player player) {
        playConfigured(player, "sound.success", 1.2f);
    }

    public void fail(Player player) {
        playConfigured(player, "sound.fail", 1.0f);
    }

    private void playConfigured(Player player, String path, float pitch) {
        String key = getConfig().getString(path, "");
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, 0.7f, pitch));
        } catch (RuntimeException ex) {
            getLogger().warning("config 裡的音效名稱有問題：" + path + " = " + key);
        }
    }

    // ------------------------------------------------------------------ name cache

    public void rememberName(UUID uuid, String name) {
        String previous = names.put(uuid, name);
        if (!name.equals(previous)) {
            database.savePlayerName(uuid, name);
        }
        economy.ensureAccount(uuid);
    }

    public String playerName(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    /** Finds a player by name among online players first, then the stored names. */
    public UUID lookupPlayer(String name) {
        var online = getServer().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
