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
 * <p>这里只在屏幕右上角画一块小面板：
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

    private MaterialScanHudPanel() {
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
        int screenWidth = context.getScaledWindowWidth();
        // 跟 Litematica 的「材料列表HUD缩放比例」一致（Ray 2026-11-18：面板太大，原来是按默认 GUI 缩放画的）
        float scale = 1.0f;
        try {
            scale = (float) fi.dy.masa.litematica.config.Configs.InfoOverlays.MATERIAL_LIST_HUD_SCALE.getDoubleValue();
        } catch (Throwable ignored) {
        }
        if (scale <= 0.05f) {
            scale = 0.5f;
        }
        int x = 4;
        int y = 4;
        int height = 12 + rows.size() * LINE_HEIGHT;

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0.0f);
        matrices.scale(scale, scale, 1.0f);

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
