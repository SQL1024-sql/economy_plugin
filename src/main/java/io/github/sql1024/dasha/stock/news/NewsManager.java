package io.github.sql1024.dasha.stock.news;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.stock.market.Stock;

/**
 * Publishes financial news and turns it into a temporary drift on the affected stock.
 *
 * <p>News is the only place in the plugin where the future leaks into the present, and it leaks on
 * purpose: a headline is right {@code accuracy-percent} of the time, and even when it is right the
 * random walk can still swamp it. Reading the news pays off across many trades, never on one.
 */
public final class NewsManager {

    private final DashaEconomyPlugin plugin;
    private final Random random = new Random();
    private final List<NewsEvent> active = new ArrayList<>();
    private final Deque<NewsEvent> history = new ArrayDeque<>();
    /** Symbol to the epoch millis its trading halt lifts. */
    private final Map<String, Long> haltUntil = new ConcurrentHashMap<>();
    /** {@code uuid/symbol} to the epoch millis that publisher may trade the stock again. */
    private final Map<String, Long> insiderUntil = new ConcurrentHashMap<>();

    public NewsManager(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public List<NewsEvent> active() {
        return Collections.unmodifiableList(active);
    }

    /** Most recently published first: the active items, then the ones that have run their course. */
    public List<NewsEvent> recent() {
        List<NewsEvent> out = new ArrayList<>(active);
        out.addAll(history);
        return out;
    }

    public List<NewsEvent> activeFor(String symbol) {
        List<NewsEvent> out = new ArrayList<>(1);
        for (NewsEvent event : active) {
            if (event.symbol().equals(symbol)) {
                out.add(event);
            }
        }
        return out;
    }

    public boolean hasNews(String symbol) {
        for (NewsEvent event : active) {
            if (event.symbol().equals(symbol)) {
                return true;
            }
        }
        return false;
    }

    /** Extra log-return applied to this stock on the current market update. */
    public double biasFor(String symbol) {
        double bias = 0.0;
        for (NewsEvent event : active) {
            if (event.symbol().equals(symbol)) {
                bias += event.biasPerUpdate() * event.actualDirection();
            }
        }
        return bias;
    }

    /** Called once per market update, after prices have moved. Expires spent news. */
    public void afterUpdate() {
        boolean changed = false;
        for (NewsEvent event : new ArrayList<>(active)) {
            event.countDown();
            if (event.expired()) {
                active.remove(event);
                remember(event);
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    /**
     * Rolls for a new headline. Called after prices move, so players get one window to react
     * before the news starts biasing the price.
     */
    public void maybePublish() {
        NewsSettings settings = plugin.newsSettings();
        if (!settings.enabled() || active.size() >= settings.maxActive()) {
            return;
        }
        if (random.nextDouble() * 100.0 >= settings.chancePercent()) {
            return;
        }

        List<Stock> candidates = new ArrayList<>();
        for (Stock stock : plugin.market().stocks()) {
            if (!hasNews(stock.symbol())) {
                candidates.add(stock);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        Stock stock = candidates.get(random.nextInt(candidates.size()));
        int published = random.nextBoolean() ? 1 : -1;
        boolean truthful = random.nextDouble() * 100.0 < settings.accuracyPercent();
        int actual = truthful ? published : -published;

        List<String> pool = published > 0 ? settings.bullishHeadlines() : settings.bearishHeadlines();
        String headline = pool.get(random.nextInt(pool.size()))
                .replace("{name}", stock.displayName())
                .replace("{symbol}", stock.symbol());

        double strength = 0.6 + random.nextDouble() * 0.8;
        double bias = settings.impactMultiplier() * stock.volatility() * strength;

        NewsEvent event = new NewsEvent(stock.symbol(), headline, published, actual, bias,
                settings.durationUpdates(), System.currentTimeMillis());
        publishWithHalt(event, stock);
    }

    /**
     * Publishes news the way the market rules require: the stock halts first, the price starts
     * moving while nobody can trade it, and only then is the headline revealed.
     *
     * <p>Without the halt the whole market is free money — a headline that lands before the price
     * moves is a guaranteed win for whoever clicks fastest. Halting first means that by the time
     * trading reopens the move has already begun, so reading the news pays over many trades
     * instead of paying instantly and without risk.
     */
    private void publishWithHalt(NewsEvent event, Stock stock) {
        active.add(event);
        persist();

        int haltSeconds = plugin.newsSettings().haltSeconds();
        if (haltSeconds <= 0) {
            broadcast(event, stock);
            return;
        }

        haltUntil.put(event.symbol(), System.currentTimeMillis() + haltSeconds * 1000L);
        if (plugin.newsSettings().broadcast()) {
            plugin.getServer().broadcast(plugin.lang().msg("news-pending",
                    "symbol", stock.symbol(),
                    "name", stock.displayName(),
                    "seconds", haltSeconds));
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            haltUntil.remove(event.symbol());
            broadcast(event, stock);
        }, haltSeconds * 20L);
    }

    /**
     * Publishes a headline chosen by an administrator. The strength is 1–5, scaled onto the same
     * bias the automatic roll uses, and the whole thing is written to the audit log before it
     * reaches anybody's screen.
     *
     * @return {@code false} when the stock already has running news
     */
    public boolean publishManual(UUID adminUuid, String adminName, Stock stock,
                                 int direction, int strength, String headline) {
        if (hasNews(stock.symbol())) {
            return false;
        }
        NewsSettings settings = plugin.newsSettings();
        int published = direction >= 0 ? 1 : -1;
        int clamped = Math.clamp(strength, 1, 5);

        // Manual news is always truthful: an admin publishing a lie the market cannot detect is
        // indistinguishable from an admin quietly handing coins to whoever they told first.
        double bias = settings.impactMultiplier() * stock.volatility() * (clamped / 3.0);

        String text = headline == null || headline.isBlank()
                ? (published > 0 ? settings.bullishHeadlines() : settings.bearishHeadlines())
                        .get(random.nextInt((published > 0
                                ? settings.bullishHeadlines()
                                : settings.bearishHeadlines()).size()))
                        .replace("{name}", stock.displayName())
                        .replace("{symbol}", stock.symbol())
                : headline;

        plugin.database().logNewsAudit(adminUuid, adminName, stock.symbol(), published, clamped, text);
        if (adminUuid != null && settings.insiderLockMinutes() > 0) {
            insiderUntil.put(insiderKey(adminUuid, stock.symbol()),
                    System.currentTimeMillis() + settings.insiderLockMinutes() * 60_000L);
        }

        NewsEvent event = new NewsEvent(stock.symbol(), text, published, published, bias,
                settings.durationUpdates(), System.currentTimeMillis());
        publishWithHalt(event, stock);
        return true;
    }

    // ------------------------------------------------------------------ 停牌與內線鎖

    /** Whether this stock is frozen because news is about to break on it. */
    public boolean halted(String symbol) {
        Long until = haltUntil.get(symbol);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            haltUntil.remove(symbol);
            return false;
        }
        return true;
    }

    public long haltSecondsLeft(String symbol) {
        Long until = haltUntil.get(symbol);
        if (until == null) {
            return 0L;
        }
        return Math.max(0L, (until - System.currentTimeMillis() + 999L) / 1000L);
    }

    /**
     * Whether this player published the news on this stock recently. An administrator who can
     * decide the next headline must not be able to take a position on it first.
     */
    public boolean insiderLocked(UUID uuid, String symbol) {
        Long until = insiderUntil.get(insiderKey(uuid, symbol));
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            insiderUntil.remove(insiderKey(uuid, symbol));
            return false;
        }
        return true;
    }

    public long insiderMinutesLeft(UUID uuid, String symbol) {
        Long until = insiderUntil.get(insiderKey(uuid, symbol));
        if (until == null) {
            return 0L;
        }
        return Math.max(0L, (until - System.currentTimeMillis() + 59_999L) / 60_000L);
    }

    private static String insiderKey(UUID uuid, String symbol) {
        return uuid + "/" + symbol;
    }

    private void broadcast(NewsEvent event, Stock stock) {
        if (!plugin.newsSettings().broadcast()) {
            return;
        }
        String key = event.bullish() ? "news-bullish" : "news-bearish";
        plugin.getServer().broadcast(plugin.lang().msg(key,
                "symbol", stock.symbol(),
                "name", stock.displayName(),
                "headline", event.headline()));
    }

    private void remember(NewsEvent event) {
        history.addFirst(event);
        while (history.size() > plugin.newsSettings().keepHistory()) {
            history.removeLast();
        }
    }

    /** Restores news that was still running when the server stopped. */
    public void load(Collection<NewsEvent> stored) {
        active.clear();
        for (NewsEvent event : stored) {
            if (!event.expired() && plugin.market().has(event.symbol())) {
                active.add(event);
            }
        }
    }

    /** Drops news about stocks that no longer exist, e.g. after a config reload. */
    public void pruneUnknownStocks() {
        if (active.removeIf(event -> !plugin.market().has(event.symbol()))) {
            persist();
        }
    }

    private void persist() {
        plugin.database().saveActiveNews(List.copyOf(active));
    }
}
