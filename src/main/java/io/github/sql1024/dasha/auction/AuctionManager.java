package io.github.sql1024.dasha.auction;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.TxnType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Holds every listing and collect box, and runs the money side of a sale. */
public final class AuctionManager {

    public enum BuyResult {
        OK,
        GONE,
        OWN_LISTING,
        NOT_ENOUGH_COIN,
        NO_ROOM
    }

    private final DashaEconomyPlugin plugin;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> collectBoxes = new HashMap<>();
    private boolean dirty;

    public AuctionManager(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    // 上架 / 下架
    // ------------------------------------------------------------

    public Listing createListing(Player seller, ItemStack offer, long price) {
        long now = System.currentTimeMillis();
        long days = plugin.auctionSettings().listingDays();
        long expires = days <= 0 ? 0L : now + TimeUnit.DAYS.toMillis(days);
        Listing listing = new Listing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(),
                offer.clone(), price, now, expires);
        listings.put(listing.id(), listing);
        dirty = true;
        return listing;
    }

    public Listing get(UUID id) {
        return listings.get(id);
    }

    public boolean remove(UUID id) {
        boolean removed = listings.remove(id) != null;
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /** 全部上架，最新的排前面。 */
    public List<Listing> all() {
        List<Listing> list = new ArrayList<>(listings.values());
        list.sort(Comparator.comparingLong(Listing::createdAt).reversed());
        return list;
    }

    public List<Listing> of(UUID seller) {
        List<Listing> list = new ArrayList<>();
        for (Listing listing : listings.values()) {
            if (listing.seller().equals(seller)) {
                list.add(listing);
            }
        }
        list.sort(Comparator.comparingLong(Listing::createdAt).reversed());
        return list;
    }

    public int countOf(UUID seller) {
        int count = 0;
        for (Listing listing : listings.values()) {
            if (listing.seller().equals(seller)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------
    // 交易
    // ------------------------------------------------------------

    /**
     * Buys one listing.
     *
     * <p>The listing is pulled out of the map before any money moves, so two players clicking the
     * same item on the same tick cannot both buy it. If the payment then fails the listing goes
     * straight back — the item is never destroyed and never sold twice.
     */
    public BuyResult buy(Player buyer, UUID listingId) {
        Listing listing = listings.get(listingId);
        if (listing == null) {
            return BuyResult.GONE;
        }
        if (listing.seller().equals(buyer.getUniqueId())) {
            return BuyResult.OWN_LISTING;
        }
        if (plugin.economy().balance(buyer) < listing.price()) {
            return BuyResult.NOT_ENOUGH_COIN;
        }
        if (!hasRoom(buyer, listing.offer())) {
            return BuyResult.NO_ROOM;
        }

        listings.remove(listingId);
        dirty = true;

        long cut = plugin.auctionSettings().saleCut(listing.price());
        String detail = Msg.itemName(listing.offer()) + " x" + listing.offer().getAmount();
        boolean paid = plugin.economy().transfer(
                buyer.getUniqueId(), buyer.getName(),
                listing.seller(), listing.sellerName(),
                listing.price(), cut, detail);
        if (!paid) {
            // Balance moved between the check and the charge; hand the listing back untouched.
            listings.put(listingId, listing);
            return BuyResult.NOT_ENOUGH_COIN;
        }

        giveOrDrop(buyer, listing.offer());
        Player seller = Bukkit.getPlayer(listing.seller());
        if (seller != null) {
            seller.sendMessage(Msg.prefixed("<green>你的 <white>" + Msg.itemName(listing.offer())
                    + "</white> 被 <white>" + buyer.getName() + "</white> 買走了，入帳 <yellow>"
                    + Fmt.coin(plugin.auctionSettings().sellerPayout(listing.price()))
                    + "</yellow> 大沙幣<gray>（已扣成交抽成 " + Fmt.coin(cut) + "）。"));
        }
        return BuyResult.OK;
    }

    /** Whether the buyer's main inventory can take the whole offer stack. */
    public boolean hasRoom(Player player, ItemStack item) {
        int maxStack = item.getMaxStackSize();
        int room = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null) {
                room += maxStack;
            } else if (stack.isSimilar(item) && stack.getAmount() < maxStack) {
                room += maxStack - stack.getAmount();
            }
            if (room >= item.getAmount()) {
                return true;
            }
        }
        return room >= item.getAmount();
    }

    /** 算玩家背包裡有幾個符合的物品（只看主背包，不含裝備欄）。 */
    public int countMatching(PlayerInventory inventory, ItemStack want) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.isSimilar(want)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** 從玩家背包扣掉指定數量的相符物品。呼叫前要自己先用 countMatching 確認數量夠。 */
    public void takeFrom(Player player, ItemStack template, int amount) {
        removeMatching(player.getInventory(), template, amount);
    }

    private void removeMatching(PlayerInventory inventory, ItemStack want, int amount) {
        ItemStack[] contents = inventory.getStorageContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !stack.isSimilar(want)) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            left -= take;
            if (stack.getAmount() - take <= 0) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }
        inventory.setStorageContents(contents);
    }

    /** 管理員強制下架後的收尾：物品退回賣家取貨箱，兩邊都通知。上架本身要先自己移除。 */
    public void forceRemoveNotify(Player admin, Listing listing) {
        depositToBox(listing.seller(), listing.offer());
        admin.sendMessage(Msg.prefixed("<yellow>已強制下架 <white>" + listing.sellerName() + "</white> 的 <white>"
                + Msg.itemName(listing.offer()) + "</white>，物品退回他的取貨箱。"));
        Player seller = Bukkit.getPlayer(listing.seller());
        if (seller != null) {
            seller.sendMessage(Msg.prefixed("<yellow>你的上架 <white>" + Msg.itemName(listing.offer())
                    + "</white> 被管理員下架了，物品在取貨箱（<white>/ah collect</white>）。"));
        }
    }

    /** 用名字找上架者的 UUID，離線玩家也找得到（只看目前有上架的人）。 */
    public UUID findSellerByName(String name) {
        for (Listing listing : listings.values()) {
            if (listing.sellerName().equalsIgnoreCase(name)) {
                return listing.seller();
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // 取貨箱
    // ------------------------------------------------------------

    /** 送東西給玩家：線上就塞背包（滿了丟腳邊），離線就進取貨箱。 */
    public void deposit(UUID target, ItemStack item) {
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            giveOrDrop(online, item);
            return;
        }
        collectBoxes.computeIfAbsent(target, k -> new ArrayList<>()).add(item.clone());
        dirty = true;
    }

    /** 一定進取貨箱，不管玩家在不在線上（過期退回用）。 */
    public void depositToBox(UUID target, ItemStack item) {
        collectBoxes.computeIfAbsent(target, k -> new ArrayList<>()).add(item.clone());
        dirty = true;
    }

    public List<ItemStack> collectBox(UUID owner) {
        return collectBoxes.getOrDefault(owner, List.of());
    }

    public int collectSize(UUID owner) {
        return collectBox(owner).size();
    }

    /** 領取取貨箱的第 index 件物品，背包滿了就丟腳邊。 */
    public boolean claim(Player player, int index) {
        List<ItemStack> box = collectBoxes.get(player.getUniqueId());
        if (box == null || index < 0 || index >= box.size()) {
            return false;
        }
        ItemStack item = box.remove(index);
        if (box.isEmpty()) {
            collectBoxes.remove(player.getUniqueId());
        }
        dirty = true;
        giveOrDrop(player, item);
        return true;
    }

    /** 一次領完，回傳領了幾件。 */
    public int claimAll(Player player) {
        List<ItemStack> box = collectBoxes.remove(player.getUniqueId());
        if (box == null || box.isEmpty()) {
            return 0;
        }
        dirty = true;
        for (ItemStack item : box) {
            giveOrDrop(player, item);
        }
        return box.size();
    }

    public void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------
    // 過期處理
    // ------------------------------------------------------------

    public int purgeExpired() {
        long now = System.currentTimeMillis();
        List<Listing> expired = new ArrayList<>();
        for (Listing listing : listings.values()) {
            if (listing.expired(now)) {
                expired.add(listing);
            }
        }
        for (Listing listing : expired) {
            listings.remove(listing.id());
            depositToBox(listing.seller(), listing.offer());
            Player seller = Bukkit.getPlayer(listing.seller());
            if (seller != null) {
                seller.sendMessage(Msg.prefixed("<yellow>你的上架 <white>" + Msg.itemName(listing.offer())
                        + "</white> 已到期，物品放進取貨箱了（<white>/ah collect</white>）。"
                        + "<gray>上架費不退還。"));
            }
        }
        if (!expired.isEmpty()) {
            dirty = true;
        }
        return expired.size();
    }

    // ------------------------------------------------------------
    // 存檔 / 讀檔
    // ------------------------------------------------------------

    public void load() {
        listings.clear();
        collectBoxes.clear();

        for (Object[] row : plugin.database().loadAuctionListings()) {
            try {
                Listing listing = new Listing(
                        UUID.fromString((String) row[0]),
                        UUID.fromString((String) row[1]),
                        (String) row[2],
                        ItemCodec.decode((String) row[3]),
                        (Long) row[4],
                        (Long) row[5],
                        (Long) row[6]);
                listings.put(listing.id(), listing);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "跳過壞掉的上架資料：" + row[0], ex);
            }
        }

        for (Object[] row : plugin.database().loadAuctionCollect()) {
            try {
                collectBoxes.computeIfAbsent(UUID.fromString((String) row[0]), key -> new ArrayList<>())
                        .add(ItemCodec.decode((String) row[1]));
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "跳過壞掉的取貨箱資料：" + row[0], ex);
            }
        }

        plugin.getLogger().info("載入 " + listings.size() + " 筆上架、" + collectBoxes.size() + " 個取貨箱。");
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        List<Object[]> listingRows = new ArrayList<>(listings.size());
        for (Listing listing : listings.values()) {
            listingRows.add(new Object[] {
                listing.id().toString(),
                listing.seller().toString(),
                listing.sellerName(),
                ItemCodec.encode(listing.offer()),
                listing.price(),
                listing.createdAt(),
                listing.expiresAt()});
        }
        List<Object[]> collectRows = new ArrayList<>();
        for (Map.Entry<UUID, List<ItemStack>> entry : collectBoxes.entrySet()) {
            for (ItemStack item : entry.getValue()) {
                collectRows.add(new Object[] {entry.getKey().toString(), ItemCodec.encode(item)});
            }
        }
        plugin.database().saveAuction(listingRows, collectRows);
        dirty = false;
    }

    /** Charges the non-refundable listing fee. Returns false when the seller cannot afford it. */
    public boolean chargeListingFee(Player seller, long price) {
        long fee = plugin.auctionSettings().listingFee(price);
        if (fee <= 0L) {
            return true;
        }
        return plugin.economy().withdraw(seller, fee, TxnType.AUCTION_LISTING_FEE,
                "上架 " + Fmt.coin(price));
    }
}
