package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.InteractionTargeting;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.network.BatchServerLink;
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
    /** 「放入背包+丢出」第二遍前多等一会儿，让服务端把背包同步回来。 */
    private static final int SETTLE_TICKS_SECOND_PASS = 2;
    /** 每 tick 最多发多少个点击包（大箱子一次几百个会顶出服务端 tick）。 */
    private static final int MAX_CLICKS_PER_TICK = 64;
    /** 服务端直连：每 tick 最多发多少个容器请求。 */
    private static final int FAST_SEND_PER_TICK = 16;
    /** 服务端直连：发完所有请求后等服务端回执的上限（tick）。 */
    private static final int FAST_TIMEOUT_TICKS = 200;

    private enum Phase {
        IDLE,
        WAIT_OPEN,
        WAIT_CONTENT,
        ACT,
        EXECUTE,
        SETTLE,
        FAST_SEND,
        FAST_WAIT
    }

    private record ClickOp(int slot, int button, SlotActionType action) {
    }

    /** 「填满容器」的物品键（严格匹配：物品 + 组件，与容器工具同一套口径）。 */
    private record ItemKey(ItemStack stack) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof ItemKey other && ItemMatcher.isSameItem(this.stack, other.stack);
        }

        @Override
        public int hashCode() {
            return ItemMatcher.matchingHash(this.stack);
        }
    }

    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private final ArrayDeque<ClickOp> clickPlan = new ArrayDeque<>();

    private Phase phase = Phase.IDLE;
    private BatchToolMode mode = BatchToolMode.CLEAR;
    /** true = 走服务端直连（装了本模组的服务端 / 单人），false = 回退到"开箱+点槽位"。 */
    private boolean useServerLink;
    /** 服务端直连：已发未回执的请求数。 */
    private int pendingFast;
    /** 服务端直连：累计缺料数（回执里带回来的）。 */
    private int fastMissing;
    /** 服务端直连：按投影填充的模板缓存（回执 DONE 后写进 RealContainerCache）。 */
    private final Map<Long, Map<Integer, ItemStack>> pendingFastTargets = new HashMap<>();
    private BlockPos current;
    private int waited;
    private long openedAtMs;
    private int syncId = -1;
    private ScreenHandler shadow;
    private boolean contentReady;
    private boolean secondPass;
    private int clearPass;
    private int total;
    private int done;
    private int skipped;
    private int consecutiveSkips;
    private int fillMissing;
    private boolean fillNoData;
    /** 跳过原因细分：true = 背包里没有明确的可填物品（填满语义），false = 投影里没这格的数据。 */
    private boolean fillNoItem;
    private Map<Integer, ItemStack> fillTargets = Map.of();
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
        BatchToolState.setActive(true);
        this.mode = selected;
        this.queue.addAll(targets);
        this.total = targets.size();
        this.done = 0;
        this.skipped = 0;
        this.useServerLink = BatchServerLink.isSupported();
        if (selected == BatchToolMode.SCHEMATIC_FILL) {
            // 模板索引是懒建的：批量开始前重建一次，避免"索引过期/投影后加"导致误判没数据
            try {
                com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData.rebuildIndex();
            } catch (Throwable ignored) {
            }
        }
        LOGGER.info("[batch] 开始：{} 个容器，动作={}，范围={}，通道={}",
                this.total, selected.getStringValue(), getSelectedRange().getStringValue(),
                this.useServerLink ? "服务端直连" : "开箱+点槽位");
        message(client, "litematica_container_filler.message.batch_tool_started", this.total);
        if (this.useServerLink) {
            this.phase = Phase.FAST_SEND;
        } else {
            openNext(client);
        }
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
        return this.shadow != null && packetSyncId == this.syncId;
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
                    if (this.secondPass) {
                        // 「放入背包+丢出」要等服务端同步回来，才知道还剩什么。
                        this.waited = 0;
                        this.phase = Phase.SETTLE;
                    } else {
                        // 包按序处理：点击一定先于下一次右键生效 ⇒ 同一 tick 直接开下一格，省掉 1 tick。
                        finishCurrent(client);
                    }
                }
            }
            case SETTLE -> {
                // 只有「放入背包+丢出」会进这个阶段：要等服务端把背包同步回来，才知道还剩什么。
                if (++this.waited >= SETTLE_TICKS_SECOND_PASS) {
                    if (this.secondPass && hasContainerItems(client)) {
                        this.secondPass = false;
                        this.clearPass = 2;
                        this.waited = 0;
                        this.phase = Phase.ACT;
                    } else {
                        finishCurrent(client);
                    }
                }
            }
            case FAST_SEND -> {
                // 服务端直连：不开箱、不发点击，直接把"位置+动作"发给服务端执行。
                int budget = FAST_SEND_PER_TICK;
                while (budget-- > 0 && !this.queue.isEmpty()) {
                    sendFast(client, this.queue.poll());
                }
                if (this.queue.isEmpty()) {
                    this.waited = 0;
                    this.phase = Phase.FAST_WAIT;
                }
            }
            case FAST_WAIT -> {
                if (this.pendingFast <= 0) {
                    finish(client);
                } else if (++this.waited > FAST_TIMEOUT_TICKS) {
                    LOGGER.info("[batch] 服务端回执超时（剩 {} 个未回执），收尾", this.pendingFast);
                    finish(client);
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
        openContainer(client, pos);
    }

    /**
     * 开下一格。**不发关箱包**：服务端 {@code openHandledScreen} 发现还有容器开着会先
     * {@code closeHandledScreen()} 关掉上一格，然后才开新的；包在同一条连接上按序处理，
     * 所以上一格的点击一定先于这次右键生效。这样每格省掉「关箱 → 等确认 → 再等 2 tick」。
     */
    private void openContainer(MinecraftClient client, BlockPos pos) {
        this.current = pos.toImmutable();
        this.waited = 0;
        this.openedAtMs = System.currentTimeMillis();
        // ⚠️ 故意不清 shadow / syncId：上一格迟到的背包同步包还要灌进玩家背包；
        //    新的 OpenScreen 到达时 handleOpenScreen 会整体替换。
        this.contentReady = false;
        this.secondPass = false;
        this.clearPass = 1;
        this.fillMissing = 0;
        this.fillNoData = false;
        this.fillNoItem = false;
        this.fillTargets = Map.of();
        this.clickPlan.clear();
        this.phase = Phase.WAIT_OPEN;

        BlockHitResult hit = InteractionTargeting.createBlockHitResult(client, this.current);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
    }

    /** 当前格处理完：记完成、刷缓存，然后直接开下一格（最后一格才真正关箱）。 */
    private void finishCurrent(MinecraftClient client) {
        this.done++;
        this.consecutiveSkips = 0;
        cacheSnapshot(client);
        LOGGER.info("[batch] 完成 {}/{} pos={} 耗时 {}ms",
                this.done, this.total, this.current, System.currentTimeMillis() - this.openedAtMs);
        if (this.total > 1) {
            message(client, "litematica_container_filler.message.batch_tool_progress", this.done, this.total);
        }
        this.current = null;

        BlockPos next = this.queue.poll();
        if (next == null) {
            finish(client);
        } else {
            openContainer(client, next);
        }
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
        // 这一格没开成/没处理成：如果服务端还开着它（或上一格），先关掉再开下一格。
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

        BlockPos next = this.queue.poll();
        if (next == null) {
            finish(client);
        } else {
            openContainer(client, next);
        }
    }

    private void finish(MinecraftClient client) {
        int doneCount = this.done;
        int skippedCount = this.skipped;
        int missingTotal = this.fastMissing;
        sendClose(client);
        resetState();
        LOGGER.info("[batch] 结束：完成 {} / 跳过 {} / 共 {}{}",
                doneCount, skippedCount, doneCount + skippedCount,
                missingTotal > 0 ? " / 缺料 " + missingTotal : "");
        message(client, "litematica_container_filler.message.batch_tool_done", doneCount, skippedCount);
        if (missingTotal > 0 && client != null && client.player != null) {
            client.player.sendMessage(Text.translatable(
                    "litematica_container_filler.message.batch_tool_fill_missing", missingTotal), true);
        }
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
        this.queue.clear();
        this.total = 0;
        this.done = 0;
        this.skipped = 0;
        this.consecutiveSkips = 0;
        this.contentReady = false;
        this.secondPass = false;
        this.clearPass = 1;
        this.fillMissing = 0;
        this.fillNoData = false;
        this.fillNoItem = false;
        this.fillTargets = Map.of();
        this.clickPlan.clear();
        this.useServerLink = false;
        this.pendingFast = 0;
        this.fastMissing = 0;
        this.pendingFastTargets.clear();
        this.lingerTicks = 20;
        // 服务端 mixin（远距离放行 / 超距不自动关箱）靠这个开关判断"是不是批量工具在跑"。
        BatchToolState.setActive(false);
        // shadow / syncId 故意保留：关箱包可能晚到一两 tick，还要靠它们吃掉
        // CloseScreen 并同步玩家背包；lingerTicks 到点或下一次 openContainer 会整体替换。
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
    // 服务端直连快速通道（单人 / 装了本模组的服务器）
    // ------------------------------------------------------------------

    /** 发一个容器的直连请求；投影没数据的按投影填充在这里就地跳过。 */
    private void sendFast(MinecraftClient client, BlockPos pos) {
        if (pos == null) {
            return;
        }
        int action;
        List<BatchServerLink.Target> targets = List.of();
        if (this.mode == BatchToolMode.CLEAR) {
            action = BatchServerLink.ACTION_CLEAR;
        } else if (this.mode == BatchToolMode.FILL_FULL) {
            action = BatchServerLink.ACTION_FILL_FULL;
        } else {
            Map<Integer, ItemStack> required = readRequired(client, pos);
            if (required == null) {
                this.skipped++;
                LOGGER.info("[batch] 跳过 pos={} 原因=投影里没有这格的数据", pos);
                return;
            }
            action = BatchServerLink.ACTION_SCHEMATIC_FILL;
            List<BatchServerLink.Target> list = new ArrayList<>(required.size());
            for (Map.Entry<Integer, ItemStack> entry : required.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                list.add(new BatchServerLink.Target(entry.getKey().byteValue(), entry.getValue()));
            }
            targets = list;
            this.pendingFastTargets.put(pos.asLong(), required);
        }

        this.pendingFast++;
        BatchServerLink.sendAction(pos, action, targets);
    }

    /** 服务端回执（主线程）。 */
    public void onServerResult(long posLong, int status, int missing) {
        if (this.phase != Phase.FAST_SEND && this.phase != Phase.FAST_WAIT) {
            return;
        }
        this.pendingFast = Math.max(0, this.pendingFast - 1);
        BlockPos pos = BlockPos.fromLong(posLong);
        Map<Integer, ItemStack> targets = this.pendingFastTargets.remove(posLong);

        switch (status) {
            case BatchServerLink.RESULT_DONE -> {
                this.done++;
                this.consecutiveSkips = 0;
                if (targets != null) {
                    RealContainerCache.put(pos, targets);
                } else if (this.mode == BatchToolMode.CLEAR) {
                    RealContainerCache.put(pos, Map.of());
                }
                if (missing > 0) {
                    this.fastMissing += missing;
                }
            }
            case BatchServerLink.RESULT_NO_CONTAINER -> this.skipped++;
            case BatchServerLink.RESULT_NO_ITEM -> this.skipped++;
            case BatchServerLink.RESULT_MISSING -> {
                this.done++;
                this.fastMissing += missing;
            }
            default -> this.skipped++;
        }

        if (this.phase == Phase.FAST_WAIT && this.pendingFast <= 0) {
            finish(MinecraftClient.getInstance());
        }
    }

    // ------------------------------------------------------------------
    // 具体动作：先生成点击计划，再按每 tick 预算发出去
    // ------------------------------------------------------------------

    private void performAction(MinecraftClient client) {
        this.clickPlan.clear();
        if (this.mode == BatchToolMode.SCHEMATIC_FILL) {
            buildFillPlan(client);
        } else if (this.mode == BatchToolMode.FILL_FULL) {
            buildFillFullPlan(client);
        } else {
            buildClearPlan(client);
        }

        if (this.fillNoData) {
            // 投影里没数据 / 背包里没有明确的可填物品：跳过（不算失败，不触发连续失败中止）。
            this.fillNoData = false;
            boolean noItem = this.fillNoItem;
            this.fillNoItem = false;
            skip(client, noItem
                    ? "litematica_container_filler.message.batch_tool_no_fill_item"
                    : "litematica_container_filler.message.batch_tool_no_schematic", false);
            return;
        }
        if (this.clickPlan.isEmpty()) {
            if (this.secondPass) {
                this.waited = 0;
                this.phase = Phase.SETTLE;
            } else {
                finishCurrent(client);
            }
            return;
        }
        this.phase = Phase.EXECUTE;
    }

    /**
     * 读某格的投影模板；{@code null} = 投影里没有这个容器的数据（跳过）。
     * 空 map = 投影里这个容器本来就是空的（模板就是"清空"）。
     */
    private Map<Integer, ItemStack> readRequired(MinecraftClient client, BlockPos pos) {
        Map<Integer, ItemStack> required;
        try {
            required = LitematicaContainerReader.getRequiredItems(pos, client.world.getRegistryManager());
        } catch (Throwable t) {
            LOGGER.warn("[batch] 读投影容器数据失败 pos={}", pos, t);
            required = Map.of();
        }
        if (required.isEmpty()) {
            // 空 map 有两种含义：①投影里没有这个容器 ②投影里这个容器本来就是空的。
            // 用"模板索引里有没有这个位置"区分：索引是按投影方块实体表建的，与渲染层范围无关。
            boolean inIndex;
            try {
                inIndex = LitematicaPlacementContainerData.getNbt(pos).isPresent();
            } catch (Throwable t) {
                inIndex = false;
            }
            if (!inIndex) {
                return null;
            }
        }
        return required;
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
     * 「按投影填充」（1.12.x 曾叫「填满容器」，2026-11-07 Ray 定名）：按**这一格自己的投影模板**
     * （{@code LitematicaContainerReader.getRequiredItems}）把容器对齐到模板——多出来的退回背包、
     * 少的从玩家背包搬进来。
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
        Map<Integer, ItemStack> required = readRequired(client, this.current);
        if (required == null) {
            this.fillNoData = true;
            return;
        }
        this.fillTargets = required;

        FillPlanState state = new FillPlanState(client);
        int missing = 0;
        int planned = 0;

        for (Slot slot : containerSlots(client)) {
            ItemStack target = required.getOrDefault(slot.getIndex(), ItemStack.EMPTY);
            planned += state.fillSlot(client, slot, target, false);
            if (state.lastMissing > 0) {
                missing += state.lastMissing;
            }
        }

        this.fillMissing = missing;
        this.fastMissing += missing;
        LOGGER.info("[batch] 按投影填充 pos={} 模板槽 {} 个，计划点击 {} 次，缺料 {}",
                this.current, required.size(), state.planned, missing);
        if (missing > 0) {
            message(client, "litematica_container_filler.message.batch_tool_fill_missing", missing);
        }
    }

    /**
     * 「填满容器」（2026-11-07 Ray 定语义）：**不看投影模板**，用背包里的物品把容器填满。
     * 选物规则与容器工具「填满容器」一致：取背包里占用槽位数最多、且容器肯收的那一种；
     * 并列第一 / 达不到阈值（{@code TOOL_FILL_FULL_THRESHOLD}）⇒ 不动。
     */
    private void buildFillFullPlan(MinecraftClient client) {
        if (this.shadow == null || this.current == null) {
            return;
        }
        ItemStack best = pickBestFillItem(client);
        if (best == null) {
            this.fillNoData = true; // 复用"跳过"通道：没有明确的可填物品
            this.fillNoItem = true;
            return;
        }

        FillPlanState state = new FillPlanState(client);
        int planned = 0;
        int max = best.getMaxCount();

        for (Slot slot : containerSlots(client)) {
            ItemStack current = state.model.getOrDefault(slot.id, ItemStack.EMPTY);
            if (!current.isEmpty() && !ItemMatcher.isSameItem(current, best)) {
                continue; // 填满不清别人的东西
            }
            if (!canInsertInto(client, slot, best)) {
                continue;
            }
            ItemStack target = best.copy();
            target.setCount(max);
            planned += state.fillSlot(client, slot, target, true);
        }

        // 预测结果：能填的槽都填满（收进缓存供高亮判断）
        Map<Integer, ItemStack> predicted = new LinkedHashMap<>();
        for (Slot slot : containerSlots(client)) {
            ItemStack stack = state.model.getOrDefault(slot.id, ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                predicted.put(slot.getIndex(), stack.copy());
            }
        }
        this.fillTargets = predicted;

        LOGGER.info("[batch] 填满容器 pos={} 物品={} 计划点击 {} 次", this.current, best.getItem(), state.planned);
    }

    /**
     * 容器工具「填满容器」的选物规则：背包里占用槽位数**严格最多**、且数量达到阈值、
     * 容器至少有一个槽肯收的那种物品；并列第一/不达标 ⇒ {@code null}。
     */
    private ItemStack pickBestFillItem(MinecraftClient client) {
        int threshold = Math.max(1, Configs.TOOL_FILL_FULL_THRESHOLD.getIntegerValue());
        Map<ItemKey, Integer> counts = new LinkedHashMap<>();
        Map<ItemKey, ItemStack> protos = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemKey key = new ItemKey(stack);
            counts.merge(key, 1, Integer::sum);
            protos.putIfAbsent(key, stack.copy());
        }

        ItemKey best = null;
        int bestCount = threshold - 1;
        boolean tie = false;
        for (Map.Entry<ItemKey, Integer> entry : counts.entrySet()) {
            boolean usable = false;
            for (Slot slot : containerSlots(client)) {
                if (canInsertInto(client, slot, entry.getKey().stack())) {
                    usable = true;
                    break;
                }
            }
            if (!usable) {
                continue;
            }
            int count = entry.getValue();
            if (count > bestCount) {
                best = entry.getKey();
                bestCount = count;
                tie = false;
            } else if (count == bestCount && best != null) {
                tie = true;
            }
        }
        if (best == null || tie) {
            return null;
        }
        ItemStack result = protos.get(best).copy();
        result.setCount(1);
        return result;
    }

    /**
     * 按槽逐个推演的填充计划状态：维护"虚拟背包模型 + 手里的一把（cursor）"，
     * 发出的点击顺序就是服务端执行的顺序。
     */
    private final class FillPlanState {
        final Map<Integer, ItemStack> model = new HashMap<>();
        final List<Integer> playerSlots = new ArrayList<>();
        ItemStack cursor = ItemStack.EMPTY;
        int lastSource = -1;
        int planned;
        int lastMissing;

        FillPlanState(MinecraftClient client) {
            for (Slot slot : BatchToolController.this.shadow.slots) {
                this.model.put(slot.id, slot.getStack().copy());
                if (slot.inventory == client.player.getInventory()) {
                    this.playerSlots.add(slot.id);
                }
            }
        }

        /**
         * 把一个容器槽对齐到 target。
         *
         * @param keepForeign true = 槽里是别的物品就跳过（填满语义）；false = 先清掉（按模板语义）
         * @return 本槽发出的点击数
         */
        int fillSlot(MinecraftClient client, Slot slot, ItemStack target, boolean keepForeign) {
            this.lastMissing = 0;
            int emitted = 0;
            ItemStack current = this.model.getOrDefault(slot.id, ItemStack.EMPTY);

            if (target.isEmpty()) {
                if (!current.isEmpty() && !keepForeign) {
                    BatchToolController.this.clickPlan.add(new ClickOp(slot.id, 1, SlotActionType.THROW));
                    this.model.put(slot.id, ItemStack.EMPTY);
                    emitted++;
                }
                return emitted;
            }
            if (ItemMatcher.isSameItem(current, target) && current.getCount() == target.getCount()) {
                return emitted;
            }
            if (!current.isEmpty()) {
                if (keepForeign) {
                    return emitted;
                }
                BatchToolController.this.clickPlan.add(new ClickOp(slot.id, 1, SlotActionType.THROW));
                this.model.put(slot.id, ItemStack.EMPTY);
                emitted++;
            }
            if (!canInsertInto(client, slot, target)) {
                // 目标槽不收这种物品（熔炉燃料槽 / 输出槽等）⇒ 记缺料，别硬塞。
                this.lastMissing = target.getCount();
                return emitted;
            }

            int placed = 0;
            while (placed < target.getCount()) {
                if (this.cursor.isEmpty()) {
                    int src = findPlayerSlotWithItem(this.model, this.playerSlots, target);
                    if (src < 0) {
                        this.lastMissing += target.getCount() - placed;
                        break;
                    }
                    this.cursor = this.model.get(src).copy();
                    this.model.put(src, ItemStack.EMPTY);
                    this.lastSource = src;
                    BatchToolController.this.clickPlan.add(new ClickOp(src, 0, SlotActionType.PICKUP));
                    emitted++;
                }
                int need = target.getCount() - placed;
                if (this.cursor.getCount() <= need) {
                    BatchToolController.this.clickPlan.add(new ClickOp(slot.id, 0, SlotActionType.PICKUP));
                    placed += this.cursor.getCount();
                    this.cursor = ItemStack.EMPTY;
                } else {
                    BatchToolController.this.clickPlan.add(new ClickOp(slot.id, 1, SlotActionType.PICKUP));
                    placed++;
                    this.cursor.decrement(1);
                    if (this.cursor.isEmpty()) {
                        this.cursor = ItemStack.EMPTY;
                    }
                }
                emitted++;
            }
            if (!this.cursor.isEmpty()) {
                int back = this.lastSource >= 0 ? this.lastSource : findEmptyPlayerSlot(this.model, this.playerSlots);
                if (back >= 0) {
                    BatchToolController.this.clickPlan.add(new ClickOp(back, 0, SlotActionType.PICKUP));
                    this.model.put(back, this.cursor.copy());
                    emitted++;
                }
                this.cursor = ItemStack.EMPTY;
            }
            if (placed > 0) {
                ItemStack placedStack = target.copy();
                placedStack.setCount(placed);
                this.model.put(slot.id, placedStack);
            }
            this.planned += emitted;
            return emitted;
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
        if (this.mode == BatchToolMode.SCHEMATIC_FILL || this.mode == BatchToolMode.FILL_FULL) {
            // 填充：计划执行完就该等于模板/填满（缺料时差一点，下次扫描会修正）。
            RealContainerCache.put(this.current, new LinkedHashMap<>(this.fillTargets));
            return;
        }
        // 清空：点击已按序发出，容器应为空（「放入背包」在背包满时可能剩一点，下次扫描会修正）。
        RealContainerCache.put(this.current, Map.of());
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
