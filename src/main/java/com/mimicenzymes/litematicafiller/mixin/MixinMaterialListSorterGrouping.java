package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialListGroups;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * **材料列表按「同一材料的各种形状」分组排序**（Ray 2026-11-13 要的；2026-11-16 从工具箱迁入）。
 *
 * <h2>要解决什么</h2>
 * <p>默认排序按数量（总计 / 缺失 / 已有），于是「平滑石英块」「平滑石英台阶」「平滑石英楼梯」
 * 会被几百条材料冲散，备货时要来回找。
 *
 * <h2>怎么做</h2>
 * <p>在 {@code compare} 最前面加一层<b>组名比较</b>（{@link MaterialListGroups#groupKey}）：
 * 组名不同 ⇒ 直接按组名排；组名相同 ⇒ **不取消**，继续走原版逻辑（数量 / 名称），
 * 所以组内顺序跟原来完全一样，只是把同一组拉到一起。
 *
 * <p>这套排序对**材料列表界面和 HUD 同时生效**（两者都用这个 Comparator）。
 * 开关：{@code materialListGroupSort}（「自动物流」页，默认开）；关掉 = 原版按数量排。
 */
@Mixin(MaterialListSorter.class)
public class MixinMaterialListSorterGrouping {

    @Inject(method = "compare", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void lcf$groupByMaterial(MaterialListEntry entry1, MaterialListEntry entry2,
                                     CallbackInfoReturnable<Integer> cir) {
        if (!Configs.MATERIAL_LIST_GROUP_SORT.getBooleanValue()) {
            return;
        }
        if (entry1 == null || entry2 == null
                || entry1.getStack() == null || entry1.getStack().isEmpty()
                || entry2.getStack() == null || entry2.getStack().isEmpty()) {
            return;
        }
        try {
            String key1 = MaterialListGroups.groupKey(entry1.getStack().getItem());
            String key2 = MaterialListGroups.groupKey(entry2.getStack().getItem());
            if (key1.isEmpty() || key2.isEmpty() || key1.equals(key2)) {
                return;   // 同组（或取不到）⇒ 交给原版按数量/名称排
            }
            cir.setReturnValue(key1.compareTo(key2));
        } catch (Throwable ignored) {
            // 排序异常不能让列表崩，退回原版
        }
    }
}
