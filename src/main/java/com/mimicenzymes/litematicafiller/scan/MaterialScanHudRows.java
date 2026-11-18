package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.block.Block;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【野外材料感知】把 HUD 材料列表里「附近能补」的行提到最上面。
 *
 * <p>Ray 2026-11-18 定稿：不新开一块“附近可采”，而是**复用原清单的行**——
 * 原清单里某行的原料在附近有货 ⇒ 这行**排到最上面**，行尾加 `← 金合欢原木 ×172`；
 * 原位置不再重复出现（隐藏重复）。这样收集完附近这批，行自己会消失，正好对上。
 *
 * <p>原木类按**附近数量最多的树种**显示（金合欢林 → `← 金合欢原木`，白桦林 → `← 白桦原木`）。
 * 只看暴露方块（扫描器配置），默认最多 6 行。
 */
public final class MaterialScanHudRows {

    private static final int MAX_ROWS = 6;

    /** 缓存：同一份清单 + 同一轮扫描只重建一次（HUD 每帧会问两次，重建会闪）。 */
    private static long cachedSnapshotVersion = -1L;
    private static long cachedCycleVersion = -1L;
    private static int cachedNormalSize = -1;
    private static List<MaterialListEntry> cached = List.of();

    private MaterialScanHudRows() {
    }

    /** 返回需要提前的“原清单行”（已复制并改过显示名）；调用方把这些行放最前、原位置跳过。 */
    public static List<MaterialListEntry> promoted(List<MaterialListEntry> normal) {
        MaterialWatchlist.Snapshot snapshot = MaterialWatchlist.snapshot();
        if (!Configs.MATERIAL_SCAN_HUD.getBooleanValue()
                || snapshot == null || snapshot.rawItems().isEmpty()
                || normal == null || normal.isEmpty()) {
            cached = List.of();
            return cached;
        }
        long cycle = NearbyMaterialScanner.cycleVersion();
        if (snapshot.version() == cachedSnapshotVersion
                && cycle == cachedCycleVersion
                && normal.size() == cachedNormalSize) {
            return cached;
        }
        Map<Item, Long> nearby = new HashMap<>(NearbyMaterialScanner.lastBlockCounts());
        NearbyMaterialScanner.lastEntityCounts().forEach((item, count) -> nearby.merge(item, count, Long::sum));
        if (nearby.isEmpty()) {
            return List.of();
        }

        Map<Item, MaterialWatchlist.WatchItem> watchByItem = new HashMap<>();
        for (MaterialWatchlist.WatchItem item : snapshot.rawItems()) {
            watchByItem.put(item.item(), item);
        }

        List<MaterialListEntry> promoted = new ArrayList<>();
        Set<MaterialListEntry> seen = new HashSet<>();
        for (MaterialListEntry entry : normal) {
            if (promoted.size() >= MAX_ROWS) {
                break;
            }
            if (entry == null || entry.getStack().isEmpty()) {
                continue;
            }
            Set<Item> raws = snapshot.primaryRaw().get(entry.getStack().getItem());
            if (raws == null || raws.isEmpty()) {
                continue;
            }
            long nearbySum = 0L;
            String source = null;
            for (Item raw : raws) {
                long count = nearby.getOrDefault(raw, 0L);
                if (count <= 0) {
                    continue;
                }
                nearbySum += count;
                if (source == null) {
                    source = displaySource(raw, watchByItem);
                }
            }
            if (nearbySum <= 0L || source == null) {
                continue;
            }
            if (!seen.add(entry)) {
                continue;
            }
            promoted.add(withSuffix(entry, source, nearbySum));
        }
        cachedSnapshotVersion = snapshot.version();
        cachedCycleVersion = cycle;
        cachedNormalSize = normal.size();
        cached = promoted;
        return promoted;
    }

    /** 行尾加 `→ 金合欢原木 ×172`；用副本，绝不改原清单的数据。 */
    private static MaterialListEntry withSuffix(MaterialListEntry entry, String source, long nearbyCount) {
        ItemStack stack = entry.getStack().copy();
        String name = stack.getName().getString();
        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal(name + " → " + source + " ×" + nearbyCount));
        return new MaterialListEntry(stack, entry.getCountTotal(), entry.getCountMissing(),
                entry.getCountMismatched(), entry.getCountAvailable());
    }

    /**
     * 通用原木（橡木代表“任意木板”配方）⇒ 取附近最多的树种；
     * 白桦/丛林/竹块等具体树种 ⇒ 用它自己的名字，绝不跟附近树种变。
     */
    private static String displaySource(Item raw, Map<Item, MaterialWatchlist.WatchItem> watchByItem) {
        MaterialWatchlist.WatchItem watch = watchByItem.get(raw);
        if (watch == null) {
            return new ItemStack(raw).getName().getString();
        }
        boolean genericLog = raw == net.minecraft.item.Items.OAK_LOG
                || raw == net.minecraft.item.Items.OAK_WOOD
                || raw == net.minecraft.item.Items.STRIPPED_OAK_LOG
                || raw == net.minecraft.item.Items.STRIPPED_OAK_WOOD;
        if (genericLog) {
            Map<Block, Long> byBlock = NearbyMaterialScanner.lastBlockByBlock();
            Block best = null;
            long bestCount = -1L;
            for (Block block : watch.target().blocks()) {
                long count = byBlock.getOrDefault(block, 0L);
                if (count > bestCount) {
                    best = block;
                    bestCount = count;
                }
            }
            if (best != null && bestCount > 0L) {
                return new ItemStack(best.asItem()).getName().getString();
            }
        }
        return watch.target().source();
    }
}
