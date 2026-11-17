package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【材料列表·ESC 一次关界面（搜索激活时）】Ray 2026-11-17 定稿：
 * 搜索后按 ESC 应该**直接关整个材料列表界面**（搜索范围保留到关闭那一刻），
 * 而不是 malilib 原版的「第一次 ESC 只关搜索框、第二次才关界面」。
 *
 * <h2>为什么必须改 ESC</h2>
 * malilib 的 {@code WidgetSearchBar} 在 ESC 时把 {@code searchOpen = false}，而
 * {@code getFilter()} 只在 {@code searchOpen == true} 时返回值 ⇒ 搜索条件当场失效、列表按无搜索刷新。
 * 这样「自动收集只收搜索结果」永远拿不到搜索范围（v1.20.11 因此撤回过）。
 *
 * <h2>只动材料列表</h2>
 * 注入 {@code Screen.keyPressed} HEAD，只对 {@link GuiMaterialList} 生效；其它界面的 ESC 行为不变。
 * 搜索框**空着**时仍走原版（第一次 ESC 只关搜索框）。想查看无搜索列表但留在界面里时，点搜索图标即可。
 */
@Mixin(Screen.class)
public abstract class MixinScreenEscapeMaterialList {

    private static final int KEY_ESCAPE = 256;

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void lcf$closeMaterialListWithSearchOnEscape(int keyCode, int scanCode, int modifiers,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (keyCode != KEY_ESCAPE) return;
        if (!((Object) this instanceof GuiMaterialList gui)) return;
        try {
            Object widget = lcf$getListWidget(gui);
            if (!(widget instanceof WidgetListBase<?, ?> list)) return;
            WidgetSearchBar searchBar = list.getSearchBarWidget();
            if (searchBar == null || !searchBar.isSearchOpen() || searchBar.getFilter().isEmpty()) {
                return; // 搜索框没开/没内容 ⇒ 原版行为
            }
            gui.close(); // 直接关整个界面（等同第二次 ESC），搜索状态保留到此刻
            cir.setReturnValue(true);
        } catch (Throwable ignored) {
            // 反射失败就退回原版 ESC
        }
    }

    /** 取 GuiListBase 的 {@code widget} 字段（protected，反射读；字段名见 malilib GuiListBase）。 */
    private static Object lcf$getListWidget(GuiMaterialList gui) throws IllegalAccessException {
        Class<?> c = gui.getClass();
        while (c != null) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals("widget")) {
                    f.setAccessible(true);
                    return f.get(gui);
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
