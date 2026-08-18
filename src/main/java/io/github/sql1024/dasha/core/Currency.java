package io.github.sql1024.dasha.core;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * How 大沙幣 is named and shown. The balance itself lives in {@link EconomyService} — this only
 * describes the presentation, so renaming the currency never touches anyone's money.
 *
 * @param displayName MiniMessage name used in chat and on GUI items
 * @param symbol      short marker printed next to bare numbers in tight spaces
 */
public record Currency(String displayName, String symbol) {

    public static Currency from(FileConfiguration config) {
        String name = config.getString("currency.display-name", "<gradient:#f6d365:#fda085>大沙幣</gradient>");
        String symbol = config.getString("currency.symbol", "沙");
        return new Currency(
                name == null || name.isBlank() ? "大沙幣" : name,
                symbol == null ? "沙" : symbol);
    }

    /** Amount followed by the currency name, e.g. {@code 1,250 大沙幣}. */
    public String format(long amount) {
        return Fmt.coin(amount) + " " + displayName;
    }
}
