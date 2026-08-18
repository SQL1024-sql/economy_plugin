package io.github.sql1024.dasha.core;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Number formatting shared by chat output and the GUI. */
public final class Fmt {

    private static final DecimalFormat PRICE =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DecimalFormat GOLD =
            new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private Fmt() {
    }

    public static String price(double value) {
        return PRICE.format(value);
    }

    public static String gold(long value) {
        return GOLD.format(value);
    }

    /** A 大沙幣 amount with thousands separators. The canonical way to print money. */
    public static String coin(long value) {
        return GOLD.format(value);
    }

    /** Shortened amount for tight GUI slots: {@code 12.3萬}, {@code 4.5億}. */
    public static String shortCoin(long value) {
        long abs = Math.abs(value);
        if (abs >= 100_000_000L) {
            return PRICE.format(value / 100_000_000.0) + "億";
        }
        if (abs >= 10_000L) {
            return PRICE.format(value / 10_000.0) + "萬";
        }
        return GOLD.format(value);
    }

    /** Signed percentage, e.g. {@code +3.42} or {@code -1.10}. */
    public static String signedPercent(double percent) {
        String body = PRICE.format(Math.abs(percent));
        if (percent > 0.0) {
            return "+" + body;
        }
        if (percent < 0.0) {
            return "-" + body;
        }
        return body;
    }

    /** MiniMessage-coloured percentage with an arrow, e.g. {@code <green>▲ +3.42%}. */
    public static String changeTag(double percent) {
        if (percent > 0.0) {
            return "<green>▲ " + signedPercent(percent) + "%";
        }
        if (percent < 0.0) {
            return "<red>▼ " + signedPercent(percent) + "%";
        }
        return "<gray>— 0.00%";
    }

    /** MiniMessage-coloured signed gold amount, used for profit and loss. */
    public static String pnlTag(long gold) {
        if (gold > 0L) {
            return "<green>+" + gold(gold) + "</green>";
        }
        if (gold < 0L) {
            return "<red>-" + gold(-gold) + "</red>";
        }
        return "<gray>0</gray>";
    }
}
