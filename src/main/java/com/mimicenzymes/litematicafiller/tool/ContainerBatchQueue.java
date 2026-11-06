package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
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
     * 用当前世界里所有**已启用投影**的容器位置重建队列（就近优先）。
     *
     * @return 入队数量（0 = 选区内没有可用容器）
     */
    public int build(MinecraftClient client) {
        this.clear();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return 0;
        }
        Set<BlockPos> candidates = new HashSet<>();
        try {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                if (placement == null || !placement.isEnabled()) {
                    continue;
                }
                candidates.addAll(LitematicaPlacementContainerData.getContainerPositions(placement));
            }
        } catch (Throwable ignored) {
            // 投影数据读不到（没装投影/正在切换）⇒ 就当没有容器，调用方会提示
            return 0;
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
        LOGGER.info("[batch] 投影容器：候选 {} / 可用 {}（未加载 {}，空格或非方块实体 {}，被容器过滤 {}）",
                candidates.size(), this.total, notLoaded, airOrNoBe, filtered);
        return this.total;
    }
}
