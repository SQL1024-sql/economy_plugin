package io.github.sql1024.dasha.auction.gui;

import io.github.sql1024.dasha.ui.Gui;
import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.AuctionSettings;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 上架介面。
 * <p>
 * 這裡的每一格都是「畫出來的」，不是真的物品格：玩家的東西全程留在自己背包，
 * 按下確認上架的當下才檢查數量並扣除。這樣關介面、離線、關服、同一 tick 連點
 * 都不可能讓物品變兩份或消失。
 * <p>
 * 上架標的是大沙幣價格，不再是以物易物。上架費在按下確認的那一刻就收走，而且
 * 不管賣不賣得掉都不退 —— 這是拍賣行變成貨幣消耗管道的關鍵，也順便讓洗版上架
 * 變成一件要花錢的事。
 */
public final class SellGui extends Gui {

    private static final int SLOT_OFFER = 20;
    private static final int SLOT_OFFER_AMOUNT = 29;
    private static final int SLOT_PRICE = 24;
    private static final int SLOT_PRICE_STEP = 33;
    private static final int SLOT_SUGGEST = 32;
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_BACK = 45;

    /** 要賣的物品範本（數量固定 1，實際數量看 offerAmount）。 */
    private ItemStack offer;
    private int offerAmount = 1;
    /** 開價（大沙幣總價，不是單價）。 */
    private long price;

    public SellGui(DashaEconomyPlugin plugin, Player player) {
        super(plugin, player);
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm("<dark_gray>上架物品 <gray>— 大沙幣計價"));
    }

    public void setOffer(ItemStack item, int amount) {
        this.offer = template(item);
        this.offerAmount = clamp(amount);
    }

    public void setPrice(long price) {
        AuctionSettings settings = plugin.auctionSettings();
        this.price = Math.clamp(price, settings.minPrice(), settings.maxPrice());
    }

    private static ItemStack template(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy;
    }

    private int clamp(int amount) {
        return Math.clamp(amount, 1, plugin.auctionSettings().maxAmountPerListing());
    }

    // ------------------------------------------------------------
    // 畫面
    // ------------------------------------------------------------

    @Override
    public void render() {
        inventory.clear();
        AuctionSettings settings = plugin.auctionSettings();

        inventory.setItem(11, icon(Material.CHEST, "<green>↓ 要賣出的物品",
                "<yellow>不用拖進來，直接點你背包裡的物品就是選它。",
                "<gray>東西會留在你背包，按下確認上架才會扣。"));
        if (offer == null) {
            inventory.setItem(SLOT_OFFER, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>還沒選要賣的物品",
                    "<yellow>直接點下面背包裡的物品即可"));
        } else {
            inventory.setItem(SLOT_OFFER, display(offer, offerAmount, List.of("",
                    "<gray>上架數量：<white>" + offerAmount + " 個",
                    "<gray>你身上有：<white>" + have(offer) + " 個",
                    "<yellow>▶ 點背包其他物品可直接換掉")));
        }
        inventory.setItem(SLOT_OFFER_AMOUNT, amountButton("<green>上架數量：<white>" + offerAmount));

        inventory.setItem(15, icon(Material.GOLD_NUGGET, "<gold>↓ 開價",
                "<gray>買家要付這麼多大沙幣。",
                "<gray>這是<white>總價</white>，不是單價。"));

        long fee = price > 0 ? settings.listingFee(price) : 0L;
        long payout = price > 0 ? settings.sellerPayout(price) : 0L;
        if (price <= 0L) {
            inventory.setItem(SLOT_PRICE, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>還沒開價",
                    "", "<yellow>▶ 點擊在聊天欄輸入價格"));
        } else {
            inventory.setItem(SLOT_PRICE, icon(Material.SUNFLOWER,
                    "<gold>開價：<white>" + Fmt.coin(price) + " 大沙幣",
                    "<gray>單價：<white>" + Fmt.price(offerAmount > 0 ? (double) price / offerAmount : price)
                            + "</white> <gray>/個",
                    "",
                    "<red>上架費：<white>" + Fmt.coin(fee) + "</white> <dark_gray>(現在就扣，不退)",
                    "<gray>成交抽成：<white>" + settings.saleCutPercent() + "%",
                    "<green>賣掉可拿：<white>" + Fmt.coin(payout) + " 大沙幣",
                    "", "<yellow>▶ 點擊重新輸入價格"));
        }
        inventory.setItem(SLOT_PRICE_STEP, icon(Material.COMPARATOR, "<gold>快速調價",
                "<gray>左鍵 <white>±" + settings.priceStep() + "</white>"
                        + "　Shift+左鍵 <white>±" + settings.priceStepShift() + "</white>",
                "<dark_gray>範圍 " + Fmt.coin(settings.minPrice()) + " ~ " + Fmt.coin(settings.maxPrice())));

        inventory.setItem(22, icon(Material.ARROW, "<yellow>換成大沙幣", "<gray>左邊給買家，右邊進你帳戶。"));
        inventory.setItem(SLOT_SUGGEST, icon(Material.SPYGLASS, "<aqua>參考行情",
                "<gray>看看拍賣行裡同樣的東西",
                "<gray>別人開多少錢。",
                "", "<yellow>▶ 點擊查詢"));

        boolean enough = offer != null && have(offer) >= offerAmount;
        boolean priced = price >= settings.minPrice();
        boolean affordFee = !priced || plugin.economy().balance(player) >= settings.listingFee(price);
        boolean ready = offer != null && priced && enough && affordFee;

        inventory.setItem(SLOT_CONFIRM, ready
                ? icon(Material.LIME_CONCRETE, "<green><bold>確認上架",
                "<gray>賣出：<white>" + Msg.itemName(offer) + " x" + offerAmount,
                "<gray>開價：<white>" + Fmt.coin(price) + " 大沙幣",
                "<red>立即扣上架費：<white>" + Fmt.coin(fee),
                "", "<yellow>▶ 點擊上架（物品與上架費這時才會扣）")
                : icon(Material.GRAY_CONCRETE, "<gray>還沒填完",
                offer == null ? "<red>✖ 還沒選要賣的物品"
                        : enough ? "<green>✔ 要賣的物品 OK"
                        : "<red>✖ 你身上只有 " + have(offer) + " / " + offerAmount + " 個",
                !priced ? "<red>✖ 還沒開價" : "<green>✔ 開價 OK",
                affordFee ? "<green>✔ 上架費付得起"
                        : "<red>✖ 上架費不夠（要 " + Fmt.coin(fee) + "，你有 "
                                + Fmt.coin(plugin.economy().balance(player)) + "）"));
        inventory.setItem(SLOT_BACK, icon(Material.RED_CONCRETE, "<red>返回", "<gray>不會動到你的物品"));

        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack display(ItemStack template, int amount, List<String> extra) {
        ItemStack shown = template.clone();
        shown.setAmount(Math.max(1, Math.min(shown.getMaxStackSize(), amount)));
        appendLore(shown, extra);
        return shown;
    }

    private ItemStack amountButton(String title) {
        return icon(Material.COMPARATOR, title,
                "<gray>左鍵 <white>±" + plugin.auctionSettings().amountStep() + "</white>"
                        + "　Shift+左鍵 <white>±" + plugin.auctionSettings().amountStepShift() + "</white>",
                "<dark_gray>上限 " + plugin.auctionSettings().maxAmountPerListing() + " 個");
    }

    private int have(ItemStack template) {
        return plugin.auctions().countMatching(player.getInventory(), template);
    }

    // ------------------------------------------------------------
    // 互動
    // ------------------------------------------------------------

    @Override
    public void onClick(InventoryClickEvent event) {
        // 這個介面不搬動任何物品，所有點擊一律取消。
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0) {
            return;
        }

        if (raw >= inventory.getSize()) {
            // 點自己背包裡的物品＝選它來賣。物品不會被搬走，只是記下來。
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) {
                return;
            }
            setOffer(clicked, clicked.getAmount());
            plugin.click(player);
            render();
            return;
        }

        switch (raw) {
            case SLOT_OFFER -> {
                plugin.click(player);
                player.sendMessage(Msg.prefixed("<yellow>直接點背包裡的物品就能選它上架。"));
            }
            case SLOT_PRICE -> {
                plugin.click(player);
                askPrice();
            }
            case SLOT_SUGGEST -> {
                plugin.click(player);
                showMarketReference();
            }
            case SLOT_OFFER_AMOUNT -> {
                int delta = delta(event.getClick());
                if (delta != 0) {
                    offerAmount = clamp(offerAmount + delta);
                    plugin.click(player);
                    render();
                }
            }
            case SLOT_PRICE_STEP -> {
                long delta = priceDelta(event.getClick());
                if (delta != 0L) {
                    setPrice(Math.max(0L, price + delta));
                    plugin.click(player);
                    render();
                }
            }
            case SLOT_BACK -> {
                plugin.click(player);
                plugin.endSellSession(player);
                reopen(new BrowseGui(plugin, player, 0));
            }
            case SLOT_CONFIRM -> submit();
            default -> {
            }
        }
    }

    /** Closes the GUI and waits for the player to type a price in chat. */
    private void askPrice() {
        AuctionSettings settings = plugin.auctionSettings();
        player.closeInventory();
        player.sendMessage(Msg.prefixed("<yellow>請在聊天欄輸入開價（大沙幣總價，"
                + Fmt.coin(settings.minPrice()) + " ~ " + Fmt.coin(settings.maxPrice()) + "）："));
        player.sendMessage(Msg.prefixed("<gray>輸入 <white>取消</white> 可放棄。"));
        plugin.promptChat(player, input -> {
            String text = input.trim();
            if (text.equalsIgnoreCase("取消") || text.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            long value;
            try {
                value = Long.parseLong(text.replace(",", ""));
            } catch (NumberFormatException e) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red><white>" + text + "</white> 不是有效的數字。"));
                open();
                return;
            }
            if (value < settings.minPrice() || value > settings.maxPrice()) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>價格要在 <white>" + Fmt.coin(settings.minPrice())
                        + "</white> ~ <white>" + Fmt.coin(settings.maxPrice()) + "</white> 之間。"));
                open();
                return;
            }
            setPrice(value);
            plugin.click(player);
            open();
        });
    }

    /** Prints what other listings of the same item are asking, so sellers can price sanely. */
    private void showMarketReference() {
        if (offer == null) {
            player.sendMessage(Msg.prefixed("<red>先選一個要賣的物品。"));
            return;
        }
        List<io.github.sql1024.dasha.auction.Listing> matches = plugin.auctions().all().stream()
                .filter(listing -> listing.offer().isSimilar(offer))
                .sorted(java.util.Comparator.comparingDouble(
                        io.github.sql1024.dasha.auction.Listing::unitPrice))
                .limit(plugin.auctionSettings().referenceListings())
                .toList();
        if (matches.isEmpty()) {
            player.sendMessage(Msg.prefixed("<gray>目前沒有人賣 <white>" + Msg.itemName(offer) + "</white>，你是第一個。"));
            return;
        }
        player.sendMessage(Msg.prefixed("<aqua><white>" + Msg.itemName(offer) + "</white> 目前行情（單價由低到高）："));
        for (var listing : matches) {
            player.sendMessage(Msg.mm("  <gray>• <white>" + Fmt.price(listing.unitPrice())
                    + "</white> <gray>/個　總價 <white>" + Fmt.coin(listing.price())
                    + "</white> <dark_gray>(" + listing.sellerName() + ")"));
        }
    }

    private int delta(ClickType click) {
        AuctionSettings settings = plugin.auctionSettings();
        return switch (click) {
            case LEFT -> settings.amountStep();
            case RIGHT -> -settings.amountStep();
            case SHIFT_LEFT -> settings.amountStepShift();
            case SHIFT_RIGHT -> -settings.amountStepShift();
            default -> 0;
        };
    }

    private long priceDelta(ClickType click) {
        AuctionSettings settings = plugin.auctionSettings();
        return switch (click) {
            case LEFT -> settings.priceStep();
            case RIGHT -> -settings.priceStep();
            case SHIFT_LEFT -> settings.priceStepShift();
            case SHIFT_RIGHT -> -settings.priceStepShift();
            default -> 0L;
        };
    }

    private void submit() {
        AuctionSettings settings = plugin.auctionSettings();
        if (offer == null || price < settings.minPrice()) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red>要賣的物品和開價都要填。"));
            return;
        }

        int max = settings.maxListingsPerPlayer();
        if (max > 0 && !player.hasPermission("dasha.admin")
                && plugin.auctions().countOf(player.getUniqueId()) >= max) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red>你的上架數量已達上限（" + max + " 筆），先下架一些吧。"));
            return;
        }

        // 扣物品跟建立上架在同一 tick 內做完，中間沒有任何空窗。
        int have = have(offer);
        if (have < offerAmount) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red>你身上的 <white>" + Msg.itemName(offer)
                    + "</white> 只有 " + have + " 個，不夠上架 " + offerAmount + " 個。"));
            render();
            return;
        }

        // 上架費先收，收不到就整筆不做，物品原封不動留在背包。
        long fee = settings.listingFee(price);
        if (!plugin.auctions().chargeListingFee(player, price)) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red>上架費不夠：需要 <white>" + Fmt.coin(fee)
                    + "</white>，你只有 <white>" + Fmt.coin(plugin.economy().balance(player)) + "</white>。"));
            render();
            return;
        }

        ItemStack offerStack = offer.clone();
        offerStack.setAmount(offerAmount);

        plugin.auctions().takeFrom(player, offer, offerAmount);
        plugin.auctions().createListing(player, offerStack, price);
        plugin.auctions().save();
        plugin.endSellSession(player);

        plugin.success(player);
        player.sendMessage(Msg.prefixed("<green>上架成功！<white>" + Msg.itemName(offerStack) + " x" + offerAmount
                + "</white> <gray>開價</gray> <yellow>" + Fmt.coin(price) + "</yellow> 大沙幣"
                + "<gray>（已扣上架費 " + Fmt.coin(fee) + "）"));
        reopen(new BrowseGui(plugin, player, 0));
    }
}
