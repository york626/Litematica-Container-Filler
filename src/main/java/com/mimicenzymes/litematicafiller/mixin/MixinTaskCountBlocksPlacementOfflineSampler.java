package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialListOfflineSampler;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * **放置禁用时，材料统计改从投影原始数据读**（Ray 2026-11-13，第二层；2026-11-16 从工具箱迁入）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>{@link MixinTaskCountBlocksPlacementIgnoreEnabled} 只解决了「扫哪些区块」。
 * {@code countAtPosition} 读的是 {@code this.schematicWorld}，而那个「投影世界」
 * <b>只由已启用 + 已渲染的放置填充</b>（{@code SchematicPlacement.getBoxesWithinChunk} /
 * {@code getRegionsTouchingChunk} 都用 {@code PLACEMENT_ENABLED}）⇒ 放置一关，
 * 扫到的全是空气，统计结果仍然是 0。
 *
 * <h2>做法</h2>
 * <p>只拦 {@code countAtPosition} 里**那一次 {@code getBlockState} 调用**：
 * 放置未启用时改从 {@link MaterialListOfflineSampler}（直接读投影容器）取状态，
 * 取不到再退回原方法。**统计口径完全不动**（total / missing / mismatch / ignoreState
 * 都是原方法自己算的），所以数字和启用时一模一样。
 *
 * <h2>⛔ 工具箱 v1.30.14 的翻车点（当时实测仍空白）</h2>
 * <p>那一版是 {@code @Inject(HEAD, cancellable=true)} 整个方法 + 用 {@code @Shadow} 拿了
 * 父类的 {@code countsTotal / countsMissing / countsMismatch / clientWorld}。
 * 后者是 <b>原版类型</b>（{@code Object2IntOpenHashMap} / {@code ClientWorld}），
 * 跨父类 shadow + {@code remap = false} 组合下解析失败，而 {@code require = 0}
 * 让整条注入<b>静默跳过</b>（日志里只有第一层那一行）。
 * ⇒ 现在只 shadow 一个纯 Litematica 类型的字段，其余交给原方法。
 *
 * <p>开关：{@code materialListIgnorePlacementEnabled}。
 */
@Mixin(TaskCountBlocksPlacement.class)
public abstract class MixinTaskCountBlocksPlacementOfflineSampler {

    /** 纯 Litematica 类型，shadow 最稳。 */
    @Shadow(remap = false)
    protected SchematicPlacement schematicPlacement;

    @Redirect(
            method = "countAtPosition",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/world/WorldSchematic;" +
                            "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            ),
            require = 0
    )
    private BlockState lcf$sampleFromSchematic(WorldSchematic world, BlockPos pos) {
        if (Configs.MATERIAL_LIST_IGNORE_PLACEMENT_ENABLED.getBooleanValue()
                && this.schematicPlacement != null
                && !this.schematicPlacement.isEnabled()) {
            BlockState state = MaterialListOfflineSampler.sampleAt(this.schematicPlacement, pos);
            if (state != null) {
                return state;
            }
            // ⛔ v1.18.2-Ray：放置全关时 schematicWorld 是 **null**（投影世界被销毁）⇒ 不能回退到原方法，
            //   否则 NPE。取不到就按空气算（区域外的坐标本来就是空气）。
            if (world == null) {
                return net.minecraft.block.Blocks.AIR.getDefaultState();
            }
        }
        return world.getBlockState(pos);
    }
}
