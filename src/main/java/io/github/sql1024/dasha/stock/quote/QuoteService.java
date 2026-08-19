package io.github.sql1024.dasha.stock.quote;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.stock.market.MarketSettings;
import io.github.sql1024.dasha.stock.market.Stock;

/**
 * Keeps a cache of live prices fresh in the background.
 *
 * <p>Every fetch happens off the server thread — a market with sixty tickers is sixty HTTP calls,
 * and doing that on the main thread would freeze the server for seconds at a time. The cache is
 * the only thing gameplay code touches, so a slow or dead provider costs latency in the price
 * feed and nothing else.
 *
 * <p>A failed symbol keeps its previous reading rather than dropping to zero. A price of zero
 * would let players buy a real company for nothing, so the failure mode has to be "stale", never
 * "free".
 */
public final class QuoteService {

    private final DashaEconomyPlugin plugin;
    private final Map<String, Quote> cache = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    private volatile QuoteProvider provider;
    private volatile long lastRunAt;
    private volatile int lastOk;
    private volatile int lastFailed;

    public QuoteService(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
        rebuildProvider();
    }

    /** Re-creates the provider after a config reload, so timeouts take effect. */
    public void rebuildProvider() {
        MarketSettings settings = plugin.settings();
        this.provider = new YahooQuoteProvider(Duration.ofSeconds(settings.quoteTimeoutSeconds()));
    }

    public QuoteProvider provider() {
        return provider;
    }

    public Quote quote(String realSymbol) {
        return cache.get(realSymbol);
    }

    public boolean hasQuote(String realSymbol) {
        return cache.containsKey(realSymbol);
    }

    public long lastRunAt() {
        return lastRunAt;
    }

    public int lastOk() {
        return lastOk;
    }

    public int lastFailed() {
        return lastFailed;
    }

    public Map<String, String> failures() {
        return Map.copyOf(failures);
    }

    /** Symbols currently in a market that is open for trading. */
    public boolean marketOpen(String realSymbol) {
        Quote quote = cache.get(realSymbol);
        return quote != null && quote.open();
    }

    /**
     * Starts one refresh pass over every real symbol in the market.
     *
     * <p>Does nothing if a pass is already running: on a slow network a scheduled refresh can
     * overlap the previous one, and stacking them would multiply the request rate until the
     * provider starts refusing traffic.
     */
    public void refreshAsync(Runnable afterOnMainThread) {
        if (!fetching.compareAndSet(false, true)) {
            return;
        }
        List<String> symbols = new ArrayList<>();
        for (Stock stock : plugin.market().stocks()) {
            if (stock.realSymbol() != null && !stock.realSymbol().isBlank()) {
                symbols.add(stock.realSymbol());
            }
        }
        if (symbols.isEmpty()) {
            fetching.set(false);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                runPass(symbols);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "抓取股價時發生非預期錯誤", e);
            } finally {
                fetching.set(false);
                if (afterOnMainThread != null) {
                    plugin.getServer().getScheduler().runTask(plugin, afterOnMainThread);
                }
            }
        });
    }

    private void runPass(Collection<String> symbols) {
        long stagger = plugin.settings().quoteStaggerMillis();
        int ok = 0;
        int failed = 0;

        List<String> failedSymbols = new ArrayList<>();
        for (String symbol : symbols) {
            String error = attempt(symbol);
            if (error == null) {
                failures.remove(symbol);
                ok++;
            } else {
                failed++;
                failedSymbols.add(symbol);
                failures.put(symbol, error);
            }
            if (stagger > 0L) {
                try {
                    Thread.sleep(stagger);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        lastRunAt = System.currentTimeMillis();
        lastOk = ok;
        lastFailed = failed;

        if (failed > 0) {
            plugin.getLogger().warning("股價更新：成功 " + ok + " 檔、失敗 " + failed
                    + " 檔（沿用上一次的價格）：" + String.join("、", failedSymbols));
        }
    }

    /**
     * Fetches one symbol, retrying once on failure.
     *
     * <p>A free public endpoint drops the occasional request under a burst of sixty-plus calls.
     * One quiet retry turns most of those into successes instead of a scary warning line and a
     * stale price nobody needed to have.
     *
     * @return {@code null} on success, otherwise the reason it failed
     */
    private String attempt(String symbol) {
        String firstError = null;
        for (int tries = 0; tries < 2; tries++) {
            try {
                cache.put(symbol, provider.fetch(symbol));
                return null;
            } catch (Exception e) {
                if (firstError == null) {
                    firstError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return firstError;
                }
            }
        }
        return firstError;
    }

    /** Drops cached readings for symbols no longer in the market, e.g. after a reload. */
    public void pruneUnknown() {
        List<String> live = new ArrayList<>();
        for (Stock stock : plugin.market().stocks()) {
            if (stock.realSymbol() != null) {
                live.add(stock.realSymbol());
            }
        }
        cache.keySet().retainAll(live);
        failures.keySet().retainAll(live);
    }
}
