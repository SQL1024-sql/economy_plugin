package io.github.sql1024.dasha.auction;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * The {@code auction} section of config.yml.
 *
 * <p>Trades between players are zero-sum on their own — they move 大沙幣 around without changing
 * how much exists. The listing fee and the sale cut are what turn the auction house into a sink,
 * and unlike buying building material, that sink never saturates: players keep trading long after
 * they have finished building.
 *
 * @param maxListingsPerPlayer listings one player may hold at once
 * @param listingDays          days before a listing expires, 0 for never
 * @param expireCheckMinutes   how often expired listings are swept
 * @param broadcastTrade       announce completed sales server-wide
 * @param listingFeePercent    percentage of the asking price charged up front, never refunded
 * @param listingFeeMin        floor under the listing fee, so cheap spam still costs something
 * @param saleCutPercent       percentage of a completed sale the server destroys
 * @param minPrice             cheapest a listing may be
 * @param maxPrice             dearest a listing may be
 * @param maxAmountPerListing  most items one listing may hold
 * @param amountStep           items a plain click adds or removes in the sell screen
 * @param amountStepShift      items a shift click adds or removes
 * @param priceStep            大沙幣 a plain click adds or removes from the asking price
 * @param priceStepShift       大沙幣 a shift click adds or removes
 * @param referenceListings    comparable listings shown by the price-reference button
 */
public record AuctionSettings(
        int maxListingsPerPlayer,
        long listingDays,
        long expireCheckMinutes,
        boolean broadcastTrade,
        double listingFeePercent,
        long listingFeeMin,
        double saleCutPercent,
        long minPrice,
        long maxPrice,
        int maxAmountPerListing,
        int amountStep,
        int amountStepShift,
        long priceStep,
        long priceStepShift,
        int referenceListings) {

    public static AuctionSettings from(FileConfiguration config) {
        return new AuctionSettings(
                Math.max(1, config.getInt("auction.max-listings-per-player", 10)),
                Math.max(0L, config.getLong("auction.listing-days", 7L)),
                Math.max(1L, config.getLong("auction.expire-check-minutes", 5L)),
                config.getBoolean("auction.broadcast-trade", false),
                Math.clamp(config.getDouble("auction.listing-fee-percent", 1.0), 0.0, 50.0),
                Math.max(0L, config.getLong("auction.listing-fee-min", 1L)),
                Math.clamp(config.getDouble("auction.sale-cut-percent", 5.0), 0.0, 50.0),
                Math.max(1L, config.getLong("auction.min-price", 1L)),
                Math.max(1L, config.getLong("auction.max-price", 100_000_000L)),
                Math.clamp(config.getInt("auction.max-amount-per-listing", 512), 1, 6912),
                Math.max(1, config.getInt("auction.amount-step", 1)),
                Math.max(1, config.getInt("auction.amount-step-shift", 8)),
                Math.max(1L, config.getLong("auction.price-step", 10L)),
                Math.max(1L, config.getLong("auction.price-step-shift", 100L)),
                Math.clamp(config.getInt("auction.reference-listings", 5), 1, 20));
    }

    /** Non-refundable fee for putting an item up at {@code price}. */
    public long listingFee(long price) {
        return Math.max(listingFeeMin, (long) Math.ceil(price * listingFeePercent / 100.0));
    }

    /** Slice of a completed sale that the server destroys. */
    public long saleCut(long price) {
        return (long) Math.floor(price * saleCutPercent / 100.0);
    }

    /** What the seller actually receives once the sale goes through. */
    public long sellerPayout(long price) {
        return price - saleCut(price);
    }
}
