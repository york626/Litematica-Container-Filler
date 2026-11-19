package com.mimicenzymes.litematicafiller.scan;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.util.math.BlockPos;
import red.jackf.whereisit.api.SearchResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 【野外材料感知】箱子外框的**选区过滤**（Ray 2026-11-19）。
 *
 * <p>Ray：「改成高亮投影选区的范围内的容器，选区外的不用管，视觉噪音小一点」。
 * 有选区（Litematica 区域选择，简单/普通模式都算）时，只保留**落在选区盒内**的容器结果；
 * 没有选区时不过滤（保持原行为）。
 *
 * <p>大箱子（连体双箱）只选到一半也算整个：
 * WhereIsIt 的 {@link SearchResult} 主位置是其中一格、另一半在 {@code otherPositions} 里
 * ⇒ 只要有**任意一格**在选区内就保留整个结果，渲染时两半都会画框。
 *
 * <p>选区盒 1 秒缓存，避免每次搜索都读 Litematica。
 */
public final class SelectionFilter {

    private static final long CACHE_MS = 1000L;

    /** 选区盒：[minX, minY, minZ, maxX, maxY, maxZ]。 */
    private static List<int[]> boxes = List.of();
    private static long boxesAt = 0L;

    private SelectionFilter() {
    }

    private static List<int[]> boxes() {
        long now = System.currentTimeMillis();
        if (now - boxesAt < CACHE_MS) {
            return boxes;
        }
        boxesAt = now;
        List<int[]> out = new ArrayList<>();
        try {
            AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
            if (selection != null && selection.getAllSubRegionBoxes() != null) {
                for (Box box : selection.getAllSubRegionBoxes()) {
                    if (box == null || box.getPos1() == null || box.getPos2() == null) {
                        continue;
                    }
                    BlockPos a = box.getPos1();
                    BlockPos b = box.getPos2();
                    out.add(new int[]{
                            Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                            Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())
                    });
                }
            }
        } catch (Throwable ignored) {
            // Litematica 没加载好 / 没有选区
        }
        boxes = out;
        return boxes;
    }

    public static int boxCount() {
        return boxes().size();
    }

    private static boolean contains(List<int[]> boxes, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        for (int[] b : boxes) {
            if (pos.getX() >= b[0] && pos.getX() <= b[3]
                    && pos.getY() >= b[1] && pos.getY() <= b[4]
                    && pos.getZ() >= b[2] && pos.getZ() <= b[5]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 过滤搜索结果：没有选区 ⇒ 原样返回；有选区 ⇒ 主位置或 {@code otherPositions}
     * 任一格在选区内就保留整个结果（双箱只选一半也整箱亮）。
     */
    public static Collection<SearchResult> filter(Collection<SearchResult> results) {
        List<int[]> boxes = boxes();
        if (boxes.isEmpty() || results == null || results.isEmpty()) {
            return results;
        }
        List<SearchResult> kept = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            if (result == null) {
                continue;
            }
            boolean keep = contains(boxes, result.pos());
            if (!keep) {
                for (BlockPos other : result.otherPositions()) {
                    if (contains(boxes, other)) {
                        keep = true;
                        break;
                    }
                }
            }
            if (keep) {
                kept.add(result);
            }
        }
        return kept;
    }
}
