package io.github.sql1024.dasha.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.sell.OreSellService;
import io.github.sql1024.dasha.sell.SellSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 礦物收購站 — the GUI face of {@code /sell}.
 *
 * <p>Shows every ore the server buys with its live price, how far the supply discount has pushed
 * it, and what the player is still allowed to earn today. Clicking an ore sells everything of that
 * kind in the backpack; the big button sells the lot.
 */
public final class SellMenu extends Gui {

    private static final int[] ORE_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
    };

    private static final int SLOT_QUOTA = 40;
    private static final int SLOT_SELL_ALL = 38;
    private static final int SLOT_SELL_HAND = 42;
    private static final int SLOT_BACK = 45;

    private final List<SellSettings.Item> shown = new ArrayList<>();

    public SellMenu(DashaEconomyPlugin plugin, Player player) {
        super(plugin, player);
        this.inventory = Bukkit.createInventory(this, 54, Msg.mm("<green><bold>礦物收購站</bold> <dark_gray>— 只收不賣"));
    }

    @Override
    public void render() {
        inventory.clear();
        shown.clear();

        int index = 0;
        for (SellSettings.Item item : plugin.sellSettings().items().values()) {
            if (index >= ORE_SLOTS.length) {
                break;
            }
            shown.add(item);
            inventory.setItem(ORE_SLOTS[index], oreIcon(item));
            index++;
        }

        drawQuota();

        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean sellableHand = !hand.getType().isAir() && plugin.sellSettings().buys(hand.getType());
        inventory.setItem(SLOT_SELL_HAND, sellableHand
                ? icon(Material.HOPPER, "<green>賣掉手上這疊",
                        "<gray>" + hand.getType().name() + " x" + hand.getAmount(),
                        "", "<yellow>▶ 點擊賣出")
                : icon(Material.GRAY_DYE, "<gray>賣掉手上這疊",
                        hand.getType().isAir() ? "<red>你手上沒有東西" : "<red>系統不收這個"));

        inventory.setItem(SLOT_SELL_ALL, icon(Material.CHEST_MINECART, "<green><bold>全部賣掉",
                "<gray>賣掉背包裡所有收購清單內的礦物。",
                "<dark_gray>只收乾淨的原版物品，",
                "<dark_gray>有改名或附魔的一律不動。",
                "", "<yellow>▶ 點擊賣出"));

        inventory.setItem(SLOT_BACK, HubMenu.backButton());
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack oreIcon(SellSettings.Item item) {
        long now = plugin.oreSell().unitPrice(item);
        double factor = plugin.oreSell().discountFactor(item);
        int held = countInBag(item.material());

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gray>目前收購價　<yellow>" + Fmt.coin(now) + "</yellow> <dark_gray>/ 基礎 "
                + Fmt.coin(item.basePrice()));
        lore.add("<gray>行情　" + bar(factor) + " <white>" + Math.round(factor * 100) + "%");
        if (!item.tier().isBlank()) {
            lore.add("<dark_gray>分級：" + item.tier());
        }
        lore.add("");
        if (held > 0) {
            lore.add("<gray>你背包裡有 <white>" + held + "</white> 個");
            lore.add("<gray>全賣約可得 <yellow>"
                    + Fmt.coin(plugin.oreSell().payoutFor(item, held)) + "</yellow>");
            lore.add("<green>▶ 點擊賣掉全部的" + item.material().name());
        } else {
            lore.add("<dark_gray>你背包裡沒有這個");
        }

        ItemStack icon = new ItemStack(item.material());
        applyMeta(icon, null, lore);
        return icon;
    }

    private void drawQuota() {
        long left = plugin.oreSell().quotaLeft(player.getUniqueId());
        long quota = plugin.sellSettings().dailyQuota();

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        if (quota <= 0L) {
            lore.add("<gray>本伺服器沒有設每日上限。");
        } else {
            long used = quota - left;
            lore.add("<gray>今日已賺　<white>" + Fmt.coin(used) + "</white> / " + Fmt.coin(quota));
            lore.add("<gray>剩餘　" + bar((double) left / quota) + " <yellow>" + Fmt.coin(left));
            lore.add("");
            lore.add("<dark_gray>台北時間午夜重置。");
            lore.add("<dark_gray>這是防掛機農場與分身帳號的上限。");
        }
        lore.add("");
        lore.add("<gray>餘額　<yellow>" + Fmt.coin(plugin.economy().balance(player)));

        ItemStack icon = new ItemStack(Material.CLOCK);
        applyMeta(icon, "<gold>今日收購額度", lore);
        inventory.setItem(SLOT_QUOTA, icon);
    }

    private int countInBag(Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static String bar(double factor) {
        int filled = (int) Math.round(Math.clamp(factor, 0.0, 1.0) * 10);
        String colour = factor > 0.75 ? "<green>" : factor > 0.45 ? "<yellow>" : "<red>";
        return colour + "▉".repeat(filled) + "<dark_gray>" + "▉".repeat(10 - filled);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (!plugin.sellSettings().enabled()) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed("<red>礦物收購目前關閉中。"));
            return;
        }

        if (slot == SLOT_BACK) {
            plugin.click(player);
            reopen(new HubMenu(plugin, player));
            return;
        }
        if (slot == SLOT_SELL_ALL) {
            announce(plugin.oreSell().sellAll(player));
            render();
            return;
        }
        if (slot == SLOT_SELL_HAND) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir() || !plugin.sellSettings().buys(hand.getType())) {
                plugin.fail(player);
                return;
            }
            announce(plugin.oreSell().sellStack(player, hand));
            render();
            return;
        }

        for (int i = 0; i < ORE_SLOTS.length && i < shown.size(); i++) {
            if (ORE_SLOTS[i] == slot) {
                sellOne(shown.get(i));
                render();
                return;
            }
        }
    }

    /**
     * Sells every stack of one ore. Each stack is passed to the service separately, which is what
     * lets the daily quota cut the sale off partway through instead of failing the whole thing.
     */
    private void sellOne(SellSettings.Item item) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int sold = 0;
        long paid = 0L;
        boolean quotaHit = false;

        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() != item.material()) {
                continue;
            }
            OreSellService.Result result = plugin.oreSell().sellStack(player, stack);
            sold += result.sold();
            paid += result.paid();
            if (result.quotaHit()) {
                quotaHit = true;
                break;
            }
        }
        player.getInventory().setStorageContents(contents);
        announce(new OreSellService.Result(sold, paid, quotaHit));
    }

    private void announce(OreSellService.Result result) {
        if (!result.anything()) {
            plugin.fail(player);
            player.sendMessage(Msg.prefixed(
                    plugin.oreSell().quotaLeft(player.getUniqueId()) <= 0L
                            ? "<red>今日收購額度已用完，明天再來。"
                            : "<yellow>沒有可以賣的東西。"));
            return;
        }
        plugin.success(player);
        player.sendMessage(Msg.prefixed("<green>賣出 <white>" + result.sold() + "</white> 個，入帳 <yellow>"
                + Fmt.coin(result.paid()) + "</yellow> 大沙幣　<gray>餘額 <white>"
                + Fmt.coin(plugin.economy().balance(player)) + "</white>"));
        if (result.quotaHit()) {
            player.sendMessage(Msg.prefixed("<yellow>已達今日收購額度上限，剩下的沒賣掉。"));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
    }
}
