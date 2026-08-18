package io.github.sql1024.dasha.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.github.sql1024.dasha.DashaEconomyPlugin;
import io.github.sql1024.dasha.core.Fmt;
import io.github.sql1024.dasha.sell.SellSettings;
import io.github.sql1024.dasha.store.StoreSettings;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * Startup audit that hunts for money printers in the config.
 *
 * <p>Ore selling is one-way and the store never buys, so on paper no loop exists. Crafting
 * reopens one: if the store sells a block that can be broken down into an ore the server buys,
 * a player can buy the block, uncraft it and sell the pieces back for more than they paid,
 * forever. A single mispriced entry — a copper block listed below nine copper ingots — is enough
 * to inflate the whole currency while everybody involved is playing entirely by the rules.
 *
 * <p>This walks the server's real recipe table rather than a hardcoded list, so it keeps working
 * when Minecraft adds blocks and when the operator adds items to either file.
 */
public final class RecipeGuard {

    /** How much more the store must charge than the scrap is worth, to leave no margin at all. */
    private static final double SAFETY_FACTOR = 1.5;

    private final DashaEconomyPlugin plugin;

    public RecipeGuard(DashaEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    /** One config problem worth telling the operator about. */
    public record Finding(Material storeItem, long storePrice, long scrapValue,
                          long suggested, String route) {
    }

    /** Runs the audit and logs anything it finds. Returns the findings for {@code /eco audit}. */
    public List<Finding> run() {
        StoreSettings store = plugin.storeSettings();
        SellSettings sell = plugin.sellSettings();
        List<Finding> findings = new ArrayList<>();

        for (StoreSettings.Entry entry : store.allEntries()) {
            // Same item on both lists: buy at the store price, sell at the ore price, repeat.
            SellSettings.Item direct = sell.item(entry.material());
            if (direct != null) {
                long value = direct.basePrice();
                if (entry.price() < Math.round(value * SAFETY_FACTOR)) {
                    findings.add(new Finding(entry.material(), entry.price(), value,
                            Math.round(value * SAFETY_FACTOR), "同時出現在收購清單"));
                }
            }

            long scrap = scrapValue(entry.material(), sell);
            if (scrap > 0L && entry.price() < Math.round(scrap * SAFETY_FACTOR)) {
                findings.add(new Finding(entry.material(), entry.price(), scrap,
                        Math.round(scrap * SAFETY_FACTOR), "可拆解成收購品"));
            }
        }

        report(findings);
        return findings;
    }

    /**
     * Highest 大沙幣 obtainable by uncrafting one unit of {@code material} into ore the server buys.
     *
     * <p>Uses the base buy-back price, not the discounted one: the discount recovers over time, so
     * the base price is what a patient exploiter eventually gets.
     */
    private long scrapValue(Material material, SellSettings sell) {
        long best = 0L;
        for (SellSettings.Item ore : sell.items().values()) {
            for (Recipe recipe : plugin.getServer().getRecipesFor(new ItemStack(ore.material()))) {
                Map<Material, Integer> ingredients = ingredientsOf(recipe);
                if (ingredients.size() != 1) {
                    continue;
                }
                Map.Entry<Material, Integer> only = ingredients.entrySet().iterator().next();
                if (only.getKey() != material) {
                    continue;
                }
                int consumed = only.getValue();
                int produced = recipe.getResult().getAmount();
                if (consumed <= 0 || produced <= 0) {
                    continue;
                }
                best = Math.max(best, (long) Math.floor((double) produced * ore.basePrice() / consumed));
            }
        }
        return best;
    }

    /** Materials a recipe consumes and how many of each, or empty when it is not a crafting recipe. */
    private static Map<Material, Integer> ingredientsOf(Recipe recipe) {
        Map<Material, Integer> counts = new HashMap<>();
        if (recipe instanceof ShapelessRecipe shapeless) {
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                addChoice(counts, choice);
            }
        } else if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> map = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (char slot : row.toCharArray()) {
                    addChoice(counts, map.get(slot));
                }
            }
        }
        return counts;
    }

    private static void addChoice(Map<Material, Integer> counts, RecipeChoice choice) {
        if (!(choice instanceof RecipeChoice.MaterialChoice material)) {
            return;
        }
        // Only single-material slots are a reliable loop; a slot accepting a tag could be filled
        // with something the store does not sell, so it proves nothing either way.
        List<Material> options = material.getChoices();
        if (options.size() != 1) {
            return;
        }
        counts.merge(options.get(0), 1, Integer::sum);
    }

    private void report(List<Finding> findings) {
        var logger = plugin.getLogger();
        if (findings.isEmpty()) {
            logger.info("反向通道檢查通過：商店與收購清單之間沒有可套利的迴圈。");
            return;
        }
        logger.warning("========================================================");
        logger.warning("⚠ 發現 " + findings.size() + " 個可能的無限印鈔迴圈！");
        logger.warning("玩家可以從伺服器商店買進，拆解後賣回系統，無限賺取大沙幣。");
        for (Finding finding : findings) {
            logger.warning("  • " + finding.storeItem().name()
                    + "：商店賣 " + Fmt.coin(finding.storePrice())
                    + "，但" + finding.route() + "可換回 " + Fmt.coin(finding.scrapValue())
                    + " → 建議至少定價 " + Fmt.coin(finding.suggested()));
        }
        logger.warning("請調整 store.yml 或 sell.yml 後用 /eco reload 重新載入。");
        logger.warning("========================================================");
    }

    /** Iterator-safe recipe count, used by {@code /eco audit} to report coverage. */
    public int recipeCount() {
        int count = 0;
        Iterator<Recipe> it = plugin.getServer().recipeIterator();
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }
}
