package com.mimicenzymes.litematicafiller.mixin;

import com.google.common.collect.ImmutableMap;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialListDiagnostics;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /** 纯 Litematica 类型，shadow 最稳（同一个目标类里的字段）。 */
    @Shadow(remap = false)
    protected SchematicPlacement schematicPlacement;

    /**
     * ⛔ v1.18.2-Ray（Ray 2026-11-16：「关掉放置启用再打开材料列表，内容没了」的**第二层**）：
     * 原版 {@code canExecute() = isInWorld() && schematicWorld != null}。而「放置全关」时 Litematica 会把
     * schematicWorld 置空（投影世界只为已启用+已渲染的放置创建）⇒ 任务**根本不执行**、onStop 把空清单
     * 写进材料列表 ⇒ 界面被清空。方块状态已由 {@link MixinTaskCountBlocksPlacementOfflineSampler} 直接读投影补上，
     * 所以这里在「本放置未启用 + 有世界」时放行。
     */
    @Inject(method = "canExecute", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void lcf$allowWithoutSchematicWorld(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if (!Configs.MATERIAL_LIST_IGNORE_PLACEMENT_ENABLED.getBooleanValue()) {
            return;
        }
        if (this.schematicPlacement == null || this.schematicPlacement.isEnabled()) {
            return;
        }
        if (MinecraftClient.getInstance().world == null) {
            return;   // 没世界时不放行（与原版 isInWorld 一致）
        }
        MaterialListDiagnostics.logExecuteWithoutSchematicWorldOnce();
        cir.setReturnValue(true);
    }

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
