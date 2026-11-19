package com.mimicenzymes.litematicafiller.scan;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import red.jackf.whereisit.api.SearchResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【箱子外框·够用就停】（Ray 2026-11-19：「我需要玻璃，这两个箱子都亮起来了，他们都有玻璃，
 * 亮一个就行了」）。
 *
 * <p>WhereIsIt 只回答“哪些容器里有缺料”，不回答“够不够”。这里给每次背景搜索建一个**预算**：
 * 按**距离从近到远**遍历结果，用容器内容去扣需求量（{@link MaterialWatchlist.Snapshot#needByItem()}），
 * 需求扣完就不再保留后面的容器 ⇒ 只亮“够用”的那几个，最近的优先。
 *
 * <ul>
 *   <li>容器内容优先读箱子追踪记忆（{@link ChestTrackerMemoryReader}，盒装按盒内算）；
 *       记忆里没有（没开过/没扫过）⇒ 用 {@code SearchResult.item()} 的数量兜底；</li>
 *   <li>一个容器同时有多种缺料时按各自剩余量一起扣；</li>
 *   <li>每次搜索<b>新建一个实例</b>（记忆库结果与服端回包分两批进来，共用同一份剩余量）。</li>
 * </ul>
 */
public final class HighlightBudget {

    private final Map<Item, Long> remaining;
    private final Vec3d eye;
    private final Set<Long> keptPositions = new HashSet<>();

    public HighlightBudget(Map<Item, Long> need, MinecraftClient client) {
        this.remaining = need == null ? new HashMap<>() : new HashMap<>(need);
        this.eye = client != null && client.player != null
                ? client.player.getEyePos() : Vec3d.ZERO;
    }

    /** 过滤一批结果（可在一次搜索内多次调用：记忆库批次 + 服务端批次共用一个预算）。 */
    public Collection<SearchResult> filter(Collection<SearchResult> results) {
        if (results == null || results.isEmpty() || remaining.isEmpty()) {
            return results;
        }
        List<SearchResult> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparingDouble(
                result -> eye.squaredDistanceTo(Vec3d.ofCenter(result.pos()))));
        List<SearchResult> kept = new ArrayList<>(ordered.size());
        for (SearchResult result : ordered) {
            if (result == null) {
                continue;
            }
            long key = result.pos().asLong();
            if (keptPositions.contains(key)) {
                continue;   // 另一批已经保留过（同位置不重复算预算）
            }
            long contribution = takeFrom(result);
            if (contribution > 0) {
                keptPositions.add(key);
                kept.add(result);
            }
        }
        return kept;
    }

    /** 从容器内容里按剩余需求扣减；返回本次有效贡献（0 ⇒ 不需要它）。 */
    private long takeFrom(SearchResult result) {
        Map<Item, Long> contents = ChestTrackerMemoryReader.contentsAt(
                result.pos(), result.otherPositions());
        long contribution = 0L;
        if (!contents.isEmpty()) {
            for (Map.Entry<Item, Long> entry : contents.entrySet()) {
                long need = remaining.getOrDefault(entry.getKey(), 0L);
                if (need <= 0) {
                    continue;
                }
                long use = Math.min(need, entry.getValue());
                remaining.put(entry.getKey(), need - use);
                contribution += use;
            }
            return contribution;
        }
        // 记忆里没有：按匹配到的物品栈兜底（未开过的箱子；数量未知时按 1 个算）
        ItemStack item = result.item();
        if (item != null && !item.isEmpty()) {
            long need = remaining.getOrDefault(item.getItem(), 0L);
            if (need > 0) {
                long use = Math.min(need, Math.max(1, item.getCount()));
                remaining.put(item.getItem(), need - use);
                return use;
            }
        }
        return 0L;
    }
}
