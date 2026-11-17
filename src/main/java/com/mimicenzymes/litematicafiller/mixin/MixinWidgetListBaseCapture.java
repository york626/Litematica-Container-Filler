package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 【自动收集投影材料】的口径来源：捕获材料列表 GUI **当前显示的条目**（视图 + 搜索过滤后的行）。
 *
 * <p>Ray 2026-11-14 的定稿口径：「根据**显示的**材料列表自动收集」——搜索框过滤（haijing/海晶）后
 * 屏幕上显示哪几行，就按那几行的缺失量收集；数据层全量清单（他的投影有 1126 种缺失）绝不能混进来。
 *
 * <h2>⚠️ 捕获点的教训（v1.16.5 实测失效）</h2>
 * <p>第一版挂在 {@code getCurrentEntries()} —— **malilib 内部根本不调用它**（公开 getter 而已，
 * 渲染流程直接用 {@code listContents} 字段）⇒ 捕获永不触发、一直回退数据层全量（1126 种超拿）。
 * 正确捕获点＝{@code refreshBrowserEntries()} TAIL —— 搜索变化 / 数据变化 / 打开界面**必经**，
 * 此时 {@code listContents} 已是过滤后的行。
 */
@Mixin(value = WidgetListBase.class, remap = false)
public abstract class MixinWidgetListBaseCapture {

    @Shadow(remap = false)
    protected List<?> listContents;

    @Inject(method = "refreshBrowserEntries", at = @At("TAIL"), remap = false, require = 0)
    private void lcf(CallbackInfo ci) {
        if ((Object) this instanceof WidgetListMaterialList) {
            FillMaterialCalculator.setDisplayedMaterialRows(this.listContents);
            // 诊断（v1.20.6）：控件真正重建的时刻 + 首行，用来钉「切视图先闪旧列表」的时序
            String first = "";
            if (!this.listContents.isEmpty()
                    && this.listContents.get(0) instanceof fi.dy.masa.litematica.materials.MaterialListEntry entry) {
                first = entry.getStack().getName().getString();
            }
            FillMaterialCalculator.logWidgetRebuild(this.listContents.size(), first);
        }
    }
}
