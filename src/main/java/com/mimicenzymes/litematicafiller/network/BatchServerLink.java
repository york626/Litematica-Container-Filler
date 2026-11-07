package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.tool.BatchToolState;
import com.mimicenzymes.litematicafiller.tool.ContainerClearOutputMode;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「批量工具」的**服务端直连快速通道**（1.13.0，Ray 2026-11-07 拍板）。
 *
 * <p>点击路径（开箱 + ClickSlot）每格要一次"右键 → 开箱 → 收内容 → 发点击"的往返（~100ms/格）；
 * 二改的「容器同步」之所以快，是因为它**服务端直接读写容器**。这条通道把同样的事搬进来：</p>
 *
 * <pre>
 * 客户端 ── batch_action(位置, 动作, 目标内容) ──▶ 服务端：直接改容器方块实体的 Inventory
 * 客户端 ◀── batch_result(位置, 结果, 缺料数) ──── 服务端：markDirty + 回执
 * </pre>
 *
 * <p>没有开箱、没有点击、没有距离闸（除保留一个可选的距离校验），一 tick 能处理一批。
 * <b>只在"单人 / 装了本模组的服务器"生效</b>：用 {@code ClientPlayNetworking.canSend} 探测——
 * 服务端没注册这个通道（纯客户端连别人的服）就探测不到 ⇒ 批量工具自动回退到点击路径。</p>
 *
 * <p>⚠️ 注册发生在客户端初始化时（含 {@code ServerPlayNetworking.registerGlobalReceiver}）：
 * 单人模式下集成服务端与客户端同 JVM，静态注册两边都生效；独立服务器不会加载本模组（client-only），
 * 所以那条路自然走不通，也自然回退。</p>
 */
public final class BatchServerLink {
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    public static final int ACTION_CLEAR = 0;
    public static final int ACTION_FILL_FULL = 1;
    public static final int ACTION_SCHEMATIC_FILL = 2;

    public static final int RESULT_DONE = 1;
    public static final int RESULT_NO_CONTAINER = 2;
    public static final int RESULT_MISSING = 3;
    public static final int RESULT_NO_ITEM = 4;
    public static final int RESULT_FAILED = 5;

    private static boolean registered;

    private BatchServerLink() {
    }

    /** 目标槽：槽位号 + 期望内容（按投影填充用；清空/填满不带）。 */
    public record Target(byte slot, ItemStack stack) {
    }

    /** C2S：对一个容器执行批量动作。 */
    public record Action(long pos, int action, List<Target> targets) implements CustomPayload {
        public static final Id<Action> ID = new Id<>(Identifier.of("litematica_container_filler", "batch_action"));

        public static final PacketCodec<RegistryByteBuf, Action> CODEC =
                PacketCodec.ofStatic(Action::write, Action::read);

        private static void write(RegistryByteBuf buf, Action value) {
            buf.writeLong(value.pos());
            buf.writeVarInt(value.action());
            buf.writeVarInt(value.targets().size());
            for (Target target : value.targets()) {
                buf.writeByte(target.slot());
                ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, target.stack());
            }
        }

        private static Action read(RegistryByteBuf buf) {
            long pos = buf.readLong();
            int action = buf.readVarInt();
            int count = buf.readVarInt();
            List<Target> targets = new ArrayList<>(Math.min(count, 256));
            for (int i = 0; i < count; i++) {
                byte slot = buf.readByte();
                ItemStack stack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
                targets.add(new Target(slot, stack));
            }
            return new Action(pos, action, targets);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** S2C：单格回执。 */
    public record Result(long pos, int status, int missing) implements CustomPayload {
        public static final Id<Result> ID = new Id<>(Identifier.of("litematica_container_filler", "batch_result"));

        public static final PacketCodec<RegistryByteBuf, Result> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_LONG, Result::pos,
                PacketCodecs.VAR_INT, Result::status,
                PacketCodecs.VAR_INT, Result::missing,
                Result::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** 注册（幂等）。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(Action.ID, Action.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.ID, Result.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Action.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null || player.getServer() == null) {
                return;
            }
            player.getServer().execute(() -> handle(payload, player));
        });
        ClientPlayNetworking.registerGlobalReceiver(Result.ID, (payload, context) ->
                context.client().execute(() ->
                        com.mimicenzymes.litematicafiller.tool.BatchToolController.getInstance()
                                .onServerResult(payload.pos(), payload.status(), payload.missing())));
    }

    /** 服务端有没有这条通道（单人 / 装了本模组的服务器 ⇒ true）。 */
    public static boolean isSupported() {
        if (!registered) {
            return false;
        }
        try {
            return ClientPlayNetworking.canSend(Action.ID);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void sendAction(BlockPos pos, int action, List<Target> targets) {
        try {
            ClientPlayNetworking.send(new Action(pos.asLong(), action, targets));
        } catch (Throwable t) {
            LOGGER.warn("[batch] 直连请求发送失败 pos={}", pos, t);
        }
    }

    // ------------------------------------------------------------------
    // 服务端执行（服务器线程）
    // ------------------------------------------------------------------

    private static void handle(Action payload, ServerPlayerEntity player) {
        long posLong = payload.pos();
        try {
            if (!BatchToolState.isActive()
                    || !Configs.ENABLE_MOD.getBooleanValue()
                    || !Configs.BATCH_TOOL_ENABLED.getBooleanValue()) {
                return; // 批量已经结束/被取消：丢弃迟到请求
            }
            BlockPos pos = BlockPos.fromLong(posLong);
            BlockEntity blockEntity = player.getServerWorld().getBlockEntity(pos);
            if (!(blockEntity instanceof Inventory inv)) {
                sendResult(player, posLong, RESULT_NO_CONTAINER, 0);
                return;
            }
            if (!Configs.BATCH_TOOL_FAR_REACH.getBooleanValue()
                    && !player.canInteractWithBlockAt(pos, 1.0)) {
                // 远距离放行没开时，仍然按原版距离规则来
                sendResult(player, posLong, RESULT_FAILED, 0);
                return;
            }

            switch (payload.action()) {
                case ACTION_CLEAR -> handleClear(posLong, player, inv);
                case ACTION_FILL_FULL -> handleFillFull(posLong, player, inv);
                case ACTION_SCHEMATIC_FILL -> handleSchematicFill(posLong, player, inv, payload.targets());
                default -> sendResult(player, posLong, RESULT_FAILED, 0);
            }
        } catch (Throwable t) {
            LOGGER.warn("[batch] 直连执行失败 pos={}", BlockPos.fromLong(posLong), t);
            sendResult(player, posLong, RESULT_FAILED, 0);
        }
    }

    private static void handleClear(long posLong, ServerPlayerEntity player, Inventory inv) {
        boolean toPlayer = !(Configs.CONTAINER_CLEAR_OUTPUT_MODE.getOptionListValue()
                instanceof ContainerClearOutputMode mode && mode == ContainerClearOutputMode.DROP);
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            inv.setStack(i, ItemStack.EMPTY);
            if (toPlayer) {
                player.getInventory().offerOrDrop(stack);
            } else {
                player.dropItem(stack, false);
            }
        }
        inv.markDirty();
        sendResult(player, posLong, RESULT_DONE, 0);
    }

    private static void handleFillFull(long posLong, ServerPlayerEntity player, Inventory inv) {
        ItemStack best = pickBestFillItem(player, inv);
        if (best == null) {
            sendResult(player, posLong, RESULT_NO_ITEM, 0);
            return;
        }
        int max = Math.min(best.getMaxCount(), Math.max(1, inv.getMaxCountPerStack()));
        int missing = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.isValid(i, best)) {
                continue;
            }
            ItemStack current = inv.getStack(i);
            int space;
            if (current.isEmpty()) {
                space = max;
            } else if (ItemMatcher.isSameItem(current, best)) {
                space = max - current.getCount();
            } else {
                continue; // 填满不清别人的东西
            }
            if (space <= 0) {
                continue;
            }
            int taken = takeFromPlayer(player, best, space);
            if (taken <= 0) {
                missing += space;
                continue;
            }
            if (current.isEmpty()) {
                ItemStack put = best.copy();
                put.setCount(taken);
                inv.setStack(i, put);
            } else {
                current.increment(taken);
            }
            if (taken < space) {
                missing += space - taken;
            }
        }
        inv.markDirty();
        sendResult(player, posLong, RESULT_DONE, missing);
    }

    private static void handleSchematicFill(long posLong, ServerPlayerEntity player, Inventory inv, List<Target> targets) {
        Set<Integer> targetSlots = new HashSet<>();
        int missing = 0;

        for (Target target : targets) {
            int slot = target.slot() & 0xFF;
            targetSlots.add(slot);
            if (slot < 0 || slot >= inv.size() || target.stack().isEmpty()) {
                continue;
            }
            if (inv instanceof CrafterBlockEntity crafter && crafter.isSlotDisabled(slot)) {
                continue; // 锁槽不动
            }
            ItemStack wanted = target.stack();
            ItemStack current = inv.getStack(slot);
            if (ItemMatcher.isSameItem(current, wanted) && current.getCount() == wanted.getCount()) {
                continue;
            }
            if (!inv.isValid(slot, wanted)) {
                missing += wanted.getCount();
                continue;
            }
            if (!current.isEmpty()) {
                inv.setStack(slot, ItemStack.EMPTY);
                player.getInventory().offerOrDrop(current);
            }
            int taken = takeFromPlayer(player, wanted, wanted.getCount());
            if (taken <= 0) {
                missing += wanted.getCount();
                continue;
            }
            ItemStack put = wanted.copy();
            put.setCount(taken);
            inv.setStack(slot, put);
            if (taken < wanted.getCount()) {
                missing += wanted.getCount() - taken;
            }
        }

        // 模板里没有的槽位：多余物品退回玩家（"按模板"语义）
        for (int i = 0; i < inv.size(); i++) {
            if (targetSlots.contains(i)) {
                continue;
            }
            if (inv instanceof CrafterBlockEntity crafter && crafter.isSlotDisabled(i)) {
                continue;
            }
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            inv.setStack(i, ItemStack.EMPTY);
            player.getInventory().offerOrDrop(stack);
        }

        inv.markDirty();
        sendResult(player, posLong, RESULT_DONE, missing);
    }

    /** 容器工具「填满容器」的选物规则：背包里占用槽位数严格最多、达到阈值、容器肯收的那种。 */
    private static ItemStack pickBestFillItem(ServerPlayerEntity player, Inventory inv) {
        int threshold = Math.max(1, Configs.TOOL_FILL_FULL_THRESHOLD.getIntegerValue());
        List<ItemStack> protos = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            int idx = -1;
            for (int j = 0; j < protos.size(); j++) {
                if (ItemMatcher.isSameItem(protos.get(j), stack)) {
                    idx = j;
                    break;
                }
            }
            if (idx < 0) {
                ItemStack proto = stack.copy();
                proto.setCount(1);
                protos.add(proto);
                counts.add(1);
            } else {
                counts.set(idx, counts.get(idx) + 1);
            }
        }

        int bestIdx = -1;
        int bestCount = threshold - 1;
        boolean tie = false;
        for (int i = 0; i < protos.size(); i++) {
            boolean usable = false;
            for (int s = 0; s < inv.size() && !usable; s++) {
                usable = inv.isValid(s, protos.get(i));
            }
            if (!usable) {
                continue;
            }
            int count = counts.get(i);
            if (count > bestCount) {
                bestIdx = i;
                bestCount = count;
                tie = false;
            } else if (count == bestCount && bestIdx >= 0) {
                tie = true;
            }
        }
        if (bestIdx < 0 || tie) {
            return null;
        }
        return protos.get(bestIdx);
    }

    private static int takeFromPlayer(ServerPlayerEntity player, ItemStack proto, int amount) {
        int remaining = amount;
        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty() || !ItemMatcher.isSameItem(stack, proto)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.decrement(take);
            if (stack.isEmpty()) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
            remaining -= take;
        }
        return amount - remaining;
    }

    private static void sendResult(ServerPlayerEntity player, long pos, int status, int missing) {
        try {
            ServerPlayNetworking.send(player, new Result(pos, status, missing));
        } catch (Throwable t) {
            LOGGER.warn("[batch] 直连回执发送失败 pos={}", pos, t);
        }
    }
}
