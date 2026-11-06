package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★★ v1.10.0-Ray：**"不显示界面"改成"界面照常创建、但不绘制"**。
 *
 * <p><b>为什么改</b>（Ray 2026-11-06 追到第 6 轮）：以前是在 `MinecraftClient#setScreen` 上
 * 把容器界面 cancel 掉。但原版 `HandledScreens$Provider#open` 是"建 handler → setScreen(界面) →
 * 把界面里的 handler 挂到 `player.currentScreenHandler`"，一旦 cancel，后面那步就废了 ⇒
 * 客户端一直停在玩家背包菜单 ⇒ 容器工具以为"没开箱"，每个容器白等 20 tick（日志实证：
 * `timeout … handler=class_1723`）。</p>
 *
 * <p>现在：界面**照常创建**（原版流程完整、菜单必然切换），只是 `render`/`renderBackground`
 * 在自动化期间什么都不画 ⇒ 玩家看不到界面，功能和速度都正常。</p>
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenRenderMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void lcf$skipRenderWhileAutomating(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (lcf$shouldHide()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void lcf$skipBackgroundWhileAutomating(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (lcf$shouldHide()) {
            ci.cancel();
        }
    }

    private static boolean lcf$shouldHide() {
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return false;
        }
        if (ContainerToolStateMachine.getInstance().shouldBlockScreens()) {
            return true;
        }
        return Configs.HIDE_PROJECTION_FILL_GUI.getBooleanValue()
                && AutoFillerStateMachine.getInstance().shouldBlockScreens();
    }
}
