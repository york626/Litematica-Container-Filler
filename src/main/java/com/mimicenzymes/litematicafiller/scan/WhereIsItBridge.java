package com.mimicenzymes.litematicafiller.scan;

import net.minecraft.item.ItemStack;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.client.api.events.SearchInvoker;
import red.jackf.whereisit.client.api.events.SearchRequestPopulator;

import java.util.List;

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
 * → ChestTracker 的 WhereIsIt 插件查记忆库 → 内嵌 WhereIsIt 在世界里画 QUADS 外框（穿墙、15 秒淡出）。
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
            SearchRequest request = new SearchRequest();
            for (ItemStack stack : items) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                SearchRequestPopulator.addItemStack(request, stack,
                        SearchRequestPopulator.Context.INVENTORY_PRECISE);
            }
            if (!request.hasCriteria()) {
                return false;
            }
            boolean result = SearchInvoker.doSearch(request);
            LOGGER.info("[material-scan] 箱子外框搜索：{} 项，结果={}", items.size(), result);
            return result;
        } catch (Throwable t) {
            LOGGER.warn("[material-scan] 箱子外框搜索失败：{}", t.toString());
            return false;
        }
    }
}
