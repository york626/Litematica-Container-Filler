package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【材料列表：搜索框有内容时，ESC 一次关整个界面】Ray 2026-11-17 定稿。
 *
 * <h2>为什么要改 ESC</h2>
 * malilib 的 {@code WidgetSearchBar} 收到 ESC 时只是把 {@code searchOpen=false}，而
 * {@code getFilter()} 只在 {@code searchOpen==true} 时返回内容 ⇒ 搜索范围当场丢失：
 * 第一次 ESC 关搜索框、第二次才关界面，「自动收集只收搜索结果」因此永远拿不到范围。
 *
 * <h2>为什么注入这里（而不是 Screen/GuiBase.keyPressed）</h2>
 * malilib 的 {@code GuiBase.keyPressed} 覆写了原版方法，先把按键交给 {@code onKeyTyped}（列表/搜索框），
 * 搜索框吃掉 ESC 后**根本不会调用 Screen.keyPressed**（v1.20.13 挂在 Screen 上所以一次都没触发）。
 * 而且 libs 里的 malilib 是 intermediary：{@code GuiBase} 里是 {@code method_25404}，写 {@code keyPressed}
 * 会匹配不到。搜索框的 {@code onKeyTypedImpl} 是 malilib **自己的**方法名（不 remap），是最稳的落点。
 *
 * <p>只对材料列表生效：{@code currentScreen instanceof GuiMaterialList}。
 * 搜索框**空着**时仍走原版；其它界面的搜索框行为不变。
 */
@Mixin(value = WidgetSearchBar.class, remap = false)
public abstract class MixinWidgetSearchBarMaterialList {

    private static final int KEY_ESCAPE = 256;

    @Shadow protected boolean searchOpen;

    @Shadow public abstract String getFilter();

    @Inject(method = "onKeyTypedImpl", at = @At("HEAD"), cancellable = true, remap = false)
    private void lcf$escapeClosesMaterialListWithSearch(int keyCode, int scanCode, int modifiers,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (keyCode != KEY_ESCAPE || !this.searchOpen) return;
        String filter = this.getFilter();
        if (filter == null || filter.isEmpty()) return; // 没内容 ⇒ 原版（第一次 ESC 只关搜索框）
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.currentScreen instanceof GuiMaterialList gui)) return;
        try {
            cir.setReturnValue(true); // 吃掉 ESC，别让搜索框自己关掉
            gui.close();              // 直接关整个界面，搜索范围保留到这一刻
            FillMaterialCalculator.logViewDebug("ESC 关材料列表：搜索范围保留（搜索 {}）", filter);
        } catch (Throwable ignored) {
            // 关闭失败就退回原版
        }
    }
}
