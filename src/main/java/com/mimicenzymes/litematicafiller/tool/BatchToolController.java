package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.InteractionTargeting;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.network.ClickPacketRateLimiter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ 1.12.0 起：**批量工具（批量容器同步）** —— 按二改（zhaixianyu/litematica-printer）的
 * 「数据通道」思路独立实现（Ray 2026-11-06 拍板，设计与教训见仓库根 {@code 批量容器操作-设计.md}）。
 *
 * <p>与 {@link ContainerToolStateMachine} 的关系：**零共享状态**。那套状态机是为"看准星、单容器、
 * 人手点一下"设计的；批量硬套它已经烧掉 1.9.0~1.11.2 十五个版本。这里另起一条最小闭环：</p>
 *
 * <pre>
 * 触发 → 收集容器（准星 1 个 / 投影选区队列）
 *   ↓ 每格：
 *   ① 发右键包开箱（远距离由服务端 mixin 放行）
 *   ② 拦 OpenScreen：只记 syncId + 建一个**影子菜单**（不创建 Screen、不挂 currentScreenHandler）
 *   ③ 等 InventoryS2CPacket 把内容灌进影子菜单
 *   ④ 直接用 ClickSlotC2SPacket 发槽位操作（不依赖客户端菜单）
 *   ⑤ 发关闭包，等 CloseScreenS2CPacket 确认 → 隔 2 tick 再开下一格
 * </pre>
 *
 * <p><b>为什么不建界面</b>：cancel {@code setScreen} 会破坏原版"界面 → currentScreenHandler"的绑定
 * （1.9.7~1.10.0 实测）；而 {@code onOpenScreen} 里建界面/挂菜单之前就拦掉，两边都不碰。
 * 影子菜单只用来读槽位/同步玩家背包（它的玩家槽引用的是同一个 {@code PlayerInventory}），
 * 从不赋给 {@code player.currentScreenHandler}，所以没有闪屏、没有鼠标锁、没有菜单竞态。</p>
 */
public final class BatchToolController {
    private static final BatchToolController INSTANCE = new BatchToolController();
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    /** 右键包发出后等 OpenScreen 的上限（tick）。 */
    private static final int OPEN_TIMEOUT_TICKS = 20;
    /** OpenScreen 之后等 InventoryS2CPacket 的上限（tick）。 */
    private static final int CONTENT_TIMEOUT_TICKS = 20;
    /** 点击包发完后等服务器同步的 tick 数（再决定要不要补第二遍）。 */
    private static final int SETTLE_TICKS = 2;
    /** 关闭包发出后等 CloseScreenS2CPacket 的上限（tick）。 */
    private static final int CLOSE_TIMEOUT_TICKS = 10;
    /** 两格之间额外等待的 tick 数（关箱确认后再等，避免"关箱顶掉刚开的菜单"）。 */
    private static final int CONTAINER_GAP_TICKS = 2;
    /** 每 tick 最多发多少个点击包（大箱子一次几百个会顶出服务端 tick）。 */
    private static final int MAX_CLICKS_PER_TICK = 64;

    private enum Phase {
        IDLE,
        WAIT_OPEN,
        WAIT_CONTENT,
        ACT,
        EXECUTE,
        SETTLE,
        WAIT_CLOSE,
        GAP
    }

    private record ClickOp(int slot, int button, SlotActionType action) {
    }

    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private final ArrayDeque<ClickOp> clickPlan = new ArrayDeque<>();

    private Phase phase = Phase.IDLE;
    private BatchToolMode mode = BatchToolMode.CLEAR;
    private BlockPos current;
    private int waited;
    private int gapTicks;
    private int syncId = -1;
    private ScreenHandler shadow;
    private boolean contentReady;
    private boolean closeSeen;
    private boolean secondPass;
    private int clearPass;
    private int total;
    private int done;
    private int skipped;
    private int consecutiveSkips;
    private int fillMissing;
    private boolean fillNoData;
    /** 收尾后保留影子菜单的 tick 数（等迟到的关箱/背包同步包；之后清掉避免 syncId 撞车）。 */
    private int lingerTicks;

    private BatchToolController() {
    }

    public static BatchToolController getInstance() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return this.phase != Phase.IDLE;
    }

    /** 供 HUD / 其他模块判断"批量工具正占着容器通道"。 */
    public BlockPos getCurrentPos() {
        return this.current;
    }

    public int getDoneCount() {
        return this.done;
    }

    public int getTotalCount() {
        return this.total;
    }

    // ------------------------------------------------------------------
    // 热键入口
    // ------------------------------------------------------------------

    /** 批量工具触发：没在跑就开跑；正在跑 = 取消（设计文档：再按热键 = 取消）。 */
    public void toggle(MinecraftClient client) {
        if (isRunning()) {
            cancel(client, "litematica_container_filler.message.batch_tool_cancelled");
            return;
        }
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return;
        }
        if (!Configs.BATCH_TOOL_ENABLED.getBooleanValue()) {
            message(client, "litematica_container_filler.message.batch_tool_disabled");
            return;
        }
        if (!(client.player.currentScreenHandler instanceof PlayerScreenHandler)) {
            message(client, "litematica_container_filler.message.batch_tool_close_screen_first");
            return;
        }

        BatchToolMode selected = getSelectedMode();
        List<BlockPos> targets = collectTargets(client);
        if (targets.isEmpty()) {
            return;
        }

        stopOtherWork(client);
        resetState();
        this.mode = selected;
        this.queue.addAll(targets);
        this.total = targets.size();
        this.done = 0;
        this.skipped = 0;
        LOGGER.info("[batch] 开始：{} 个容器，动作={}，范围={}",
                this.total, selected.getStringValue(), getSelectedRange().getStringValue());
        message(client, "litematica_container_filler.message.batch_tool_started", this.total);
        openNext(client);
    }

    /** 批量工具取消热键。 */
    public void cancel(MinecraftClient client) {
        if (!isRunning()) {
            return;
        }
        cancel(client, "litematica_container_filler.message.batch_tool_cancelled");
    }

    /** 模组被关掉 / 换世界时由客户端 tick 调用：不弹提示，直接收尾。 */
    public void stopForDisabledMod(MinecraftClient client) {
        if (isRunning()) {
            sendClose(client);
            resetState();
        }
        // 换世界/关模组：syncId 会重新计数，别让旧影子菜单继续吃包。
        this.shadow = null;
        this.syncId = -1;
    }

    // ------------------------------------------------------------------
    // 数据通道：由 ClientPlayNetworkHandlerMixin 在主线程调用
    // ------------------------------------------------------------------

    /**
     * 拦到 OpenScreen 时调用（mixin 在主线程、{@code forceMainThread} 之后调用）。
     *
     * @return true = 这条包由批量工具吃掉（mixin 取消原版，不建 Screen、不挂菜单）
     */
    public boolean handleOpenScreen(int packetSyncId, ScreenHandlerType<?> type) {
        if (!isRunning()) {
            return false;
        }
        if (this.phase == Phase.WAIT_OPEN) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null) {
                return false;
            }
            try {
                this.shadow = type.create(packetSyncId, client.player.getInventory());
            } catch (Throwable t) {
                LOGGER.warn("[batch] 建影子菜单失败 syncId={} type={}", packetSyncId, type, t);
                this.shadow = null;
            }
            this.syncId = packetSyncId;
            this.contentReady = false;
            this.closeSeen = false;
            this.waited = 0;
            this.phase = Phase.WAIT_CONTENT;
            LOGGER.info("[batch] 接管界面 syncId={} type={} pos={}", packetSyncId, type, this.current);
            return true;
        }

        // 不是我们等的那次开箱（玩家自己开的 / 上一格迟到的回包）：只吃掉并关掉它，不中止整批。
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(packetSyncId));
        }
        LOGGER.info("[batch] 忽略多余的开箱 syncId={} 当前阶段={} pos={}", packetSyncId, this.phase, this.current);
        return true;
    }

    /** 收到整份容器内容（InventoryS2CPacket）时调用。 */
    public void handleInventory(InventoryS2CPacket packet) {
        if (this.shadow == null || packet.getSyncId() != this.syncId) {
            return;
        }
        try {
            this.shadow.updateSlotStacks(packet.getRevision(), packet.getContents(), packet.getCursorStack());
            this.contentReady = true;
        } catch (Throwable t) {
            LOGGER.warn("[batch] 应用 InventoryS2CPacket 失败 syncId={}", packet.getSyncId(), t);
        }
    }

    /** 收到单槽位同步（ScreenHandlerSlotUpdateS2CPacket）时调用。 */
    public void handleSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet) {
        if (this.shadow == null || packet.getSyncId() != this.syncId) {
            return;
        }
        int slot = packet.getSlot();
        if (slot < 0 || slot >= this.shadow.slots.size()) {
            return;
        }
        try {
            this.shadow.setStackInSlot(slot, packet.getRevision(), packet.getStack());
        } catch (Throwable t) {
            LOGGER.warn("[batch] 应用槽位同步失败 syncId={} slot={}", packet.getSyncId(), slot, t);
        }
    }

    /**
     * 收到 CloseScreen 时调用。
     *
     * @return true = 这条包由批量工具吃掉（避免原版 {@code player.closeScreen()} 关掉玩家正开着的其他界面）
     */
    public boolean handleCloseScreen(int packetSyncId) {
        if (this.shadow != null && packetSyncId == this.syncId) {
            this.closeSeen = true;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    public void tick(MinecraftClient client) {
        if (this.phase == Phase.IDLE) {
            // 收尾后让影子菜单再活 1 秒：吃掉迟到的关箱包，并把最后几笔背包同步应用完。
            if (this.shadow != null && --this.lingerTicks <= 0) {
                this.shadow = null;
                this.syncId = -1;
            }
            return;
        }
        if (client == null || client.player == null || client.world == null) {
            resetState();
            return;
        }
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            stopForDisabledMod(client);
            return;
        }
        // 玩家自己打开了任何界面（背包/聊天/ESC/其他模组 GUI）⇒ 让路，别和他的操作抢菜单。
        if (client.currentScreen != null) {
            cancel(client, "litematica_container_filler.message.batch_tool_screen_opened");
            return;
        }

        switch (this.phase) {
            case WAIT_OPEN -> {
                if (++this.waited > OPEN_TIMEOUT_TICKS) {
                    skip(client, "litematica_container_filler.message.batch_tool_open_timeout");
                }
            }
            case WAIT_CONTENT -> {
                if (this.contentReady) {
                    this.waited = 0;
                    this.phase = Phase.ACT;
                } else if (++this.waited > CONTENT_TIMEOUT_TICKS) {
                    skip(client, "litematica_container_filler.message.batch_tool_content_timeout");
                }
            }
            case ACT -> {
                performAction(client);
                this.waited = 0;
            }
            case EXECUTE -> {
                int budget = MAX_CLICKS_PER_TICK;
                while (budget-- > 0 && !this.clickPlan.isEmpty()) {
                    ClickOp op = this.clickPlan.poll();
                    sendClick(op.slot(), op.button(), op.action());
                }
                if (this.clickPlan.isEmpty()) {
                    this.waited = 0;
                    this.phase = Phase.SETTLE;
                }
            }
            case SETTLE -> {
                if (++this.waited >= SETTLE_TICKS) {
                    if (this.secondPass && hasContainerItems(client)) {
                        this.secondPass = false;
                        this.clearPass = 2;
                        this.waited = 0;
                        this.phase = Phase.ACT;
                    } else {
                        sendClose(client);
                        this.waited = 0;
                        this.phase = Phase.WAIT_CLOSE;
                    }
                }
            }
            case WAIT_CLOSE -> {
                if (this.closeSeen) {
                    completeCurrent(client);
                    this.gapTicks = CONTAINER_GAP_TICKS;
                    this.phase = Phase.GAP;
                } else if (++this.waited > CLOSE_TIMEOUT_TICKS) {
                    completeCurrent(client);
                    this.gapTicks = CONTAINER_GAP_TICKS;
                    this.phase = Phase.GAP;
                }
            }
            case GAP -> {
                if (--this.gapTicks <= 0) {
                    openNext(client);
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // 队列 / 开箱 / 收尾
    // ------------------------------------------------------------------

    private List<BlockPos> collectTargets(MinecraftClient client) {
        List<BlockPos> targets = new ArrayList<>();
        BatchToolRange range = getSelectedRange();
        if (range == BatchToolRange.CROSSHAIR) {
            BlockPos looked = getLookedContainerPos(client);
            if (looked == null) {
                message(client, "litematica_container_filler.message.batch_tool_no_target");
                return targets;
            }
            targets.add(normalizeContainerPos(client, looked));
            return targets;
        }

        // 选区优先（Ray 2026-11-06 口径）：只处理框选范围内的真实容器。
        targets.addAll(ContainerBatchQueue.build(client));
        if (targets.isEmpty()) {
            message(client, "litematica_container_filler.message.batch_tool_no_containers");
        }
        return targets;
    }

    private void openNext(MinecraftClient client) {
        BlockPos pos = this.queue.poll();
        if (pos == null) {
            finish(client);
            return;
        }
        this.current = pos.toImmutable();
        this.waited = 0;
        // ⚠️ 故意不清 shadow / syncId：上一格迟到的背包同步包还要灌进玩家背包；
        //    新的 OpenScreen 到达时 handleOpenScreen 会整体替换。
        this.contentReady = false;
        this.closeSeen = false;
        this.secondPass = false;
        this.clearPass = 1;
        this.fillMissing = 0;
        this.fillNoData = false;
        this.clickPlan.clear();
        this.phase = Phase.WAIT_OPEN;

        BlockHitResult hit = InteractionTargeting.createBlockHitResult(client, this.current);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
    }

    private void completeCurrent(MinecraftClient client) {
        this.done++;
        this.consecutiveSkips = 0;
        cacheSnapshot(client);
        LOGGER.info("[batch] 完成 {}/{} pos={}", this.done, this.total, this.current);
        if (this.total > 1) {
            message(client, "litematica_container_filler.message.batch_tool_progress", this.done, this.total);
        }
        this.current = null;
    }

    private void skip(MinecraftClient client, String messageKey) {
        skip(client, messageKey, true);
    }

    /**
     * @param failure true = 计入"连续打不开"（服务端拒绝的判据）；false = 正常跳过（例如投影里没数据）
     */
    private void skip(MinecraftClient client, String messageKey, boolean failure) {
        this.skipped++;
        if (failure) {
            this.consecutiveSkips++;
        }
        LOGGER.info("[batch] 跳过 pos={} 原因={} syncId={} 影子菜单={}",
                this.current, messageKey, this.syncId, this.shadow == null ? "无" : "有");
        sendClose(client);
        message(client, messageKey);
        // 跳过不代表容器被清空，RealContainerCache 保持原样（下次高亮/填充仍会看到它）。
        this.current = null;

        // 一个都没成功却连着 3 格打不开 ⇒ 多半是服务端在拒绝（远距离开箱没生效 / 没装本模组）。
        if (failure && this.done == 0 && this.consecutiveSkips >= 3) {
            LOGGER.info("[batch] 连续 {} 格打不开，中止（done=0）", this.consecutiveSkips);
            resetState();
            message(client, "litematica_container_filler.message.batch_tool_all_failed");
            return;
        }

        this.gapTicks = CONTAINER_GAP_TICKS;
        this.phase = Phase.GAP;
    }

    private void finish(MinecraftClient client) {
        int doneCount = this.done;
        int skippedCount = this.skipped;
        resetState();
        LOGGER.info("[batch] 结束：完成 {} / 跳过 {} / 共 {}", doneCount, skippedCount, doneCount + skippedCount);
        message(client, "litematica_container_filler.message.batch_tool_done", doneCount, skippedCount);
    }

    private void cancel(MinecraftClient client, String messageKey) {
        sendClose(client);
        resetState();
        LOGGER.info("[batch] 取消：{}", messageKey);
        message(client, messageKey);
    }

    private void resetState() {
        this.phase = Phase.IDLE;
        this.mode = BatchToolMode.CLEAR;
        this.current = null;
        this.waited = 0;
        this.gapTicks = 0;
        this.queue.clear();
        this.total = 0;
        this.done = 0;
        this.skipped = 0;
        this.consecutiveSkips = 0;
        this.contentReady = false;
        this.closeSeen = false;
        this.secondPass = false;
        this.clearPass = 1;
        this.fillMissing = 0;
        this.fillNoData = false;
        this.clickPlan.clear();
        this.lingerTicks = 20;
        // shadow / syncId 故意保留：关箱包可能晚到一两 tick，还要靠它们吃掉
        // CloseScreen 并同步玩家背包；lingerTicks 到点或下一次 openNext 会整体替换。
    }

    private void sendClose(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null || this.syncId < 0) {
            return;
        }
        client.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(this.syncId));
    }

    private void stopOtherWork(MinecraftClient client) {
        AutoFillerStateMachine filler = AutoFillerStateMachine.getInstance();
        if (Configs.WORKING_STATE.getBooleanValue() || filler.isWorking()) {
            Configs.WORKING_STATE.setBooleanValue(false);
            filler.emergencyStop(client);
        }
        ContainerToolStateMachine tool = ContainerToolStateMachine.getInstance();
        if (tool.isWorking()) {
            tool.stopForDisabledMod(client);
        }
        ClickPacketRateLimiter.reset();
    }

    // ------------------------------------------------------------------
    // 具体动作：先生成点击计划，再按每 tick 预算发出去
    // ------------------------------------------------------------------

    private void performAction(MinecraftClient client) {
        this.clickPlan.clear();
        if (this.mode == BatchToolMode.FILL) {
            buildFillPlan(client);
        } else {
            buildClearPlan(client);
        }

        if (this.fillNoData) {
            // 投影里没有这个容器的内容数据：跳过（不算失败，不触发连续失败中止）。
            this.fillNoData = false;
            skip(client, "litematica_container_filler.message.batch_tool_no_schematic", false);
            return;
        }
        if (this.clickPlan.isEmpty()) {
            this.waited = 0;
            this.phase = Phase.SETTLE;
            return;
        }
        this.phase = Phase.EXECUTE;
    }

    private void buildClearPlan(MinecraftClient client) {
        ContainerClearOutputMode output = getClearOutputMode();
        boolean toInventory = output != ContainerClearOutputMode.DROP;
        boolean secondRound = this.clearPass >= 2;

        int clicks = 0;
        for (Slot slot : containerSlots(client)) {
            if (!slot.hasStack()) {
                continue;
            }
            if (toInventory && !secondRound) {
                // 先尝试整组塞进玩家背包；背包满的会留在容器里，第二遍再丢。
                this.clickPlan.add(new ClickOp(slot.id, 0, SlotActionType.QUICK_MOVE));
            } else {
                this.clickPlan.add(new ClickOp(slot.id, 1, SlotActionType.THROW));
            }
            clicks++;
        }

        if (output == ContainerClearOutputMode.INVENTORY_THEN_DROP && !secondRound) {
            this.secondPass = true;
        }
        LOGGER.info("[batch] 清空 pos={} 输出={} 第{}遍 计划点击 {} 次",
                this.current, output.getStringValue(), this.clearPass, clicks);
    }

    /**
     * 「填满容器」：按**这一格自己的投影模板**（{@code LitematicaContainerReader.getRequiredItems}）
     * 把容器对齐到模板——多出来的丢掉、少的从玩家背包搬进来。
     *
     * <p>点击计划用一个**虚拟背包模型**逐步推演（服务端会按同样顺序执行这些包），
     * 所以能精确到"某槽放 10 个"而不是只会整组塞。规则：</p>
     * <ul>
     *   <li>整组够用（手里 ≤ 还差的数量）⇒ 左键一次整组放入；</li>
     *   <li>只需要一部分 ⇒ 右键一个一个放（NBT 模板里不满一组的情况很少）；</li>
     *   <li>手里剩的放回来源槽，保证下一次拾取时手上是空的。</li>
     * </ul>
     */
    private void buildFillPlan(MinecraftClient client) {
        if (this.shadow == null || this.current == null) {
            return;
        }
        Map<Integer, ItemStack> required;
        try {
            required = LitematicaContainerReader.getRequiredItems(this.current, client.world.getRegistryManager());
        } catch (Throwable t) {
            LOGGER.warn("[batch] 读投影容器数据失败 pos={}", this.current, t);
            required = Map.of();
        }
        if (required.isEmpty()) {
            // 空 map 有两种含义：①投影里没有这个容器（跳过）②投影里这个容器本来就是空的（清空它）。
            boolean schematicHasContainer = false;
            try {
                var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                schematicHasContainer = schematicWorld != null && schematicWorld.getBlockEntity(this.current) != null;
            } catch (Throwable ignored) {
            }
            if (!schematicHasContainer) {
                this.fillNoData = true;
                return;
            }
        }

        Map<Integer, ItemStack> model = new HashMap<>();
        List<Integer> playerSlots = new ArrayList<>();
        for (Slot slot : this.shadow.slots) {
            model.put(slot.id, slot.getStack().copy());
            if (slot.inventory == client.player.getInventory()) {
                playerSlots.add(slot.id);
            }
        }

        ItemStack cursor = ItemStack.EMPTY;
        int lastSource = -1;
        int missing = 0;
        int planned = 0;

        for (Slot slot : containerSlots(client)) {
            ItemStack target = required.getOrDefault(slot.getIndex(), ItemStack.EMPTY);
            ItemStack current = model.getOrDefault(slot.id, ItemStack.EMPTY);

            if (target.isEmpty()) {
                if (!current.isEmpty()) {
                    this.clickPlan.add(new ClickOp(slot.id, 1, SlotActionType.THROW));
                    model.put(slot.id, ItemStack.EMPTY);
                    planned++;
                }
                continue;
            }
            if (ItemMatcher.isSameItem(current, target) && current.getCount() == target.getCount()) {
                continue;
            }
            if (!current.isEmpty()) {
                this.clickPlan.add(new ClickOp(slot.id, 1, SlotActionType.THROW));
                model.put(slot.id, ItemStack.EMPTY);
                planned++;
            }
            if (!canInsertInto(client, slot, target)) {
                // 目标槽不收这种物品（熔炉燃料槽 / 输出槽等）⇒ 记缺料，别硬塞。
                missing += target.getCount();
                continue;
            }

            int placed = 0;
            while (placed < target.getCount()) {
                if (cursor.isEmpty()) {
                    int src = findPlayerSlotWithItem(model, playerSlots, target);
                    if (src < 0) {
                        missing += target.getCount() - placed;
                        break;
                    }
                    cursor = model.get(src).copy();
                    model.put(src, ItemStack.EMPTY);
                    lastSource = src;
                    this.clickPlan.add(new ClickOp(src, 0, SlotActionType.PICKUP));
                    planned++;
                }
                int need = target.getCount() - placed;
                if (cursor.getCount() <= need) {
                    this.clickPlan.add(new ClickOp(slot.id, 0, SlotActionType.PICKUP));
                    placed += cursor.getCount();
                    cursor = ItemStack.EMPTY;
                } else {
                    this.clickPlan.add(new ClickOp(slot.id, 1, SlotActionType.PICKUP));
                    placed++;
                    cursor.decrement(1);
                    if (cursor.isEmpty()) {
                        cursor = ItemStack.EMPTY;
                    }
                }
                planned++;
            }
            if (!cursor.isEmpty()) {
                int back = lastSource >= 0 ? lastSource : findEmptyPlayerSlot(model, playerSlots);
                if (back >= 0) {
                    this.clickPlan.add(new ClickOp(back, 0, SlotActionType.PICKUP));
                    model.put(back, cursor.copy());
                    planned++;
                }
                cursor = ItemStack.EMPTY;
            }
            if (placed > 0) {
                ItemStack placedStack = target.copy();
                placedStack.setCount(placed);
                model.put(slot.id, placedStack);
            }
        }

        this.fillMissing = missing;
        LOGGER.info("[batch] 填满 pos={} 模板槽 {} 个，计划点击 {} 次，缺料 {}",
                this.current, required.size(), planned, missing);
        if (missing > 0) {
            message(client, "litematica_container_filler.message.batch_tool_fill_missing", missing);
        }
    }

    /**
     * 判断某个容器槽能不能放这种物品。优先问**真实方块实体**的 Inventory：
     * 影子菜单里的槽位是 `SimpleInventory`（什么都收），会漏掉熔炉输出槽/燃料槽这类限制。
     */
    private boolean canInsertInto(MinecraftClient client, Slot slot, ItemStack target) {
        if (this.current != null && client.world != null) {
            net.minecraft.block.entity.BlockEntity blockEntity = client.world.getBlockEntity(this.current);
            if (blockEntity instanceof net.minecraft.inventory.Inventory inventory) {
                try {
                    return inventory.isValid(slot.getIndex(), target);
                } catch (Throwable ignored) {
                }
            }
        }
        return slot.canInsert(target);
    }

    private int findPlayerSlotWithItem(Map<Integer, ItemStack> model, List<Integer> playerSlots, ItemStack target) {        for (int slotId : playerSlots) {
            ItemStack stack = model.getOrDefault(slotId, ItemStack.EMPTY);
            if (!stack.isEmpty() && ItemMatcher.isSameItem(stack, target)) {
                return slotId;
            }
        }
        return -1;
    }

    private int findEmptyPlayerSlot(Map<Integer, ItemStack> model, List<Integer> playerSlots) {
        for (int slotId : playerSlots) {
            if (model.getOrDefault(slotId, ItemStack.EMPTY).isEmpty()) {
                return slotId;
            }
        }
        return -1;
    }

    private void sendClick(int slot, int button, SlotActionType action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || this.syncId < 0) {
            return;
        }
        int revision = this.shadow == null ? 0 : this.shadow.getRevision();
        client.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                this.syncId,
                revision,
                slot,
                button,
                action,
                ItemStack.EMPTY,
                Int2ObjectMaps.emptyMap()
        ));
    }

    private boolean hasContainerItems(MinecraftClient client) {
        for (Slot slot : containerSlots(client)) {
            if (slot.hasStack()) {
                return true;
            }
        }
        return false;
    }

    /** 影子菜单里"属于容器自己"的槽位（排除玩家背包 36 格）。 */
    private List<Slot> containerSlots(MinecraftClient client) {
        List<Slot> slots = new ArrayList<>();
        if (this.shadow == null || client == null || client.player == null) {
            return slots;
        }
        for (Slot slot : this.shadow.slots) {
            if (slot.inventory == null || slot.inventory == client.player.getInventory()) {
                continue;
            }
            if (!slot.isEnabled()) {
                continue;
            }
            slots.add(slot);
        }
        return slots;
    }

    private void cacheSnapshot(MinecraftClient client) {
        if (this.current == null) {
            return;
        }
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        for (Slot slot : containerSlots(client)) {
            if (!slot.getStack().isEmpty()) {
                items.put(slot.getIndex(), slot.getStack().copy());
            }
        }
        RealContainerCache.put(this.current, items);
    }

    // ------------------------------------------------------------------
    // 目标 / 配置
    // ------------------------------------------------------------------

    private BlockPos getLookedContainerPos(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult hit) || client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.world.getBlockState(pos);
        if (state == null || state.isAir() || !state.hasBlockEntity()) {
            return null;
        }
        if (!ContainerBlockFilter.isAllowedForTools(state, client.world, pos)) {
            return null;
        }
        return pos.toImmutable();
    }

    /** 双箱统一用第一半作为任务位置，避免同一对箱子被处理两次。 */
    private BlockPos normalizeContainerPos(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, pos, state);
        return halves != null ? halves[0].toImmutable() : pos.toImmutable();
    }

    private BatchToolMode getSelectedMode() {
        if (Configs.BATCH_TOOL_MODE.getOptionListValue() instanceof BatchToolMode selected) {
            return selected;
        }
        return BatchToolMode.CLEAR;
    }

    private BatchToolRange getSelectedRange() {
        if (Configs.BATCH_TOOL_RANGE.getOptionListValue() instanceof BatchToolRange selected) {
            return selected;
        }
        return BatchToolRange.SELECTION;
    }

    private ContainerClearOutputMode getClearOutputMode() {
        if (Configs.CONTAINER_CLEAR_OUTPUT_MODE.getOptionListValue() instanceof ContainerClearOutputMode selected) {
            return selected;
        }
        return ContainerClearOutputMode.DROP;
    }

    private void message(MinecraftClient client, String key, Object... args) {
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.translatable(key, args), true);
        }
    }
}
