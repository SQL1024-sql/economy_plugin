package io.github.sql1024.dasha.stock.gui;

import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.core.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Small helpers for building GUI items from MiniMessage strings. */
public final class Icons {

    private Icons() {
    }

    public static ItemStack of(Material material, int amount, String nameMini, List<String> loreMini) {
        ItemStack item = new ItemStack(material, Math.clamp(amount, 1, 64));
        item.editMeta(meta -> apply(meta, nameMini, loreMini));
        return item;
    }

    public static ItemStack of(Material material, String nameMini, List<String> loreMini) {
        return of(material, 1, nameMini, loreMini);
    }

    public static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>​", List.of());
    }

    private static void apply(ItemMeta meta, String nameMini, List<String> loreMini) {
        meta.displayName(Lang.item(nameMini));
        if (!loreMini.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreMini.size());
            for (String line : loreMini) {
                lore.add(Lang.item(line));
            }
            meta.lore(lore);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
    }
}
