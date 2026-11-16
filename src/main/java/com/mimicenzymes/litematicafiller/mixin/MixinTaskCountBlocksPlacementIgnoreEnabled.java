package com.mimicenzymes.litematicafiller.mixin;

import com.google.common.collect.ImmutableMap;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialListDiagnostics;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement;
import fi.dy.masa.litematica.selection.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * **「放置启用」关掉时，材料统计照样扫得到区块**（Ray 2026-11-13，第一层；2026-11-16 从工具箱迁入）。
 *
 * <h2>为什么需要</h2>
 * <p>{@code TaskCountBlocksPlacement} 构造器里取子区域盒子用的是
 * {@code RequiredEnabled.PLACEMENT_ENABLED}，而 {@code matchesRequirement(PLACEMENT_ENABLED)}
 * 就是 {@code isEnabled()} ⇒ 放置一关返回空 map ⇒ {@code addPerChunkBoxes} 一个区块都没加
 * ⇒ 任务扫 0 个方块 ⇒ 材料清单为空。
 *
 * <h2>⚠️ 光有这一层还不够</h2>
 * <p>盒子有了，但 {@code countAtPosition} 是从 **schematicWorld** 读方块的，而那个世界
 * 只由「已启用 + 已渲染」的放置填充（{@code SchematicPlacement.getBoxesWithinChunk} 也用
 * {@code PLACEMENT_ENABLED}）⇒ 读到的全是空气。第二层由
 * {@link MixinTaskCountBlocksPlacementOfflineSampler} 补上（直接从投影原始数据读）。
 *
 * <p>开关：{@code materialListIgnorePlacementEnabled}；关掉 = 原版行为（完全不动）。
 */
@Mixin(TaskCountBlocksPlacement.class)
public class MixinTaskCountBlocksPlacementIgnoreEnabled {

    @Redirect(
            method = "<init>(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;" +
                    "Lfi/dy/masa/litematica/materials/IMaterialList;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;" +
                            "getSubRegionBoxes(Lfi/dy/masa/litematica/schematic/placement/" +
                            "SubRegionPlacement$RequiredEnabled;)Lcom/google/common/collect/ImmutableMap;"
            ),
            remap = false,
            require = 0
    )
    private ImmutableMap<String, Box> lcf$boxes(SchematicPlacement self,
                                                SubRegionPlacement.RequiredEnabled required) {
        if (Configs.MATERIAL_LIST_IGNORE_PLACEMENT_ENABLED.getBooleanValue()) {
            MaterialListDiagnostics.logIgnorePlacementOnce();
            return self.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY);
        }
        return self.getSubRegionBoxes(required);
    }
}
