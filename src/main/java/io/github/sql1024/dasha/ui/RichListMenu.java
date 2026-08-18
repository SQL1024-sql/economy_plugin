package io.github.sql1024.dasha.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
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
 * <p>The share-of-supply column is the point of this screen for an operator: if one player holds
 * most of the money, that is not skill showing through, it is a channel leaking.
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

        List<Map.Entry<UUID, Long>> sorted =
                new ArrayList<>(plugin.economy().allBalances().entrySet());
        sorted.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));

        long supply = Math.max(1L, plugin.economy().totalSupply());
        int index = 0;
        for (Map.Entry<UUID, Long> entry : sorted) {
            if (index >= SLOTS.length || entry.getValue() <= 0L) {
                break;
            }
            inventory.setItem(SLOTS[index], headFor(index + 1, entry.getKey(), entry.getValue(), supply));
            index++;
        }
        if (index == 0) {
            inventory.setItem(SLOTS[0], icon(Material.BARRIER, "<gray>還沒有人持有大沙幣"));
        }

        inventory.setItem(SLOT_SUPPLY, icon(Material.GOLD_BLOCK, "<gold>全服流通總量",
                "<gray>總量　<yellow>" + Fmt.coin(plugin.economy().totalSupply()),
                "<gray>帳戶　<white>" + plugin.economy().accountCount() + "</white> 個",
                "<gray>人均　<white>" + Fmt.coin(plugin.economy().accountCount() == 0
                        ? 0L : plugin.economy().totalSupply() / plugin.economy().accountCount()),
                "",
                "<dark_gray>如果有人佔比特別高，那通常不是他很會玩，",
                "<dark_gray>是某條管道漏了。"));

        inventory.setItem(SLOT_BACK, HubMenu.backButton());
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack headFor(int rank, UUID uuid, long balance, long supply) {
        double share = balance * 100.0 / supply;
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
                "<gray>餘額　<yellow>" + Fmt.coin(balance),
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
