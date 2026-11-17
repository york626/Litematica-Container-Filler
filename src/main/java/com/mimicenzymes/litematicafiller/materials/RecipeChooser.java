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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **显示端与收集/提取端共用的「选哪条配方」**（Ray 2026-11-17 P3 #5）。
 *
 * <h2>为什么要有 Index</h2>
 * v1.20.16 第一版每次调用都遍历整张配方表，并且对每个候选再遍历一遍查「能不能互转」
 * ⇒ 收集 100 多个物品时变成几百次全表扫描（Ray 2026-11-17 报「自动收集木门非常卡」）。
 * 现在先建一次 **按产出物品分组** 的索引（{@code byOutput}），之后每个物品只看自己那几条候选，
 * 互转查询也只查候选物品的配方。
 *
 * <h2>选配方规则（与 HUD 的 ←/≈ 提示一致）</h2>
 * 能互转优先 → 原料种类少的优先 → 单次产量小的优先；tag 配方按 {@code options[0]} 取首个候选，
 * 自己合自己（9 锭 ↔ 1 块的反向）与 1 换 1 不参与。
 */
public final class RecipeChooser {

    /** 选中的配方：单次产量、每格原料（options[0]，同种按格子数合并）、能否互转。 */
    public record Choice(int outputCount, List<MaterialDemandCore.Ingredient> ingredients, boolean bidirectional) {
    }

    /**
     * 配方索引：建一次（按产出物品分组），之后 {@link #choose(Item)} 只查该物品那几条。
     * 配方表变化（数据包重载）后要重建。
     */
    public static final class Index {
        private final ClientWorld world;
        private final Map<Item, List<CraftingRecipe>> byOutput = new HashMap<>();

        private Index(ClientWorld world) {
            this.world = world;
        }

        /** 选中的配方；没有可用的合成配方 ⇒ {@code null}。 */
        public Choice choose(Item requiredItem) {
            if (world == null || requiredItem == null) {
                return null;
            }
            List<CraftingRecipe> holders = byOutput.get(requiredItem);
            if (holders == null || holders.isEmpty()) {
                return null;
            }
            List<Choice> candidates = new ArrayList<>(holders.size());
            for (CraftingRecipe recipe : holders) {
                try {
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
                            && hasReverseRecipe(counts.keySet().iterator().next(), requiredItem);
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

        /** 是否存在「用 requiredItem 造出 candidateItem」的配方（两个物品能互相转换）。 */
        private boolean hasReverseRecipe(Item candidateItem, Item requiredItem) {
            List<CraftingRecipe> holders = byOutput.get(candidateItem);
            if (holders == null) {
                return false;
            }
            for (CraftingRecipe recipe : holders) {
                try {
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

    private RecipeChooser() {
    }

    /** 建索引（按产出物品分组；`getResult` 只在这里算一次）。 */
    public static Index index(ClientWorld world, RecipeManager manager) {
        Index index = new Index(world);
        if (world == null || manager == null) {
            return index;
        }
        for (RecipeEntry<CraftingRecipe> holder : manager.listAllOfType(RecipeType.CRAFTING)) {
            try {
                CraftingRecipe recipe = holder.value();
                if (recipe == null) {
                    continue;
                }
                ItemStack output = recipe.getResult(world.getRegistryManager());
                if (output == null || output.isEmpty() || output.getCount() <= 0) {
                    continue;
                }
                index.byOutput.computeIfAbsent(output.getItem(), k -> new ArrayList<>()).add(recipe);
            } catch (Throwable ignored) {
                // 单条配方异常不影响索引
            }
        }
        return index;
    }

    /** 兼容旧调用（一次性索引；热路径请自行持有 {@link Index}）。 */
    public static Choice choose(ClientWorld world, RecipeManager manager, Item requiredItem) {
        return index(world, manager).choose(requiredItem);
    }
}
