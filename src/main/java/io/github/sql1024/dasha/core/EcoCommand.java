package io.github.sql1024.dasha.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.guard.RecipeGuard;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /eco} and its alias {@code /money} — balances for everyone, and the economy dashboard
 * plus administrative money handling for staff.
 */
public final class EcoCommand implements TabExecutor {

    private static final List<String> PLAYER_SUB = List.of("bal", "menu", "top", "help");
    private static final List<String> ADMIN_SUB =
            List.of("stats", "top", "give", "take", "set", "audit", "reload", "help");

    private final DashaEconomyPlugin plugin;

    public EcoCommand(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "bal", "balance", "餘額" ->
                    balance(sender, sender instanceof Player player ? player.getName() : null);
            case "menu", "gui" -> {
                if (sender instanceof Player player) {
                    new io.github.sql1024.dasha.ui.HubMenu(plugin, player).open();
                }
            }
            case "stats" -> stats(sender, args);
            case "top" -> top(sender, args);
            case "give", "take", "set" -> adjust(sender, sub, args);
            case "audit" -> audit(sender);
            case "reload" -> reload(sender);
            case "help" -> help(sender);
            case "" -> {
                if (sender instanceof Player player) {
                    // The hub is the front door: everything the player can do lives behind it.
                    new io.github.sql1024.dasha.ui.HubMenu(plugin, player).open();
                } else {
                    help(sender);
                }
            }
            default -> balance(sender, args[0]);
        }
        return true;
    }

    // ------------------------------------------------------------------ 玩家

    private void balance(CommandSender sender, String target) {
        if (target == null) {
            sender.sendMessage(Lang.mini("<red>用法：<white>/money <玩家></white>"));
            return;
        }
        boolean self = sender instanceof Player player && player.getName().equalsIgnoreCase(target);
        if (!self && !sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }

        UUID uuid = plugin.lookupPlayer(target);
        if (uuid == null) {
            sender.sendMessage(Lang.mini("<red>找不到玩家 <white>" + target + "</white>。"));
            return;
        }
        long balance = plugin.economy().balance(uuid);
        sender.sendMessage(Lang.mini((self ? "<gray>你的餘額：" : "<gray>" + plugin.playerName(uuid) + " 的餘額：")
                + "<yellow>" + Fmt.coin(balance) + "</yellow> " + plugin.currency().displayName()));

        if (self && sender instanceof Player player) {
            long quotaLeft = plugin.oreSell().quotaLeft(player.getUniqueId());
            if (quotaLeft != Long.MAX_VALUE) {
                sender.sendMessage(Lang.mini("<gray>今日剩餘收購額度：<white>"
                        + Fmt.coin(quotaLeft) + "</white>"));
            }
        }
    }

    // ------------------------------------------------------------------ 管理

    private void stats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        int days = plugin.tuning().reportDefaultDays();
        if (args.length > 1) {
            try {
                days = Math.clamp(Integer.parseInt(args[1]), 1, plugin.tuning().reportMaxDays());
            } catch (NumberFormatException e) {
                sender.sendMessage(Lang.mini("<red><white>" + args[1] + "</white> 不是有效的天數。"));
                return;
            }
        }
        for (Component line : plugin.stats().report(days)) {
            sender.sendMessage(line);
        }
    }

    private void top(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dasha.top")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        int limit = plugin.tuning().topDefault();
        if (args.length > 1) {
            try {
                limit = Math.clamp(Integer.parseInt(args[1]), 1, plugin.tuning().topMax());
            } catch (NumberFormatException e) {
                limit = plugin.tuning().topDefault();
            }
        }
        for (Component line : plugin.stats().richList(limit)) {
            sender.sendMessage(line);
        }
    }

    /**
     * Administrative balance changes. Every one of these is written to the ledger as an
     * {@code ADMIN} entry, kept out of the faucet and sink totals so hand-outs can never be
     * mistaken for the economy actually earning or burning the money.
     */
    private void adjust(CommandSender sender, String action, String[] args) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Lang.mini("<red>用法：<white>/eco " + action + " <玩家> <金額></white>"));
            return;
        }
        UUID uuid = plugin.lookupPlayer(args[1]);
        if (uuid == null) {
            sender.sendMessage(Lang.mini("<red>找不到玩家 <white>" + args[1] + "</white>。"));
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2].replace(",", ""));
        } catch (NumberFormatException e) {
            sender.sendMessage(Lang.mini("<red><white>" + args[2] + "</white> 不是有效的金額。"));
            return;
        }
        if (amount < 0L) {
            sender.sendMessage(Lang.mini("<red>金額不能是負數。"));
            return;
        }

        String name = plugin.playerName(uuid);
        String by = "由 " + sender.getName();
        switch (action) {
            case "give" -> {
                plugin.economy().deposit(uuid, name, amount, TxnType.ADMIN, by + " 發放");
                sender.sendMessage(Lang.mini("<green>已給 <white>" + name + "</white> <yellow>"
                        + Fmt.coin(amount) + "</yellow> 大沙幣。"));
            }
            case "take" -> {
                if (!plugin.economy().withdraw(uuid, name, amount, TxnType.ADMIN, by + " 沒收")) {
                    sender.sendMessage(Lang.mini("<red>" + name + " 的餘額只有 <white>"
                            + Fmt.coin(plugin.economy().balance(uuid)) + "</white>，不夠扣。"));
                    return;
                }
                sender.sendMessage(Lang.mini("<yellow>已扣除 <white>" + name + "</white> 的 <white>"
                        + Fmt.coin(amount) + "</white> 大沙幣。"));
            }
            case "set" -> {
                plugin.economy().set(uuid, name, amount, by + " 設定");
                sender.sendMessage(Lang.mini("<green>已將 <white>" + name + "</white> 的餘額設為 <yellow>"
                        + Fmt.coin(amount) + "</yellow>。"));
            }
            default -> {
            }
        }
        plugin.getLogger().info("[稽核] " + sender.getName() + " 對 " + name + " 執行 "
                + action + " " + amount + "，餘額變為 " + plugin.economy().balance(uuid));
    }

    private void audit(CommandSender sender) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        List<RecipeGuard.Finding> findings = plugin.guard().run();
        sender.sendMessage(Lang.mini("<dark_gray>━━━━━━ <gold>反向通道檢查</gold> <dark_gray>━━━━━━"));
        if (findings.isEmpty()) {
            sender.sendMessage(Lang.mini("<green>✔ 沒有找到可套利的迴圈。"));
            sender.sendMessage(Lang.mini("<gray>商店賣的每樣東西都比它能拆出來的礦物值錢。"));
            return;
        }
        sender.sendMessage(Lang.mini("<red>⚠ 找到 <white>" + findings.size() + "</white> 個可能的無限印鈔迴圈："));
        for (RecipeGuard.Finding finding : findings) {
            sender.sendMessage(Lang.mini("<gray>• <white>" + finding.storeItem().name() + "</white>"
                    + "　商店 <yellow>" + Fmt.coin(finding.storePrice()) + "</yellow>"
                    + " <dark_gray>vs</dark_gray> " + finding.route() + " <red>"
                    + Fmt.coin(finding.scrapValue()) + "</red>"));
            sender.sendMessage(Lang.mini("  <dark_gray>建議至少定價 " + Fmt.coin(finding.suggested())));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        plugin.reloadEverything();
        sender.sendMessage(Lang.mini("<green>大沙幣經濟系統設定已重新載入。"));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Lang.mini("<dark_gray>━━━━━━ <gradient:#f6d365:#fda085>大沙幣</gradient> <dark_gray>━━━━━━"));
        sender.sendMessage(Lang.mini("<yellow>/money <gray>— 看自己的餘額"));
        sender.sendMessage(Lang.mini("<yellow>/eco top <gray>— 富豪榜"));
        sender.sendMessage(Lang.mini("<gray>賺錢：<white>/sell</white> 賣礦物、<white>/stock</white> 股市、<white>/ah</white> 賣東西給別人"));
        sender.sendMessage(Lang.mini("<gray>花錢：<white>/store</white> 買建材、<white>/ah</white> 跟別人買東西"));
        if (sender.hasPermission("dasha.admin")) {
            sender.sendMessage(Lang.mini("<dark_red>/eco stats [天數] <gray>— 經濟報告"));
            sender.sendMessage(Lang.mini("<dark_red>/eco give|take|set <玩家> <金額>"));
            sender.sendMessage(Lang.mini("<dark_red>/eco audit <gray>— 反向通道檢查"));
            sender.sendMessage(Lang.mini("<dark_red>/eco reload"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(
                    sender.hasPermission("dasha.admin") ? ADMIN_SUB : PLAYER_SUB);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                out.add(online.getName());
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            out.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
            return out;
        }
        if (args.length == 2 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission("dasha.admin")) {
            List<String> names = new ArrayList<>();
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                names.add(online.getName());
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            names.removeIf(name -> !name.toLowerCase(Locale.ROOT).startsWith(prefix));
            return names;
        }
        return List.of();
    }
}
