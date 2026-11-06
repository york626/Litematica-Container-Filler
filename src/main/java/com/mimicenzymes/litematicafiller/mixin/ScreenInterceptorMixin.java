package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ScreenHandlerProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 拦截原版Minecraft客户端的屏幕渲染
@Mixin(MinecraftClient.class)
public class ScreenInterceptorMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void interceptScreen(Screen screen, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (screen instanceof HandledScreen) {
            Screen currentScreen = ((MinecraftClient)(Object)this).currentScreen;
            boolean shouldHideProjectionFillGui = Configs.HIDE_PROJECTION_FILL_GUI.getBooleanValue() &&
                    AutoFillerStateMachine.getInstance().shouldBlockScreens();
            boolean shouldHideToolGui = ContainerToolStateMachine.getInstance().shouldBlockScreens();
            boolean shouldPreservePassiveScreen = currentScreen != null &&
                    !(currentScreen instanceof HandledScreen) &&
                    (AutoFillerStateMachine.getInstance().isWorking() || ContainerToolStateMachine.getInstance().isWorking());
            if (shouldHideProjectionFillGui || shouldHideToolGui || shouldPreservePassiveScreen) {
                // ★★ v1.9.7-Ray 真凶修复：原版 `HandledScreens$Provider#open` 的顺序是
                //   ① 建 ScreenHandler → ② `client.setScreen(界面)` → ③ `player.currentScreenHandler = 界面里的 handler`。
                //   我们为了"不弹界面"把 ② cancel 掉 ⇒ **③ 永远不执行** ⇒ 客户端一直以为还在背包界面
                //   ⇒ 容器工具看不到容器、每个容器白等 20 tick 超时
                //   （Ray 2026-11-06：前 10 个很快、第 11 个起一秒一格）。
                //   这里把 ③ 补上：**界面不显示，但菜单照常切过去**。
                MinecraftClient mc = (MinecraftClient) (Object) this;
                if (screen instanceof ScreenHandlerProvider<?> provider && mc.player != null) {
                    mc.player.currentScreenHandler = provider.getScreenHandler();
                }
                ci.cancel();
            }
        }
    }
}
