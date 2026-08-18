package io.github.sql1024.dasha.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * One item on sale for a fixed 大沙幣 price.
 *
 * <p>Listings used to be barter — an item offered for another item. Pricing them in 大沙幣 instead
 * is what lets the auction house share one balance with the stock market and the store, and what
 * makes the listing fee and the sale cut possible: both are money the economy destroys, and they
 * are the only sinks that keep working for as long as players keep trading with each other.
 */
public final class Listing {

    private final UUID id;
    private final UUID seller;
    private final String sellerName;
    private final ItemStack offer;
    private final long price;
    private final long createdAt;
    private final long expiresAt;

    public Listing(UUID id, UUID seller, String sellerName, ItemStack offer, long price,
                   long createdAt, long expiresAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.offer = offer;
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID id() {
        return id;
    }

    public UUID seller() {
        return seller;
    }

    public String sellerName() {
        return sellerName;
    }

    /** 賣家拿出來的東西。回傳 clone，外面改不到內部狀態。 */
    public ItemStack offer() {
        return offer.clone();
    }

    /** 買家要付的大沙幣總價。 */
    public long price() {
        return price;
    }

    /** 每個物品平均多少大沙幣，用來比價。 */
    public double unitPrice() {
        int amount = Math.max(1, offer.getAmount());
        return (double) price / amount;
    }

    public long createdAt() {
        return createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public boolean expired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }
}
