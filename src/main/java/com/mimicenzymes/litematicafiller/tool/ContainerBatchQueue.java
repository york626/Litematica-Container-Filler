package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「批量工具」的容器队列（Ray 2026-11-06 定口径：**以 Litematica 投影选区为准**）。
 *
 * <p>选区框到哪就处理哪：扫选区里的真实方块实体，只收 {@link ContainerBlockFilter#isAllowedForTools}
 * 认的容器；没有选区时才退回"已启用投影里带容器数据的位置"（老行为）。</p>
 *
 * <p>★ 1.9.1 的教训：判区块加载必须用单参数 {@code world.isChunkLoaded(BlockPos)} ——
 * 双参数重载的参数含义不同，传 {@code >>4} 的区块坐标会被当方块坐标 ⇒ 全判"未加载" ⇒ 队列恒空。</p>
 */
public final class ContainerBatchQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");
    /** 选区可能很大（几十万格）⇒ 扫到这么多格就停，避免按一下卡住。 */
    private static final int MAX_SELECTION_SCAN = 200_000;

    private ContainerBatchQueue() {
    }

    /**
     * 建队列（就近优先）。
     *
     * @return 可用容器位置；空 = 选区/投影里没有可用容器
     */
    public static List<BlockPos> build(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return List.of();
        }

        Set<BlockPos> candidates = new HashSet<>();
        boolean capped = false;
        int scanned = 0;

        AreaSelection selection = null;
        try {
            selection = DataManager.getSelectionManager().getCurrentSelection();
        } catch (Throwable ignored) {
        }

        if (selection != null && selection.getAllSubRegionBoxes() != null
                && !selection.getAllSubRegionBoxes().isEmpty()) {
            outer:
            for (Box box : selection.getAllSubRegionBoxes()) {
                if (box == null || box.getPos1() == null || box.getPos2() == null) {
                    continue;
                }
                BlockPos a = box.getPos1();
                BlockPos b = box.getPos2();
                int minX = Math.min(a.getX(), b.getX());
                int minY = Math.min(a.getY(), b.getY());
                int minZ = Math.min(a.getZ(), b.getZ());
                int maxX = Math.max(a.getX(), b.getX());
                int maxY = Math.max(a.getY(), b.getY());
                int maxZ = Math.max(a.getZ(), b.getZ());
                for (BlockPos pos : BlockPos.iterate(minX, minY, minZ, maxX, maxY, maxZ)) {
                    if (++scanned > MAX_SELECTION_SCAN) {
                        capped = true;
                        break outer;
                    }
                    if (!client.world.isChunkLoaded(pos)) {
                        continue;
                    }
                    BlockState state = client.world.getBlockState(pos);
                    if (state.isAir() || !state.hasBlockEntity()) {
                        continue;
                    }
                    if (!ContainerBlockFilter.isAllowedForTools(state, client.world, pos)) {
                        continue;
                    }
                    candidates.add(normalize(client, pos, state));
                }
            }
        }

        boolean fromSelection = !candidates.isEmpty();
        if (!fromSelection) {
            // 没有选区：退回"已启用投影里带容器数据的位置"（与容器工具以前的行为一致）。
            try {
                for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                    if (placement == null || !placement.isEnabled()) {
                        continue;
                    }
                    for (BlockPos pos : LitematicaPlacementContainerData.getContainerPositions(placement)) {
                        candidates.add(pos.toImmutable());
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Vec3d eye = client.player.getEyePos();
        List<BlockPos> usable = new ArrayList<>();
        int notLoaded = 0;
        int notContainer = 0;
        for (BlockPos pos : candidates) {
            BlockPos immutable = pos.toImmutable();
            if (!client.world.isChunkLoaded(immutable)) {
                notLoaded++;
                continue;
            }
            BlockState state = client.world.getBlockState(immutable);
            if (state.isAir() || !state.hasBlockEntity()
                    || !ContainerBlockFilter.isAllowedForTools(state, client.world, immutable)) {
                notContainer++;
                continue;
            }
            usable.add(immutable);
        }
        usable.sort(Comparator.comparingDouble(pos -> eye.squaredDistanceTo(Vec3d.ofCenter(pos))));

        LOGGER.info("[batch] 容器队列：候选 {} / 可用 {}（未加载 {}，非容器/被过滤 {}）"
                        + " ｜ 来源：{}，扫描 {} 格{}",
                candidates.size(), usable.size(), notLoaded, notContainer,
                fromSelection ? "投影选区" : "已加载投影",
                scanned, capped ? "（超过上限已截断）" : "");
        return usable;
    }

    /** 双箱统一用第一半，避免同一对箱子进队两次。 */
    private static BlockPos normalize(MinecraftClient client, BlockPos pos, BlockState state) {
        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, pos, state);
        return halves != null ? halves[0].toImmutable() : pos.toImmutable();
    }
}
