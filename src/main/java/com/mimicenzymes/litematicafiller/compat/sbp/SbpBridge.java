package com.mimicenzymes.litematicafiller.compat.sbp;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
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
    /** 取货成功 → 材料在客户端可见的上限。散装几乎立刻到；盒装要等服务端的槽位同步。 */
    private static final long SYNC_TIMEOUT_MS = 6000L;
    /** 不知道缺的是哪几种（请求来自 prefetch，还没走过缺料判定）时只等一两拍，不久等。 */
    private static final long UNKNOWN_SYNC_GRACE_MS = 500L;

    private static final Set<Item> waiting = new HashSet<>();
    /** waiting 的起始 tick（自愈用：回包丢了不能永久卡住这个物品）。 */
    private static final Map<Item, Long> waitingSince = new HashMap<>();
    private static final Map<Item, Long> lastRequestTick = new HashMap<>();
    private static final Map<Item, Long> noStockUntil = new HashMap<>();
    /** 被「在途 / 去重窗口 / 无货退避」挡下的取货需求（物品 → 最少要取几个），冷却后自动补发。 */
    private static final Map<Item, Integer> demand = new HashMap<>();

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

    /**
     * 记一次"缺这个物品"的取货需求。
     *
     * <p>★ v1.8.3：被「在途 / 40 tick 去重 / 无货退避」挡下的需求**不再静默丢掉**，
     * 而是压进 {@link #demand}，由 {@link #flushDemand()} 在冷却结束时补发 —— 旧实现里
     * 第二个容器在同一个去重窗口内喊缺时请求被吞，容器被记进 failedContainers 就再也不重试，
     * 表现就是"填一次之后一直报缺材料"（Ray 2026-11-05 第二次实测）。</p>
     */
    private static void request(Item item) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        long now = client.world.getTime();
        int want = wantedAmount(item);
        Long last = lastRequestTick.get(item);
        boolean coolingDown = last != null && now - last < RETRY_TICKS;
        if (waiting.contains(item) || coolingDown) {
            demand.merge(item, want, Math::max);   // 等在途 / 冷却结束后补发
            return;
        }
        Long until = noStockUntil.get(item);
        if (until != null) {
            if (now < until) {
                return;
            }
            noStockUntil.remove(item);
        }
        send(item, want, now);
    }

    /** 冷却结束就把压下来的需求补发出去（没有压单时是空转）。 */
    private static void flushDemand() {
        if (demand.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !SbpPayloads.canSend()) {
            return;
        }
        long now = client.world.getTime();
        java.util.Iterator<Map.Entry<Item, Integer>> it = demand.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Item, Integer> entry = it.next();
            Item item = entry.getKey();
            if (waiting.contains(item)) {
                continue;                          // 还在等回包
            }
            Long last = lastRequestTick.get(item);
            if (last != null && now - last < RETRY_TICKS) {
                continue;                          // 还在去重窗口
            }
            Long until = noStockUntil.get(item);
            if (until != null && now < until) {
                continue;                          // 背包里确实没有，退避中
            }
            it.remove();
            send(item, Math.max(entry.getValue(), wantedAmount(item)), now);
        }
    }

    private static void send(Item item, int count, long now) {
        lastRequestTick.put(item, now);
        waiting.add(item);
        waitingSince.put(item, now);
        Identifier id = Registries.ITEM.getId(item);
        SbpPayloads.sendExtract(id, Math.max(1, count));
        LOGGER.info("[sbp] 填充机缺料 {} → 请求从精妙背包取货 x{}", id, count);
    }

    /**
     * 这次要取多少个 ＝ **所有排队容器对该物品的需求合计 − 背包里现有的散装数量**。
     *
     * <p>★ v1.8.3：旧实现固定按 {@code item.getMaxCount()} 取 —— 工具类（锹/镐）最大堆叠
     * 只有 1，于是一排容器每填一个都要往返一次，慢到爆；而且中间那些容器全被判缺料。</p>
     */
    private static int wantedAmount(Item item) {
        int need = 0;
        for (PendingTask task : pendingTasks.values()) {
            for (ItemStack req : task.required.values()) {
                if (!req.isEmpty() && req.getItem() == item) {
                    need += req.getCount();
                }
            }
        }
        return Math.max(1, need - looseCount(item));
    }

    /** 玩家 36 格里的散装数量（潜影盒内的不算：那些要开盒才能用，仍算"缺"）。 */
    private static int looseCount(Item item) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < Math.min(36, client.player.getInventory().size()); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 这些材料是否正在取货（在途或排队补发）⇒ 给"抑制缺少材料红字"用。 */
    public static boolean isFetching(java.util.Collection<Item> types) {
        if (types == null || types.isEmpty()) {
            return false;
        }
        for (Item item : types) {
            if (waiting.contains(item) || demand.containsKey(item)) {
                return true;
            }
        }
        return false;
    }

    /** 服务端回包：reason 0=成功 / 1=没找到 / 2=空间不足。 */
    public static void onResponse(Identifier itemId, int reason, String stashed) {
        Item item = Registries.ITEM.get(itemId);
        waiting.remove(item);
        waitingSince.remove(item);
        demand.remove(item);            // 这一次的账已结清；还需要更多会在下次判缺料时重新记
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
        flushDemand();
        tickReturnStash();
        if (pendingTasks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        AutoFillerStateMachine machine = null;
        for (PendingTask task : new java.util.ArrayList<>(pendingTasks.values())) {
            if (now - task.storedAt > 30_000L) {
                pendingTasks.remove(task.pos.toShortString());
                continue;
            }
            if (task.armedAt == 0L) {
                continue;                       // 还没取到货
            }
            // ★ v1.8.2：就绪判据改成「材料在客户端真的看得见」（含背包里潜影盒的内容），
            //   并且等不到就**不再硬提交** —— 硬提交会把"还没同步"误报成「目标材料耗尽」。
            long waited = now - task.armedAt;
            Set<Item> missing = task.missing;
            boolean known = missing != null && !missing.isEmpty();
            if (known ? !reachable(missing) : waited < UNKNOWN_SYNC_GRACE_MS) {
                if (known && waited >= SYNC_TIMEOUT_MS) {
                    pendingTasks.remove(task.pos.toShortString());
                    LOGGER.info("[sbp] 等了 {} ms 仍看不到材料 {} ⇒ 放弃本次自动重提交（请再按一次填充）", waited, missing);
                    toast("§e[背包] 材料还没同步到背包，请再按一次填充键");
                }
                continue;
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
        // ⚠️ 填充进行中 / 有容器界面打开时不要动背包：
        //    否则服务端搬物品会让填充机的槽位映射错乱，表现为"鼠标卡住"（2026-11-05 Ray 实测）
        if (AutoFillerStateMachine.getInstance().isWorking()) {
            return;
        }
        if (client.currentScreen != null) {
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

    /**
     * 这些物品是否已经**在客户端可见**（散装，或装在玩家背包里的潜影盒内）。
     *
     * <p>★ v1.8.2：旧版只数散装（`allInInventory`）⇒ 服务端把「整盒」搬过来时永远判不可见，
     * 超时后还会无条件重提交 ⇒ 盒子还没同步到客户端，重提交的任务当场判缺料、
     * 报「目标材料耗尽」（Ray 2026-11-05 实测：搬出一盒、填一次，再填就报错）。</p>
     */
    private static boolean reachable(Set<Item> types) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }
        Set<Item> found = new HashSet<>();
        for (int slot = 0; slot < Math.min(36, client.player.getInventory().size()); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (types.contains(stack.getItem())) {
                found.add(stack.getItem());
            }
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container == null) {
                    continue;
                }
                for (ItemStack inner : container.stream().toList()) {
                    if (!inner.isEmpty() && types.contains(inner.getItem())) {
                        found.add(inner.getItem());
                    }
                }
            }
        }
        return found.containsAll(types);
    }
}
