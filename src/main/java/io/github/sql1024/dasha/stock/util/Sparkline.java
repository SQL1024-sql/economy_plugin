package io.github.sql1024.dasha.stock.util;

import java.util.List;

/** Renders a price history into a one-line block chart, as a MiniMessage string. */
public final class Sparkline {

    private static final char[] BLOCKS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private Sparkline() {
    }

    public static String render(List<Double> history, int width) {
        if (history.isEmpty()) {
            return "<dark_gray>（尚無歷史資料）";
        }
        int from = Math.max(0, history.size() - Math.max(2, width));
        List<Double> points = history.subList(from, history.size());
        if (points.size() < 2) {
            return "<dark_gray>（資料不足）";
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double value : points) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        double span = max - min;

        StringBuilder bars = new StringBuilder(points.size());
        for (double value : points) {
            int index = span <= 0.0
                    ? BLOCKS.length / 2
                    : (int) Math.round((value - min) / span * (BLOCKS.length - 1));
            bars.append(BLOCKS[Math.clamp(index, 0, BLOCKS.length - 1)]);
        }

        double first = points.get(0);
        double last = points.get(points.size() - 1);
        String colour = last > first ? "<green>" : last < first ? "<red>" : "<gray>";
        return colour + bars;
    }
}
