package io.github.sql1024.dasha.auction;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.auction.gui.Gui;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

public final class GuiListener implements Listener {

    private final DashaEconomyPlugin plugin;

    public GuiListener(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    private Gui guiOf(InventoryHolder holder) {
        return holder instanceof Gui gui ? gui : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Gui gui = guiOf(event.getView().getTopInventory().getHolder());
        if (gui != null) {
            gui.onClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Gui gui = guiOf(event.getView().getTopInventory().getHolder());
        if (gui != null) {
            gui.onDrag(event);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Gui gui = guiOf(event.getView().getTopInventory().getHolder());
        if (gui != null) {
            gui.onClose(event);
        }
    }

    /** 玩家正在輸入搜尋關鍵字時，把那則訊息吃掉，不要送到公頻。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (plugin.consumeChat(event.getPlayer(), text)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        int pending = plugin.auctions().collectSize(event.getPlayer().getUniqueId());
        if (pending > 0) {
            event.getPlayer().sendMessage(Msg.prefixed("<yellow>取貨箱裡有 <white>" + pending
                    + "</white> 件物品等你領取：<white>/ah collect</white>"));
        }
    }
}
