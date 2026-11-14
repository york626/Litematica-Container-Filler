package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.network.BatchServerLink;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ★ 1.16.0：**自动收集投影材料**（Ray 2026-11-14：「给 CF 加上 QC 的自动收集，认容器材料；
 * 功能集中到 CF」——参考 NeoForge 的 Backpack Side GUI 系同类功能 QuickCraft 的交互重写）。
 *
 * <h2>交互（复刻 QC 的手感，去掉它的缺陷）</h2>
 * <ul>
 *   <li>**长按热键 ≥0.5 秒**激活（防误触，与 QC 的 longPress 同思路）；准星对准目标容器；</li>
 *   <li>激活即一次性收集：清单「全部」视图的缺失材料（**含投影容器里的材料**）从
 *       玩家背包 → 潜影盒/精妙背包（服务端补救链）塞进准星容器；</li>
 *   <li>**松开即结束**。⚠️ 与 QC 的差异：CF 走服务端直连、不经过光标，
 *       所以没有 QC 那套「松开归还」——东西不会停在手上。</li>
 * </ul>
 *
 * <h2>数据源口径</h2>
 * <p>{@link FillMaterialCalculator#getCurrentMissingForAutoCollect()} = 材料列表「显示类型：全部」
 * 的缺失清单（含容器材料）。⚠️ 缓存在材料列表 GUI 打开/刷新时才热 —— 没开过 ⇒ 提示先按 M。
 *
 * <h2>为什么一次发送而不是按住持续发</h2>
 * <p>QC 用客户端点击只能一格一格点（必须按住分批）；CF 服务端直连一次把 3 轮补救全做完，
 * 长按只是防误触门槛，不是分批节流。
 */
public final class AutoCollectController {
    private static final AutoCollectController INSTANCE = new AutoCollectController();
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    /** 长按多少 tick 才算"真的想收集"（0.5 秒 @20tps）。 */
    private static final int HOLD_THRESHOLD_TICKS = 10;
    /** 触发后的冷却（tick）：给服务端一点执行时间，也防按住期间反复触发。 */
    private static final int COOLDOWN_TICKS = 20;

    private int holdTicks;
    private int cooldownTicks;
    private boolean fired;
    /** 最近一次收集请求的目标与发送 tick（回包 pos 匹配才算，避免和批量结果串音）。 */
    private long lastSentPos = Long.MIN_VALUE;
    private long lastSentTick = Long.MIN_VALUE;

    public static AutoCollectController getInstance() {
        return INSTANCE;
    }

    private AutoCollectController() {
    }

    /** 由客户端入口每 tick 调用。 */
    public void tick(MinecraftClient client) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (!Configs.AUTO_COLLECT_ENABLED.getBooleanValue()
                || !Configs.ENABLE_MOD.getBooleanValue()
                || client.player == null) {
            holdTicks = 0;
            fired = false;
            return;
        }
        boolean held = Hotkeys.AUTO_COLLECT_TRIGGER.getKeybind().isKeybindHeld();
        if (!held) {
            holdTicks = 0;
            fired = false;
            return;
        }
        if (fired || cooldownTicks > 0) {
            return;
        }
        holdTicks++;
        if (holdTicks < HOLD_THRESHOLD_TICKS) {
            return;
        }
        fired = true;
        cooldownTicks = COOLDOWN_TICKS;
        trigger(client);
    }

    private void trigger(MinecraftClient client) {
        // ① 准星必须对着容器方块（服务端也要能找到 Inventory）
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            actionbar(client, "请把准星对准目标容器再按住");
            return;
        }
        BlockPos pos = hit.getBlockPos();
        lastSentPos = pos.asLong();
        lastSentTick = client.player == null ? Long.MIN_VALUE : client.player.age;
        // ② 服务端通道可用（单人 / 装了本模组的服务器）
        if (!BatchServerLink.isSupported()) {
            actionbar(client, "服务端通道不可用（单人/装本模组的服务器才能用）");
            return;
        }
        // ③ 清单缓存要热（材料列表打开/刷新过）
        List<ItemStack> missing = FillMaterialCalculator.getCurrentMissingForAutoCollect();
        if (missing.isEmpty()) {
            actionbar(client, "没有可收集的缺失材料（先按 M 打开材料列表让它刷新，或材料已齐）");
            return;
        }
        // ④ 发送（服务端：背包 → 潜影盒/精妙背包补救 → 塞准星容器）
        try {
            BatchServerLink.sendCollectMissing(pos, missing);
            actionbar(client, "自动收集已发出：" + missing.size() + " 种缺失材料 → 准星容器");
            LOGGER.info("[auto-collect] 已发送收集请求：目标 {}，{} 种缺料", pos.toShortString(), missing.size());
        } catch (Throwable t) {
            LOGGER.warn("[auto-collect] 发送失败", t);
            actionbar(client, "发送失败（详见日志）");
        }
    }

    /** 服务端回包由 {@code BatchServerLink} 的 Result 接收转到这里显示（pos 匹配最近请求才算）。 */
    public void onPossibleCollectResult(long pos, int status, int missing) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || lastSentPos != pos) {
            return;
        }
        long age = client.player.age;
        if (lastSentTick == Long.MIN_VALUE || age - lastSentTick > 200) {
            lastSentPos = Long.MIN_VALUE;
            return;
        }
        lastSentPos = Long.MIN_VALUE;
        onServerResult(status, missing);
    }

    private void onServerResult(int status, int missing) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        if (status == BatchServerLink.RESULT_DONE) {
            actionbar(client, missing > 0
                    ? "收集完成（还缺 " + missing + " 个：材料不足，已尽量补救）"
                    : "收集完成：清单缺失材料已全部放进容器");
        } else if (status == BatchServerLink.RESULT_NO_MATERIAL) {
            actionbar(client, "背包/潜影盒/精妙背包里都没有清单缺料");
        } else {
            actionbar(client, "收集失败（容器满或目标无效）");
        }
    }

    private static void actionbar(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }
}
