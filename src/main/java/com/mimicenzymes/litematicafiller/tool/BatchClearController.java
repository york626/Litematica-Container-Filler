package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.InteractionTargeting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/**
 * ★ 1.11.0-Ray：**批量清空容器（独立实现）**（Ray 2026-11-06 拍板）。
 *
 * <p>为什么独立：容器工具（{@code ContainerToolStateMachine}）是为"看准星、单个容器"设计的，
 * 中间夹着界面拦截/限流/冷却/自动填充互斥。批量硬套它，每修一层就撞上另一层耦合
 * （距离 → 界面拦截 → 菜单绑定 → 关箱竞态 → 菜单残留），今天已经烧掉十几个版本。
 * ⇒ 这里走一条**完全独立**的最小闭环，**不碰容器工具的任何状态**：</p>
 *
 * <pre>
 * 选区 → 容器队列（就近优先）
 *   ↓ 每格：
 *   ① 发右键包开箱（远距离由服务端 mixin 放行）
 *   ② 等"新菜单"出现（syncId 变了，防把上一个容器当新的）
 *   ③ 直接发槽位点击包（丢地上 / 收进背包，按「清空输出方式」配置）
 *   ④ 关箱，等菜单回到玩家背包 → 下一格
 * </pre>
 *
 * <p><b>界面不弹</b>：`OpenScreenS2CPacket` 由 {@code BatchClearScreenMixin} 接管 ——
 * 只把菜单挂到 `player.currentScreenHandler`、不创建 Screen ⇒ 不闪屏、不抢鼠标。</p>
 */
public final class BatchClearController {
    private static final BatchClearController INSTANCE = new BatchClearController();
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int CLOSE_TIMEOUT_TICKS = 10;

    private enum Phase { IDLE, WAIT_OPEN, CLEARING, WAIT_CLOSE }

    private final ContainerBatchQueue queue = new ContainerBatchQueue();
    private Phase phase = Phase.IDLE;
    private BlockPos current;
    private int waited;
    private int previousSyncId = -1;
    private int dropped;

    private BatchClearController() {
    }

    public static BatchClearController getInstance() {
        return INSTANCE;
    }

    /** 供 mixin 判断"现在这条 OpenScreen 是不是我自己要的"。 */
    public boolean isRunning() {
        return this.phase != Phase.IDLE;
    }

    /** 快捷键：再按一次 = 取消。 */
    public void toggle(MinecraftClient client) {
        if (this.isRunning()) {
            stop(client, "§e[批量清空] 已取消");
            return;
        }
        if (client.player == null || client.world == null) {
            return;
        }
        // ★ 1.11.1-Ray（Ray 2026-11-06）：批量**沿用容器工具选中的模式**（不再单独设模式页）；
        //   批量快捷键只负责"对选区跑这个模式"。目前批量只实现了清空，其余模式先明确提示。
        Object selectedMode = Configs.CONTAINER_TOOL_MODE.getOptionListValue();
        if (!(selectedMode instanceof com.mimicenzymes.litematicafiller.tool.ContainerToolMode toolMode)
                || toolMode != com.mimicenzymes.litematicafiller.tool.ContainerToolMode.CLEAR) {
            message(client, "§e[批量] 现在只支持「清空容器」模式（在工具页把模式切成清空）。");
            return;
        }
        int built = this.queue.build(client);
        if (built <= 0) {
            message(client, "§e[批量清空] 投影选区里没有容器（先框选）。");
            return;
        }
        this.dropped = 0;
        this.phase = Phase.IDLE;
        next(client);
        message(client, "§b[批量清空] 开始：共 " + built + " 个容器");
    }

    private void stop(MinecraftClient client, String text) {
        this.phase = Phase.IDLE;
        this.current = null;
        this.queue.clear();
        if (client.player != null
                && !(client.player.currentScreenHandler instanceof PlayerScreenHandler)) {
            client.player.closeHandledScreen();
        }
        message(client, text);
    }

    private void next(MinecraftClient client) {
        BlockPos pos = this.queue.poll();
        if (pos == null) {
            int total = this.queue.total();
            this.phase = Phase.IDLE;
            this.current = null;
            message(client, "§a[批量清空] 完成：共 " + total + " 个容器");
            return;
        }
        this.current = pos;
        this.waited = 0;
        this.previousSyncId = client.player == null ? -1 : client.player.currentScreenHandler.syncId;
        this.phase = Phase.WAIT_OPEN;
        // ① 发右键包（远距离由服务端 ServerUseItemRangeMixin 放行）
        BlockHitResult hit = InteractionTargeting.createBlockHitResult(client, pos);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
    }

    public void tick(MinecraftClient client) {
        if (this.phase == Phase.IDLE || client.player == null || client.world == null) {
            return;
        }
        ClientPlayerEntity player = client.player;
        ScreenHandler handler = player.currentScreenHandler;

        switch (this.phase) {
            case WAIT_OPEN -> {
                // ② 等到"和上一格不同"的菜单出现（防把旧容器当新容器）
                if (!(handler instanceof PlayerScreenHandler) && handler.syncId != this.previousSyncId) {
                    this.phase = Phase.CLEARING;
                    return;
                }
                if (++this.waited > OPEN_TIMEOUT_TICKS) {
                    message(client, "§c[批量清空] 打不开 " + this.current + "，跳过");
                    org.slf4j.LoggerFactory.getLogger("litematica_container_filler").info(
                            "[batch] 打不开 {} handler={} 上次syncId={} 已接管界面={}",
                            this.current,
                            handler.getClass().getSimpleName(),
                            this.previousSyncId,
                            com.mimicenzymes.litematicafiller.mixin.BatchClearScreenMixin.attachedCount());
                    skipToNext(client);
                }
            }
            case CLEARING -> {
                clearContainer(client, handler);
                this.phase = Phase.WAIT_CLOSE;
                this.waited = 0;
            }
            case WAIT_CLOSE -> {
                if (handler instanceof PlayerScreenHandler) {
                    next(client);
                    return;
                }
                if (this.waited == 0) {
                    player.closeHandledScreen();
                }
                if (++this.waited > CLOSE_TIMEOUT_TICKS) {
                    next(client);
                }
            }
            default -> {
            }
        }
    }

    private void skipToNext(MinecraftClient client) {
        if (!(client.player.currentScreenHandler instanceof PlayerScreenHandler)) {
            client.player.closeHandledScreen();
        }
        next(client);
    }

    private void clearContainer(MinecraftClient client, ScreenHandler handler) {
        boolean toInventory = Configs.CONTAINER_CLEAR_OUTPUT_MODE.getOptionListValue()
                instanceof com.mimicenzymes.litematicafiller.tool.ContainerClearOutputMode mode
                && mode == com.mimicenzymes.litematicafiller.tool.ContainerClearOutputMode.INVENTORY;
        for (Slot slot : handler.slots) {
            if (slot.inventory == client.player.getInventory()) {
                continue;   // 只看容器自己的槽位
            }
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (toInventory) {
                client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, client.player);
            } else {
                client.interactionManager.clickSlot(handler.syncId, slot.id, 1, SlotActionType.THROW, client.player);
            }
            this.dropped++;
        }
    }

    private void message(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), true);
        }
    }
}
