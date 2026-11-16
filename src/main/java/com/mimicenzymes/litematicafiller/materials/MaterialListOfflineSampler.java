package com.mimicenzymes.litematicafiller.materials;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * **直接从投影数据取方块状态**（不经过 Litematica 的「投影世界」）。
 *
 * <p>2026-11-16 从 Ray 工具箱迁入（批次 1：材料列表忽略「放置启用」的第二层）。
 *
 * <h2>为什么要它</h2>
 * <p>Litematica 的 {@code schematicWorld} 只由「已启用 + 已渲染」的放置填充
 * （{@code SchematicPlacement.getBoxesWithinChunk} / {@code getRegionsTouchingChunk}
 * 全部用 {@code RequiredEnabled.PLACEMENT_ENABLED}）⇒ 放置一关，那个世界里全是空气，
 * 材料统计（{@code TaskCountBlocksPlacement.countAtPosition}）读出来自然也是 0。
 *
 * <p>这里绕开那一层，直接拿 {@link LitematicaSchematic} 的
 * {@link LitematicaBlockStateContainer}：世界坐标减掉子区域包围盒的最小角，就是容器局部坐标。
 *
 * <h2>⚠️ 只在「无旋转、无镜像」时有效</h2>
 * <p>有旋转/镜像时世界坐标轴会互换，必须做逆变换；算错会让材料数字全错，
 * 比「不显示」更糟 ⇒ 这种情况直接返回 {@code null}，让调用方退回原版逻辑。
 *
 * <h2>缓存</h2>
 * <p>统计任务会为<b>每一个方块坐标</b>调用一次，所以区域列表按 1 秒缓存
 * （统计期间用户不会去搬投影）；同时做尺寸校验，对不上就当不可用。
 */
public final class MaterialListOfflineSampler {

    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    /** 区域列表缓存有效期（毫秒）。统计任务通常几秒内跑完。 */
    private static final long CACHE_MS = 1000L;

    private static SchematicPlacement cachedPlacement;
    private static long cachedAt;
    private static List<Region> cachedRegions = List.of();

    /** 「离线采样真的用上了」日志只打一次（Mixin 里不能放静态初始化字段，挪到这里）。 */
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    /** v1.18.3 诊断：采样调用 / 命中次数（任务收尾时打印，用于区分「没跑」和「跑了但全空」）。 */
    private static final java.util.concurrent.atomic.AtomicLong SAMPLE_CALLS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SAMPLE_HITS =
            new java.util.concurrent.atomic.AtomicLong();

    private MaterialListOfflineSampler() {
    }

    public static long sampleCalls() {
        return SAMPLE_CALLS.get();
    }

    public static long sampleHits() {
        return SAMPLE_HITS.get();
    }

    /**
     * 取某个世界坐标在投影里应该是什么方块。
     *
     * @return 方块状态；不在任何可用子区域内 / 结构不认识时返回 {@code null}
     */
    public static BlockState sampleAt(SchematicPlacement placement, BlockPos pos) {
        if (placement == null || pos == null) {
            return null;
        }
        SAMPLE_CALLS.incrementAndGet();
        List<Region> regions = regionsFor(placement);
        for (Region region : regions) {
            if (!region.contains(pos)) {
                continue;
            }
            try {
                BlockState state = region.container.get(
                        pos.getX() - region.minX,
                        pos.getY() - region.minY,
                        pos.getZ() - region.minZ);
                if (state != null) {
                    SAMPLE_HITS.incrementAndGet();
                }
                if (state != null && LOGGED.compareAndSet(false, true)) {
                    LOGGER.info("[material-list] 材料统计：放置未启用 ⇒ 改为直接读投影数据（{} 个区域可用）",
                            regions.size());
                }
                return state;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<Region> regionsFor(SchematicPlacement placement) {
        long now = System.currentTimeMillis();
        if (placement == cachedPlacement && now - cachedAt < CACHE_MS) {
            return cachedRegions;
        }
        cachedPlacement = placement;
        cachedAt = now;
        cachedRegions = build(placement);
        return cachedRegions;
    }

    private static List<Region> build(SchematicPlacement placement) {
        try {
            if (placement.getRotation() != BlockRotation.NONE
                    || placement.getMirror() != BlockMirror.NONE) {
                return List.of();
            }
            LitematicaSchematic schematic = placement.getSchematic();
            if (schematic == null) {
                return List.of();
            }
            List<Region> out = new ArrayList<>();
            for (Map.Entry<String, Box> entry
                    : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).entrySet()) {
                SubRegionPlacement sub = placement.getRelativeSubRegionPlacement(entry.getKey());
                if (sub == null
                        || sub.getRotation() != BlockRotation.NONE
                        || sub.getMirror() != BlockMirror.NONE) {
                    continue;
                }
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(entry.getKey());
                Box box = entry.getValue();
                if (container == null || box == null || box.getPos1() == null || box.getPos2() == null) {
                    continue;
                }
                BlockPos p1 = box.getPos1();
                BlockPos p2 = box.getPos2();
                int minX = Math.min(p1.getX(), p2.getX());
                int minY = Math.min(p1.getY(), p2.getY());
                int minZ = Math.min(p1.getZ(), p2.getZ());
                int maxX = Math.max(p1.getX(), p2.getX());
                int maxY = Math.max(p1.getY(), p2.getY());
                int maxZ = Math.max(p1.getZ(), p2.getZ());
                Vec3i size = container.getSize();
                // 尺寸对不上 ⇒ 坐标系不是「直接平移」，宁可不统计也不给错数
                if (size.getX() != maxX - minX + 1
                        || size.getY() != maxY - minY + 1
                        || size.getZ() != maxZ - minZ + 1) {
                    continue;
                }
                out.add(new Region(minX, minY, minZ, maxX, maxY, maxZ, container));
            }
            return out.isEmpty() ? Collections.emptyList() : out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** 一个子区域在世界里的包围盒 + 对应的投影容器。 */
    private static final class Region {
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        final LitematicaBlockStateContainer container;

        Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
               LitematicaBlockStateContainer container) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.container = container;
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= this.minX && pos.getX() <= this.maxX
                    && pos.getY() >= this.minY && pos.getY() <= this.maxY
                    && pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ;
        }
    }
}
