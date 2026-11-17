package com.mimicenzymes.litematicafiller.materials;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **显示端与收集/提取端共用的「选哪条配方」**（Ray 2026-11-17 P3 #5）。
 *
 * <h2>之前的不一致</h2>
 * <ul>
 *   <li>显示端（{@link MaterialBreakdownHint}）：把所有能做出该物品的配方都算一遍，按
 *       「能互转优先 → 原料种类少 → 单次产量小」挑一条展示；</li>
 *   <li>收集/提取端（{@code FillMaterialCalculator#buildRecipeSource}）：遍历配方表，
 *       **遇到第一条匹配就 break**。</li>
 * </ul>
 * 于是会出现「列表提示 ← 铁块，收集却拿 9 铁粒」或「列表没有 ← 提示、原料却被拿走」。
 * 现在两端都走这里，选出来的配方、原料种类和数量完全一致（tag 配方仍按 {@code options[0]} 口径）。
 */
public final class RecipeChooser {

    /** 选中的配方：单次产量、每格原料（options[0]，同种按格子数合并）、能否互转。 */
    public record Choice(int outputCount, List<MaterialDemandCore.Ingredient> ingredients, boolean bidirectional) {
    }

    private RecipeChooser() {
    }

    /** 选中的配方；没有可用的合成配方 ⇒ {@code null}。 */
    public static Choice choose(ClientWorld world, RecipeManager manager, Item requiredItem) {
        if (world == null || manager == null || requiredItem == null) {
            return null;
        }
        List<Choice> candidates = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> holder : manager.listAllOfType(RecipeType.CRAFTING)) {
            try {
                CraftingRecipe recipe = holder.value();
                if (recipe == null) {
                    continue;
                }
                ItemStack output = recipe.getResult(world.getRegistryManager());
                if (output == null || output.isEmpty() || !output.isOf(requiredItem)) {
                    continue;
                }
                int outputCount = output.getCount();
                if (outputCount <= 0) {
                    continue;
                }
                Map<Item, Integer> counts = new LinkedHashMap<>();
                boolean unusable = false;
                int cells = 0;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) {
                        continue;
                    }
                    if (++cells > 9) {
                        unusable = true;
                        break;
                    }
                    ItemStack[] matching = ingredient.getMatchingStacks();
                    if (matching.length == 0 || matching[0] == null || matching[0].isEmpty()) {
                        unusable = true;
                        break;
                    }
                    Item inputItem = matching[0].getItem();
                    if (inputItem == requiredItem) {
                        unusable = true; // 自己合自己（9 锭 ↔ 1 块的反向）没有参考意义
                        break;
                    }
                    counts.merge(inputItem, 1, Integer::sum);
                }
                if (unusable || counts.isEmpty()) {
                    continue;
                }
                // 1 换 1 没有折算意义
                if (outputCount == 1 && counts.size() == 1 && counts.values().iterator().next() == 1) {
                    continue;
                }
                List<MaterialDemandCore.Ingredient> ingredients = new ArrayList<>(counts.size());
                for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
                    ingredients.add(new MaterialDemandCore.Ingredient(entry.getKey(), entry.getValue()));
                }
                boolean bidirectional = counts.size() == 1
                        && hasReverseRecipe(manager, world, counts.keySet().iterator().next(), requiredItem);
                candidates.add(new Choice(outputCount, ingredients, bidirectional));
            } catch (Throwable ignored) {
                // 单条配方异常不影响其它候选
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Choice best = null;
        for (Choice candidate : candidates) {
            if (best == null) {
                best = candidate;
                continue;
            }
            if (candidate.bidirectional() != best.bidirectional()) {
                if (candidate.bidirectional()) {
                    best = candidate;
                }
                continue;
            }
            if (candidate.ingredients().size() != best.ingredients().size()) {
                if (candidate.ingredients().size() < best.ingredients().size()) {
                    best = candidate;
                }
                continue;
            }
            if (candidate.outputCount() < best.outputCount()) {
                best = candidate;
            }
        }
        return best;
    }

    /** 是否存在「用 requiredItem 造出 candidateItem」的配方（即两个物品能互相转换）。 */
    private static boolean hasReverseRecipe(RecipeManager manager, ClientWorld world, Item candidateItem, Item requiredItem) {
        for (RecipeEntry<CraftingRecipe> holder : manager.listAllOfType(RecipeType.CRAFTING)) {
            try {
                CraftingRecipe recipe = holder.value();
                if (recipe == null) {
                    continue;
                }
                ItemStack output = recipe.getResult(world.getRegistryManager());
                if (output == null || output.isEmpty() || !output.isOf(candidateItem)) {
                    continue;
                }
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) {
                        continue;
                    }
                    for (ItemStack stack : ingredient.getMatchingStacks()) {
                        if (stack != null && !stack.isEmpty() && stack.isOf(requiredItem)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 忽略单条异常
            }
        }
        return false;
    }
}
