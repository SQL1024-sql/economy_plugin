package io.github.sql1024.dasha.core;

/**
 * Every way 大沙幣 can move. The sign tells the economy monitor whether a channel creates money,
 * destroys it, or just moves it between players — that split is what {@code /eco stats} reports.
 */
public enum TxnType {

    /** Ore sold to the server. The only faucet in the whole economy. */
    ORE_SELL(Flow.FAUCET, "礦物收購"),
    /** Payout of a stock sale, before fees. */
    STOCK_SELL(Flow.FAUCET, "股票賣出"),

    /** Cost of a stock purchase, before fees. */
    STOCK_BUY(Flow.SINK, "股票買入"),
    /** Buying building materials from the server store. */
    STORE_BUY(Flow.SINK, "商店購買"),
    /** Fee charged on a stock trade. */
    STOCK_FEE(Flow.SINK, "股票手續費"),
    /** Non-refundable fee for putting an item on the auction house. */
    AUCTION_LISTING_FEE(Flow.SINK, "上架費"),
    /** Cut the server takes out of an auction sale. */
    AUCTION_CUT(Flow.SINK, "成交抽成"),

    /** Buyer's side of an auction sale. */
    AUCTION_BUY(Flow.TRANSFER, "拍賣付款"),
    /** Seller's side of an auction sale, net of the cut. */
    AUCTION_PAYOUT(Flow.TRANSFER, "拍賣收款"),

    /** Payer's side of a {@code /pay} transfer, before the fee. */
    PAY_SENT(Flow.TRANSFER, "轉帳付出"),
    /** Payee's side of a {@code /pay} transfer, net of the fee. */
    PAY_RECEIVED(Flow.TRANSFER, "轉帳收款"),
    /** Fee taken out of a {@code /pay} transfer. */
    PAY_FEE(Flow.SINK, "轉帳手續費"),

    /** Balance moved by an administrator. Always audited. */
    ADMIN(Flow.ADMIN, "管理調整");

    /** What a transaction does to the total money supply. */
    public enum Flow {
        /** Creates 大沙幣 out of nothing. */
        FAUCET,
        /** Destroys 大沙幣. */
        SINK,
        /** Moves 大沙幣 between players, total unchanged. */
        TRANSFER,
        /** Administrative, tracked separately so it never hides in the real numbers. */
        ADMIN
    }

    private final Flow flow;
    private final String label;

    TxnType(Flow flow, String label) {
        this.flow = flow;
        this.label = label;
    }

    public Flow flow() {
        return flow;
    }

    /** Chinese label used in {@code /eco stats} and transaction listings. */
    public String label() {
        return label;
    }
}
