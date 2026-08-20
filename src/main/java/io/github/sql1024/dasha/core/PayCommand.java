package io.github.sql1024.dasha.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /pay <玩家> <金額>} — hands 大沙幣 to another player.
 *
 * <p>This is the only place money moves between two accounts with nothing traded for it, so the
 * command is deliberately talkative: it always states the fee and what the payee will actually
 * receive before anything happens, and a large amount has to be typed twice. A transfer cannot be
 * undone by the sender, and the usual mistake is an extra zero.
 */
public final class PayCommand implements TabExecutor {

    private final DashaEconomyPlugin plugin;

    public PayCommand(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.mini("<red>只有玩家能轉帳。主控台請用 <white>/eco give</white>。"));
            return true;
        }
        if (!player.hasPermission("dasha.pay")) {
            plugin.lang().send(player, "no-permission");
            return true;
        }
        if (!plugin.paySettings().enabled()) {
            player.sendMessage(Lang.mini("<red>這個伺服器沒有開放玩家轉帳。"));
            return true;
        }

        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "" -> usage(player);
            case "confirm", "確認" -> confirm(player);
            case "cancel", "取消" -> cancel(player);
            case "help", "?" -> usage(player);
            default -> {
                if (args.length < 2) {
                    usage(player);
                    return true;
                }
                start(player, args[0], args[1]);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 主流程

    private void start(Player player, String targetName, String rawAmount) {
        UUID target = plugin.lookupPlayer(targetName);
        if (target == null) {
            plugin.fail(player);
            player.sendMessage(Lang.mini("<red>找不到玩家 <white>" + targetName
                    + "</white>。對方必須上過這個伺服器。"));
            return;
        }
        if (!plugin.paySettings().allowOffline()
                && plugin.getServer().getPlayer(target) == null) {
            plugin.fail(player);
            player.sendMessage(Lang.mini("<red>只能轉帳給在線上的玩家。"));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(rawAmount.replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            plugin.fail(player);
            player.sendMessage(Lang.mini("<red><white>" + rawAmount + "</white> 不是有效的金額。"));
            return;
        }
        if (amount <= 0L) {
            plugin.fail(player);
            player.sendMessage(Lang.mini("<red>金額要大於 0。"));
            return;
        }

        execute(player, target, plugin.playerName(target), amount, false);
    }

    private void confirm(Player player) {
        PayService.Pending pending = plugin.pay().pendingFor(player.getUniqueId());
        if (pending == null) {
            plugin.fail(player);
            player.sendMessage(Lang.mini("<red>你沒有待確認的轉帳，或它已經過期了。"));
            return;
        }
        execute(player, pending.target(), pending.targetName(), pending.amount(), true);
    }

    private void cancel(Player player) {
        if (plugin.pay().pendingFor(player.getUniqueId()) == null) {
            player.sendMessage(Lang.mini("<gray>你沒有待確認的轉帳。"));
            return;
        }
        plugin.pay().clearPending(player.getUniqueId());
        plugin.click(player);
        player.sendMessage(Lang.mini("<yellow>已取消轉帳。"));
    }

    /**
     * Runs the transfer and reports the outcome. Shared by the command and the hub GUI, so a
     * refusal reads the same however the player got here.
     */
    public void execute(Player player, UUID target, String targetName, long amount,
                        boolean confirmed) {
        PaySettings settings = plugin.paySettings();
        long fee = settings.feeFor(amount);
        long net = amount - fee;

        switch (plugin.pay().pay(player, target, targetName, amount, confirmed)) {
            case OK -> {
                plugin.success(player);
                player.sendMessage(Lang.mini("<green>已轉給 <white>" + targetName + "</white> <yellow>"
                        + Fmt.coin(net) + "</yellow> " + plugin.currency().displayName() + "。"));
                if (fee > 0L) {
                    player.sendMessage(Lang.mini("<gray>你付出 <white>" + Fmt.coin(amount)
                            + "</white>，其中 <white>" + Fmt.coin(fee) + "</white> 是手續費（"
                            + settings.feePercent() + "%，直接銷毀）。"));
                }
                player.sendMessage(Lang.mini("<gray>餘額：<yellow>"
                        + Fmt.coin(plugin.economy().balance(player)) + "</yellow>"));
                notifyPayee(player, target, targetName, net);
                broadcast(player, targetName, net);
            }
            case NEEDS_CONFIRM -> {
                plugin.fail(player);
                player.sendMessage(Lang.mini("<dark_gray>━━━━━━ <gold>確認轉帳</gold> <dark_gray>━━━━━━"));
                player.sendMessage(Lang.mini("<gray>收款人　<white>" + targetName));
                player.sendMessage(Lang.mini("<gray>你付出　<yellow>" + Fmt.coin(amount)));
                player.sendMessage(Lang.mini("<gray>手續費　<red>-" + Fmt.coin(fee)));
                player.sendMessage(Lang.mini("<gray>對方收到　<green>" + Fmt.coin(net)));
                player.sendMessage(Lang.mini("<red>這筆金額很大，而且<white>轉出去就拿不回來</white>。"));
                player.sendMessage(Lang.mini("<yellow>輸入 <white>/pay confirm</white> 確認，<white>/pay cancel</white> 取消。"
                        + "<dark_gray>（" + settings.confirmSecs() + " 秒內有效）"));
            }
            case NO_SELF_PAY -> fail(player, "<red>不能轉帳給自己。");
            case DISABLED -> fail(player, "<red>這個伺服器沒有開放玩家轉帳。");
            case BELOW_MIN -> fail(player, "<red>最少要轉 <white>"
                    + Fmt.coin(settings.minAmount()) + "</white>。");
            case NET_ZERO -> fail(player, "<red>這個金額扣完手續費之後對方會收到 <white>0</white>。"
                    + "<gray>至少要轉 <white>" + Fmt.coin(settings.smallestUseful()) + "</white>。");
            case ABOVE_MAX -> fail(player, "<red>單筆最多只能轉 <white>"
                    + Fmt.coin(settings.maxAmount()) + "</white>。");
            case DAILY_LIMIT -> fail(player, "<red>超過今日轉帳上限。今天還能轉 <white>"
                    + Fmt.coin(plugin.pay().dailyLeft(player.getUniqueId())) + "</white>。");
            case COOLDOWN -> fail(player, "<red>轉帳太頻繁，請再等 <white>"
                    + plugin.pay().cooldownLeft(player.getUniqueId()) + "</white> 秒。");
            case NOT_ENOUGH -> fail(player, "<red>餘額不足。需要 <white>" + Fmt.coin(amount)
                    + "</white>，你只有 <white>" + Fmt.coin(plugin.economy().balance(player)) + "</white>。");
        }
    }

    private void notifyPayee(Player payer, UUID target, String targetName, long net) {
        if (!plugin.paySettings().notifyPayee()) {
            return;
        }
        Player online = plugin.getServer().getPlayer(target);
        if (online == null) {
            payer.sendMessage(Lang.mini("<gray><white>" + targetName
                    + "</white> 目前離線，錢已經進他的帳戶了。"));
            return;
        }
        online.sendMessage(Lang.mini("<green>收到 <white>" + payer.getName() + "</white> 轉來的 <yellow>"
                + Fmt.coin(net) + "</yellow> " + plugin.currency().displayName() + "。"));
        online.sendMessage(Lang.mini("<gray>餘額：<yellow>"
                + Fmt.coin(plugin.economy().balance(online)) + "</yellow>"));
        online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    private void broadcast(Player payer, String targetName, long net) {
        if (!plugin.paySettings().broadcast()) {
            return;
        }
        plugin.getServer().broadcast(Lang.mini("<gray><white>" + payer.getName()
                + "</white> 轉給 <white>" + targetName + "</white> <yellow>"
                + Fmt.coin(net) + "</yellow>。"));
    }

    private void fail(Player player, String message) {
        plugin.fail(player);
        player.sendMessage(Lang.mini(message));
    }

    private void usage(Player player) {
        PaySettings settings = plugin.paySettings();
        player.sendMessage(Lang.mini("<dark_gray>━━━━━━ <gold>轉帳</gold> <dark_gray>━━━━━━"));
        player.sendMessage(Lang.mini("<yellow>/pay <玩家> <金額> <gray>— 把大沙幣轉給別人"));
        player.sendMessage(Lang.mini("<gray>手續費 <white>" + settings.feePercent()
                + "%</white>（最少 " + Fmt.coin(settings.feeMin())
                + "），從轉出的金額裡扣，對方收到的是剩下的。"));
        player.sendMessage(Lang.mini("<gray>單筆 <white>" + Fmt.coin(settings.minAmount())
                + "</white> ~ <white>" + Fmt.coin(settings.maxAmount()) + "</white>"));
        long left = plugin.pay().dailyLeft(player.getUniqueId());
        if (left != Long.MAX_VALUE) {
            player.sendMessage(Lang.mini("<gray>今日還能轉 <white>" + Fmt.coin(left) + "</white>"));
        }
        player.sendMessage(Lang.mini("<red>轉出去的錢拿不回來，收款人名字看清楚再送。"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(sender)) {
                    out.add(online.getName());
                }
            }
            if (sender instanceof Player player
                    && plugin.pay().pendingFor(player.getUniqueId()) != null) {
                out.add("confirm");
                out.add("cancel");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            out.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
            return out;
        }
        if (args.length == 2) {
            return List.of("100", "1000", "10000");
        }
        return List.of();
    }
}
