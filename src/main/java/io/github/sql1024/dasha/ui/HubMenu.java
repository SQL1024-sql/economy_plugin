package io.github.sql1024.dasha.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.auction.gui.BrowseGui;
import io.github.sql1024.dasha.auction.gui.CollectGui;
import io.github.sql1024.dasha.auction.gui.MyListingsGui;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.PayCommand;
import io.github.sql1024.dasha.core.PaySettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 大沙幣總管 — the one door into the whole economy.
 *
 * <p>Five separate commands meant five things to remember. This is the single entry point: the
 * player's balance and net worth up top, every place money can move below it, and the admin panel
 * behind a permission check. Every sub-menu carries a button back here, so nobody has to type a
 * command again once they know {@code /eco}.
 */
public final class HubMenu extends Gui {

    private static final int SLOT_PROFILE = 4;

    private static final int SLOT_STOCK = 19;
    private static final int SLOT_AUCTION = 21;
    private static final int SLOT_STORE = 23;
    private static final int SLOT_SELL = 25;

    private static final int SLOT_PORTFOLIO = 28;
    private static final int SLOT_LISTINGS = 30;
    private static final int SLOT_COLLECT = 32;
    private static final int SLOT_NEWS = 34;

    private static final int SLOT_PAY = 38;
    private static final int SLOT_TOP = 42;
    private static final int SLOT_ADMIN = 49;
    private static final int SLOT_HELP = 45;
    private static final int SLOT_CLOSE = 53;

    public HubMenu(DashaEconomyPlugin plugin, Player player) {
        super(plugin, player);
        this.inventory = Bukkit.createInventory(this, 54,
                Msg.mm("<gradient:#f6d365:#fda085><bold>大沙幣總管</bold></gradient>"));
    }

    @Override
    public void render() {
        inventory.clear();

        drawProfile();
        drawEarn();
        drawSpend();
        drawExtras();

        if (player.hasPermission("dasha.admin")) {
            inventory.setItem(SLOT_ADMIN, icon(Material.COMMAND_BLOCK, "<dark_red><bold>管理面板",
                    "<gray>經濟報告、反向通道檢查、",
                    "<gray>發布新聞、玩家錢包管理。",
                    "", "<yellow>▶ 點擊進入"));
        }
        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER, "<red>關閉"));

        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    /** The player's own head, carrying every number that describes their position. */
    private void drawProfile() {
        long balance = plugin.economy().balance(player);
        long holdings = plugin.portfolios().marketValue(player.getUniqueId());
        long quotaLeft = plugin.oreSell().quotaLeft(player.getUniqueId());

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gray>餘額　<yellow>" + Fmt.coin(balance));
        lore.add("<gray>持股市值　<aqua>" + Fmt.coin(holdings));
        lore.add("<gray>總資產　<white>" + Fmt.coin(balance + holdings));
        lore.add("");
        lore.add("<gray>今日收購額度　<white>"
                + (quotaLeft == Long.MAX_VALUE ? "無上限" : Fmt.coin(quotaLeft)));
        lore.add("<gray>上架中　<white>" + plugin.auctions().countOf(player.getUniqueId())
                + "</white> / " + plugin.auctionSettings().maxListingsPerPlayer() + " 筆");

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(player));
        applyMeta(head, "<gradient:#f6d365:#fda085>" + player.getName(), lore);
        inventory.setItem(SLOT_PROFILE, head);
    }

    /** 賺錢的三條路。 */
    private void drawEarn() {
        inventory.setItem(SLOT_SELL, icon(Material.DIAMOND_PICKAXE, "<green><bold>賣礦物",
                "<dark_gray>唯一的錢從哪來",
                "<gray>把挖到的礦賣給系統換大沙幣。",
                "<gray>價格會隨全服賣出量下滑，",
                "<gray>停手一陣子會自己回升。",
                "", "<yellow>▶ 點擊開啟收購站"));

        long value = plugin.portfolios().marketValue(player.getUniqueId());
        inventory.setItem(SLOT_STOCK, icon(Material.EMERALD, "<aqua><bold>股市",
                "<gray>買賣伺服器固定的股票。",
                "<gray>股價只被<white>新聞</white>推動。",
                "",
                "<gray>你的持股市值　<white>" + Fmt.coin(value),
                "", "<yellow>▶ 點擊看盤"));

        inventory.setItem(SLOT_AUCTION, icon(Material.CHEST, "<gold><bold>拍賣行",
                "<gray>跟其他玩家買賣東西。",
                "<gray>目前有 <white>" + plugin.auctions().all().size() + "</white> 筆上架。",
                "",
                "<dark_gray>上架費 " + plugin.auctionSettings().listingFeePercent()
                        + "%　成交抽成 " + plugin.auctionSettings().saleCutPercent() + "%",
                "", "<yellow>▶ 點擊瀏覽"));
    }

    /** 花錢的地方。 */
    private void drawSpend() {
        int items = plugin.storeSettings().allEntries().size();
        inventory.setItem(SLOT_STORE, icon(Material.BRICKS, "<light_purple><bold>伺服器商店",
                "<gray>買建築材料。<red>只賣不收。",
                "<gray>共 <white>" + items + "</white> 項商品，"
                        + plugin.storeSettings().categories().size() + " 個分類。",
                "", "<yellow>▶ 點擊選購"));
    }

    private void drawExtras() {
        inventory.setItem(SLOT_PORTFOLIO, icon(Material.WRITABLE_BOOK, "<aqua>我的持股",
                "<gray>看每檔的成本、現值與損益。",
                "", "<yellow>▶ 點擊查看"));

        inventory.setItem(SLOT_LISTINGS, icon(Material.BOOKSHELF, "<gold>我的上架",
                "<gray>目前 <white>" + plugin.auctions().countOf(player.getUniqueId()) + "</white> 筆。",
                "<gray>點進去可以下架取回。",
                "", "<yellow>▶ 點擊查看"));

        int box = plugin.auctions().collectSize(player.getUniqueId());
        inventory.setItem(SLOT_COLLECT, icon(box > 0 ? Material.ENDER_CHEST : Material.BARREL,
                (box > 0 ? "<green>" : "<gray>") + "取貨箱",
                "<gray>待領取 <white>" + box + "</white> 件",
                "<dark_gray>離線時賣掉的、過期退回的都在這",
                "", "<yellow>▶ 點擊領取"));

        int news = plugin.news().active().size();
        inventory.setItem(SLOT_NEWS, icon(news > 0 ? Material.PAPER : Material.MAP,
                "<yellow>財經新聞",
                "<gray>進行中 <white>" + news + "</white> 則",
                "<dark_gray>新聞是唯一會影響股價的東西",
                "", "<yellow>▶ 點擊查看"));

        PaySettings pay = plugin.paySettings();
        List<String> payLore = new ArrayList<>();
        if (pay.enabled()) {
            payLore.add("<gray>把大沙幣直接轉給其他玩家。");
            payLore.add("<gray>手續費 <white>" + pay.feePercent() + "%</white>"
                    + "，對方拿到的是扣完之後的。");
            payLore.add("");
            payLore.add("<gray>單筆上限　<white>" + Fmt.coin(pay.maxAmount()));
            long left = plugin.pay().dailyLeft(player.getUniqueId());
            if (left != Long.MAX_VALUE) {
                payLore.add("<gray>今日還能轉　<white>" + Fmt.coin(left));
            }
            payLore.add("");
            payLore.add("<red>轉出去就拿不回來。");
            payLore.add("<yellow>▶ 點擊轉帳　<dark_gray>也可以打 /pay");
        } else {
            payLore.add("<red>這個伺服器沒有開放玩家轉帳。");
        }
        ItemStack payIcon = new ItemStack(pay.enabled() ? Material.SUNFLOWER : Material.GRAY_DYE);
        applyMeta(payIcon, (pay.enabled() ? "<yellow>" : "<gray>") + "<bold>轉帳給玩家", payLore);
        inventory.setItem(SLOT_PAY, payIcon);

        inventory.setItem(SLOT_TOP, icon(Material.GOLD_BLOCK, "<gold>富豪榜",
                "<gray>誰手上大沙幣最多，",
                "<gray>各佔全服總量幾 %。",
                "", "<yellow>▶ 點擊查看"));

        inventory.setItem(SLOT_HELP, icon(Material.BOOK, "<white>這套經濟怎麼運作",
                "<gray>大沙幣只有兩個地方會憑空出現：",
                "<gray>  • 賣礦物給系統",
                "<gray>  • 股市贏過做市商",
                "<gray>消失的地方：商店、各種手續費。",
                "",
                "<gray>玩家之間的交易與轉帳是<white>零和</white>的，",
                "<gray>錢只是換人拿，總量不變。",
                "", "<dark_gray>沒有簽到、沒有殺怪掉錢"));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        switch (slot) {
            case SLOT_STOCK -> {
                if (require("dasha.stock")) {
                    plugin.click(player);
                    plugin.menus().openMarket(player);
                }
            }
            case SLOT_PORTFOLIO -> {
                if (require("dasha.stock")) {
                    plugin.click(player);
                    plugin.menus().openPortfolio(player);
                }
            }
            case SLOT_AUCTION -> {
                if (require("dasha.market")) {
                    plugin.click(player);
                    reopen(new BrowseGui(plugin, player, 0));
                }
            }
            case SLOT_LISTINGS -> {
                if (require("dasha.market")) {
                    plugin.click(player);
                    reopen(new MyListingsGui(plugin, player, 0));
                }
            }
            case SLOT_COLLECT -> {
                if (require("dasha.market")) {
                    plugin.click(player);
                    reopen(new CollectGui(plugin, player, 0));
                }
            }
            case SLOT_STORE -> {
                if (require("dasha.store")) {
                    plugin.click(player);
                    reopen(new StoreCategoryMenu(plugin, player));
                }
            }
            case SLOT_SELL -> {
                if (require("dasha.sell")) {
                    plugin.click(player);
                    reopen(new SellMenu(plugin, player));
                }
            }
            case SLOT_NEWS -> {
                plugin.click(player);
                reopen(new NewsMenu(plugin, player));
            }
            case SLOT_PAY -> {
                if (require("dasha.pay")) {
                    plugin.click(player);
                    askPayTarget();
                }
            }
            case SLOT_TOP -> {
                if (require("dasha.top")) {
                    plugin.click(player);
                    reopen(new RichListMenu(plugin, player));
                }
            }
            case SLOT_ADMIN -> {
                if (player.hasPermission("dasha.admin")) {
                    plugin.click(player);
                    reopen(new AdminMenu(plugin, player));
                }
            }
            case SLOT_CLOSE -> {
                plugin.click(player);
                later(player::closeInventory);
            }
            default -> {
            }
        }
    }

    /**
     * Transfer flow for players who never type commands: recipient first, then amount, both in
     * chat. It deliberately ends in the same {@link PayCommand#execute} the command uses, so the
     * fee, the limits and the large-amount confirmation behave identically either way.
     */
    private void askPayTarget() {
        if (!plugin.paySettings().enabled()) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red>這個伺服器沒有開放玩家轉帳。"));
            return;
        }
        player.closeInventory();
        player.sendMessage(Msg.prefixed("<yellow>要轉給誰？請輸入玩家名字："));
        player.sendMessage(Msg.prefixed("<gray>輸入 <white>取消</white> 放棄。"));
        plugin.promptChat(player, input -> {
            String name = input.trim();
            if (cancelled(name)) {
                open();
                return;
            }
            java.util.UUID target = plugin.lookupPlayer(name);
            if (target == null) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>找不到玩家 <white>" + name
                        + "</white>。對方必須上過這個伺服器。"));
                open();
                return;
            }
            if (target.equals(player.getUniqueId())) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>不能轉帳給自己。"));
                open();
                return;
            }
            plugin.click(player);
            askPayAmount(target, plugin.playerName(target));
        });
    }

    private void askPayAmount(java.util.UUID target, String targetName) {
        PaySettings settings = plugin.paySettings();
        player.sendMessage(Msg.prefixed("<yellow>要轉多少給 <white>" + targetName + "</white>？"));
        player.sendMessage(Msg.prefixed("<gray>範圍 <white>" + Fmt.coin(settings.minAmount())
                + "</white> ~ <white>" + Fmt.coin(settings.maxAmount())
                + "</white>，手續費 <white>" + settings.feePercent() + "%</white>。"));
        plugin.promptChat(player, input -> {
            String text = input.trim();
            if (cancelled(text)) {
                open();
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(text.replace(",", "").replace("_", ""));
            } catch (NumberFormatException e) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red><white>" + text + "</white> 不是有效的金額。"));
                askPayAmount(target, targetName);
                return;
            }
            if (amount <= 0L) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>金額要大於 0。"));
                askPayAmount(target, targetName);
                return;
            }
            new PayCommand(plugin).execute(player, target, targetName, amount, false);
        });
    }

    private static boolean cancelled(String input) {
        return input.equalsIgnoreCase("取消") || input.equalsIgnoreCase("cancel");
    }

    private boolean require(String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        plugin.fail(player);
        plugin.lang().send(player, "no-permission");
        return false;
    }

    /** The button every sub-menu puts in its free slot to get back here. */
    public static ItemStack backButton() {
        return icon(Material.NETHER_STAR, "<gradient:#f6d365:#fda085>返回主選單",
                "<gray>回到大沙幣總管");
    }
}
