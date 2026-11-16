package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialListHudDrawQueue;
import fi.dy.masa.litematica.event.RenderHandler;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 Litematica 的 InfoHud（材料列表 HUD 属于它）**推迟到整帧 HUD 画完再画**
 * （Ray 2026-11-11 要的「材料列表显示在最上面」；2026-11-16 从工具箱迁入）。
 *
 * <p>原版 {@code RenderHandler.onRenderGameOverlayPost} 的顺序是：
 * <code>InfoHud.renderHud（材料列表） → ToolHud → OverlayRenderer.renderHoverInfo（方块信息框）</code>
 * ⇒ 信息框后画、把材料列表压在下面；而 Inventory HUD+ 的状态图标又直接 mixin 在
 * {@code InGameHud.render} 尾部、比整个 HUD 回调都晚 ⇒ 同样会盖住材料列表。
 *
 * <p>所以这里**只负责把调用拦下来、存进 {@link MaterialListHudDrawQueue}**；真正绘制在
 * {@code GameRenderer.render} 的 HUD 调用返回之后（{@code MixinGameRendererHudTail}）——
 * 那一时刻已经在所有 HUD（含状态图标、方块信息框）之后。
 *
 * <p>只影响渲染顺序，不改任何数据；开关关着时原样调用、零行为变化。
 * ⚠️ 第三方 mixin ⇒ {@code require = 0}：Litematica 改结构时安静回到原顺序，不拦启动。
 */
@Mixin(value = RenderHandler.class, remap = false)
public abstract class MixinRenderHandlerHudOrder {
    @Redirect(
            method = "onRenderGameOverlayPost",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/render/infohud/InfoHud;renderHud"
                            + "(Lnet/minecraft/class_332;Lnet/minecraft/class_3695;)V"
            ),
            remap = false,
            require = 0
    )
    private void lcf$deferInfoHud(InfoHud hud, DrawContext context, Profiler profiler) {
        if (!Configs.MATERIAL_LIST_HUD_TWEAK.getBooleanValue()) {
            hud.renderHud(context, profiler);
            return;
        }
        MaterialListHudDrawQueue.defer(hud, context, profiler);
    }
}
