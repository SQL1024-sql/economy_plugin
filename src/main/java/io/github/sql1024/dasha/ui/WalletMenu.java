package io.github.sql1024.dasha.ui;

import java.util.UUID;
import java.util.function.LongConsumer;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.Msg;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.TxnType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 一位玩家的錢包管理頁。
 *
 * <p>Every change here is booked as {@link TxnType#ADMIN}, which keeps it out of the faucet and
 * sink totals — a compensation payout must never look like the economy earning money.
 */
public final class WalletMenu extends Gui {

    private static final int SLOT_TARGET = 4;
    private static final int SLOT_GIVE = 20;
    private static final int SLOT_TAKE = 22;
    private static final int SLOT_SET = 24;
    private static final int SLOT_HOLDINGS = 31;
    private static final int SLOT_BACK = 45;

    private final UUID target;

    public WalletMenu(DashaEconomyPlugin plugin, Player player, UUID target) {
        super(plugin, player);
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 54,
                Msg.mm("<green>錢包管理 <dark_gray>— " + plugin.playerName(target)));
    }

    @Override
    public void render() {
        inventory.clear();

        long balance = plugin.economy().balance(target);
        long holdings = plugin.portfolios().marketValue(target);
        long supply = Math.max(1L, plugin.economy().totalSupply());

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        OfflinePlayer owner = Bukkit.getOfflinePlayer(target);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        applyMeta(head, "<white>" + plugin.playerName(target), java.util.List.of(
                "<dark_gray>━━━━━━━━━━━━━━━",
                "<gray>餘額　<yellow>" + Fmt.coin(balance),
                "<gray>持股市值　<aqua>" + Fmt.coin(holdings),
                "<gray>總資產　<white>" + Fmt.coin(balance + holdings),
                "<gray>佔全服　<white>" + Fmt.price(balance * 100.0 / supply) + "%",
                "",
                "<gray>今日已賣礦　<white>" + Fmt.coin(plugin.oreSell().quotaUsed(target)),
                "<gray>上架中　<white>" + plugin.auctions().countOf(target) + "</white> 筆"));
        inventory.setItem(SLOT_TARGET, head);

        inventory.setItem(SLOT_GIVE, icon(Material.LIME_CONCRETE, "<green>發放大沙幣",
                "<gray>給這位玩家一筆錢。",
                "", "<yellow>▶ 點擊輸入金額"));

        inventory.setItem(SLOT_TAKE, icon(Material.ORANGE_CONCRETE, "<gold>扣除大沙幣",
                "<gray>從這位玩家身上扣錢。",
                "<dark_gray>餘額不夠會失敗，不會變負數。",
                "", "<yellow>▶ 點擊輸入金額"));

        inventory.setItem(SLOT_SET, icon(Material.RED_CONCRETE, "<red>直接設定餘額",
                "<gray>把餘額硬設成指定數字。",
                "<dark_gray>差額不會記成產出或消耗。",
                "", "<yellow>▶ 點擊輸入金額"));

        inventory.setItem(SLOT_HOLDINGS, icon(Material.WRITABLE_BOOK, "<aqua>查看持股",
                "<gray>印在聊天欄。",
                "", "<yellow>▶ 點擊查看"));

        inventory.setItem(SLOT_BACK, icon(Material.BARRIER, "<red>返回管理面板"));
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
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

        String name = plugin.playerName(target);
        switch (slot) {
            case SLOT_BACK -> {
                plugin.click(player);
                reopen(new AdminMenu(plugin, player));
            }
            case SLOT_GIVE -> askAmount("要發放給 " + name + " 的金額", amount -> {
                plugin.economy().deposit(target, name, amount, TxnType.ADMIN,
                        "由 " + player.getName() + " 發放");
                player.sendMessage(Msg.prefixed("<green>已給 <white>" + name + "</white> <yellow>"
                        + Fmt.coin(amount) + "</yellow> 大沙幣。"));
                log("give", amount);
            });
            case SLOT_TAKE -> askAmount("要從 " + name + " 扣除的金額", amount -> {
                if (!plugin.economy().withdraw(target, name, amount, TxnType.ADMIN,
                        "由 " + player.getName() + " 沒收")) {
                    plugin.fail(player);
                    player.sendMessage(Msg.prefixed("<red>" + name + " 的餘額只有 <white>"
                            + Fmt.coin(plugin.economy().balance(target)) + "</white>，不夠扣。"));
                    return;
                }
                player.sendMessage(Msg.prefixed("<yellow>已扣除 <white>" + name + "</white> 的 <white>"
                        + Fmt.coin(amount) + "</white> 大沙幣。"));
                log("take", amount);
            });
            case SLOT_SET -> askAmount(name + " 的新餘額", amount -> {
                plugin.economy().set(target, name, amount, "由 " + player.getName() + " 設定");
                player.sendMessage(Msg.prefixed("<green>已將 <white>" + name
                        + "</white> 的餘額設為 <yellow>" + Fmt.coin(amount) + "</yellow>。"));
                log("set", amount);
            });
            case SLOT_HOLDINGS -> {
                plugin.click(player);
                showHoldings();
            }
            default -> {
            }
        }
    }

    private void askAmount(String what, LongConsumer action) {
        player.closeInventory();
        player.sendMessage(Msg.prefixed("<yellow>請輸入" + what + "（輸入 <white>取消</white> 放棄）："));
        plugin.promptChat(player, input -> {
            String text = input.trim();
            if (text.equalsIgnoreCase("取消") || text.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(text.replace(",", ""));
            } catch (NumberFormatException e) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red><white>" + text + "</white> 不是有效的金額。"));
                open();
                return;
            }
            if (amount < 0L) {
                plugin.fail(player);
                player.sendMessage(Msg.prefixed("<red>金額不能是負數。"));
                open();
                return;
            }
            action.accept(amount);
            plugin.success(player);
            open();
        });
    }

    private void showHoldings() {
        later(() -> {
            player.closeInventory();
            String name = plugin.playerName(target);
            player.sendMessage(Msg.mm("<dark_gray>━━━━━━ <aqua>" + name + " 的持股</aqua> <dark_gray>━━━━━━"));

            var holdings = plugin.portfolios().all(target);
            if (holdings.isEmpty()) {
                player.sendMessage(Msg.mm("<gray>沒有任何持股。"));
                return;
            }
            for (var entry : holdings.entrySet()) {
                var stock = plugin.market().stock(entry.getKey());
                var holding = entry.getValue();
                long value = stock == null ? 0L : Math.round(stock.price() * holding.shares());
                player.sendMessage(Msg.mm("<gray>" + entry.getKey()
                        + "　<white>" + holding.shares() + "</white> 股"
                        + "　<gray>成本 <white>" + Fmt.price(holding.averageCost()) + "</white>"
                        + "　<gray>現值 <yellow>" + Fmt.coin(value) + "</yellow>"
                        + "　" + Fmt.pnlTag(value - holding.invested())));
            }
            player.sendMessage(Msg.mm("<gray>持股總市值　<white>"
                    + Fmt.coin(plugin.portfolios().marketValue(target)) + "</white>"));
        });
    }

    private void log(String action, long amount) {
        plugin.getLogger().info("[稽核] " + player.getName() + " 對 " + plugin.playerName(target)
                + " 執行 " + action + " " + amount + "，餘額變為 " + plugin.economy().balance(target));
    }
}
