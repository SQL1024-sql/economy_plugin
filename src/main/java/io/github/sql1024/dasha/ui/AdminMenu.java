package io.github.sql1024.dasha.ui;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.TxnType;
import io.github.sql1024.dasha.guard.RecipeGuard;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * 管理面板 — every admin command reachable without typing one.
 *
 * <p>Reports that are genuinely long text (the economy report, the audit trail) close the menu and
 * print to chat rather than being squeezed into item lore; everything else acts in place. Anything
 * needing a free-typed value — an amount, a player name, a headline — hands off to a chat prompt
 * and comes straight back here.
 */
public final class AdminMenu extends Gui {

    private static final int SLOT_STATS = 10;
    private static final int SLOT_AUDIT = 12;
    private static final int SLOT_TOP = 14;
    private static final int SLOT_LEDGER = 16;

    private static final int SLOT_NEWS = 28;
    private static final int SLOT_NEWS_AUDIT = 30;
    private static final int SLOT_SETPRICE = 32;
    private static final int SLOT_TICK = 34;

    private static final int SLOT_WALLET = 40;
    private static final int SLOT_RELOAD = 49;
    private static final int SLOT_BACK = 45;

    public AdminMenu(DashaEconomyPlugin plugin, Player player) {
        super(plugin, player);
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm("<dark_red><bold>大沙幣管理面板</bold>"));
    }

    @Override
    public void render() {
        inventory.clear();

        long supply = plugin.economy().totalSupply();
        inventory.setItem(SLOT_STATS, icon(Material.WRITTEN_BOOK, "<gold>經濟報告",
                "<gray>流通總量　<yellow>" + Fmt.coin(supply),
                "<gray>本次開機產出　<green>+" + Fmt.coin(plugin.economy().mintedThisSession()),
                "<gray>本次開機消耗　<red>-" + Fmt.coin(plugin.economy().burnedThisSession()),
                "",
                "<dark_gray>完整報告會印在聊天欄（近 7 天）",
                "<yellow>▶ 左鍵 7 天　右鍵 30 天"));

        inventory.setItem(SLOT_AUDIT, icon(Material.CRAFTING_TABLE, "<gold>反向通道檢查",
                "<gray>掃描商店與收購清單之間",
                "<gray>有沒有可以無限套利的迴圈。",
                "",
                "<dark_gray>走 Minecraft 真實的合成表反查，",
                "<dark_gray>每次改完 store.yml 或 sell.yml 都該跑一次。",
                "", "<yellow>▶ 點擊執行"));

        inventory.setItem(SLOT_TOP, icon(Material.GOLD_BLOCK, "<gold>富豪榜",
                "<gray>誰手上錢最多、佔全服幾 %。",
                "", "<yellow>▶ 點擊查看"));

        long stockNet = stockNetFlow();
        inventory.setItem(SLOT_LEDGER, icon(stockNet > 0 ? Material.REDSTONE_TORCH : Material.LEVER,
                "<aqua>股市淨資金流 <dark_gray>(近 7 天)",
                "<gray>" + Fmt.pnlTag(stockNet),
                "",
                "<dark_gray>玩家從做市商手上淨賺的錢",
                "<dark_gray>＝系統憑空印出的錢。",
                stockNet > 0
                        ? "<red>持續為正且大於挖礦收入 → 該收緊了"
                        : "<green>目前沒有在印鈔"));

        inventory.setItem(SLOT_NEWS, icon(Material.PAPER, "<yellow>發布新聞",
                "<gray>挑一檔股票發利多或利空。",
                "",
                "<red>會觸發停牌 " + plugin.newsSettings().haltSeconds() + " 秒，",
                "<red>而且你自己 " + plugin.newsSettings().insiderLockMinutes()
                        + " 分鐘內不能交易那檔。",
                "<dark_gray>全部寫入稽核紀錄。",
                "", "<yellow>▶ 點擊選股票"));

        inventory.setItem(SLOT_NEWS_AUDIT, icon(Material.BOOK, "<yellow>新聞稽核紀錄",
                "<gray>誰、何時、對哪檔、什麼方向。",
                "<dark_gray>防內線交易用的。",
                "", "<yellow>▶ 點擊查看（印在聊天欄）"));

        inventory.setItem(SLOT_SETPRICE, icon(Material.COMPARATOR, "<yellow>手動改股價",
                "<gray>直接把某檔股票設成指定價格。",
                "<dark_gray>會記進歷史，走勢圖看得到。",
                "", "<yellow>▶ 點擊選股票"));

        inventory.setItem(SLOT_TICK, icon(Material.CLOCK, "<yellow>手動更新股價",
                "<gray>立刻跑一次股價更新，",
                "<gray>不用等 " + plugin.settings().updateIntervalSeconds() + " 秒。",
                "", "<yellow>▶ 點擊執行"));

        inventory.setItem(SLOT_WALLET, icon(Material.PLAYER_HEAD, "<green>玩家錢包管理",
                "<gray>查詢、發放、扣除、直接設定餘額。",
                "",
                "<dark_gray>所有調整都標記成 ADMIN，",
                "<dark_gray>不會混進產出／消耗的統計。",
                "", "<yellow>▶ 點擊輸入玩家名稱"));

        inventory.setItem(SLOT_RELOAD, icon(Material.LIME_CONCRETE, "<green>重新載入設定",
                "<gray>重讀全部 5 個設定檔，",
                "<gray>並重跑一次反向通道檢查。",
                "", "<yellow>▶ 點擊執行"));

        inventory.setItem(SLOT_BACK, HubMenu.backButton());
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    /** Coins players took off the market maker in the last week, net of fees. */
    private long stockNetFlow() {
        Map<TxnType, Long> totals =
                plugin.database().ledgerTotals(System.currentTimeMillis() - 7L * 86_400_000L);
        return totals.getOrDefault(TxnType.STOCK_SELL, 0L)
                - totals.getOrDefault(TxnType.STOCK_BUY, 0L)
                - totals.getOrDefault(TxnType.STOCK_FEE, 0L);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (!player.hasPermission("dasha.admin")) {
            plugin.lang().send(player, "no-permission");
            later(player::closeInventory);
            return;
        }

        switch (slot) {
            case SLOT_BACK -> {
                plugin.click(player);
                reopen(new HubMenu(plugin, player));
            }
            case SLOT_STATS -> {
                plugin.click(player);
                int days = event.getClick().isRightClick() ? 30 : 7;
                later(() -> {
                    player.closeInventory();
                    for (Component line : plugin.stats().report(days)) {
                        player.sendMessage(line);
                    }
                });
            }
            case SLOT_AUDIT -> {
                plugin.click(player);
                runGuard();
            }
            case SLOT_TOP -> {
                plugin.click(player);
                reopen(new RichListMenu(plugin, player));
            }
            case SLOT_NEWS -> {
                plugin.click(player);
                reopen(new StockPickMenu(plugin, player, StockPickMenu.Purpose.NEWS));
            }
            case SLOT_SETPRICE -> {
                plugin.click(player);
                reopen(new StockPickMenu(plugin, player, StockPickMenu.Purpose.SET_PRICE));
            }
            case SLOT_NEWS_AUDIT -> {
                plugin.click(player);
                showNewsAudit();
            }
            case SLOT_TICK -> {
                plugin.click(player);
                plugin.market().tick();
                player.sendMessage(Msg.prefixed("<green>已手動更新股價。"));
                render();
            }
            case SLOT_WALLET -> {
                plugin.click(player);
                askPlayer();
            }
            case SLOT_RELOAD -> {
                plugin.click(player);
                plugin.reloadEverything();
                player.sendMessage(Msg.prefixed("<green>設定已重新載入（共 <white>"
                        + plugin.market().size() + "</white> 檔股票）。"));
                render();
            }
            default -> {
            }
        }
    }

    private void runGuard() {
        List<RecipeGuard.Finding> findings = plugin.guard().run();
        if (findings.isEmpty()) {
            plugin.success(player);
            player.sendMessage(Msg.prefixed("<green>✔ 沒有找到可套利的迴圈。"));
            player.sendMessage(Msg.prefixed("<gray>商店賣的每樣東西都比它能拆出來的礦物值錢。"));
            return;
        }
        plugin.fail(player);
        later(() -> {
            player.closeInventory();
            player.sendMessage(Msg.prefixed("<red>⚠ 找到 <white>" + findings.size()
                    + "</white> 個可能的無限印鈔迴圈："));
            for (RecipeGuard.Finding finding : findings) {
                player.sendMessage(Msg.mm("<gray>• <white>" + finding.storeItem().name() + "</white>"
                        + "　商店 <yellow>" + Fmt.coin(finding.storePrice()) + "</yellow>"
                        + " <dark_gray>vs</dark_gray> " + finding.route() + " <red>"
                        + Fmt.coin(finding.scrapValue()) + "</red>"));
                player.sendMessage(Msg.mm("  <dark_gray>建議至少定價 " + Fmt.coin(finding.suggested())));
            }
        });
    }

    private void showNewsAudit() {
        List<Object[]> rows = plugin.database().recentNewsAudit(20);
        later(() -> {
            player.closeInventory();
            player.sendMessage(Msg.mm("<dark_gray>━━━━━━ <gold>新聞稽核紀錄</gold> <dark_gray>━━━━━━"));
            if (rows.isEmpty()) {
                player.sendMessage(Msg.mm("<gray>目前沒有任何人工發布的新聞。"));
                return;
            }
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm");
            for (Object[] row : rows) {
                int direction = (Integer) row[2];
                player.sendMessage(Msg.mm("<dark_gray>"
                        + format.format(new java.util.Date((Long) row[5]))
                        + "</dark_gray> <white>" + row[0] + "</white> → <white>" + row[1] + "</white> "
                        + (direction > 0 ? "<green>利多" : "<red>利空")
                        + " <gray>強度 " + row[3]));
            }
        });
    }

    /** Asks for a player name in chat, then opens that player's wallet screen. */
    private void askPlayer() {
        player.closeInventory();
        player.sendMessage(Msg.prefixed("<yellow>請在聊天欄輸入玩家名稱（輸入 <white>取消</white> 放棄）："));
        plugin.promptChat(player, input -> {
            String text = input.trim();
            if (text.equalsIgnoreCase("取消") || text.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            UUID target = plugin.lookupPlayer(text);
            if (target == null) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>找不到玩家 <white>" + text + "</white>。"));
                open();
                return;
            }
            new WalletMenu(plugin, player, target).open();
        });
    }
}
