package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.MaterialListDiagnostics;
import com.mimicenzymes.litematicafiller.materials.MaterialListOfflineSampler;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksBase;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 材料统计任务的收尾处理（2026-11-16 从工具箱迁入；v1.18.6 加自愈）。
 *
 * <p>诊断：打印任务类型、清单条数、统计状态数/块数、离线采样命中数。
 *
 * <p>⛔ v1.18.6-Ray 自愈（Ray 2026-11-16：「关掉放置启用再开材料列表就空了」的最后一层）：
 * 实测任务**统计到了完整数据**（1532 种 / 145533 块），但结束时的材料清单是 0 条——
 * 说明原版「统计结果 → 材料清单」这一步在某条路径上被跳过/写空（finished 判定 / 转换失败）。
 * 这里在 onStop 末尾兜底：**清单是空的、但统计表有数据** ⇒ 自己用原版同一个
 * {@link MaterialListUtils#getMaterialList} 重建并写回。原版写成功时这段是 no-op。
 */
@Mixin(TaskCountBlocksBase.class)
public abstract class MixinTaskCountBlocksBaseStopLog {

    // ⚠️ `finished` 在父类 TaskBase 里，跨父类 @Shadow 会报 "field finished was not located"
    //   ⇒ 改成 @Shadow 目标类的 materialList（纯 Litematica 接口）来打日志，不再碰父类字段。
    @Shadow(remap = false)
    protected fi.dy.masa.litematica.materials.IMaterialList materialList;

    @Inject(method = "onStop", at = @At("RETURN"), remap = false, require = 0)
    private void lcf$logStopAndRepair(CallbackInfo ci) {
        try {
            int size = -1;
            if (this.materialList instanceof fi.dy.masa.litematica.materials.MaterialListBase base) {
                size = base.getMaterialsAll().size();
            }

            // 反射读计数表（不 @Shadow 跨类字段，避免解析风险）
            int stateCount = -1;
            long countedBlocks = -1;
            Object totalMap = null;
            Object missingMap = null;
            Object mismatchMap = null;
            try {
                Class<?> base = fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksBase.class;
                java.lang.reflect.Field ft = base.getDeclaredField("countsTotal");
                java.lang.reflect.Field fm = base.getDeclaredField("countsMissing");
                java.lang.reflect.Field fx = base.getDeclaredField("countsMismatch");
                ft.setAccessible(true);
                fm.setAccessible(true);
                fx.setAccessible(true);
                totalMap = ft.get(this);
                missingMap = fm.get(this);
                mismatchMap = fx.get(this);
                if (totalMap instanceof it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> m) {
                    stateCount = m.size();
                    long sum = 0;
                    for (int v : m.values()) {
                        sum += Math.max(0, v);
                    }
                    countedBlocks = sum;
                }
            } catch (Throwable ignored) {
            }

            MaterialListDiagnostics.logTaskStop(this.getClass().getSimpleName(), this.materialList != null, size,
                    stateCount, countedBlocks,
                    MaterialListOfflineSampler.sampleCalls(), MaterialListOfflineSampler.sampleHits(),
                    MaterialListOfflineSampler.sampleNonAir());

            // —— 自愈：清单空、但统计表有数据 ⇒ 照原版同一个方法重建一次 ——
            if (size == 0 && stateCount > 0 && this.materialList != null && totalMap != null) {
                @SuppressWarnings("unchecked")
                it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.block.BlockState> total =
                        (it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.block.BlockState>) totalMap;
                @SuppressWarnings("unchecked")
                it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.block.BlockState> missing =
                        (it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.block.BlockState>) missingMap;
                @SuppressWarnings("unchecked")
                it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.block.BlockState> mismatch =
                        (it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.block.BlockState>) mismatchMap;
                List<MaterialListEntry> rebuilt = MaterialListUtils.getMaterialList(
                        total, missing, mismatch, MinecraftClient.getInstance().player);
                MaterialListDiagnostics.logSelfRepair(rebuilt.size());
                if (!rebuilt.isEmpty()) {
                    this.materialList.setMaterialListEntries(rebuilt);
                }
            }
        } catch (Throwable ignored) {
            // 只是日志 / 兜底
        }
    }
}
