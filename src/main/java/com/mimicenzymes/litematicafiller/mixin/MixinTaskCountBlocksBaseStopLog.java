package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.MaterialListDiagnostics;
import com.mimicenzymes.litematicafiller.materials.MaterialListOfflineSampler;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断用：统计任务收尾时打一行 {@code finished}（2026-11-16 从工具箱迁入）。
 *
 * <p>原版 {@code onStop()} 里只有 {@code finished && isInWorld()} 成立时才会把统计结果写进
 * 材料清单；如果任务因为「周边区块没加载」一直卡在 pending 里，就永远不会写 ⇒ 界面空白。
 * 这行日志能一眼分清「统计没跑完」和「统计跑了但列表被清空」。
 *
 * <p>{@code finished} 是 {@code boolean}（基本类型），跨父类 {@code @Shadow} 不会有
 * 工具箱 v1.30.14 那种原版对象类型解析失败的问题。
 */
@Mixin(TaskCountBlocksBase.class)
public abstract class MixinTaskCountBlocksBaseStopLog {

    // ⚠️ `finished` 在父类 TaskBase 里，跨父类 @Shadow 会报 "field finished was not located"
    //   ⇒ 改成 @Shadow 目标类的 materialList（纯 Litematica 接口）来打日志，不再碰父类字段。
    @Shadow(remap = false)
    protected fi.dy.masa.litematica.materials.IMaterialList materialList;

    @Inject(method = "onStop", at = @At("RETURN"), remap = false, require = 0)
    private void lcf$logStop(CallbackInfo ci) {
        try {
            int size = -1;
            if (this.materialList instanceof fi.dy.masa.litematica.materials.MaterialListBase base) {
                size = base.getMaterialsAll().size();
            }
            MaterialListDiagnostics.logTaskStop(this.getClass().getSimpleName(), this.materialList != null, size,
                    MaterialListOfflineSampler.sampleCalls(), MaterialListOfflineSampler.sampleHits(),
                    MaterialListOfflineSampler.sampleNonAir());
        } catch (Throwable ignored) {
            // 只是日志
        }
    }
}
