package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「批量容器操作」的队列（Ray 2026-11-06 需求，设计见仓库根 `批量容器操作-设计.md`）。
 *
 * <p>把**投影里带容器数据的方块实体**（`LitematicaPlacementContainerData.getContainerPositions`）
 * 排成队列、就近优先，交给 {@link ContainerToolStateMachine} 逐个处理 —— 容器工具原来只认准星
 * 指着的那个，现在能一次处理一整片。</p>
 *
 * <p>⚠️ 只收「投影里真的有容器数据的方块实体」：**不会**把玩家自己放的箱子也掏空。</p>
 */
public final class ContainerBatchQueue {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");
    /** 选区可能很大（几十万格）⇒ 扫到这么多格就停，避免按一下卡住。 */
    private static final int MAX_SELECTION_SCAN = 200_000;

    private final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
    private final Set<BlockPos> completed = new HashSet<>();
    private int total;

    /** 是否还有没处理的。 */
    public boolean isEmpty() {
        return this.pending.isEmpty();
    }

    public BlockPos peek() {
        return this.pending.peek();
    }

    public BlockPos poll() {
        return this.pending.poll();
    }

    public int total() {
        return this.total;
    }

    public int doneCount() {
        return this.completed.size();
    }

    public void complete(BlockPos pos) {
        if (pos != null) {
            this.completed.add(pos.toImmutable());
        }
    }

    public void clear() {
        this.pending.clear();
        this.completed.clear();
        this.total = 0;
    }

    /**
     * 重建队列（就近优先）。
     *
     * <p>★ v1.9.2-Ray（Ray 2026-11-06 定口径）：「容器工具」走的是**投影选区** ——
     * <b>只处理选区框出来的范围内的容器</b>；没有选区时才退回"已加载投影里带容器数据的位置"。</p>
     *
     * @return 入队数量（0 = 选区/投影里没有可用容器）
     */
    public int build(MinecraftClient client) {
        this.clear();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return 0;
        }
        Set<BlockPos> candidates = new HashSet<>();
        boolean capped = false;

        // ① 优先：投影**选区**框内的世界里所有容器方块实体（Ray 实际用法：框选 → 清空）
        AreaSelection selection = null;
        try {
            selection = DataManager.getSelectionManager().getCurrentSelection();
        } catch (Throwable ignored) {
        }
        int scanned = 0;
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
                    net.minecraft.block.BlockState state = client.world.getBlockState(pos);
                    if (state.isAir() || !state.hasBlockEntity()) {
                        continue;
                    }
                    if (!ContainerBlockFilter.isContainerLike(state, client.world, pos)) {
                        continue;
                    }
                    candidates.add(pos.toImmutable());
                }
            }
        }

        // ② 没有选区时退回：已启用投影里"带容器数据"的方块实体（老行为）
        boolean fromSelection = !candidates.isEmpty();
        if (!fromSelection) {
            try {
                for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                    if (placement == null || !placement.isEnabled()) {
                        continue;
                    }
                    candidates.addAll(LitematicaPlacementContainerData.getContainerPositions(placement));
                }
            } catch (Throwable ignored) {
            }
        }

        Vec3d eye = player.getEyePos();
        List<BlockPos> usable = new ArrayList<>();
        int notLoaded = 0;
        int airOrNoBe = 0;
        int filtered = 0;
        for (BlockPos pos : candidates) {
            BlockPos immutable = pos.toImmutable();
            // ⚠️ 必须用单参数 `isChunkLoaded(BlockPos)` —— 工程里到处用的是这个；
            //    双参数版本参数含义不同，之前传 `>>4` 的区块坐标会被当成方块坐标 ⇒ 全部判成"未加载"⇒ 队列恒为空。
            if (!client.world.isChunkLoaded(immutable)) {
                notLoaded++;
                continue;
            }
            if (client.world.getBlockState(immutable).isAir()
                    || !client.world.getBlockState(immutable).hasBlockEntity()) {
                airOrNoBe++;   // 还没建出来 / 被换成别的方块了
                continue;
            }
            if (!ContainerBlockFilter.isAllowedForTools(client.world.getBlockState(immutable), client.world, immutable)) {
                filtered++;    // 被「容器过滤」排除
                continue;
            }
            usable.add(immutable);
        }
        usable.sort(Comparator.comparingDouble(pos -> eye.squaredDistanceTo(Vec3d.ofCenter(pos))));
        this.pending.addAll(usable);
        this.total = usable.size();
        LOGGER.info("[batch] 容器队列：候选 {} / 可用 {}（未加载 {}，空格或非方块实体 {}，被容器过滤 {}）"
                        + " ｜ 来源：{}，扫描 {} 格{}",
                candidates.size(), this.total, notLoaded, airOrNoBe, filtered,
                fromSelection ? "投影选区" : "已加载投影",
                scanned, capped ? "（超过上限已截断）" : "");
        return this.total;
    }
}
