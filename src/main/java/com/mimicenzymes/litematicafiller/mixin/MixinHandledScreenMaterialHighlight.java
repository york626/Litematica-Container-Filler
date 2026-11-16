package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.materials.MaterialHighlightState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 高亮材料列表里的物品（Ray 2026-11-12 要的「高亮投影材料」；2026-11-16 从工具箱迁入，批次 2）。
 *
 * <p>打开箱子/桶/潜影盒等容器界面时，把槽位里**还缺的材料**用绿色边框 + 淡绿底色标出来，
 * 「装着该材料的潜影盒 / 收纳袋 / 精妙背包」也算（匹配逻辑在 {@link MaterialHighlightState}）；
 * 他手动拿取，不自动收集。
 *
 * <p><b>只闪「够这次用」的数量</b>（Ray 2026-11-12 追加）：按材料列表的**缺失数量**逐个槽位扣减，
 * 例如缺 574 个激活铁轨 ⇒ 只高亮前 9 组；一箱子盒装激活铁轨如果一盒就够 574 ⇒ **只闪一盒**，
 * 不会整箱全闪。
 *
 * <p>⛔ <b>v1.19.0-Ray（批次 2 迁移时的新要求）</b>：数据源改成**跟随材料列表当前视图**
 * （{@link FillMaterialCalculator#getCurrentViewMissingForHighlight()}）——
 * 容器视图只闪容器材料、全部视图闪合并结果、方块视图只闪原生建筑方块；**三视图不串**。
 *
 * <h2>闪烁几秒就停（Ray 2026-11-12 二次要求）</h2>
 * 打开容器时**闪烁 3 秒**，之后不再画（参考 ChestTracker 的「打开后提醒一下」）。
 * 材料集合在**打开那一刻算一次**并冻结，闪烁期间不再刷新 ⇒ 绿框不会一跳一跳。
 *
 * <p>⚠️ **不含玩家自己**（Ray 明确）：
 * <ul>
 *   <li>{@code slot.inventory == player.getInventory()} 的槽位跳过 ⇒ 玩家背包不高亮；</li>
 *   <li>**精妙背包自己的界面**整个跳过（身上背的背包打开后不闪）。</li>
 * </ul>
 *
 * <p>挂在 {@code HandledScreen.drawForeground} 的 TAIL：所有槽位和悬停高亮都画完了、
 * tooltip 还没画 ⇒ 绿框压在物品上、不挡提示。{@code drawForeground} 被调用时矩阵已经平移
 * {@code (x, y)}，槽位的 {@link Slot#x}/{@link Slot#y} 直接就是 GUI 内坐标。
 */
@Mixin(HandledScreen.class)
public abstract class MixinHandledScreenMaterialHighlight {
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    /** 淡绿底色（约 19% 不透明，实际透明度随时间渐变）。 */
    private static final int FILL_RGB = 0x00FF00;
    private static final int FILL_ALPHA_MAX = 0x30;
    /** 亮绿边框（透明度随时间渐变）。 */
    private static final int BORDER_ALPHA_MAX = 0xFF;
    /** 打开后闪烁总时长（Ray 2026-11-12：2 秒有点短，定回 3 秒）。 */
    private static final long BLINK_DURATION_MS = 3000L;
    /** 一个完整亮→灭→亮周期（1000ms，3 秒内走三轮）。 */
    private static final double BLINK_PERIOD_MS = 1000.0;

    /** 上一次看到的界面实例（换界面就重新计时 + 重算材料集合）。 */
    @Unique
    private static Screen lcf$lastScreen;
    @Unique
    private static long lcf$openedAt;
    /** 判据日志只打一次。 */
    @Unique
    private static boolean lcf$logged;

    @Shadow
    @Final
    protected ScreenHandler handler;

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void lcf$highlightMaterialSlots(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if (!Configs.MATERIAL_HIGHLIGHT.getBooleanValue()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (client.currentScreen != lcf$lastScreen) {
            // 刚打开一个界面：按「当前材料列表视图」重算一次，并开始 3 秒渐隐计时
            lcf$lastScreen = client.currentScreen;
            lcf$openedAt = now;
            try {
                MaterialHighlightState.update(FillMaterialCalculator.getCurrentViewMissingForHighlight());
            } catch (Throwable ignored) {
                // 清单没建好 / 投影不在：本次不高亮
            }
        }
        long elapsed = now - lcf$openedAt;
        if (elapsed >= BLINK_DURATION_MS) {
            return;
        }
        // 柔和闪烁：余弦脉冲（亮→暗→亮）＋整体随时间的淡出包络 ⇒ 不是硬开关
        double pulse = 0.5 + 0.5 * Math.cos(2.0 * Math.PI * (elapsed / BLINK_PERIOD_MS));
        double envelope = 1.0 - (double) elapsed / (double) BLINK_DURATION_MS;
        double alpha = pulse * envelope;
        if (alpha <= 0.02) {
            return;
        }
        int borderColor = ((int) (BORDER_ALPHA_MAX * alpha) << 24) | FILL_RGB;
        int fillColor = ((int) (FILL_ALPHA_MAX * alpha) << 24) | FILL_RGB;
        if (lcf$isSophisticatedBackpackScreen(client.currentScreen)) {
            return;
        }
        // 按「还缺多少」逐个槽位扣减：只闪够这次用的那几个槽（盒装内容按盒内数量算，一盒够就只闪一盒）
        Map<Item, Long> remaining = MaterialHighlightState.copyCounts();
        if (remaining.isEmpty()) {
            return;
        }
        int highlighted = 0;
        for (Slot slot : this.handler.slots) {
            // Ray 明确：不含玩家背包（玩家背包里的潜影盒 / 精妙背包也不高亮）
            if (slot.inventory == client.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !MaterialHighlightState.consume(remaining, stack)) {
                continue;
            }
            highlighted++;
            lcf$drawFrame(context, slot.x, slot.y, fillColor, borderColor);
        }
        if (highlighted > 0 && !lcf$logged) {
            lcf$logged = true;
            LOGGER.info("[material-list] 材料高亮：视图={}｜需求 {} 项 → 本次高亮 {} 槽（余需求 {} 项）",
                    FillMaterialCalculator.listMode, MaterialHighlightState.neededCount(),
                    highlighted, remaining.size());
        }
    }

    /** 精妙背包（身上背的那个）自己的界面不闪。 */
    @Unique
    private static boolean lcf$isSophisticatedBackpackScreen(Screen screen) {
        String name = screen.getClass().getName();
        return name.startsWith("net.p3pp3rf1y.sophisticatedbackpacks")
                || name.startsWith("net.p3pp3rf1y.sophisticatedcore");
    }

    /** 和 Litematica 自带的「高亮正看着的方块」同款：外圈 1px 亮绿 + 内部淡绿（透明度随时间渐变）。 */
    @Unique
    private static void lcf$drawFrame(DrawContext context, int x, int y, int fillColor, int borderColor) {
        context.fill(x + 1, y + 1, x + 15, y + 15, fillColor);
        context.fill(x, y, x + 16, y + 1, borderColor);
        context.fill(x, y + 15, x + 16, y + 16, borderColor);
        context.fill(x, y, x + 1, y + 16, borderColor);
        context.fill(x + 15, y, x + 16, y + 16, borderColor);
    }
}
