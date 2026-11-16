package com.mimicenzymes.litematicafiller.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialBreakdownHint;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * **投影二级材料列表显示**（Ray 2026-11-08 命名 + 要的）。
 *
 * <p>显示规则：二级材料**已经备够**（身上数量 ≥ 需求）⇒ **亮白**，表示可以直接合成；
 * 还没备够 ⇒ **浅灰**。
 *
 * <p>每行追加两段信息：
 * <ol>
 *   <li>本行物品的二级材料：{@code ←684 冰 10组+44}（单向）／{@code ≈19 红石块}（互转）</li>
 *   <li>（已并入数量列，不再并列显示）</li>
 * </ol>
 *
 * <h2>数量列格式也一起改了</h2>
 * <p>原版画的是 {@code 234 (3 x 64 + 42)} —— 括号 + {@code x 64} 很占宽度。
 * 这里在同一个注入点上换成 {@code 249 3组+57}（Ray 2026-11-08 要的）；
 * 够一盒仍是 {@code 50 1.85潜影盒}，与原版判定完全一致，只是去掉括号。
 *
 * <h2>「其他行要的东西」并进数量列</h2>
 * <p>「平滑石台阶 ←15 平滑石头」会让 <b>平滑石头那一行</b>的数量从 234 变成 <b>249</b>
 * （Ray 2026-11-08 要的：备货一次备齐）。
 *
 * <p>⚠️ <b>只并「单向（←）」的需求</b>：{@code ←} 表示「必须用这个来合成」，是硬需求；
 * {@code ≈} 只是「也可以拿它换」（红石粉 ⇄ 红石块），并进去会让两个方向互相抬价。
 *
 * <h2>挂在哪儿</h2>
 * <p>Litematica 材料列表界面顶部那个 **HUD 信息显示** 开关（{@code GuiMaterialList} 的
 * {@code TOGGLE_INFO_HUD}）打开后，屏幕上的清单由 {@link MaterialListHudRenderer#render} 画。
 * 这里**不改它的布局、不新做面板**，只替换它本来就要画的那串数量文本。
 *
 * <h2>为什么改 {@code getFormattedCountString} 而不是画新文字</h2>
 * <p>{@code render} 里这个方法被调用**两次**：第一次算 {@code maxCountLength}（决定背景框多宽），
 * 第二次才真正画。两次用的是同一组「条目 + 数量」⇒ 只在这一点上拼，
 * <b>背景宽度会自动跟着变</b>，不用另算坐标、也不会溢出。
 *
 * <p>⚠️ 这是**第三方 mixin** ⇒ 必须注册在 {@code raytoolbox-compat.mixins.json}（required: false），
 * 并且 {@code require = 0}：Litematica 改版导致注入不上时只记日志、不能拦住游戏启动。
 *
 * <p>⛔ **v1.19.0 翻车点（2026-11-08，Ray「材料列表打不开了」）**：{@code @Redirect} 的 handler
 * **第一个参数必须是目标方法的所有者**（{@code MaterialListHudRenderer}），即使调用点写的是
 * {@code this.getFormattedCountString(...)} 也一样 —— 少了它，Mixin 在**类加载阶段**抛
 * {@code InvalidInjectionException}，而 {@code MaterialListHudRenderer} 是 {@code MaterialListBase}
 * 构造器里引用的静态成员 ⇒ 按 M 建材料列表时直接炸在类加载上、界面打不开。
 * 日志证据：{@code MaterialListBase.<init> → ... KeyCallbackHotkeys.onKeyAction}。
 * ⇒ **改签名后先看日志有没有 `Mixin apply ... failed`，别只看编译通过。**
 */
@Mixin(MaterialListHudRenderer.class)
public abstract class MixinMaterialListHudRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");


    /** 材料清单本体（要它的 multiplier 才能算出每行的真实需求）。 */
    @Shadow(remap = false)
    protected MaterialListBase materialList;

    /** 「其他行需要本物品」的汇总（只含单向需求）—— 数据与缓存都在 {@link MaterialBreakdownHint}。 */
    @Unique
    private static Map<Item, Long> raytoolbox$summaryFor() {
        return MaterialBreakdownHint.oneWayDemand();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/materials/MaterialListHudRenderer;getFormattedCountString(II)Ljava/lang/String;"
            ),
            remap = false,
            require = 0
    )
    private String raytoolbox$appendBreakdown(MaterialListHudRenderer self, int count, int maxStackSize,
                                              @Local MaterialListEntry entry) {
        if (!Configs.MATERIAL_BREAKDOWN.getBooleanValue() || entry == null) {
            return this.raytoolbox$formatCount(count, maxStackSize);
        }

        // ⛔ v1.34.0（Ray 2026-11-14 定稿：「实际需要 697 个，不用包含二级材料需要的了」）：
        // 数量列**不再并入二级单向需求**（之前 982 = 697 自身 + 285 平滑石英楼梯/台阶的合成需求）。
        // 二级需求**区分开**：只留在行尾的合成提示文本里（hint），数量列显示自身净缺口。
        // ⚠️ 参与显示的是**原始 count**（可为负 = 已有的富余量），最后兜底 max(0) 防止显示「-N」。
        long displayCount = Math.max(0L, count);

        StringBuilder out = new StringBuilder(this.raytoolbox$formatCount(displayCount, maxStackSize));

        // ② 本行物品的二级材料（按**原数量**算，不受 extra 影响，避免两个方向互相抬价）
        //    「备够了没有」按该物品的**总备货需求**判断（自身需求 + 其他行单向需求）——
        //    否则自身缺口把材料占满时仍会显示「已备够」（2026-11-12 Ray 报的「补 697 就满足了」）
        MaterialBreakdownHint.Result hint = MaterialBreakdownHint.describe(entry, count, true);
        if (hint != null) {
            // 二级材料**备够了**用亮白（可以直接合成）；还没备够用浅灰 §7（深灰在黑底上看不见）
            String color = hint.satisfied() ? GuiBase.TXT_WHITE : GuiBase.TXT_GRAY;
            out.append(' ').append(color).append(hint.text()).append(GuiBase.TXT_RST);
            // 状态一变就打一条（补够 / 又变缺时各一条）—— 排查「明明够了还显示灰」就看这几行
            this.raytoolbox$logOnChange(entry.getStack(), hint);
        }
        return out.toString();
    }

    /** 每个物品「上次是不是备够」—— 只在状态翻转时打日志，避免每帧刷屏。 */
    @Unique
    private static final Map<Item, Boolean> raytoolbox$lastSatisfied = new java.util.concurrent.ConcurrentHashMap<>();

    @Unique
    private void raytoolbox$logOnChange(net.minecraft.item.ItemStack ownStack, MaterialBreakdownHint.Result hint) {
        try {
            Item own = ownStack.getItem();
            Boolean previous = raytoolbox$lastSatisfied.put(own, hint.satisfied());
            if (previous == null || previous.booleanValue() != hint.satisfied()) {
                LOGGER.info(
                        "[material-list] 二级材料判定：{} 需要 {} → {}｜身上 {} / 总需 {}（{}）",
                        own.getName().getString(), hint.amount(),
                        hint.inputItem().getName().getString(),
                        hint.available(), hint.need(),
                        hint.satisfied() ? "已备够" : "还没备够");
            }
        } catch (Throwable ignored) {
            // 日志失败不影响渲染
        }
    }

    /**
     * 替代原版 {@code getFormattedCountString}：**判定规则照抄，只换写法**（Ray 2026-11-08 要的）。
     *
     * <p>实际格式化在 {@link MaterialBreakdownHint#formatCount(long, int)}（二级材料那段也用同一套，
     * 保证整张清单一个写法）：
     * <ul>
     *   <li>不够一组 ⇒ {@code 76}</li>
     *   <li>不够一盒 ⇒ {@code 249 3组+57}（原版 {@code 249 (3 x 64 + 57)}）</li>
     *   <li>够一盒 ⇒ {@code 50 1盒+23}（原版 {@code 50 (1.85 潜影盒)} —— 小数看不出到底几个）</li>
     *   <li>盒后还有余 ⇒ {@code 2000 1盒+4组+16}</li>
     * </ul>
     */
    @Unique
    private String raytoolbox$formatCount(long count, int maxStackSize) {
        return MaterialBreakdownHint.formatCount(count, maxStackSize);
    }

    /**
     * 把整张清单扫一遍：每行的**单向**二级材料需求累加到「被需求物品」名下。
     *
     * <p>互转（{@code ≈}）的需求**不计入** —— 那是「也可以拿它换」，两边都加会互相抬价。
     * 只并入 {@code ←}（必须用这个合成），这样「平滑石台阶的 15 个平滑石头」才会加到 234 上。
     *
     * <p>实现已搬到 {@link MaterialBreakdownHint#oneWayDemand()}（那里同时给「备够没备够」提供
     * 自身需求，一份数据两处用，避免两个地方各算一遍数字打架）；按 tick 缓存。
     */
}
