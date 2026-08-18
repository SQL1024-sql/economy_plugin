package io.github.sql1024.dasha.auction;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

public final class ItemCodec {

    private ItemCodec() {
    }

    public static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static ItemStack decode(String data) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
    }
}
