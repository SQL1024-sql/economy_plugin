package io.github.sql1024.dasha.stock.listener;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Keeps the uuid to name mapping fresh so admin lookups and the leaderboard show real names. */
public final class PlayerListener implements Listener {

    private final DashaEconomyPlugin plugin;

    public PlayerListener(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.rememberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
