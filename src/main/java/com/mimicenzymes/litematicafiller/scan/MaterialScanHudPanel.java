package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 【野外材料感知】独立 HUD 面板（Ray 2026-11-18 定稿）。
 *
 * <p>不再修改 Litematica 的材料列表 HUD——它的 {@code render} 会自己排序并复用列表，
 * 任何“替换列表”的做法都会和它的 2 秒重算帧打架、出现两态跳。
 *
 * <p>这里只在材料列表 HUD 的**正上方**画一块小面板（位置由 {@link MaterialScanHudAnchor} 捕获）：
 * <pre>
 * 附近可补
 * [icon] 白桦木栅栏门 → 白桦原木 ×172   7439
 * ...
 * </pre>
 * 数据仍来自 {@link MaterialScanHudRows#promoted(List)}（原清单行 + 附近来源），
 * 原材料列表保持原版，不再被碰。
 */
public final class MaterialScanHudPanel {

    private static final int MAX_ROWS = 6;
    private static final int LINE_HEIGHT = 18;
    private static final int PANEL_WIDTH = 250;

    /** 本帧 HUD 的 DrawContext（在 HudRenderCallback 里存，等 GameRenderer 尾部再画）。 */
    private static DrawContext pendingContext;

    private MaterialScanHudPanel() {
    }

    /**
     * 本帧先记账、不画（Ray 2026-11-18：面板要被**信标效果图标压住** ⇒ 必须画在 InGameHud 之后）。
     * 真正的绘制在 {@link #flushDeferred()}，由 {@code MixinGameRendererHudTail} 在材料列表之后调用。
     */
    public static void defer(DrawContext context) {
        pendingContext = context;
    }

    /** 在整帧 HUD（含状态效果图标、材料列表）之后画；没有待画内容时什么都不做。 */
    public static void flushDeferred() {
        DrawContext context = pendingContext;
        pendingContext = null;
        if (context != null) {
            render(context);
        }
    }

    public static void render(DrawContext context) {
        if (!Configs.MATERIAL_SCAN_HUD.getBooleanValue()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options.hudHidden) {
            return;
        }
        MaterialListBase materialList = DataManager.getMaterialList();
        if (materialList == null) {
            return;
        }
        List<MaterialListEntry> normal;
        try {
            normal = materialList.getMaterialsMissingOnly(false);
        } catch (Throwable ignored) {
            return;
        }
        if (normal == null || normal.isEmpty()) {
            return;
        }
        List<MaterialListEntry> rows = MaterialScanHudRows.promoted(normal);
        if (rows.isEmpty()) {
            return;
        }

        TextRenderer textRenderer = client.textRenderer;
        // 跟 Litematica 的「材料列表HUD缩放比例」一致（Ray 2026-11-18：面板太大，原来是按默认 GUI 缩放画的）
        float scale = 1.0f;
        try {
            scale = (float) fi.dy.masa.litematica.config.Configs.InfoOverlays.MATERIAL_LIST_HUD_SCALE.getDoubleValue();
        } catch (Throwable ignored) {
        }
        if (scale <= 0.05f) {
            scale = 0.5f;
        }
        int height = 12 + rows.size() * LINE_HEIGHT;
        // 贴在材料列表 HUD 正上方（Ray 2026-11-18）：坐标取 Litematica 本帧实际画背景的矩形。
        // 拿不到（清单关了 / 没有缺料 / 第一帧）才退回左上角。
        int x = 4;
        int y = 4;
        if (MaterialScanHudAnchor.isFresh()) {
            // 面板背景从 (-4,-4) 画到 (PANEL_WIDTH,height)：
            // 先 scale 再 translate ⇒ 原点 (x,y) 也是**缩放前**坐标，与 anchor 同一坐标系。
            x = MaterialScanHudAnchor.left() + 4;
            y = MaterialScanHudAnchor.top() - height;
            if (y < 4) {
                y = 4;
            }
        }

        var matrices = context.getMatrices();
        matrices.push();
        // ⛔ 顺序必须是先 scale 再 translate：Litematica 的 posX/posY 是缩放前坐标
        //    （它 scale 之后直接 fill(posX,...)）；先 translate 会把我们的坐标当屏幕像素用，
        //    面板就会掉进材料列表里面、被后画的清单压住（Ray 2026-11-18 实测）。
        matrices.scale(scale, scale, 1.0f);
        matrices.translate(x, y, 0.0f);

        context.fill(-4, -4, PANEL_WIDTH, height, 0xB0000000);
        context.drawTextWithShadow(textRenderer, "附近可补", 0, 0, 0xFFFFD060);
        int rowY = 12;

        for (MaterialListEntry entry : rows) {
            ItemStack stack = entry.getStack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            context.drawItem(stack, 0, rowY);
            String name = stack.getName().getString();
            if (name.length() > 34) {
                name = name.substring(0, 34) + "…";
            }
            context.drawTextWithShadow(textRenderer, name, 20, rowY + 4, 0xFFFFFFFF);
            String count = String.valueOf(Math.max(0, entry.getCountMissing()));
            int countWidth = textRenderer.getWidth(count);
            context.drawTextWithShadow(textRenderer, count, PANEL_WIDTH - 8 - countWidth, rowY + 4, 0xFFFFD060);
            rowY += LINE_HEIGHT;
        }
        matrices.pop();
    }
}
