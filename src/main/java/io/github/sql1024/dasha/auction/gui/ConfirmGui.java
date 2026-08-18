package io.github.sql1024.dasha.auction.gui;

import io.github.sql1024.dasha.ui.Gui;
import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.AuctionManager;
import io.github.sql1024.dasha.auction.Listing;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public final class ConfirmGui extends Gui {

    private static final int SLOT_OFFER = 11;
    private static final int SLOT_ARROW = 13;
    private static final int SLOT_PRICE = 15;
    private static final int SLOT_CONFIRM = 21;
    private static final int SLOT_CANCEL = 23;

    private final UUID listingId;
    private final int backPage;

    public ConfirmGui(DashaEconomyPlugin plugin, Player player, UUID listingId, int backPage) {
        super(plugin, player);
        this.listingId = listingId;
        this.backPage = backPage;
        this.inventory = Bukkit.createInventory(this, 27, Msg.mm("<dark_gray>確認購買"));
    }

    @Override
    public void render() {
        inventory.clear();
        Listing listing = plugin.auctions().get(listingId);
        if (listing == null) {
            inventory.setItem(SLOT_ARROW, icon(Material.BARRIER, "<red>這筆上架已經不在了"));
            fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
            return;
        }

        ItemStack offer = listing.offer();
        appendLore(offer, List.of("", "<green>你會得到這個", "<gray>賣家：<white>" + listing.sellerName()));
        inventory.setItem(SLOT_OFFER, offer);

        long balance = plugin.economy().balance(player);
        boolean enough = balance >= listing.price();
        boolean room = plugin.auctions().hasRoom(player, listing.offer());

        inventory.setItem(SLOT_PRICE, icon(Material.SUNFLOWER,
                "<gold>價格：<white>" + Fmt.coin(listing.price()) + " 大沙幣",
                "<gray>單價：<white>" + Fmt.price(listing.unitPrice()) + "</white> <gray>/個",
                "",
                enough
                        ? "<green>✔ 你有 " + Fmt.coin(balance)
                        : "<red>✖ 你只有 " + Fmt.coin(balance) + "，還差 "
                                + Fmt.coin(listing.price() - balance)));

        inventory.setItem(SLOT_ARROW, icon(Material.ARROW, "<yellow>購買",
                "<gray>左邊給你，大沙幣給賣家。"));

        inventory.setItem(SLOT_CONFIRM, enough && room
                ? icon(Material.LIME_CONCRETE, "<green><bold>確認購買", "<gray>點下去就成交，無法反悔。")
                : !enough
                        ? icon(Material.GRAY_CONCRETE, "<gray>大沙幣不足",
                                "<red>需要 " + Fmt.coin(listing.price()) + " 大沙幣")
                        : icon(Material.GRAY_CONCRETE, "<gray>背包空間不足",
                                "<red>先清出位置再來買，避免東西掉在地上。"));
        inventory.setItem(SLOT_CANCEL, icon(Material.RED_CONCRETE, "<red>取消", "<gray>回到拍賣行"));

        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (slot == SLOT_CANCEL) {
            plugin.click(player);
            reopen(new BrowseGui(plugin, player, backPage));
            return;
        }
        if (slot != SLOT_CONFIRM) {
            return;
        }

        Listing listing = plugin.auctions().get(listingId);
        if (listing == null) {
            player.sendMessage(Msg.prefixed("<red>手慢了，這筆上架已經被買走或下架了。"));
            plugin.fail(player);
            reopen(new BrowseGui(plugin, player, backPage));
            return;
        }

        AuctionManager.BuyResult result = plugin.auctions().buy(player, listingId);
        switch (result) {
            case OK -> {
                plugin.auctions().save();
                plugin.success(player);
                player.sendMessage(Msg.prefixed("<green>交易完成！你得到 <white>" + Msg.itemName(listing.offer())
                        + " x" + listing.offer().getAmount() + "</white>，付出 <yellow>"
                        + Fmt.coin(listing.price()) + "</yellow> 大沙幣。"));
                if (plugin.auctionSettings().broadcastTrade()) {
                    Bukkit.broadcast(Msg.prefixed("<gray><white>" + player.getName()
                            + "</white> 用 <yellow>" + Fmt.coin(listing.price())
                            + "</yellow> 大沙幣買下了 <white>" + listing.sellerName() + "</white> 的 <white>"
                            + Msg.itemName(listing.offer()) + "</white>。"));
                }
                reopen(new BrowseGui(plugin, player, backPage));
            }
            case NOT_ENOUGH_COIN -> {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>大沙幣不夠：需要 <white>" + Fmt.coin(listing.price())
                        + "</white>，你只有 <white>" + Fmt.coin(plugin.economy().balance(player)) + "</white>。"));
                render();
            }
            case NO_ROOM -> {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>背包空間不夠，先清出位置再買。"));
                render();
            }
            case OWN_LISTING -> {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>不能跟自己交易。"));
                reopen(new BrowseGui(plugin, player, backPage));
            }
            case GONE -> {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>這筆上架已經不在了。"));
                reopen(new BrowseGui(plugin, player, backPage));
            }
        }
    }
}
