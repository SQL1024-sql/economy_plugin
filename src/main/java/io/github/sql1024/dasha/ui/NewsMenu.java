package io.github.sql1024.dasha.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.stock.market.Stock;
import io.github.sql1024.dasha.stock.news.NewsEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 財經新聞板。
 *
 * <p>Deliberately shows only what a headline <em>said</em>, never whether it was true — the
 * accuracy roll is the whole reason reading the news pays across many trades instead of paying
 * every time. Spent news is listed afterwards so players can judge the hit rate themselves.
 */
public final class NewsMenu extends Gui {

    private static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
    };
    private static final int SLOT_STATUS = 38;
    private static final int SLOT_INFO = 40;
    private static final int SLOT_BACK = 45;

    public NewsMenu(DashaEconomyPlugin plugin, Player player) {
        super(plugin, player);
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm("<yellow><bold>財經新聞</bold>"));
    }

    @Override
    public void render() {
        inventory.clear();

        List<NewsEvent> recent = plugin.news().recent();
        int index = 0;
        for (NewsEvent event : recent) {
            if (index >= SLOTS.length) {
                break;
            }
            inventory.setItem(SLOTS[index], newsIcon(event));
            index++;
        }
        if (recent.isEmpty()) {
            inventory.setItem(SLOTS[0], plugin.newsSettings().enabled()
                    ? icon(Material.MAP, "<gray>目前沒有任何新聞",
                            "<dark_gray>股價還是會隨機波動，",
                            "<dark_gray>只是沒有方向性的消息在推。")
                    : icon(Material.BARRIER, "<red>自動新聞已關閉",
                            "<gray>管理員把自動新聞停掉了，",
                            "<gray>目前股價只剩隨機波動。",
                            "<dark_gray>管理員仍可手動發布重大消息。"));
        }

        if (!plugin.newsSettings().enabled()) {
            inventory.setItem(SLOT_STATUS, icon(Material.BARRIER, "<red>自動新聞：已關閉",
                    "<gray>目前不會自動產生新聞，",
                    "<gray>股價只剩隨機波動。",
                    "<dark_gray>管理員仍可手動發布。"));
        }

        if (plugin.settings().priceSource()
                == io.github.sql1024.dasha.stock.market.PriceSource.REAL) {
            inventory.setItem(SLOT_INFO, icon(Material.BOOK, "<white>新聞與股價",
                    "<gray>本伺服器的股價<white>跟著真實股市</white>走。",
                    "",
                    "<yellow>所以這裡的新聞<red>不會</red>影響股價 —— ",
                    "<gray>遊戲內的消息推不動真實市場的報價。",
                    "<dark_gray>新聞在這個模式下只是氣氛。",
                    "",
                    "<gray>想知道股價為什麼動，去看真實世界的財經新聞。"));
            inventory.setItem(SLOT_BACK, HubMenu.backButton());
            fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
            return;
        }

        inventory.setItem(SLOT_INFO, icon(Material.BOOK, "<white>新聞怎麼影響股價",
                "<gray>新聞是<white>唯一</white>會推動股價的東西。",
                "<gray>玩家買多買少完全不影響價格。",
                "",
                "<gray>但新聞<red>不保證</red>是真的 —— 有 <white>"
                        + plugin.newsSettings().accuracyPercent() + "%</white> 機率說對方向，",
                "<gray>而且隨機波動仍可能蓋過它。",
                "<dark_gray>所以看新聞下注長期會贏，單筆還是會輸。",
                "",
                "<gray>發布前該股會<red>停牌 " + plugin.newsSettings().haltSeconds() + " 秒</red>，",
                "<gray>停牌期間股價已經開始走。",
                "<dark_gray>沒有這道，看到利多全押就是穩賺。"));

        inventory.setItem(SLOT_BACK, HubMenu.backButton());
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack newsIcon(NewsEvent event) {
        Stock stock = plugin.market().stock(event.symbol());
        boolean active = !event.expired();
        boolean halted = plugin.news().halted(event.symbol());

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gray>" + event.headline());
        lore.add("");
        lore.add((event.bullish() ? "<green>利多" : "<red>利空") + " <white>" + event.symbol());
        if (stock != null) {
            lore.add("<gray>現價　<white>" + Fmt.price(stock.price())
                    + "</white>　" + Fmt.changeTag(stock.dayChangePercent()));
        }
        lore.add("");
        if (halted) {
            lore.add("<red>⏸ 停牌中，還有 " + plugin.news().haltSecondsLeft(event.symbol()) + " 秒");
        } else if (active) {
            lore.add("<yellow>影響中　還剩 " + event.updatesLeft() + " 次更新");
        } else {
            lore.add("<dark_gray>已結束");
            // Only once the news has run its course is it fair to say whether it was true.
            lore.add(event.wasTrue() ? "<green>結果：消息屬實" : "<red>結果：假消息");
        }

        ItemStack item = new ItemStack(active
                ? (event.bullish() ? Material.LIME_DYE : Material.RED_DYE)
                : Material.GRAY_DYE);
        applyMeta(item, (event.bullish() ? "<green>📈 " : "<red>📉 ")
                + (stock == null ? event.symbol() : stock.displayName()), lore);
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getRawSlot() == SLOT_BACK) {
            plugin.click(player);
            reopen(new HubMenu(plugin, player));
        }
    }
}
