package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.materials.MaterialListHudDrawQueue;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@code GameRenderer.render} 里、**HUD 之后那次深度缓冲清理之后**，把延迟的材料列表画出来
 * （Ray 2026-11-11「材料列表要在最上面」；2026-11-16 从工具箱迁入）。
 *
 * <h2>为什么要卡在 clear 之后</h2>
 * 原版顺序是：{@code InGameHud.render(...) → RenderSystem.clear(256)（清深度）→ 屏幕/后续覆盖层}。
 * 如果材料列表在 HUD 后面、clear 前面画，状态图标（Inventory HUD+ 的 LayeredDraw 层）写进深度缓冲的值
 * 还在，材料列表的物品图标会被深度测试挡掉 ⇒ 看起来还是被盖住。清完深度再画，物品图标才能真的压上去。
 *
 * <p>{@code ordinal = 1}：{@code render} 里有两处 {@code RenderSystem.clear(IZ)}，第二处才是 HUD 后的。
 * 没装 Litematica 时直接跳过；{@code require = 0}：版本变化时安静失效。
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererHudTail {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void lcf$flushMaterialListHud(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return;
        }
        MaterialListHudDrawQueue.flush();
    }
}
