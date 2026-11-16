package com.mimicenzymes.litematicafiller.materials;

import fi.dy.masa.litematica.render.infohud.InfoHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.profiler.Profiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 材料列表 HUD 的**延迟绘制队列**（Ray 2026-11-11「要显示在最上面」，2026-11-16 从工具箱迁入）。
 *
 * <p>原版 Litematica 在 {@code RenderHandler.onRenderGameOverlayPost}（HUD 回调）里画材料列表，
 * 而 Inventory HUD+ 的状态图标是直接 mixin 在 {@code InGameHud.render} 尾部画的 ⇒ 比材料列表晚，
 * 会盖住它。这里把材料列表的绘制参数先存下来，等 {@code GameRenderer.render} 里整个
 * {@code InGameHud.render} 返回后再画（见 {@code MixinGameRendererHudTail}）⇒ 一定在最上面。
 *
 * <p>⚠️ 本类引用 Litematica 的 {@link InfoHud} ⇒ **只在装了投影时才会被调用**
 * （compat mixin 里 defer、GameRenderer mixin 里先判 {@code isModLoaded("litematica")}）。
 */
public final class MaterialListHudDrawQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    private static InfoHud pendingHud;
    private static DrawContext pendingContext;
    private static Profiler pendingProfiler;
    /** 判据日志只打一次（启动后第一次真正延迟绘制时）。 */
    private static boolean logged;

    private MaterialListHudDrawQueue() {
    }

    /** 记下这一帧要画的内容（不立即画）。 */
    public static void defer(InfoHud hud, DrawContext context, Profiler profiler) {
        pendingHud = hud;
        pendingContext = context;
        pendingProfiler = profiler;
    }

    /** 在 HUD 全部画完后调用；没有待画内容时什么都不做。 */
    public static void flush() {
        InfoHud hud = pendingHud;
        if (hud == null) {
            return;
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[material-list] 材料列表 HUD：延迟绘制已执行（HUD 之后）");
        }
        DrawContext context = pendingContext;
        Profiler profiler = pendingProfiler;
        pendingHud = null;
        pendingContext = null;
        pendingProfiler = null;
        hud.renderHud(context, profiler);
    }
}
