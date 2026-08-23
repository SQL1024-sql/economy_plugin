package io.github.sql1024.dasha.ui;

import java.util.List;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.stats.EcoStats;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 富豪榜。
 *
 * <p>Ranked on net worth — cash plus holdings at market value — because ranking on cash alone
 * quietly punished anyone who was invested at the moment somebody looked.
 *
 * <p>The share column is the point of this screen for an operator: if one player holds most of
 * the wealth, that is not skill showing through, it is a channel leaking.
 */
public final class RichListMenu extends Gui {

    private static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
    };
    private static final int SLOT_SUPPLY = 40;
    private static final int SLOT_BACK = 45;

    public RichListMenu(DashaEconomyPlugin plugin, Player player) {
        super(plugin, player);
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm("<gold><bold>大沙幣富豪榜</bold>"));
    }

    @Override
    public void render() {
        inventory.clear();

        List<EcoStats.Wealth> ranked = plugin.stats().ranking();
        long total = Math.max(1L, plugin.stats().totalWealth());

        int index = 0;
        for (EcoStats.Wealth wealth : ranked) {
            if (index >= SLOTS.length) {
                break;
            }
            inventory.setItem(SLOTS[index], headFor(index + 1, wealth, total));
            index++;
        }
        if (index == 0) {
            inventory.setItem(SLOTS[0], icon(Material.BARRIER, "<gray>還沒有人持有大沙幣"));
        }

        long cash = plugin.economy().totalSupply();
        long held = Math.max(0L, plugin.stats().totalWealth() - cash);
        int accounts = plugin.economy().accountCount();
        inventory.setItem(SLOT_SUPPLY, icon(Material.GOLD_BLOCK, "<gold>全服資產總量",
                "<gray>現金流通　<yellow>" + Fmt.coin(cash),
                "<gray>持股市值　<aqua>" + Fmt.coin(held),
                "<gray>合計　<white>" + Fmt.coin(cash + held),
                "",
                "<gray>帳戶　<white>" + accounts + "</white> 個",
                "<gray>人均　<white>" + Fmt.coin(accounts == 0 ? 0L : (cash + held) / accounts),
                "",
                "<dark_gray>持股市值會隨股價浮動，所以這個總量",
                "<dark_gray>不等於系統發行出去的大沙幣數量。",
                "",
                "<dark_gray>如果有人佔比特別高，那通常不是他很會玩，",
                "<dark_gray>是某條管道漏了。"));

        inventory.setItem(SLOT_BACK, HubMenu.backButton());
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack headFor(int rank, EcoStats.Wealth wealth, long total) {
        UUID uuid = wealth.uuid();
        double share = wealth.total() * 100.0 / total;
        String name = plugin.playerName(uuid);

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));

        String medal = switch (rank) {
            case 1 -> "<gold>🥇 ";
            case 2 -> "<white>🥈 ";
            case 3 -> "<#cd7f32>🥉 ";
            default -> "<gray>" + rank + ". ";
        };
        applyMeta(head, medal + "<white>" + name, List.of(
                "<dark_gray>━━━━━━━━━━━━━━━",
                "<gray>總資產　<white>" + Fmt.coin(wealth.total()),
                "",
                "<gray>現金　<yellow>" + Fmt.coin(wealth.cash()),
                "<gray>持股市值　<aqua>" + Fmt.coin(wealth.stocks()),
                "",
                "<gray>佔全服　<white>" + Fmt.price(share) + "%"));
        return head;
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
