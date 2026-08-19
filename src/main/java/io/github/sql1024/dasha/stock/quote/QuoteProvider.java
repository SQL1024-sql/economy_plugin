package io.github.sql1024.dasha.stock.quote;

/**
 * A source of live market prices.
 *
 * <p>Kept as an interface so the plugin is not welded to one vendor: the default provider uses an
 * undocumented endpoint that could stop working, and swapping in a keyed provider should not mean
 * touching the market engine.
 */
public interface QuoteProvider {

    /** Human-readable provider name, for logs and the admin panel. */
    String name();

    /**
     * Fetches one symbol. Called from a background thread, never the server thread.
     *
     * @throws Exception when the symbol is unknown, the request fails, or the response is unusable
     */
    Quote fetch(String symbol) throws Exception;
}
