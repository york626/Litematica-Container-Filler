package com.mimicenzymes.litematicafiller.scan;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.criteria.Criterion;
import red.jackf.whereisit.api.criteria.builtin.AnyOfCriterion;
import red.jackf.whereisit.client.api.events.SearchInvoker;
import red.jackf.whereisit.client.api.events.SearchRequestPopulator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 【野外材料感知】箱子外框：**照搬 ChestTracker 点物品的行为**。
 *
 * <p>Ray 2026-11-18：「我在箱子追踪界面点一个物品，他就高亮那个箱子外框，套用他这个就行」。
 * ChestTracker 的实现就是：
 * <pre>
 * SearchRequest request = new SearchRequest();
 * SearchRequestPopulator.addItemStack(request, stack, Context.INVENTORY_PRECISE);
 * SearchInvoker.doSearch(request);
 * </pre>
 * → ChestTracker 的 WhereIsIt 插件查记忆库 → 内嵌 WhereIsIt 在世界里画 QUADS 外框（穿墙、淡出）。
 *
 * <p>这里只负责“把缺料清单丢进同一个搜索”，不自己画外框、不写 waypoint 文件。
 * WhereIsIt 是 ChestTracker 的内嵌依赖，没装就静默跳过（聊天提示不受影响）。
 */
public final class WhereIsItBridge {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    private static Boolean available;

    private WhereIsItBridge() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("red.jackf.whereisit.client.api.events.SearchInvoker");
                Class.forName("red.jackf.whereisit.client.api.events.SearchRequestPopulator");
                Class.forName("red.jackf.whereisit.api.criteria.builtin.AnyOfCriterion");
                available = true;
            } catch (Throwable ignored) {
                available = false;
            }
        }
        return available;
    }

    /** 用缺料清单触发一次 WhereIsIt 搜索；返回是否真的发出。 */
    public static boolean search(List<ItemStack> items) {
        if (items == null || items.isEmpty() || !isAvailable()) {
            return false;
        }
        try {
            // ⛔ 2026-11-19 根因：SearchRequest 把「顶层判据」当 AND 用（accept() 还会把
            //    AllOfCriterion 拆平到顶层）。以前逐项 addItemStack ⇒ 721 个物品 = 721 条判据
            //    全部 AND ⇒ 任何物品都不可能同时等于 721 种东西 ⇒ 0 结果、外框永远不亮。
            //    多条物品的正确写法是把每条包进一个 AnyOfCriterion（WhereIsIt 自己的 EMI 插件同款）。
            List<Criterion> criteria = new ArrayList<>();
            Set<Item> seen = new HashSet<>();
            for (ItemStack stack : items) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                // 同一物品只留一条判据（材料列表可能多行/多视图重复）；按物品类型匹配 =
                // WhereIsIt 背包里直接点物品的语义（带 Ctrl 才精确到组件）。
                if (!seen.add(stack.getItem())) {
                    continue;
                }
                SearchRequestPopulator.addItemStack(criteria::add, stack,
                        SearchRequestPopulator.Context.INVENTORY);
            }
            if (criteria.isEmpty()) {
                return false;
            }
            SearchRequest request = new SearchRequest();
            request.accept(new AnyOfCriterion(criteria).compact());
            boolean result = SearchInvoker.doSearch(request);
            LOGGER.info("[material-scan] 箱子外框搜索：{} 种物品（来源 {} 项）→ AnyOf {} 条判据，结果={}",
                    seen.size(), items.size(), criteria.size(), result);
            return result;
        } catch (Throwable t) {
            LOGGER.warn("[material-scan] 箱子外框搜索失败：{}", t.toString());
            return false;
        }
    }
}
