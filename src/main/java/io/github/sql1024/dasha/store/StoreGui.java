package io.github.sql1024.dasha.store;

import java.util.ArrayList;
import java.util.List;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.core.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/** 伺服器商店介面。只賣不收，商品與價格全部來自 store.yml。 */
public final class StoreGui implements InventoryHolder {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int SLOT_BALANCE = 49;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;

    private final DashaEconomyPlugin plugin;
    private final Player player;
    private final StoreSettings.Category category;
    private final Inventory inventory;
    private int page;
    private List<StoreSettings.Entry> shown = new ArrayList<>();

    public StoreGui(DashaEconomyPlugin plugin, Player player, StoreSettings.Category category, int page) {
        this.plugin = plugin;
        this.player = player;
        this.category = category;
        this.page = Math.max(0, page);
        this.inventory = Bukkit.createInventory(this, SIZE,
                Lang.mini(plugin.storeSettings().guiTitle() + " <dark_gray>| <gray>" + category.name()));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        render();
        player.openInventory(inventory);
    }

    public void render() {
        inventory.clear();

        List<StoreSettings.Entry> entries = category.entries();
        int pages = Math.max(1, (entries.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.clamp(page, 0, pages - 1);

        int from = page * ITEMS_PER_PAGE;
        int to = Math.min(entries.size(), from + ITEMS_PER_PAGE);
        shown = from >= to ? new ArrayList<>() : new ArrayList<>(entries.subList(from, to));

        long balance = plugin.economy().balance(player);
        for (int i = 0; i < shown.size(); i++) {
            inventory.setItem(i, icon(shown.get(i), balance));
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREV, button(Material.ARROW, "<yellow>← 上一頁",
                    List.of("<gray>第 " + page + " / " + pages + " 頁")));
        }
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT, button(Material.ARROW, "<yellow>下一頁 →",
                    List.of("<gray>第 " + (page + 2) + " / " + pages + " 頁")));
        }

        List<String> tabs = new ArrayList<>();
        for (StoreSettings.Category other : plugin.storeSettings().categories()) {
            tabs.add((other == category ? "<green>▶ " : "<dark_gray>  ") + other.name());
        }
        inventory.setItem(SLOT_BALANCE, button(Material.SUNFLOWER,
                "<gold>餘額：<white>" + Fmt.coin(balance),
                concat(List.of(
                        "<gray>" + plugin.currency().displayName(),
                        "",
                        "<yellow>左鍵 <gray>買 1　<yellow>Shift+左鍵 <gray>買 64",
                        "<yellow>右鍵 <gray>買 一組(16)",
                        "",
                        "<dark_gray>分類（用 /store <名稱> 切換）"), tabs)));

        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, "<gray>", List.of());
        for (int i = ITEMS_PER_PAGE; i < SIZE; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    private ItemStack icon(StoreSettings.Entry entry, long balance) {
        ItemStack item = new ItemStack(entry.material());
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>━━━━━━━━━━━━━━━");
        lore.add("<gold>單價：<white>" + Fmt.coin(entry.price()) + "</white> <gray>大沙幣");
        lore.add("<gray>一組 64 個：<white>" + Fmt.coin(entry.price() * 64));
        if (!entry.note().isBlank()) {
            lore.add("<dark_gray>" + entry.note());
        }
        lore.add("");
        if (balance >= entry.price()) {
            lore.add("<gray>你買得起 <white>" + Fmt.coin(balance / entry.price()) + "</white> 個");
            lore.add("<green>▶ 左鍵買 1　Shift+左鍵買 64");
        } else {
            lore.add("<red>✖ 大沙幣不足");
        }
        apply(item, null, lore);
        return item;
    }

    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }

        if (slot == SLOT_PREV && page > 0) {
            page--;
            plugin.click(player);
            render();
            return;
        }
        if (slot == SLOT_NEXT) {
            page++;
            plugin.click(player);
            render();
            return;
        }
        if (slot >= shown.size()) {
            return;
        }

        StoreSettings.Entry entry = shown.get(slot);
        int amount = switch (event.getClick()) {
            case LEFT -> 1;
            case SHIFT_LEFT -> 64;
            case RIGHT -> 16;
            default -> 0;
        };
        if (amount <= 0) {
            return;
        }

        StoreService.Purchase purchase = plugin.store().buy(player, entry.material(), amount);
        switch (purchase.result()) {
            case OK -> {
                plugin.success(player);
                player.sendMessage(Lang.mini("<green>買下 <white>" + entry.material().name()
                        + " x" + purchase.bought() + "</white> <gray>花費 <white>"
                        + Fmt.coin(purchase.cost()) + "</white>　<gray>餘額 <white>"
                        + Fmt.coin(plugin.economy().balance(player)) + "</white>"));
                render();
            }
            case NOT_ENOUGH_COIN -> {
                plugin.fail(player);
                player.sendMessage(Lang.mini("<red>大沙幣不足：需要 <white>"
                        + Fmt.coin(entry.price() * amount) + "</white>，你有 <white>"
                        + Fmt.coin(plugin.economy().balance(player)) + "</white>。"));
            }
            case NO_ROOM -> {
                plugin.fail(player);
                player.sendMessage(Lang.mini("<red>背包滿了，先清出位置。"));
            }
            case NOT_SOLD -> {
                plugin.fail(player);
                player.sendMessage(Lang.mini("<red>商店沒有賣這個。"));
            }
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        apply(item, name, lore);
        return item;
    }

    private static void apply(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        if (name != null) {
            meta.displayName(Lang.item(name));
        }
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(Lang.item(line));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
    }

    /** Click types the GUI reacts to, kept next to the lore that documents them. */
    public static boolean isBuyClick(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.SHIFT_LEFT || click == ClickType.RIGHT;
    }
}
