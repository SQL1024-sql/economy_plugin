package io.github.sql1024.dasha.core;

/** One row of the transaction log. */
public record TxnRecord(
        String playerName,
        String symbol,
        String type,
        int shares,
        double unitPrice,
        long gold,
        long timestamp) {

    public boolean isBuy() {
        return "BUY".equalsIgnoreCase(type);
    }
}
