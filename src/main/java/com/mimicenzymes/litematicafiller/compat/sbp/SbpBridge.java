package com.mimicenzymes.litematicafiller.compat.sbp;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【快捷精妙背包提取】客户端：填充机判"缺料"时，请求服务端把材料从精妙背包抽到玩家背包，
 * 取到后**自动重提交刚才那次填充任务** —— 用户只按一次填充键。
 *
 * <p>为什么需要"重提交"：填充机判缺料时会直接放弃那次任务（容器被记进 failedContainers），
 * 光把材料抽进背包不会让它自己再试。
 */
public final class SbpBridge {
    private SbpBridge() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    private static final int RETRY_TICKS = 40;
    private static final int NO_STOCK_TICKS = 200;

    private static final Set<Item> waiting = new HashSet<>();
    private static final Map<Item, Long> lastRequestTick = new HashMap<>();
    private static final Map<Item, Long> noStockUntil = new HashMap<>();

    /** 每个容器最近一次的填充请求（用于自动重提交）。 */
    private static final Map<String, PendingTask> pendingTasks = new HashMap<>();

    private static final class PendingTask {
        final BlockPos pos;
        final Map<Integer, ItemStack> required;
        final boolean manual;
        Set<Item> missing;
        final long storedAt = System.currentTimeMillis();
        /** >0 表示"取货成功、等待背包同步后重提交"。 */
        long armedAt = 0L;

        PendingTask(BlockPos pos, Map<Integer, ItemStack> required, boolean manual, Set<Item> missing) {
            this.pos = pos;
            this.required = required;
            this.manual = manual;
            this.missing = missing;
        }
    }

    public static boolean enabled() {
        return SbpSupport.available() && Configs.ENABLE_SBP_EXTRACTION.getBooleanValue();
    }

    /** 记下"这个容器刚才要填什么"（手动/自动两条路都记）。 */
    public static void rememberTask(BlockPos pos, Map<Integer, ItemStack> required, boolean manual) {
        if (!enabled() || pos == null || required == null || required.isEmpty()) {
            return;
        }
        String key = pos.toShortString();
        PendingTask old = pendingTasks.get(key);
        Set<Item> missing = old != null ? old.missing : new HashSet<>();
        pendingTasks.put(key, new PendingTask(pos.toImmutable(), new HashMap<>(required), manual, missing));
        while (pendingTasks.size() > 16) {
            String oldest = pendingTasks.keySet().iterator().next();
            pendingTasks.remove(oldest);
        }
    }

    /** 填充机判缺料时调用：按物品请求取货，并记下缺的是什么。 */
    public static void onMissing(BlockPos pos, Set<Item> missingTypes) {
        if (!enabled() || missingTypes == null || missingTypes.isEmpty()) {
            return;
        }
        String key = pos == null ? null : pos.toShortString();
        if (key != null) {
            PendingTask task = pendingTasks.get(key);
            if (task != null) {
                task.missing = new HashSet<>(missingTypes);
            }
        }
        for (Item item : missingTypes) {
            request(item);
        }
    }

    private static void request(Item item) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        long now = client.world.getTime();
        if (waiting.contains(item)) {
            return;
        }
        Long until = noStockUntil.get(item);
        if (until != null) {
            if (now < until) {
                return;
            }
            noStockUntil.remove(item);
        }
        Long last = lastRequestTick.get(item);
        if (last != null && now - last < RETRY_TICKS) {
            return;
        }
        if (!SbpPayloads.canSend()) {
            return;
        }
        lastRequestTick.put(item, now);
        waiting.add(item);
        Identifier id = Registries.ITEM.getId(item);
        SbpPayloads.send(id, Math.max(1, item.getMaxCount()));
        LOGGER.info("[sbp] 填充机缺料 {} → 请求从精妙背包取货", id);
    }

    /** 服务端回包：成功 ⇒ 重提交待填充任务；失败 ⇒ 该物品退避。 */
    public static void onResponse(Identifier itemId, boolean success) {
        Item item = Registries.ITEM.get(itemId);
        waiting.remove(item);
        MinecraftClient client = MinecraftClient.getInstance();
        long now = client.world == null ? 0L : client.world.getTime();
        if (!success) {
            noStockUntil.put(item, now + NO_STOCK_TICKS);
            LOGGER.info("[sbp] 背包里没有 {}（{} tick 内不再请求）", itemId, NO_STOCK_TICKS);
            return;
        }
        LOGGER.info("[sbp] 取料成功 {} ⇒ 等背包同步后重提交填充任务", itemId);
        long armAt = System.currentTimeMillis();
        for (PendingTask task : pendingTasks.values()) {
            if (task.armedAt == 0L) {
                task.armedAt = armAt;
            }
        }
    }

    /**
     * 每客户端 tick 调：把"已取到货"的任务在背包同步完成后重新提交。
     *
     * <p>⚠️ 不能只在回包里立刻重提交：回包先到、客户端背包同步后到，立刻提交仍会被判缺料
     * （2026-11-03 实测：这就是"要按两次"的原因之一）。所以这里按 tick 重试，最多等 2 秒。
     */
    public static void tick() {
        if (pendingTasks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        AutoFillerStateMachine machine = null;
        for (PendingTask task : new java.util.ArrayList<>(pendingTasks.values())) {
            if (now - task.storedAt > 10_000L) {
                pendingTasks.remove(task.pos.toShortString());
                continue;
            }
            if (task.armedAt == 0L) {
                continue;                       // 还没取到货
            }
            boolean ready = task.missing == null || task.missing.isEmpty() || allInInventory(task.missing);
            if (!ready && now - task.armedAt < 2000L) {
                continue;                       // 等背包同步
            }
            if (machine == null) {
                machine = AutoFillerStateMachine.getInstance();
            }
            try {
                machine.clearFailedContainer(task.pos);
                if (task.manual) {
                    machine.addManualTask(task.pos, task.required);
                } else {
                    machine.addTask(task.pos, task.required);
                }
                LOGGER.info("[sbp] 已自动重提交填充任务 {}", task.pos);
            } catch (Throwable error) {
                LOGGER.warn("[sbp] 重提交填充任务失败 {}", task.pos, error);
            }
            pendingTasks.remove(task.pos.toShortString());
        }
    }

    private static boolean allInInventory(Set<Item> types) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }
        Set<Item> found = new HashSet<>();
        for (int slot = 0; slot < Math.min(36, client.player.getInventory().size()); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && types.contains(stack.getItem())) {
                found.add(stack.getItem());
            }
        }
        return found.containsAll(types);
    }
}
