package io.github.sql1024.dasha.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** {@code /store} — 伺服器建材商店。同時當這個 GUI 的事件監聽器。 */
public final class StoreCommand implements TabExecutor, Listener {

    private final DashaEconomyPlugin plugin;

    public StoreCommand(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.mini("<red>這個指令只能由玩家使用。"));
            return true;
        }
        if (!player.hasPermission("dasha.store")) {
            plugin.lang().send(player, "no-permission");
            return true;
        }

        StoreSettings settings = plugin.storeSettings();
        if (!settings.enabled() || settings.categories().isEmpty()) {
            player.sendMessage(Lang.mini("<red>伺服器商店目前沒有販售任何東西。"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            list(player);
            return true;
        }

        if (args.length == 0) {
            // With a dozen categories, dropping the player into whichever one happens to be first
            // hides most of the shop. The picker is the honest front page.
            new io.github.sql1024.dasha.ui.StoreCategoryMenu(plugin, player).open();
            return true;
        }

        StoreSettings.Category category = settings.category(args[0]);
        if (category == null) {
            player.sendMessage(Lang.mini("<red>沒有這個分類。可用分類："));
            list(player);
            return true;
        }
        new StoreGui(plugin, player, category, 0).open();
        return true;
    }

    private void list(Player player) {
        player.sendMessage(Lang.mini("<dark_gray>━━━━━━ <aqua>伺服器商店</aqua> <dark_gray>━━━━━━"));
        player.sendMessage(Lang.mini("<gray>只賣建築材料，<red>不收購任何東西</red>。"));
        for (StoreSettings.Category category : plugin.storeSettings().categories()) {
            long low = Long.MAX_VALUE;
            long high = 0L;
            for (StoreSettings.Entry entry : category.entries()) {
                low = Math.min(low, entry.price());
                high = Math.max(high, entry.price());
            }
            player.sendMessage(Lang.mini("<yellow>/store " + category.key() + " <gray>— "
                    + category.name() + " <dark_gray>(" + category.entries().size() + " 項，"
                    + Fmt.coin(low) + "~" + Fmt.coin(high) + ")"));
        }
    }

    // ------------------------------------------------------------------ GUI 事件

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof StoreGui gui) {
            gui.onClick(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof StoreGui) {
            event.setCancelled(true);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            out.add("list");
            for (StoreSettings.Category category : plugin.storeSettings().categories()) {
                out.add(category.key());
            }
            out.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
            return out;
        }
        return List.of();
    }
}
