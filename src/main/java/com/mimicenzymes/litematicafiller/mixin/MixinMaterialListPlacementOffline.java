package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.OfflineMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【离线材料列表】放置区域区块没加载时，不再调度 {@code TaskCountBlocksPlacement}
 * （它会一直等客户端区块），直接用 {@link OfflineMaterialList} 从投影数据 + 缓存生成清单。
 *
 * <p>Ray 2026-11-20 立项：野外打开 GUI 材料列表不再依赖加载投影区块。
 * 开关：{@code materialListOfflineEnabled}；构建失败/区域已加载 ⇒ 走原版逻辑（不拦截）。
 */
@Mixin(MaterialListPlacement.class)
public abstract class MixinMaterialListPlacementOffline {

    @Shadow(remap = false)
    @Final
    private SchematicPlacement placement;

    @Inject(method = "reCreateMaterialList", at = @At("HEAD"), cancellable = true, remap = false)
    private void lcf$offlineMaterialList(CallbackInfo ci) {
        if (!OfflineMaterialList.shouldUseOffline(this.placement)) {
            return;
        }
        if (OfflineMaterialList.build(this.placement, (MaterialListBase) (Object) this)) {
            ci.cancel();
        }
    }
}
