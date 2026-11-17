package com.mimicenzymes.litematicafiller.mixin;

import com.google.common.collect.ImmutableList;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialBreakdownHint;
import com.mimicenzymes.litematicafiller.materials.MaterialListDiagnostics;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * **材料列表的「够不够才显示」完全按总备货需求来**（Ray 2026-11-12 / 11-13 的要求）。
 *
 * <h2>两个原版缺陷</h2>
 * <ol>
 *   <li><b>隐藏得太早</b>：Litematica 只判 {@code countAvailable < countMissing}（697），
 *       身上一到 697 这一行就消失，于是「合成台阶还要 285」这条需求在 HUD 上彻底看不见。
 *       Ray 要的是「<b>够 982 才隐藏</b>」（697 直接放 + 285 合成用）。</li>
 *   <li><b>消失了回不来</b>：{@code recreateFilteredList()} 在开着「隐藏已有材料」时会把满足的条目
 *       从 {@code materialListPreFiltered} 里 <b>remove 掉</b>（破坏性），之后材料用掉 / 丢掉，
 *       这一行<b>永远不会再出现</b>（Ray 2026-11-13 报的「把平滑石英块丢掉，列表不刷新」）。
 *       只有重新统计整份清单（{@code setMaterialListEntries}）才会恢复。</li>
 * </ol>
 *
 * <h2>怎么做</h2>
 * <ul>
 *   <li><b>HEAD</b>：先从完整清单 {@code materialListAll} 还原 {@code preFiltered}
 *       （走原版 {@code refreshPreFilteredList()}，它会顺带排除「忽略」的条目）⇒ 修缺陷 2；</li>
 *   <li><b>RETURN</b>：按「自身需求 + 其他行单向需求」这个<b>总备货需求</b>重新裁决一遍：
 *       够 ⇒ 从两份列表里移除；自身够但合成不够 ⇒ 补回两份列表 ⇒ 修缺陷 1。</li>
 * </ul>
 *
 * <h2>为什么用 {@code availableOf} 而不是 {@code entry.getCountAvailable()}</h2>
 * <p>后者是 Litematica 自己算的，<b>认不出精妙背包</b>（以及精妙背包里的盒装材料）。
 * 如果两处口径不同，就会出现「HUD 说够了、列表还挂着」的错位 ⇒ 统一走工具箱的统计。
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>开关关着 ⇒ 两个注入都不动，行为与原版一致；</li>
 *   <li>「忽略」的条目不补回；{@code remove} 只按同一对象移除，不会误伤别的物品；</li>
 *   <li>整段 try/catch：第三方 mixin，任何异常都不能让材料列表打不开。</li>
 * </ul>
 */
@Mixin(MaterialListBase.class)
public abstract class MixinMaterialListBaseSynthesisFilter {

    @Shadow(remap = false)
    protected List<MaterialListEntry> materialListPreFiltered;

    @Shadow(remap = false)
    protected List<MaterialListEntry> materialListFiltered;

    /** 完整清单（不受「隐藏已有材料」影响）—— 被隐藏的条目只能从这里找回来。 */
    @Shadow(remap = false)
    protected ImmutableList<MaterialListEntry> materialListAll;

    /** 被用户点「忽略」的条目 —— 绝不补回来（否则「忽略」按钮失效）。 */
    @Shadow(remap = false)
    protected java.util.Set<MaterialListEntry> ignored;

    @Shadow(remap = false)
    protected int multiplier;

    /** 关掉时 GUI 直接用 `materialListPreFiltered`（全部材料）⇒ 那时候不能从它里面删条目。 */
    @Shadow(remap = false)
    protected boolean hideAvailable;

    @Shadow(remap = false)
    public abstract void refreshPreFilteredList();

    @Shadow(remap = false)
    public abstract void recreateFilteredList();

    /**
     * 统计任务写完整份清单之后，**主动重建一次可见列表**。
     *
     * <h2>为什么必须补这一刀</h2>
     * <p>原版 {@code setMaterialListEntries} 只做两件事：存 {@code materialListAll}、
     * 刷新 {@code preFiltered}，<b>不会重建 {@code materialListFiltered}</b>。
     * 平时靠材料列表 HUD 每 2 秒的 {@code getMaterialsMissingOnly(true)} 顺手重建；
     * 但 Ray 的「HUD 信息显示」是<b>关</b>的 ⇒ 界面打开时 filtered 还是上一轮的空表 ⇒
     * 统计明明跑完了、界面却一行没有。（这也是「放置启用」开着时能看见、关掉后看不见
     * 的差别之一 —— 两种情况下 filtered 都不该靠 HUD 来维护。）
     */
    @Inject(method = "setMaterialListEntries", at = @At("RETURN"), remap = false, require = 0)
    private void raytoolbox$rebuildAfterCount(java.util.List<MaterialListEntry> list, CallbackInfo ci) {
        try {
            MaterialListDiagnostics.logRebuildOnce();
            this.recreateFilteredList();
            MaterialListDiagnostics.logListSize(this.materialListAll.size(), this.materialListFiltered.size());
        } catch (Throwable ignored) {
            // 重建失败就等 HUD / 界面自己刷
        }
    }

    /**
     * 修「材料丢掉后那一行再也不回来」：每次重建前从完整清单还原一次。
     *
     * <p>原版只在 {@code setMaterialListEntries}（重新统计整份清单）时才调
     * {@code refreshPreFilteredList()}，平时靠 {@code recreateFilteredList()} 从
     * 已经删过东西的 {@code preFiltered} 里再筛一遍 ⇒ 删掉的条目回不来。
     */
    @Inject(method = "recreateFilteredList", at = @At("HEAD"), remap = false, require = 0)
    private void raytoolbox$rebuildPreFiltered(CallbackInfo ci) {
        if (!Configs.MATERIAL_BREAKDOWN.getBooleanValue()) {
            return;
        }
        try {
            this.refreshPreFilteredList();
        } catch (Throwable ignored) {
            // 还原失败就按原版逻辑走（只是那一行暂时回不来）
        }
        // ⭐ v1.20.17-Ray（Ray 2026-11-17：「陶片放盒子里/精妙背包里识别不了」）：
        //   Litematica 的「已有」只数玩家背包本体 + 顶层潜影盒；精妙背包（内容在世界存档）和
        //   背包装的盒子它都看不见 ⇒ 行不隐藏、「已有」列恒 0。这里把「已有」补齐成 CF 的口径
        //   （{@link MaterialBreakdownHint#availableOf}：背包 + 潜影盒 + 收纳袋 + 精妙背包 + Trinkets）。
        try {
            for (MaterialListEntry entry : this.materialListAll) {
                if (entry == null || entry.getStack() == null || entry.getStack().isEmpty()) {
                    continue;
                }
                int full = MaterialBreakdownHint.availableOf(entry.getStack().getItem());
                if (full > entry.getCountAvailable()) {
                    entry.setCountAvailable(full);
                }
            }
        } catch (Throwable ignored) {
            // 补不上就按原版口径，不影响列表
        }
    }

    @Inject(method = "recreateFilteredList", at = @At("RETURN"), remap = false, require = 0)
    private void raytoolbox$applyTotalDemand(CallbackInfo ci) {
        if (!Configs.MATERIAL_BREAKDOWN.getBooleanValue()) {
            return;
        }
        try {
            // 注入成功只打一次：第三方 mixin 最怕静默失效，日志里有没有这行就是分水岭
            MaterialBreakdownHint.logFilterAppliedOnce();
            for (MaterialListEntry entry : this.materialListAll) {
                if (entry == null || entry.getStack() == null || entry.getStack().isEmpty()) {
                    continue;
                }
                // 被「忽略」的条目绝不插手
                if (this.ignored != null && this.ignored.contains(entry)) {
                    continue;
                }
                Item item = entry.getStack().getItem();
                int self = this.multiplier == 1
                        ? entry.getCountMissing()
                        : this.multiplier * entry.getCountTotal();
                if (self <= 0) {
                    continue;
                }
                // ⛔ v1.34.0（Ray 2026-11-14 定稿）：裁决**只按自身需求**——
                // 「实际需要 697 个」= 身上够 697 就隐藏；二级材料（楼梯/台阶的合成需求 285）
                // 区分开，不再卡这一行的隐藏。保留精妙背包口径的补刀（原版认不出精妙背包）。
                // ⚠️ 用工具箱的统计（含潜影盒 / 精妙背包里的盒装材料），和 HUD 的「够不够」同一口径
                int available = MaterialBreakdownHint.availableOf(item);

                if (available >= self) {
                    // 备够了 ⇒ 该隐藏（Litematica 认不出精妙背包时会留着，这里补一刀）
                    // ⚠️ preFiltered 只在开了「隐藏已有材料」时才动 ——
                    //    关着的时候 GUI 就是拿它当「全部材料」显示，删了会缺行
                    this.materialListFiltered.remove(entry);
                    if (this.hideAvailable) {
                        this.materialListPreFiltered.remove(entry);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 材料列表本体不受影响
        }
    }

    /**
     * ⭐ v1.20.8-Ray（Ray 2026-11-17：「HUD 的材料列表还是会跳变」）：
     * **重建出来的 filtered 列表立刻按 GUI 同一个 {@code MaterialListSorter} 排好**。
     *
     * <p>原版 {@code MaterialListHudRenderer} 只在**自己每 2 秒的刷新**里
     * {@code Collections.sort(getMaterialsMissingOnly(true), sorter)}；其余帧直接按
     * {@code getMaterialsMissingOnly(false)} 的顺序画。可切视图（setMaterialListEntries → 重建 filtered）
     * 和按提取（{@code getMaterialsMissingOnly(true)}）都会把 filtered 重置成**原始顺序** ⇒
     * HUD 先按原始顺序画一版，2 秒定时器到了再排序 ⇒ 看起来"跳变"。
     * 在这里排好序，HUD 任何一帧拿到的都是最终顺序（GUI 本来就会自己再排一次副本，不受影响）。
     */
    @Inject(method = "recreateFilteredList", at = @At("RETURN"), remap = false, require = 0)
    private void raytoolbox$keepFilteredSorted(CallbackInfo ci) {
        try {
            if (this.materialListFiltered.size() > 1) {
                java.util.Collections.sort(this.materialListFiltered,
                        new fi.dy.masa.litematica.materials.MaterialListSorter((MaterialListBase) (Object) this));
            }
        } catch (Throwable ignored) {
            // 排序失败不影响列表本体
        }
    }
}
