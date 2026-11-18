package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.materials.MaterialDemandCore;
import com.mimicenzymes.litematicafiller.materials.RecipeChooser;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.BlastingRecipe;
import net.minecraft.recipe.SmokingRecipe;
import net.minecraft.recipe.CampfireCookingRecipe;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * 【野外材料感知】把 GUI 材料列表的缺料递归展开到“可直接采集的原料”。
 *
 * <p>Ray 2026-11-18 定的口径：楼梯 → 木板 → 原木、玻璃板 → 玻璃 → 沙子、台阶 → 石头 → 圆石，
 * 最多 4 层防环，停在 {@link MaterialScanTargets#isCuratedGatherable(Item)} 或“没有任何配方”的物品上。
 *
 * <p>配方来源：
 * <ul>
 *   <li>合成：复用 {@link RecipeChooser}（与材料列表 ← 提示同一套选配方）；</li>
 *   <li>熔炼/高炉/烟熏/营火：按产出物品建表（1:1 也收，和合成的“1 换 1 跳过”不同）；</li>
 *   <li>切石：按产出物品建表（石阶/墙等有时只有切石配方）；</li>
 *   <li>混凝土：水转换不是配方 ⇒ 特判 {@code *_concrete → *_concrete_powder}。</li>
 * </ul>
 */
public final class DeepMaterialPlanner {

    /** 展开上限（Ray：最多 4 层防环）。 */
    public static final int MAX_DEPTH = 4;

    public record Plan(List<ItemStack> primaryItems, Map<Item, Long> rawNeed,
                       Map<Item, Set<Item>> primaryRaw) {
    }

    private record RecipeStep(int outputCount, List<MaterialDemandCore.Ingredient> ingredients) {
    }

    private record Demand(Item item, long need, int depth, Set<Item> path, Item origin) {
    }

    /** 配方表缓存：同一 manager/world 只建一次烹饪/切石表。 */
    private static RecipeManager cachedManager;
    private static ClientWorld cachedWorld;
    private static Map<Item, RecipeStep> cachedCooking;
    private static Map<Item, RecipeStep> cachedStonecutting;

    private DeepMaterialPlanner() {
    }

    public static Plan plan(List<MaterialListEntry> rows,
                            ClientWorld world,
                            RecipeManager manager,
                            ToIntFunction<Item> available,
                            Set<String> blacklist) {
        Map<Item, Long> raw = new LinkedHashMap<>();
        Map<Item, Set<Item>> primaryRaw = new LinkedHashMap<>();
        List<ItemStack> primaryItems = new ArrayList<>();
        if (rows == null || rows.isEmpty() || world == null || manager == null) {
            return new Plan(primaryItems, raw, primaryRaw);
        }

        RecipeChooser.Index crafting = RecipeChooser.index(world, manager);
        Map<Item, RecipeStep> cooking = cookingSteps(world, manager);
        Map<Item, RecipeStep> stonecutting = stonecuttingSteps(world, manager);

        Deque<Demand> queue = new ArrayDeque<>();
        for (MaterialListEntry entry : rows) {
            if (entry == null) continue;
            ItemStack stack = entry.getStack();
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (isBlacklisted(item, blacklist)) continue;
            int missing = Math.max(0, entry.getCountMissing());
            if (missing <= 0) continue;
            ItemStack searchStack = stack.copy();
            searchStack.setCount(1);
            primaryItems.add(searchStack);

            Set<Item> path = new HashSet<>();
            path.add(item);
            queue.add(new Demand(item, missing, 0, path, item));
        }

        while (!queue.isEmpty()) {
            Demand demand = queue.poll();
            if (demand.need() <= 0) continue;

            RecipeStep step = findStep(demand.item(), crafting, cooking, stonecutting);
            boolean curated = MaterialScanTargets.isCuratedGatherable(demand.item());

            if (curated || step == null || demand.depth() >= MAX_DEPTH) {
                raw.merge(demand.item(), demand.need(), Long::sum);
                primaryRaw.computeIfAbsent(demand.origin(), k -> new HashSet<>()).add(demand.item());
                continue;
            }

            long times = ceilDiv(demand.need(), Math.max(1, step.outputCount()));
            for (MaterialDemandCore.Ingredient ingredient : step.ingredients()) {
                if (ingredient == null || ingredient.item() == null || ingredient.amount() <= 0) {
                    continue;
                }
                long childNeed = ingredient.amount() * times;
                if (childNeed <= 0) {
                    continue;
                }
                if (demand.path().contains(ingredient.item())) {
                    // 环：把当前物品当原料收，避免无限递归（例如红石粉 ⇄ 红石块）
                    raw.merge(demand.item(), demand.need(), Long::sum);
                    primaryRaw.computeIfAbsent(demand.origin(), k -> new HashSet<>()).add(demand.item());
                    continue;
                }
                Set<Item> path = new HashSet<>(demand.path());
                path.add(ingredient.item());
                queue.add(new Demand(ingredient.item(), childNeed, demand.depth() + 1, path, demand.origin()));
            }
        }

        // 扣已有（背包 + 潜影盒 + 精妙背包；与材料列表“已有”同一口径）
        Map<Item, Long> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : raw.entrySet()) {
            int have = 0;
            try {
                have = available.applyAsInt(entry.getKey());
            } catch (Throwable ignored) {
            }
            long need = Math.max(0L, entry.getValue() - (long) have);
            if (need > 0) {
                out.put(entry.getKey(), need);
            }
        }
        return new Plan(primaryItems, out, primaryRaw);
    }

    private static boolean isBlacklisted(Item item, Set<String> blacklist) {
        if (blacklist == null || blacklist.isEmpty() || item == null) {
            return false;
        }
        String id = Registries.ITEM.getId(item).toString();
        for (String raw : blacklist) {
            if (raw == null) continue;
            String pattern = raw.trim();
            if (pattern.isEmpty()) continue;
            if (pattern.equals(id) || pattern.equals(Registries.ITEM.getId(item).getPath())) {
                return true;
            }
        }
        return false;
    }

    private static RecipeStep findStep(Item item,
                                       RecipeChooser.Index crafting,
                                       Map<Item, RecipeStep> cooking,
                                       Map<Item, RecipeStep> stonecutting) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        // 混凝土：水转换不是配方
        Identifier id = Registries.ITEM.getId(item);
        String path = id.getPath();
        if (path.endsWith("_concrete")) {
            Item powder = Registries.ITEM.get(Identifier.of(id.getNamespace(),
                    path.substring(0, path.length() - "_concrete".length()) + "_concrete_powder"));
            if (powder != Items.AIR && powder != item) {
                return new RecipeStep(1, List.of(new MaterialDemandCore.Ingredient(powder, 1)));
            }
        }

        RecipeChooser.Choice choice = crafting.choose(item);
        if (choice != null && choice.outputCount() > 0 && !choice.ingredients().isEmpty()) {
            return new RecipeStep(choice.outputCount(), choice.ingredients());
        }
        RecipeStep cooked = cooking.get(item);
        if (cooked != null) {
            return cooked;
        }
        return stonecutting.get(item);
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1L) / divisor;
    }

    // ---------------------------------------------------------------- 烹饪/切石表

    private static Map<Item, RecipeStep> cookingSteps(ClientWorld world, RecipeManager manager) {
        if (cachedCooking != null && cachedManager == manager && cachedWorld == world) {
            return cachedCooking;
        }
        Map<Item, RecipeStep> out = new HashMap<>();
        try {
            for (RecipeEntry<SmeltingRecipe> entry : manager.listAllOfType(RecipeType.SMELTING)) {
                putCooking(out, entry == null ? null : entry.value(), world);
            }
            for (RecipeEntry<BlastingRecipe> entry : manager.listAllOfType(RecipeType.BLASTING)) {
                putCooking(out, entry == null ? null : entry.value(), world);
            }
            for (RecipeEntry<SmokingRecipe> entry : manager.listAllOfType(RecipeType.SMOKING)) {
                putCooking(out, entry == null ? null : entry.value(), world);
            }
            for (RecipeEntry<CampfireCookingRecipe> entry : manager.listAllOfType(RecipeType.CAMPFIRE_COOKING)) {
                putCooking(out, entry == null ? null : entry.value(), world);
            }
        } catch (Throwable ignored) {
        }
        cachedManager = manager;
        cachedWorld = world;
        cachedCooking = out;
        return out;
    }

    private static void putCooking(Map<Item, RecipeStep> out, AbstractCookingRecipe recipe, ClientWorld world) {
        if (recipe == null) return;
        try {
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result == null || result.isEmpty()) return;
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients == null || ingredients.isEmpty()) return;
            Ingredient first = ingredients.get(0);
            if (first == null || first.isEmpty()) return;
            ItemStack[] matching = first.getMatchingStacks();
            if (matching.length == 0 || matching[0] == null || matching[0].isEmpty()) return;
            out.putIfAbsent(result.getItem(), new RecipeStep(1,
                    List.of(new MaterialDemandCore.Ingredient(matching[0].getItem(), 1))));
        } catch (Throwable ignored) {
        }
    }

    private static Map<Item, RecipeStep> stonecuttingSteps(ClientWorld world, RecipeManager manager) {
        if (cachedStonecutting != null && cachedManager == manager && cachedWorld == world) {
            return cachedStonecutting;
        }
        Map<Item, RecipeStep> out = new HashMap<>();
        try {
            for (RecipeEntry<StonecuttingRecipe> entry : manager.listAllOfType(RecipeType.STONECUTTING)) {
                StonecuttingRecipe recipe = entry == null ? null : entry.value();
                if (recipe == null) continue;
                ItemStack result = recipe.getResult(world.getRegistryManager());
                if (result == null || result.isEmpty() || result.getCount() <= 0) continue;
                List<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients == null || ingredients.isEmpty()) continue;
                Ingredient first = ingredients.get(0);
                if (first == null || first.isEmpty()) continue;
                ItemStack[] matching = first.getMatchingStacks();
                if (matching.length == 0 || matching[0] == null || matching[0].isEmpty()) continue;
                out.putIfAbsent(result.getItem(), new RecipeStep(result.getCount(),
                        List.of(new MaterialDemandCore.Ingredient(matching[0].getItem(), 1))));
            }
        } catch (Throwable ignored) {
        }
        cachedManager = manager;
        cachedWorld = world;
        cachedStonecutting = out;
        return out;
    }
}
