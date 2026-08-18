package io.github.sql1024.dasha.auction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.concurrent.TimeUnit;

public final class Msg {

    public static final String PREFIX = "<gradient:#ffd75f:#ff9f43><bold>[拍賣行]</bold></gradient> ";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    public static Component mm(String text) {
        return MM.deserialize(text);
    }

    /** 給物品名稱／lore 用，關掉預設斜體。 */
    public static Component plain(String text) {
        return MM.deserialize("<!italic>" + text);
    }

    public static Component prefixed(String text) {
        return MM.deserialize(PREFIX + text);
    }

    /** 物品的顯示名稱字串，有自訂名稱就用自訂名稱，否則用材質名。 */
    public static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                return PlainTextComponentSerializer.plainText().serialize(name);
            }
        }
        String chinese = Lang.name(item.getType());
        return chinese != null ? chinese : prettyMaterial(item);
    }

    public static String prettyMaterial(ItemStack item) {
        String raw = item.getType().name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder(raw.length());
        boolean upper = true;
        for (char c : raw.toCharArray()) {
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = c == ' ';
        }
        return sb.toString();
    }

    /** 把剩餘毫秒數轉成「3 天 4 小時」這種字串。 */
    public static String duration(long millis) {
        if (millis <= 0) {
            return "已過期";
        }
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        if (days > 0) {
            return days + " 天 " + hours + " 小時";
        }
        if (hours > 0) {
            return hours + " 小時 " + minutes + " 分";
        }
        return Math.max(1, minutes) + " 分鐘";
    }
}
