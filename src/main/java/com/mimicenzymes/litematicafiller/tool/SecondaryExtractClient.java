package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【提取二级材料到当前容器】（Ray 2026-11-15 从 Ray 工具箱整块移植）。
 *
 * <p><b>做什么</b>：把材料清单里「需要先合成的那部分原料」按**数量预算**塞进准星指的容器，
 * 剩下的材料原封不动 —— 这样合成时一眼知道哪些要动手，不会和直接放置的材料混在一起。
 *
 * <p><b>方向</b>：与「自动收集投影材料」正好相反（那个是 容器 → 背包，这个是 背包 → 容器）。
 *
 * <p><b>怎么算量</b>：{@link FillMaterialCalculator#collectSecondaryNeeds()}（与收集侧的
 * 二级展开同一套判定），真正的扣减在服务端做：每种只搬「需求 − 目标容器里已有」，负数按 0 计。
 *
 * <p><b>为什么走「直调服务端」而不是点击</b>：单人自带服务端就在同一个 JVM。
 * 客户端点击只能作用在**打开着的**界面，而且一次一组、要排队限速；直调服务端可以按需精确搬、
 * 还能一并处理「装在精妙背包/潜影盒里的盒装材料」。连别人的服务器时本功能不可用（提示即可）。
 *
 * <p>服务端实现在 {@link SecondaryExtractServer}（整块移植自工具箱的 `ServerMaterialExtractor`，
 * 预算 / 盒装拆取 / Trinkets 装备槽 / 精妙背包反射 / 显式逐槽同步都在里面）。
 */
public final class SecondaryExtractClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    private SecondaryExtractClient() {
    }

    /** 热键入口。 */
    public static void trigger(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.interactionManager == null) {
            return;
        }
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return;
        }
        if (!Configs.EXTRACT_SECONDARY_ENABLED.getBooleanValue()) {
            actionBar(mc, "「提取二级材料」已关闭（设置 → 批量工具）");
            return;
        }
        Map<Item, Integer> needed = FillMaterialCalculator.collectSecondaryNeeds();
        if (needed == null) {
            actionBar(mc, "拿不到材料清单：先在投影里打开一次材料列表（M）");
            return;
        }
        if (needed.isEmpty()) {
            actionBar(mc, "这份清单没有需要合成的二级材料");
            return;
        }
        BlockHitResult hit = (mc.crosshairTarget instanceof BlockHitResult b
                && b.getType() == HitResult.Type.BLOCK) ? b : null;
        if (hit == null) {
            actionBar(mc, "请把准星对着目标容器（箱子 / 潜影盒 / 精妙背包）");
            return;
        }
        Map<Item, Long> demand = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : needed.entrySet()) {
            demand.put(e.getKey(), e.getValue().longValue());
        }
        if (SecondaryExtractServer.handleDirect(mc, hit.getBlockPos(), demand)) {
            actionBar(mc, "提取二级材料：" + describe(demand) + " 按需进准星容器（盒装自动拆取）");
            LOGGER.info("[extract] 提取二级材料：直调服务端（{} 种：{}，目标 {}）",
                    demand.size(), describe(demand), hit.getBlockPos().toShortString());
        } else {
            actionBar(mc, "服务端不可用：本功能只在单人 / 装有本模组的服务器上可用");
        }
    }

    private static String describe(Map<Item, Long> demand) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Long> e : demand.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey().getName().getString()).append('×').append(e.getValue());
        }
        return sb.toString();
    }

    private static void actionBar(MinecraftClient mc, String message) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(message), true);
        }
    }
}
