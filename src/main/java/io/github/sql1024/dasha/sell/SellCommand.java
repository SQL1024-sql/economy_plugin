package io.github.sql1024.dasha.sell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.Lang;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** {@code /sell} — 把礦物賣給系統換大沙幣。單向，系統不賣礦物。 */
public final class SellCommand implements TabExecutor {

    private static final List<String> SUB = List.of("all", "price", "help");

    private final DashaEconomyPlugin plugin;

    public SellCommand(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.mini("<red>這個指令只能由玩家使用。"));
            return true;
        }
        if (!player.hasPermission("dasha.sell")) {
            plugin.lang().send(player, "no-permission");
            return true;
        }
        if (!plugin.sellSettings().enabled()) {
            player.sendMessage(Lang.mini("<red>礦物收購目前關閉中。"));
            return true;
        }

        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "all" -> sellAll(player);
            case "price" -> priceList(player);
            case "help" -> help(player);
            default -> sellHand(player);
        }
        return true;
    }

    private void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(Lang.mini("<red>你手上沒有東西。用 <white>/sell all</white> 賣掉背包裡所有礦物。"));
            return;
        }
        if (!plugin.sellSettings().buys(hand.getType())) {
            player.sendMessage(Lang.mini("<red>系統不收 <white>" + hand.getType().name()
                    + "</white>。用 <white>/sell price</white> 看收購清單。"));
            return;
        }

        OreSellService.Result result = plugin.oreSell().sellStack(player, hand);
        if (!result.anything()) {
            player.sendMessage(Lang.mini("<red>今日收購額度已用完，明天再來。"));
            return;
        }
        announce(player, result);
    }

    private void sellAll(Player player) {
        OreSellService.Result result = plugin.oreSell().sellAll(player);
        if (!result.anything()) {
            player.sendMessage(Lang.mini(plugin.oreSell().quotaLeft(player.getUniqueId()) <= 0L
                    ? "<red>今日收購額度已用完，明天再來。"
                    : "<yellow>背包裡沒有系統收購的礦物。用 <white>/sell price</white> 看清單。"));
            return;
        }
        announce(player, result);
    }

    private void announce(Player player, OreSellService.Result result) {
        player.sendMessage(Lang.mini("<green>賣出 <white>" + result.sold() + "</white> 個，入帳 <yellow>"
                + Fmt.coin(result.paid()) + "</yellow> " + plugin.currency().displayName()));
        long left = plugin.oreSell().quotaLeft(player.getUniqueId());
        if (left != Long.MAX_VALUE) {
            player.sendMessage(Lang.mini("<gray>今日剩餘收購額度：<white>" + Fmt.coin(left) + "</white>"));
        }
        if (result.quotaHit()) {
            player.sendMessage(Lang.mini("<yellow>已達今日收購額度上限，剩下的沒賣掉。"));
        }
        player.sendMessage(Lang.mini("<gray>餘額：<white>"
                + Fmt.coin(plugin.economy().balance(player)) + "</white>"));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
    }

    private void priceList(Player player) {
        player.sendMessage(Lang.mini("<dark_gray>━━━━━━ <gold>礦物收購價</gold> <dark_gray>━━━━━━"));
        player.sendMessage(Lang.mini("<gray>價格會隨全服賣出量下滑，停手一陣子會自己回升。"));
        player.sendMessage(Lang.mini("<dark_gray>系統<red>只收購</red>，不賣礦物。"));
        player.sendMessage(net.kyori.adventure.text.Component.empty());

        for (SellSettings.Item item : plugin.sellSettings().items().values()) {
            long now = plugin.oreSell().unitPrice(item);
            double factor = plugin.oreSell().discountFactor(item);
            String colour = factor > 0.75 ? "<green>" : factor > 0.45 ? "<yellow>" : "<red>";
            String tier = item.tier().isBlank() ? "" : " <dark_gray>[" + item.tier() + "]";
            player.sendMessage(Lang.mini("<gray>" + item.material().name() + tier
                    + "　" + colour + Fmt.coin(now) + "</white>"
                    + "<dark_gray>/" + Fmt.coin(item.basePrice())
                    + "　(" + Math.round(factor * 100) + "%)"));
        }

        long left = plugin.oreSell().quotaLeft(player.getUniqueId());
        player.sendMessage(net.kyori.adventure.text.Component.empty());
        player.sendMessage(Lang.mini("<gray>今日剩餘額度：<white>"
                + (left == Long.MAX_VALUE ? "無上限" : Fmt.coin(left)) + "</white>"));
    }

    private void help(Player player) {
        player.sendMessage(Lang.mini("<dark_gray>━━━━━━ <gold>礦物收購</gold> <dark_gray>━━━━━━"));
        player.sendMessage(Lang.mini("<yellow>/sell <gray>— 賣掉手上這疊"));
        player.sendMessage(Lang.mini("<yellow>/sell all <gray>— 賣掉背包裡所有收購清單內的礦物"));
        player.sendMessage(Lang.mini("<yellow>/sell price <gray>— 看目前收購價與今日剩餘額度"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>(SUB);
            out.removeIf(option -> !option.startsWith(prefix));
            return out;
        }
        return List.of();
    }
}
