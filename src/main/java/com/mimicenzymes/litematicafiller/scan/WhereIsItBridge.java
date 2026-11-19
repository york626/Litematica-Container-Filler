package com.mimicenzymes.litematicafiller.scan;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.criteria.Criterion;
import red.jackf.whereisit.api.criteria.builtin.AnyOfCriterion;
import red.jackf.whereisit.client.api.events.SearchInvoker;
import red.jackf.whereisit.client.api.events.SearchRequestPopulator;
import red.jackf.whereisit.client.render.Rendering;

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

    /** 背景搜索后「抑制 WhereIsIt 槽位高亮」的截止时间（ms）。 */
    private static long suppressSlotHighlightUntilMs = 0L;
    /** 抑制窗口：覆盖 WhereIsIt 服务端回包把 lastRequest 再设回来（通常 1~2 tick）。 */
    private static final long SUPPRESS_WINDOW_MS = 3000L;
    /** 最近一次背景搜索用的请求（只读诊断：核对“箱子里的东西到底在不在搜索条件里”）。 */
    private static volatile SearchRequest lastRequest;

    private WhereIsItBridge() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("red.jackf.whereisit.client.api.events.SearchInvoker");
                Class.forName("red.jackf.whereisit.client.api.events.SearchRequestPopulator");
                Class.forName("red.jackf.whereisit.api.criteria.builtin.AnyOfCriterion");
                Class.forName("red.jackf.whereisit.client.render.Rendering");
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
            lastRequest = request;
            boolean result = SearchInvoker.doSearch(request);
            // 背景搜索只负责世界外框；打开界面后的槽位高亮交给 CF 自己的「材料高亮」
            // （只闪够用的盒子、跳过玩家背包/精妙背包界面）。这里开启抑制窗口。
            suppressSlotHighlightUntilMs = System.currentTimeMillis() + SUPPRESS_WINDOW_MS;
            LOGGER.info("[material-scan] 箱子外框搜索：{} 种物品（来源 {} 项）→ AnyOf {} 条判据，结果={}",
                    seen.size(), items.size(), criteria.size(), result);
            return result;
        } catch (Throwable t) {
            LOGGER.warn("[material-scan] 箱子外框搜索失败：{}", t.toString());
            return false;
        }
    }

    /**
     * 每 tick 调用：抑制 WhereIsIt 的**槽位高亮**（黄框），只保留世界外框。
     *
     * <p>Ray 2026-11-19：「玩家自己背包 / 身上的精妙背包里的物品也高亮了，这不对」＋
     * 「箱子里一排盒装煤炭块都在高亮，只高亮够用的一个盒子就行」。
     * 两个现象都是 WhereIsIt 的 `Rendering.renderSlotHighlight`：它拿 `lastRequest`
     * 把**任何界面**（玩家背包、精妙背包、箱子）里匹配的槽位全涂黄，而且不看数量。
     *
     * <p>做法：背景搜索后的 {@link #SUPPRESS_WINDOW_MS} 内每 tick 把 `lastRequest` 清掉
     * （WhereIsIt 服务端回包会再设回来，所以必须持续清）。窗口之外不动 —— 玩家自己在
     * 容器界面里手动发的 WhereIsIt 搜索仍然照常高亮槽位。
     */
    public static void suppressSlotHighlights() {
        if (suppressSlotHighlightUntilMs == 0L || !isAvailable()) {
            return;
        }
        if (System.currentTimeMillis() >= suppressSlotHighlightUntilMs) {
            suppressSlotHighlightUntilMs = 0L;
            return;
        }
        try {
            Rendering.setLastRequest(null);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 只读诊断

    /** WhereIsIt 当前结果数（-1 = API 不可用）。 */
    public static int resultCount() {
        if (!isAvailable()) {
            return -1;
        }
        try {
            return Rendering.getResults().size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 这个方块位置是否在 WhereIsIt 当前结果里（世界外框就是按这个 Map 画的）。 */
    public static boolean isHighlighted(BlockPos pos) {
        if (pos == null || !isAvailable()) {
            return false;
        }
        try {
            return Rendering.getResults().containsKey(pos);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这个物品（含盒内内容，WhereIsIt 的嵌套搜索语义）是否命中上次背景搜索的条件。 */
    public static boolean matches(ItemStack stack) {
        SearchRequest request = lastRequest;
        if (request == null || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return SearchRequest.check(stack, request);
        } catch (Throwable t) {
            return false;
        }
    }
}
