package io.github.sql1024.dasha.stock.quote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads live prices from Yahoo Finance's chart endpoint.
 *
 * <p>Chosen because it needs no API key and no signup: every keyed free tier that would cover this
 * many tickers runs out of quota fast (Alpha Vantage is down to a handful of calls a day), and a
 * server operator should not have to register anywhere to get the plugin working. The trade-off is
 * that this is an undocumented endpoint — it can change or start refusing traffic without notice,
 * which is exactly why {@link QuoteService} keeps the last good price and the market falls back to
 * simulation rather than breaking.
 *
 * <p>One request per symbol: the batch endpoint now demands a session crumb, so it is not usable
 * without cookie juggling. Requests are spaced out by the caller instead.
 */
public final class YahooQuoteProvider implements QuoteProvider {

    /**
     * {@code range=1d} is load-bearing, not a default.
     *
     * <p>{@code chartPreviousClose} is relative to the start of the requested window, so asking
     * for five days makes it the close from five days ago and every "day change" comes out several
     * points too large. One day is what makes the percentage match the one on a real ticker.
     */
    private static final String ENDPOINT =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=1d";

    /** Yahoo answers plain programmatic clients with 403, so identify as a normal browser. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final HttpClient http;
    private final Duration timeout;

    public YahooQuoteProvider(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "Yahoo Finance";
    }

    @Override
    public Quote fetch(String symbol) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(ENDPOINT, encode(symbol))))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return parse(symbol, response.body());
    }

    private static Quote parse(String symbol, String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject chart = root.getAsJsonObject("chart");
        if (chart == null || chart.get("result") == null || chart.get("result").isJsonNull()) {
            throw new IllegalStateException("回應沒有 result 區段");
        }
        JsonObject meta = chart.getAsJsonArray("result").get(0).getAsJsonObject()
                .getAsJsonObject("meta");

        double price = number(meta, "regularMarketPrice", Double.NaN);
        if (Double.isNaN(price) || price <= 0.0) {
            throw new IllegalStateException("沒有有效的 regularMarketPrice");
        }
        // chartPreviousClose is the previous session's close; previousClose appears on some
        // instruments instead, so take whichever is present rather than assuming one.
        double previous = number(meta, "chartPreviousClose", number(meta, "previousClose", price));

        String currency = string(meta, "currency", "USD");
        String name = string(meta, "shortName", string(meta, "longName", symbol));
        boolean open = "REGULAR".equalsIgnoreCase(string(meta, "marketState", ""));

        return new Quote(symbol, price, previous, currency.toUpperCase(Locale.ROOT), name,
                open, System.currentTimeMillis());
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Percent-encodes the parts of a ticker that are not legal in a URI path.
     *
     * <p>Index tickers start with {@code ^} — {@code ^GSPC} for the S&P 500 — and that character
     * is not allowed in a URI, so {@code URI.create} rejects it outright. Command-line tools
     * encode it silently, which is exactly why this only shows up once the code runs.
     */
    private static String encode(String symbol) {
        return symbol.replace("^", "%5E").replace(" ", "%20");
    }
}
