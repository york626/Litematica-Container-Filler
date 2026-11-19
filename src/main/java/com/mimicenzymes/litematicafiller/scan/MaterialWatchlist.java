package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.materials.MaterialBreakdownHint;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【野外材料感知】当前“要盯的原料”快照。
 *
 * <p>数据源：GUI 材料列表当前视图的缺失行（{@link FillMaterialCalculator#getCurrentMissingMaterialsForCollection()}，
 * **不是 HUD 前几行**）→ 递归展开到可采集原料（{@link DeepMaterialPlanner}）→ 扣已有/黑名单 → 映射世界载体。
 *
 * <p>两份产出：
 * <ul>
 *   <li>{@code rawItems}：给附近扫描/聊天用（沙子、原木、牛…）；</li>
 *   <li>{@code searchItems}：给 ChestTracker/WhereIsIt 箱子搜索用（材料列表的**成品**缺失项）。</li>
 * </ul>
 */
public final class MaterialWatchlist {

    public record WatchItem(Item item, long need, MaterialScanTargets.Target target) {
    }

    public record Snapshot(List<WatchItem> rawItems, List<ItemStack> searchItems,
                           Map<Item, Long> needByItem,
                           Map<Item, Set<Item>> primaryRaw, long version) {
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    private static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), Map.of(), Map.of(), 0);

    private static volatile Snapshot snapshot = EMPTY;
    private static volatile boolean dirty = true;
    private static long version = 0;
    private static long lastRefreshMs = 0L;

    private MaterialWatchlist() {
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static void markDirty() {
        dirty = true;
    }

    public static boolean needsRefresh(long intervalMs) {
        return dirty || System.currentTimeMillis() - lastRefreshMs >= Math.max(250L, intervalMs);
    }

    public static void refresh(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null
                || client.player.networkHandler == null) {
            snapshot = EMPTY;
            return;
        }
        long started = System.currentTimeMillis();
        try {
            MaterialListBase materialList = DataManager.getMaterialList();
            if (materialList == null) {
                snapshot = EMPTY;
                dirty = false;
                lastRefreshMs = System.currentTimeMillis();
                return;
            }
            List<MaterialListEntry> rows = FillMaterialCalculator.getCurrentMissingMaterialsForCollection();
            Set<String> blacklist = new HashSet<>(Configs.MATERIAL_SCAN_BLACKLIST.getStrings());

            DeepMaterialPlanner.Plan plan = DeepMaterialPlanner.plan(rows, client.world,
                    client.player.networkHandler.getRecipeManager(),
                    MaterialBreakdownHint::availableOf,
                    blacklist);

            List<WatchItem> rawItems = new ArrayList<>();
            for (Map.Entry<Item, Long> entry : plan.rawNeed().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                MaterialScanTargets.Target target = MaterialScanTargets.targetFor(entry.getKey());
                if (target == null || (!target.hasBlock() && !target.hasEntity())) {
                    continue;   // 结构/NBT 物品没有世界载体 ⇒ 只走箱子搜索
                }
                rawItems.add(new WatchItem(entry.getKey(), entry.getValue(), target));
            }
            rawItems.sort(Comparator.comparingLong(WatchItem::need).reversed());

            // 【Ray 2026-11-19】箱子搜索条件 = 材料列表缺失（一级）+ 「绿色材料高亮」同源集合（含二级材料）。
            // 例：材料列表缺「灵魂灯笼 ×4」⇒ 绿色高亮会闪箱子里的「灵魂火把」（二级材料）；
            // 以前箱子搜索只带一级成品，箱子里有灵魂火把也不会亮 —— 两边口径必须一致。
            List<ItemStack> searchItems = new ArrayList<>(plan.primaryItems());
            Set<Item> searchSeen = new HashSet<>();
            for (ItemStack stack : searchItems) {
                if (stack != null && !stack.isEmpty()) {
                    searchSeen.add(stack.getItem());
                }
            }
            // 需求量（够用就停的预算用）：一级缺失 + 绿色高亮同源（二级材料也算），同一物品取较大值
            Map<Item, Long> needByItem = new HashMap<>();
            for (MaterialListEntry entry : rows) {
                if (entry == null || entry.getStack() == null || entry.getStack().isEmpty()) {
                    continue;
                }
                int missing = Math.max(0, entry.getCountMissing());
                if (missing > 0) {
                    needByItem.merge(entry.getStack().getItem(), (long) missing, Math::max);
                }
            }
            int primaryCount = searchItems.size();
            try {
                Map<Item, Long> highlightNeed = FillMaterialCalculator.getCurrentViewMissingForHighlight();
                for (Map.Entry<Item, Long> entry : highlightNeed.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                        continue;
                    }
                    if (searchSeen.add(entry.getKey())) {
                        searchItems.add(new ItemStack(entry.getKey()));
                    }
                    needByItem.merge(entry.getKey(), entry.getValue(), Math::max);
                }
            } catch (Throwable ignored) {
                // 高亮口径算不出来（材料列表没建好等）⇒ 退回只用一级缺失
            }

            snapshot = new Snapshot(List.copyOf(rawItems), List.copyOf(searchItems),
                    Map.copyOf(needByItem), plan.primaryRaw(), ++version);
            dirty = false;
            lastRefreshMs = System.currentTimeMillis();
            LOGGER.info("[material-scan] 清单刷新：{} ms｜原料 {} 种、箱子搜索 {} 项（缺料行 {}，其中一级 {} + 二级 {}）",
                    System.currentTimeMillis() - started, rawItems.size(), searchItems.size(), rows.size(),
                    primaryCount, searchItems.size() - primaryCount);
        } catch (Throwable t) {
            LOGGER.warn("[material-scan] 清单刷新失败：{}", t.toString());
        }
    }
}
