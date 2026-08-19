package io.github.sql1024.dasha.stock.command;

import io.github.sql1024.dasha.core.Fmt;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.stock.market.Stock;
import io.github.sql1024.dasha.stock.portfolio.Holding;
import io.github.sql1024.dasha.core.TxnRecord;
import io.github.sql1024.dasha.core.Lang;
import io.github.sql1024.dasha.stock.util.Report;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /stock} — the whole command surface of the plugin. */
public final class StockCommand implements TabExecutor {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final List<String> PUBLIC_SUBCOMMANDS =
            List.of("help", "list", "info", "news", "buy", "sell", "portfolio", "log", "top");
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("reload", "setprice", "tick", "audit");

    private final DashaEconomyPlugin plugin;

    public StockCommand(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender,
                            Command command,
                            String label,
                            String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!require(player, "dasha.stock")) {
                    return true;
                }
                plugin.menus().openMarket(player);
            } else {
                sendLines(sender, Report.marketList(plugin, null));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "list", "market" -> list(sender);
            case "info", "price" -> info(sender, args);
            case "buy" -> trade(sender, args, true);
            case "sell" -> trade(sender, args, false);
            case "portfolio", "p" -> portfolio(sender, args);
            case "news" -> news(sender, args);
            case "audit" -> audit(sender);
            case "log", "history" -> log(sender, args);
            case "top" -> top(sender);
            case "reload" -> reload(sender);
            case "setprice" -> setPrice(sender, args);
            case "tick" -> tick(sender);
            default -> help(sender);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void help(CommandSender sender) {
        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>▬▬▬▬▬ <aqua>股市 <dark_gray>▬▬▬▬▬"));
        lines.add(Lang.mini("<gray>貨幣是 " + plugin.currency().displayName()
                + "<gray>，買賣直接進出你的背包。"));
        lines.add(Lang.mini("<yellow>/stock <gray>開啟股市介面"));
        lines.add(Lang.mini("<yellow>/stock list <gray>所有股票與現價"));
        lines.add(Lang.mini("<yellow>/stock info 代號 <gray>單檔詳細資訊、走勢與技術面"));
        lines.add(Lang.mini("<yellow>/stock news <gray>財經新聞（唯一的預測線索）"));
        lines.add(Lang.mini("<yellow>/stock buy 代號 股數 <gray>買入"));
        lines.add(Lang.mini("<yellow>/stock sell 代號 股數|all <gray>賣出"));
        lines.add(Lang.mini("<yellow>/stock portfolio <gray>我的持股"));
        lines.add(Lang.mini("<yellow>/stock log [筆數] <gray>我的交易紀錄"));
        lines.add(Lang.mini("<yellow>/stock top <gray>資產排行榜"));
        if (sender.hasPermission("dasha.admin")) {
            lines.add(Lang.mini("<dark_gray>— 管理 —"));
            lines.add(Lang.mini("<red>/stock reload <gray>重新載入設定"));
            lines.add(Lang.mini("<red>/stock setprice 代號 價格 <gray>手動設定股價"));
            lines.add(Lang.mini("<red>/stock tick <gray>立刻更新一次股價"));
            lines.add(Lang.mini("<red>/stock portfolio 玩家 <gray>查看他人持股"));
        }
        sendLines(sender, lines);
    }

    private void list(CommandSender sender) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        sendLines(sender, Report.marketList(plugin, viewer));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Lang.mini("<red>用法：/stock info 代號"));
            return;
        }
        Stock stock = plugin.market().stock(args[1]);
        if (stock == null) {
            plugin.lang().send(sender, "unknown-stock", "symbol", args[1]);
            return;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        sendLines(sender, Report.stockInfo(plugin, stock, viewer));
    }

    private void trade(CommandSender sender, String[] args, boolean buying) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "player-only");
            return;
        }
        if (!require(player, "dasha.stock")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Lang.mini("<red>用法：/stock " + (buying ? "buy" : "sell")
                    + " 代號 股數" + (buying ? "" : "|all")));
            return;
        }

        Stock stock = plugin.market().stock(args[1]);
        if (stock == null) {
            plugin.lang().send(sender, "unknown-stock", "symbol", args[1]);
            return;
        }

        if (!buying && args[2].equalsIgnoreCase("all")) {
            plugin.trades().sellAll(player, stock);
            refresh(player);
            return;
        }

        int shares;
        try {
            shares = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.lang().send(sender, "invalid-number", "input", args[2]);
            return;
        }
        if (shares <= 0) {
            plugin.lang().send(sender, "invalid-number", "input", args[2]);
            return;
        }

        if (buying) {
            plugin.trades().buy(player, stock, shares);
        } else {
            plugin.trades().sell(player, stock, shares);
        }
        refresh(player);
    }

    private void portfolio(CommandSender sender, String[] args) {
        UUID target;
        String targetName;

        if (args.length >= 2) {
            if (!sender.hasPermission("dasha.admin")) {
                plugin.lang().send(sender, "no-permission");
                return;
            }
            UUID found = plugin.lookupPlayer(args[1]);
            if (found == null) {
                sender.sendMessage(Lang.mini("<red>找不到玩家 <white>" + args[1] + "</white>（他可能還沒交易過）。"));
                return;
            }
            target = found;
            targetName = plugin.playerName(found);
        } else if (sender instanceof Player player) {
            if (!require(player, "dasha.stock")) {
                return;
            }
            target = player.getUniqueId();
            targetName = player.getName();
        } else {
            plugin.lang().send(sender, "player-only");
            return;
        }

        Map<String, Holding> holdings = plugin.portfolios().all(target);
        if (holdings.isEmpty()) {
            plugin.lang().send(sender, "no-holdings");
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>▬▬▬ <gold>" + targetName + " <gray>的投資組合<dark_gray> ▬▬▬"));
        long value = 0L;
        long invested = 0L;
        for (Map.Entry<String, Holding> entry : holdings.entrySet()) {
            Stock stock = plugin.market().stock(entry.getKey());
            Holding holding = entry.getValue();
            if (stock == null) {
                lines.add(Lang.mini("<dark_gray>" + entry.getKey() + " <gray>持有 <white>"
                        + holding.shares() + "</white> 股 <red>(這檔股票已從設定移除)"));
                continue;
            }
            long positionValue = (long) Math.floor(stock.price() * holding.shares());
            value += positionValue;
            invested += holding.invested();
            lines.add(Lang.mini("<gray>" + stock.symbol() + " <dark_gray>· " + stock.displayName()
                    + "<gray>　<white>" + holding.shares() + "</white> 股　均價 <white>"
                    + Fmt.price(holding.averageCost()) + "</white>　市值 <gold>"
                    + Fmt.gold(positionValue) + "</gold>　" + Fmt.pnlTag(positionValue - holding.invested())));
        }
        lines.add(Lang.mini("<gray>合計市值 <gold>" + Fmt.gold(value) + "</gold> <gray>" + plugin.currency().displayName() + "　"
                + "投入 <gold>" + Fmt.gold(invested) + "</gold>　未實現 " + Fmt.pnlTag(value - invested)));
        sendLines(sender, lines);
    }

    private void news(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            sendLines(sender, Report.newsBoard(plugin));
            return;
        }
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Lang.mini(
                    "<red>用法：<white>/stock news <代號> <up|down> <強度1-5> [標題]</white>"));
            return;
        }

        Stock stock = plugin.market().stock(args[1]);
        if (stock == null) {
            plugin.lang().send(sender, "unknown-stock", "symbol", args[1]);
            return;
        }
        String dir = args[2].toLowerCase(Locale.ROOT);
        int direction = switch (dir) {
            case "up", "bullish", "利多", "+" -> 1;
            case "down", "bearish", "利空", "-" -> -1;
            default -> 0;
        };
        if (direction == 0) {
            sender.sendMessage(Lang.mini("<red>方向要是 <white>up</white> 或 <white>down</white>。"));
            return;
        }
        int strength;
        try {
            strength = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            plugin.lang().send(sender, "invalid-number", "input", args[3]);
            return;
        }

        String headline = args.length > 4
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length))
                : "";
        UUID adminUuid = sender instanceof Player player ? player.getUniqueId() : null;

        if (!plugin.news().publishManual(adminUuid, sender.getName(), stock,
                direction, strength, headline)) {
            sender.sendMessage(Lang.mini("<red><white>" + stock.symbol()
                    + "</white> 已經有一則新聞在跑，等它結束再發。"));
            return;
        }
        if (!plugin.news().newsAffectsMarket()) {
            sender.sendMessage(Lang.mini("<yellow>已廣播消息，但<red>不會影響股價</red> —— "
                    + "本伺服器的股價跟著真實市場走。"));
            return;
        }
        sender.sendMessage(Lang.mini("<green>已發布新聞，<white>" + stock.symbol()
                + "</white> 停牌 <white>" + plugin.newsSettings().haltSeconds() + "</white> 秒。"));
        if (adminUuid != null && plugin.newsSettings().insiderLockMinutes() > 0) {
            sender.sendMessage(Lang.mini("<gray>你在 <white>"
                    + plugin.newsSettings().insiderLockMinutes()
                    + "</white> 分鐘內不能交易這檔股票（防內線）。"));
        }
    }

    /** {@code /stock audit} — who published which manual headline, and when. */
    private void audit(CommandSender sender) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        List<Object[]> rows = plugin.database().recentNewsAudit(plugin.tuning().newsAuditEntries());
        sender.sendMessage(Lang.mini("<dark_gray>━━━━━━ <gold>新聞稽核紀錄</gold> <dark_gray>━━━━━━"));
        if (rows.isEmpty()) {
            sender.sendMessage(Lang.mini("<gray>目前沒有任何人工發布的新聞。"));
            return;
        }
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm");
        for (Object[] row : rows) {
            int direction = (Integer) row[2];
            sender.sendMessage(Lang.mini("<dark_gray>" + format.format(new java.util.Date((Long) row[5]))
                    + "</dark_gray> <white>" + row[0] + "</white> → <white>" + row[1] + "</white> "
                    + (direction > 0 ? "<green>利多" : "<red>利空")
                    + " <gray>強度 " + row[3]));
            sender.sendMessage(Lang.mini("  <dark_gray>" + row[4]));
        }
    }

    private void log(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "player-only");
            return;
        }
        if (!require(player, "dasha.stock")) {
            return;
        }
        int limit = plugin.tuning().topDefault();
        if (args.length >= 2) {
            try {
                limit = Math.clamp(Integer.parseInt(args[1]), 1, plugin.tuning().topMax());
            } catch (NumberFormatException e) {
                plugin.lang().send(sender, "invalid-number", "input", args[1]);
                return;
            }
        }

        List<TxnRecord> records = plugin.database().recentTransactions(player.getUniqueId(), limit);
        if (records.isEmpty()) {
            sender.sendMessage(Lang.mini("<yellow>你還沒有任何交易紀錄。"));
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>▬▬▬ <gold>最近 " + records.size() + " 筆交易<dark_gray> ▬▬▬"));
        for (TxnRecord record : records) {
            String verb = record.isBuy() ? "<green>買入" : "<red>賣出";
            lines.add(Lang.mini("<dark_gray>" + STAMP.format(Instant.ofEpochMilli(record.timestamp()))
                    + " " + verb + "<reset> <white>" + record.shares() + "</white> 股 <gray>"
                    + record.symbol() + " <dark_gray>@ <white>" + Fmt.price(record.unitPrice())
                    + "</white>　<gold>" + Fmt.gold(record.gold()) + "</gold> <gray>" + plugin.currency().displayName() + ""));
        }
        sendLines(sender, lines);
    }

    private void top(CommandSender sender) {
        if (!sender.hasPermission("dasha.top")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        record Entry(UUID uuid, long value) {
        }

        List<Entry> ranking = new ArrayList<>();
        for (UUID uuid : plugin.portfolios().everyone().keySet()) {
            ranking.add(new Entry(uuid, plugin.portfolios().marketValue(uuid)));
        }
        ranking.sort(Comparator.comparingLong(Entry::value).reversed());

        if (ranking.isEmpty()) {
            sender.sendMessage(Lang.mini("<yellow>目前還沒有人持有股票。"));
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mini("<dark_gray>▬▬▬ <gold>持股市值排行<dark_gray> ▬▬▬"));
        int rank = 1;
        for (Entry entry : ranking) {
            if (rank > plugin.tuning().topDefault()) {
                break;
            }
            lines.add(Lang.mini("<gray>" + rank + ". <white>" + plugin.playerName(entry.uuid())
                    + "</white>　<gold>" + Fmt.gold(entry.value()) + "</gold> <gray>" + plugin.currency().displayName() + ""));
            rank++;
        }
        sendLines(sender, lines);
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        plugin.reloadEverything();
        plugin.lang().send(sender, "reloaded", "count", plugin.market().size());
    }

    private void setPrice(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Lang.mini("<red>用法：/stock setprice 代號 價格"));
            return;
        }
        Stock stock = plugin.market().stock(args[1]);
        if (stock == null) {
            plugin.lang().send(sender, "unknown-stock", "symbol", args[1]);
            return;
        }
        double price;
        try {
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            plugin.lang().send(sender, "invalid-number", "input", args[2]);
            return;
        }
        if (!(price > 0.0) || !Double.isFinite(price)) {
            plugin.lang().send(sender, "invalid-number", "input", args[2]);
            return;
        }
        plugin.market().setPrice(stock, price);
        plugin.lang().send(sender, "price-set",
                "name", stock.displayName(), "price", Fmt.price(stock.price()));
    }

    private void tick(CommandSender sender) {
        if (!sender.hasPermission("dasha.admin")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        plugin.market().tick();
        plugin.lang().send(sender, "ticked");
    }

    // ------------------------------------------------------------------ helpers

    private boolean require(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        plugin.lang().send(player, "no-permission");
        return false;
    }

    private void refresh(Player player) {
        var menu = plugin.menus().menuOf(player);
        if (menu != null) {
            menu.render();
            player.updateInventory();
        }
    }

    private void sendLines(CommandSender sender, List<Component> lines) {
        for (Component line : lines) {
            sender.sendMessage(line);
        }
    }

    // ------------------------------------------------------------------ completion

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                     Command command,
                                     String label,
                                     String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(PUBLIC_SUBCOMMANDS);
            if (sender.hasPermission("dasha.admin")) {
                options.addAll(ADMIN_SUBCOMMANDS);
            }
            return filter(options, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "info", "price", "buy", "sell", "setprice", "news" -> {
                    return filter(symbols(), args[1]);
                }
                case "portfolio", "p" -> {
                    if (sender.hasPermission("dasha.admin")) {
                        return filter(onlineNames(), args[1]);
                    }
                    return List.of();
                }
                case "log", "history" -> {
                    return filter(List.of("5", "10", "25", "50"), args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3) {
            switch (sub) {
                case "buy" -> {
                    return filter(List.of("1", "10", "64", "100"), args[2]);
                }
                case "sell" -> {
                    return filter(List.of("1", "10", "64", "100", "all"), args[2]);
                }
                case "setprice" -> {
                    Stock stock = plugin.market().stock(args[1]);
                    return stock == null ? List.of() : filter(List.of(Fmt.price(stock.price())), args[2]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private List<String> symbols() {
        List<String> out = new ArrayList<>(plugin.market().size());
        plugin.market().stocks().forEach(stock -> out.add(stock.symbol()));
        return out;
    }

    private List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        plugin.getServer().getOnlinePlayers().forEach(player -> out.add(player.getName()));
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(option);
            }
        }
        return out;
    }
}
