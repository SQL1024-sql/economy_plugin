package io.github.sql1024.dasha.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.TxnType;
import io.github.sql1024.dasha.guard.RecipeGuard;
import io.github.sql1024.dasha.sell.SellSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

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

    private static final int SLOT_NEWS_TOGGLE = 22;
    private static final int SLOT_NEWS = 28;
    private static final int SLOT_NEWS_AUDIT = 30;
    private static final int SLOT_SETPRICE = 32;
    private static final int SLOT_TICK = 34;
    private static final int SLOT_FEED = 20;

    private static final int SLOT_ORE_REFRESH = 38;
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
                "<dark_gray>完整報告會印在聊天欄（近 "
                        + plugin.tuning().reportDefaultDays() + " 天）",
                "<yellow>▶ 左鍵 " + plugin.tuning().reportDefaultDays()
                        + " 天　右鍵 " + plugin.tuning().reportLongDays() + " 天"));

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
                "<aqua>股市淨資金流 <dark_gray>(近 " + plugin.tuning().reportDefaultDays() + " 天)",
                "<gray>" + Fmt.pnlTag(stockNet),
                "",
                "<dark_gray>玩家從做市商手上淨賺的錢",
                "<dark_gray>＝系統憑空印出的錢。",
                stockNet > 0
                        ? "<red>持續為正且大於挖礦收入 → 該收緊了"
                        : "<green>目前沒有在印鈔"));

        drawNewsToggle();
        drawFeedStatus();

        inventory.setItem(SLOT_NEWS, plugin.news().newsAffectsMarket()
                ? icon(Material.PAPER, "<yellow>發布新聞",
                        "<gray>挑一檔股票發利多或利空。",
                        "",
                        "<red>會觸發停牌 " + plugin.newsSettings().haltSeconds() + " 秒，",
                        "<red>而且你自己 " + plugin.newsSettings().insiderLockMinutes()
                                + " 分鐘內不能交易那檔。",
                        "<dark_gray>全部寫入稽核紀錄。",
                        "", "<yellow>▶ 點擊選股票")
                : icon(Material.GRAY_DYE, "<gray>發布新聞 <dark_gray>（純廣播）",
                        "<gray>股價跟著真實市場，這則消息",
                        "<red>不會影響任何價格</red><gray>，只是廣播文字。",
                        "",
                        "<dark_gray>玩家若當真去下單，只會白付手續費。",
                        "", "<yellow>▶ 仍要發布就點擊"));

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

        drawOreRefresh();

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

    /**
     * The automatic-news kill switch.
     *
     * <p>Switching it off stops new headlines from rolling; news already running finishes its
     * course rather than being yanked, because a stock frozen mid-move with no explanation is
     * worse for players than letting the last story play out.
     */
    /**
     * Ore buy-back health, and the button that skips the wait.
     *
     * <p>The pool drains on its own, so this is never <em>needed</em> — it exists for the cases
     * where waiting is the wrong answer: a dupe or a test run flooded a pool with material nobody
     * actually mined, and the honest players should not spend an hour selling at the floor for it.
     */
    private void drawOreRefresh() {
        int discounted = 0;
        long longestWait = 0L;
        String worst = null;
        double worstFactor = 1.0;

        for (SellSettings.Item item : plugin.sellSettings().items().values()) {
            double factor = plugin.oreSell().discountFactor(item);
            if (factor >= 1.0) {
                continue;
            }
            discounted++;
            long wait = plugin.oreSell().secondsToFullPrice(item);
            longestWait = Math.max(longestWait, wait);
            if (factor < worstFactor) {
                worstFactor = factor;
                worst = item.material().name();
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gray>打折中　<white>" + discounted + "</white> / "
                + plugin.sellSettings().items().size() + " 種");
        if (worst != null) {
            lore.add("<gray>最慘的　<white>" + worst + "</white> <red>"
                    + Math.round(worstFactor * 100.0) + "%</red> 價");
            lore.add("<gray>全部回到頂點還要　<white>" + Fmt.duration(longestWait));
        } else {
            lore.add("<green>目前每一種都是頂點價格。");
        }
        lore.add("");
        lore.add("<gray>自然回復時間　<white>"
                + Fmt.duration(Math.round(plugin.sellSettings().recoveryHours() * 3600.0))
                + "</white> <dark_gray>(sell.yml recovery-hours)");
        lore.add("");
        lore.add("<red>點下去會把所有供給池歸零，");
        lore.add("<red>收購價立刻回到頂點。");
        lore.add("<dark_gray>不會動到玩家的每日額度。");
        lore.add("");
        lore.add("<yellow>▶ 點擊刷新");

        ItemStack item = new ItemStack(discounted > 0 ? Material.RAW_IRON : Material.IRON_BLOCK);
        applyMeta(item, "<aqua><bold>手動刷新收購價", lore);
        inventory.setItem(SLOT_ORE_REFRESH, item);
    }

    private void drawNewsToggle() {
        if (!plugin.news().newsAffectsMarket()) {
            org.bukkit.inventory.ItemStack inert =
                    new org.bukkit.inventory.ItemStack(Material.STRUCTURE_VOID);
            applyMeta(inert, "<dark_gray><bold>財經新聞：不適用", java.util.List.of(
                    "<dark_gray>━━━━━━━━━━━━━━━",
                    "<gray>價格來源是 <white>" + plugin.settings().priceSource() + "</white>，",
                    "<gray>股價完全跟著真實市場走。",
                    "",
                    "<gray>新聞推不動真實報價，所以整套機制",
                    "<gray>已經停用：不自動發布、不停牌、不鎖內線。",
                    "",
                    "<yellow>想讓新聞真的會推動股價，",
                    "<yellow>把 stocks.yml 的 price-source 改成 hybrid。"));
            inventory.setItem(SLOT_NEWS_TOGGLE, inert);
            return;
        }

        boolean on = plugin.newsSettings().enabled();
        int active = plugin.news().active().size();

        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add(on
                ? "<green>● 開啟中 <dark_gray>— 每次股價更新有 "
                        + plugin.newsSettings().chancePercent() + "% 機率發新聞"
                : "<red>● 已關閉 <dark_gray>— 不會再自動產生新聞");
        lore.add("");
        lore.add("<gray>進行中的新聞：<white>" + active + "</white> 則");
        if (!on && active > 0) {
            lore.add("<dark_gray>已在跑的會自己跑完，不會硬中斷。");
        }
        lore.add("");
        lore.add("<gray>關掉之後股價只剩隨機波動，");
        lore.add("<gray>沒有任何方向性的消息在推。");
        lore.add("<yellow>你仍然可以手動發新聞。");
        lore.add("");
        lore.add("<dark_gray>寫入 stocks.yml，重開伺服器後仍然有效。");
        lore.add(on ? "<red>▶ 點擊關閉" : "<green>▶ 點擊開啟");

        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(
                on ? Material.LIME_DYE : Material.GRAY_DYE);
        applyMeta(item, on ? "<green><bold>自動財經新聞：開" : "<red><bold>自動財經新聞：關", lore);
        inventory.setItem(SLOT_NEWS_TOGGLE, item);
    }

    /** Live price feed health: source, freshness, and anything that failed to fetch. */
    private void drawFeedStatus() {
        var settings = plugin.settings();
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gray>價格來源　<white>" + settings.priceSource());

        if (!settings.priceSource().needsFeed()) {
            lore.add("");
            lore.add("<gray>目前是純模擬，不連外。");
            lore.add("<dark_gray>改 stocks.yml 的 price-source 可切換。");
            org.bukkit.inventory.ItemStack off = new org.bukkit.inventory.ItemStack(Material.MAP);
            applyMeta(off, "<gray>即時報價：未使用", lore);
            inventory.setItem(SLOT_FEED, off);
            return;
        }

        var quotes = plugin.quotes();
        long ago = quotes.lastRunAt() == 0L ? -1L
                : (System.currentTimeMillis() - quotes.lastRunAt()) / 1000L;
        boolean healthy = quotes.lastRunAt() != 0L && quotes.lastFailed() == 0;

        lore.add("<gray>提供者　<white>" + quotes.provider().name());
        lore.add("<gray>更新間隔　<white>" + settings.quoteIntervalSeconds() + "</white> 秒");
        lore.add("");
        if (ago < 0L) {
            lore.add("<yellow>還沒完成第一次抓取");
        } else {
            lore.add("<gray>上次更新　<white>" + ago + "</white> 秒前");
            lore.add("<gray>成功 <green>" + quotes.lastOk() + "</green>　失敗 "
                    + (quotes.lastFailed() > 0 ? "<red>" : "<gray>") + quotes.lastFailed());
        }

        var failures = quotes.failures();
        if (!failures.isEmpty()) {
            lore.add("");
            lore.add("<red>抓不到的代號：");
            failures.entrySet().stream().limit(5).forEach(e ->
                    lore.add("<dark_gray>  " + e.getKey() + " — " + e.getValue()));
            lore.add("<dark_gray>失敗的會沿用上一次的價格，不會歸零。");
        }
        lore.add("");
        lore.add("<yellow>▶ 點擊立刻重抓一次");

        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(
                healthy ? Material.RECOVERY_COMPASS : Material.COMPASS);
        applyMeta(item, healthy ? "<green><bold>即時報價：正常" : "<yellow><bold>即時報價：注意", lore);
        inventory.setItem(SLOT_FEED, item);
    }

    /** Coins players took off the market maker in the last week, net of fees. */
    private long stockNetFlow() {
        Map<TxnType, Long> totals =
                plugin.database().ledgerTotals(System.currentTimeMillis()
                        - (long) plugin.tuning().reportDefaultDays() * 86_400_000L);
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
                int days = event.getClick().isRightClick()
                        ? plugin.tuning().reportLongDays()
                        : plugin.tuning().reportDefaultDays();
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
            case SLOT_NEWS_TOGGLE -> {
                boolean next = !plugin.newsSettings().enabled();
                if (plugin.setNewsEnabled(next)) {
                    plugin.success(player);
                    player.sendMessage(Msg.prefixed(next
                            ? "<green>自動財經新聞已<white>開啟</white>。"
                            : "<yellow>自動財經新聞已<white>關閉</white>，手動發布仍然可用。"));
                } else {
                    plugin.fail(player);
                    player.sendMessage(Msg.prefixed("<red>寫入 stocks.yml 失敗，詳見主控台。"));
                }
                render();
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
            case SLOT_FEED -> {
                plugin.click(player);
                if (!plugin.settings().priceSource().needsFeed()) {
                    player.sendMessage(Msg.prefixed("<gray>目前是純模擬模式，沒有即時報價可抓。"));
                    return;
                }
                player.sendMessage(Msg.prefixed("<yellow>開始重抓即時報價…"));
                plugin.quotes().refreshAsync(() -> {
                    player.sendMessage(Msg.prefixed("<green>報價更新完成：成功 <white>"
                            + plugin.quotes().lastOk() + "</white>　失敗 <white>"
                            + plugin.quotes().lastFailed() + "</white>"));
                    render();
                });
            }
            case SLOT_TICK -> {
                plugin.click(player);
                plugin.market().tick();
                player.sendMessage(Msg.prefixed("<green>已手動更新股價。"));
                render();
            }
            case SLOT_ORE_REFRESH -> {
                plugin.click(player);
                int cleared = plugin.oreSell().resetPools();
                plugin.success(player);
                player.sendMessage(Msg.prefixed(cleared > 0
                        ? "<green>已刷新收購價：<white>" + cleared
                                + "</white> 種礦物的供給池歸零，價格回到頂點。"
                        : "<gray>收購價本來就都在頂點，沒有東西需要刷新。"));
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
        List<Object[]> rows = plugin.database().recentNewsAudit(plugin.tuning().newsAuditEntries());
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
