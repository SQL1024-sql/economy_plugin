package io.github.sql1024.dasha.auction;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.gui.BrowseGui;
import io.github.sql1024.dasha.auction.gui.CollectGui;
import io.github.sql1024.dasha.auction.gui.MyListingsGui;
import io.github.sql1024.dasha.auction.gui.SellGui;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AhCommand implements TabExecutor {

    private static final List<String> SUB = List.of("sell", "my", "collect", "help");

    private final DashaEconomyPlugin plugin;

    public AhCommand(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("dasha.admin")) {
                sender.sendMessage(Msg.prefixed("<red>你沒有權限。"));
                return true;
            }
            plugin.reloadConfig();
            Lang.load(plugin);
            plugin.restartExpireTask();
            sender.sendMessage(Msg.prefixed("<green>設定已重新載入。"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.prefixed("<red>這個指令只能由玩家使用。"));
            return true;
        }
        if (!player.hasPermission("dasha.market")) {
            player.sendMessage(Msg.prefixed("<red>你沒有權限使用拍賣行。"));
            return true;
        }

        switch (sub) {
            case "sell" -> plugin.sellSession(player).open();
            case "my" -> new MyListingsGui(plugin, player, 0).open();
            case "collect" -> new CollectGui(plugin, player, 0).open();
            case "help" -> help(player);
            case "admin" -> admin(player, args);
            default -> new BrowseGui(plugin, player, 0).open();
        }
        return true;
    }

    private void admin(Player player, String[] args) {
        if (!player.hasPermission("dasha.admin")) {
            player.sendMessage(Msg.prefixed("<red>你沒有權限。"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Msg.prefixed("<yellow>用法：<white>/ah admin <玩家名></white> — 查看並強制下架該玩家的上架。"));
            player.sendMessage(Msg.prefixed("<gray>也可以在 /ah 畫面對別人的上架 <white>Shift+右鍵</white> 直接強制下架。"));
            return;
        }
        UUID target = plugin.auctions().findSellerByName(args[1]);
        if (target == null) {
            player.sendMessage(Msg.prefixed("<red>找不到 <white>" + args[1] + "</white> 的上架。"));
            return;
        }
        new MyListingsGui(plugin, player, target, args[1], 0).open();
    }

    private void help(Player player) {
        player.sendMessage(Msg.mm("<dark_gray>━━━━━━ <gradient:#ffd75f:#ff9f43>拍賣行</gradient> <dark_gray>━━━━━━"));
        player.sendMessage(Msg.mm("<yellow>/ah <gray>— 瀏覽所有上架"));
        player.sendMessage(Msg.mm("<yellow>/ah sell <gray>— 上架物品（指定想換什麼）"));
        player.sendMessage(Msg.mm("<yellow>/ah my <gray>— 我的上架 / 下架取回"));
        player.sendMessage(Msg.mm("<yellow>/ah collect <gray>— 取貨箱"));
        if (player.hasPermission("dasha.admin")) {
            player.sendMessage(Msg.mm("<dark_red>/ah admin <玩家名> <gray>— 管理該玩家的上架（可強制下架）"));
            player.sendMessage(Msg.mm("<dark_red>/ah reload <gray>— 重新載入設定"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(SUB);
            if (sender.hasPermission("dasha.admin")) {
                options.add("reload");
                options.add("admin");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            options.removeIf(option -> !option.startsWith(prefix));
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")
                && sender.hasPermission("dasha.admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Listing listing : plugin.auctions().all()) {
                String name = listing.sellerName();
                if (!names.contains(name) && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(name);
                }
            }
            return names;
        }
        return List.of();
    }
}
