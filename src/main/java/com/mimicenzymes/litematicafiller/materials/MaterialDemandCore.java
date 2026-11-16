package com.mimicenzymes.litematicafiller.materials;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 【材料需求核心】—— 收集 / 提取 与材料列表显示**共用的一套折算**（批次 3 · 步骤 A）。
 *
 * <h2>为什么要有它</h2>
 * <p>以前「二级材料」在两边各算一套：材料列表显示（工具箱 `MaterialBreakdownHint`）用
 * 「自身需求 + 单向需求」，收集（本模组 `expandCraftingIngredients`）用「总需求 − 持有 − 富余」，
 * 结果同一条需求两个数 ⇒ 2026-11-16 连续三个 bug（还缺 1 却不收、缺 10 只收 9、能整盒却拆散货）都是这个根。
 *
 * <h2>边界（保持“纯”）</h2>
 * <ul>
 *   <li><b>不碰渲染 / 网络 / 配置</b>：配方查询、持有量、自用总量都由调用方以函数/lambda 传进来；</li>
 *   <li>只做四件事：按行找配方 → 折算原料量 → 聚合 → 统一扣一次「富余」；</li>
 *   <li>结果同时给出**加进清单的原料**、**每行的二级计划**（服务端判成品够不够用）、
 *       **每种原料的中间量**（判据日志用）。</li>
 * </ul>
 *
 * <p>⚠️ 步骤 A 只把**收集侧**搬进来、行为一字不改（{@code FillMaterialCalculator} 只是薄适配器）；
 * 步骤 B 再把显示侧切到同一份输出。
 */
public final class MaterialDemandCore {

    /** 一条原料槽：物品 + 单次配方消耗量。 */
    public record Ingredient(Item item, int amount) {
    }

    /** 配方的最小信息：单次产量 + 原料槽（第一条能产出该物品的配方）。 */
    public record Recipe(int outputCount, List<Ingredient> ingredients) {
    }

    /** 配方查询（客户端提供；找不到返回 null）。 */
    public interface RecipeSource {
        Recipe find(Item output);
    }

    /** 一条行的自身需求快照（显示侧将直接复用）。 */
    public record RowDemand(Item item, int total, int held, int missing) {
    }

    /** 某个原料的中间量：聚合需求 / 已有 / 自用 / 富余 / 最终。 */
    public record Demand(Item item, int aggregated, int have, int reserved, int spare, int finalNeed) {
    }

    /** 一行展开出来的原料（原始量，**未扣富余**；服务端按容器里成品够多少来折算要不要收）。 */
    public record SecondaryPlan(Item primary, int need, List<ItemStack> ingredients) {
    }

    /** 二级展开结果。 */
    public record Result(List<ItemStack> additions, List<SecondaryPlan> plan, List<Demand> demands) {
    }

    /** 自动收集结果：要收的清单 ＋ 二级计划。 */
    public record AutoCollectResult(List<ItemStack> items, List<SecondaryPlan> plan) {
    }

    private MaterialDemandCore() {
    }

    /**
     * 二级展开（与 v1.16.x 历史行为一致，仅抽离）：
     * 每行找第一条配方 → {@code times = ceil(行缺失 / 单次产量)} → 原料量 = 单槽量 × times；
     * 所有行按物品聚合后**统一扣一次富余**（{@code spare = max(0, 持有 − 该物品自用总量)}）。
     *
     * @param wanted   当前视图的缺失清单（每条的 count＝本行缺失量）
     * @param recipes  配方查询
     * @param held     该物品**现在身上**有多少
     * @param reserved 该物品**在整份清单里的自身总需求**（直接放置要用的部分）
     * @param threshold 二级展开阈值（行缺失量超过它就不展开原料）
     */
    public static Result expand(List<ItemStack> wanted, RecipeSource recipes,
                                ToIntFunction<Item> held, ToIntFunction<Item> reserved,
                                int threshold) {
        List<SecondaryPlan> plan = new ArrayList<>();
        Map<Item, Integer> totalNeeds = new LinkedHashMap<>();
        if (wanted != null) {
            for (ItemStack want : wanted) {
                if (want == null || want.isEmpty()) {
                    continue;
                }
                int missing = want.getCount();
                if (missing <= 0 || missing > threshold) {
                    continue;   // 巨量缺料不展开原料（走专门获取途径）
                }
                Recipe recipe = recipes.find(want.getItem());
                if (recipe == null || recipe.outputCount() <= 0 || recipe.ingredients().isEmpty()) {
                    continue;
                }
                int times = (int) Math.ceil((double) missing / (double) recipe.outputCount());
                if (times <= 0) {
                    continue;
                }
                Map<Item, Integer> rowNeeds = new LinkedHashMap<>();
                for (Ingredient ingredient : recipe.ingredients()) {
                    int need = ingredient.amount() * times;
                    if (need > 0) {
                        rowNeeds.merge(ingredient.item(), need, Integer::sum);
                    }
                }
                if (rowNeeds.isEmpty()) {
                    continue;
                }
                List<ItemStack> rowIngredients = new ArrayList<>();
                rowNeeds.forEach((item, amount) -> {
                    totalNeeds.merge(item, amount, Integer::sum);
                    rowIngredients.add(new ItemStack(item, amount));
                });
                plan.add(new SecondaryPlan(want.getItem(), missing, rowIngredients));
            }
        }
        List<ItemStack> additions = new ArrayList<>();
        List<Demand> demands = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : totalNeeds.entrySet()) {
            int have = held.applyAsInt(entry.getKey());
            int res = reserved.applyAsInt(entry.getKey());
            int spare = Math.max(0, have - res);
            int need = Math.max(0, entry.getValue() - spare);
            demands.add(new Demand(entry.getKey(), entry.getValue(), have, res, spare, need));
            if (need > 0) {
                additions.add(new ItemStack(entry.getKey(), need));
            }
        }
        return new Result(additions, plan, demands);
    }
}
