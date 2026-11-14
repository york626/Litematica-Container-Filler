package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【自动收集投影材料】的口径来源：捕获材料列表 GUI **当前显示的条目**（视图 + 搜索过滤后的行）。
 *
 * <p>Ray 2026-11-14 的定稿口径：「根据**显示的**材料列表自动收集」——他在搜索框里过滤
 * （如 haijing/海晶）后屏幕上显示哪几行，就按那几行的缺失量收集；v1.16.1~v1.16.3 固定用
 * 数据层全量清单（1126 种），把整个投影的建造缺料都算进去了 ⇒ 超拿。
 *
 * <p>{@code getCurrentEntries()} 是 malilib 的 public 方法（含视图/搜索过滤），
 * 每次列表绘制都会被调用 ⇒ 缓存总是新鲜；GUI 关掉后保留最后一次的行（去仓库收集时仍有效）。
 */
@Mixin(value = WidgetListBase.class, remap = false)
public abstract class MixinWidgetListBaseCapture {

    @Inject(method = "getCurrentEntries", at = @At("RETURN"), require = 0)
    private void lcf(CallbackInfoReturnable<java.util.List<?>> cir) {
        if ((Object) this instanceof WidgetListMaterialList) {
            FillMaterialCalculator.setDisplayedMaterialRows(cir.getReturnValue());
        }
    }
}
