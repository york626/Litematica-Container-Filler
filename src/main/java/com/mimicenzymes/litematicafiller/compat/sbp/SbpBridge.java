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
    /** waiting 的起始 tick（自愈用：回包丢了不能永久卡住这个物品）。 */
    private static final Map<Item, Long> waitingSince = new HashMap<>();
    private static final Map<Item, Long> lastRequestTick = new HashMap<>();
    private static final Map<Item, Long> noStockUntil = new HashMap<>();

    /** 每个容器最近一次的填充请求（用于自动重提交）。 */
    private static final Map<String, PendingTask> pendingTasks = new HashMap<>();
    /** 我们自己塞进精妙背包的物品（填充腾位用）⇒ 等背包有空间自动取回，不让物品"凭空消失"。 */
    private static final Map<Item, Integer> stashedForReturn = new HashMap<>();

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

    /**
     * 提前取货：按下填充键时就把"背包里能补的材料"先请求出来，
     * 让填充机判缺料时多半已经到货（减少一次"缺料→重试"的往返）。
     */
    public static void prefetchFor(Map<Integer, ItemStack> required) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled() || client.player == null || required == null) {
            return;
        }
        java.util.Set<Item> needed = new java.util.HashSet<>();
        for (ItemStack stack : required.values()) {
            if (!stack.isEmpty()) {
                needed.add(stack.getItem());
            }
        }
        java.util.Set<Item> inInventory = new java.util.HashSet<>();
        for (int slot = 0; slot < Math.min(36, client.player.getInventory().size()); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                inInventory.add(stack.getItem());
            }
        }
        for (Item item : needed) {
            if (!inInventory.contains(item)) {
                request(item);
            }
        }
    }

    /** 这个容器是否还处在"已请求取货、等着重提交"的状态（用来抑制"缺少材料"红字）。 */
    public static boolean hasPending(BlockPos pos) {
        return pos != null && pendingTasks.containsKey(pos.toShortString());
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
        waitingSince.put(item, now);
        Identifier id = Registries.ITEM.getId(item);
        SbpPayloads.sendExtract(id, Math.max(1, item.getMaxCount()));
        LOGGER.info("[sbp] 填充机缺料 {} → 请求从精妙背包取货", id);
    }

    /** 服务端回包：reason 0=成功 / 1=没找到 / 2=空间不足。 */
    public static void onResponse(Identifier itemId, int reason, String stashed) {
        Item item = Registries.ITEM.get(itemId);
        waiting.remove(item);
        waitingSince.remove(item);
        registerStashed(stashed);
        MinecraftClient client = MinecraftClient.getInstance();
        long now = client.world == null ? 0L : client.world.getTime();
        if (reason != SbpPayloads.OK) {
            noStockUntil.put(item, now + NO_STOCK_TICKS);
            if (reason == SbpPayloads.NO_SPACE) {
                LOGGER.info("[sbp] 背包空间不足，需要玩家清理");
                toast("§c[背包] 空间不足：请清理出 1 格空间（或把东西放进箱子）再填充");
            } else {
                LOGGER.info("[sbp] 背包里没有 {}（{} tick 内不再请求）", itemId, NO_STOCK_TICKS);
            }
            return;
        }
        LOGGER.info("[sbp] 操作成功 {} ⇒ 等背包同步后重提交填充任务", itemId);
        if (stashedForReturn.remove(item) != null) {
            LOGGER.info("[sbp] 暂存物品 {} 已取回", itemId);
        }
        long armAt = System.currentTimeMillis();
        for (PendingTask task : pendingTasks.values()) {
            if (task.armedAt == 0L) {
                task.armedAt = armAt;
            }
        }
    }

    /**
     * 背包满、又没有可用的潜影盒腾位时：把最大的"非任务需求"物品塞回精妙背包。
     *
     * <p>Ray 2026-11-04：填充机的「有序存放」只认玩家背包里的潜影盒，希望也能用精妙背包。
     *
     * @return 是否已发出回存请求（发出后由服务端回包决定是否重提交任务）
     */
    public static boolean tryStashIntoBackpack(BlockPos pos, Map<Integer, ItemStack> required) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled() || client.player == null || pos == null || required == null) {
            return false;
        }
        java.util.Set<Item> needed = new java.util.HashSet<>();
        for (ItemStack stack : required.values()) {
            if (!stack.isEmpty()) {
                needed.add(stack.getItem());
            }
        }
        Item bestItem = null;
        int bestCount = 0;
        for (int slot = 9; slot < Math.min(36, client.player.getInventory().size()); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty() || needed.contains(stack.getItem())) {
                continue;
            }
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestItem = stack.getItem();
            }
        }
        if (bestItem == null || bestCount <= 0 || !SbpPayloads.canSend()) {
            return false;
        }
        rememberTask(pos, required, true);
        stashedForReturn.merge(bestItem, bestCount, Integer::sum);
        toast("§e[背包] 背包已满：先把 " + bestItem.getName().getString() + " x" + bestCount + " 暂存进精妙背包，填充结束后自动取回");
        SbpPayloads.sendStore(Registries.ITEM.getId(bestItem), bestCount);
        LOGGER.info("[sbp] 背包满 ⇒ 请求把 {} x{} 存回精妙背包腾位", Registries.ITEM.getId(bestItem), bestCount);
        return true;
    }

    private static void toast(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(message), true);
        }
    }

    /**
     * 每客户端 tick 调：把"已取到货"的任务在背包同步完成后重新提交。
     *
     * <p>⚠️ 不能只在回包里立刻重提交：回包先到、客户端背包同步后到，立刻提交仍会被判缺料
     * （2026-11-03 实测：这就是"要按两次"的原因之一）。所以这里按 tick 重试，最多等 2 秒。
     */
    public static void tick() {
        expireStaleWaiting();
        tickReturnStash();
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

    /** 回包丢了（服务器没回/被中断）时，200 tick 后自己解除等待，避免永久卡死。 */
    private static void expireStaleWaiting() {
        if (waiting.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        long now = client.world.getTime();
        java.util.Iterator<Item> it = waiting.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            Long since = waitingSince.get(item);
            if (since != null && now - since > 200L) {
                it.remove();
                waitingSince.remove(item);
                LOGGER.info("[sbp] 等待超时，解除 {} 的等待状态", Registries.ITEM.getId(item));
            }
        }
    }

    private static long lastReturnAttempt = 0L;
    private static long lastReturnDiag = 0L;

    /** 有空间时，把之前"暂存进背包"的物品取回来（每 500ms 最多试一件）。 */
    private static void tickReturnStash() {
        if (stashedForReturn.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastReturnDiag > 3000L) {
            lastReturnDiag = nowMs;
            LOGGER.info("[sbp] 待取回清单：{}", stashedForReturn);
        }
        long now = System.currentTimeMillis();
        if (now - lastReturnAttempt < 500L) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !SbpPayloads.canSend()) {
            return;
        }
        // 按"这件物品能不能放下"判断（有空格 或 有同类未满堆），而不是死要 2 个空格
        // （2026-11-04 Ray 实测：只剩 1 格时空桶永远取不回）
        java.util.Iterator<Map.Entry<Item, Integer>> it = stashedForReturn.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Item, Integer> entry = it.next();
            Item item = entry.getKey();
            boolean room = false;
            for (int slot = 0; slot < Math.min(36, client.player.getInventory().size()); slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (stack.isEmpty()
                        || (stack.isOf(item) && stack.getCount() < stack.getMaxCount())) {
                    room = true;
                    break;
                }
            }
            if (!room) {
                return; // 这件还放不下，等填充消耗掉一些
            }
            lastReturnAttempt = now;
            // ⚠️ 不要在这里把 entry 删掉：只有**成功回包**才删（2026-11-04 Ray 报"没归还"：
            //    原先发出去就删，一旦那次请求失败就永久丢失重试机会）
            request(item);
            LOGGER.info("[sbp] 请求取回暂存物品 {} x{}", Registries.ITEM.getId(item), entry.getValue());
            return;
        }
    }

    /** 服务端为了腾位把哪些东西塞进背包了 ⇒ 记进待取回清单（填充结束后自动取回）。 */
    private static void registerStashed(String stashed) {
        if (stashed == null || stashed.isBlank()) {
            return;
        }
        for (String part : stashed.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                Identifier id = Identifier.of(part.substring(0, eq));
                int amount = Integer.parseInt(part.substring(eq + 1));
                Item item = Registries.ITEM.get(id);
                stashedForReturn.merge(item, amount, Integer::sum);
                LOGGER.info("[sbp] 登记待取回：{} x{}", id, amount);
            } catch (Throwable ignored) {
                // 单个解析失败不影响其它
            }
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
