package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.tool.BatchToolState;
import com.mimicenzymes.litematicafiller.tool.ContainerClearOutputMode;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.mimicenzymes.litematicafiller.compat.sbp.SbpServerExtract;
import com.mimicenzymes.litematicafiller.compat.sbp.SbpSupport;
import com.mimicenzymes.litematicafiller.tool.BatchFillItemMode;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
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
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    /** 材料耗尽：这一格一点都没填上（客户端据此中止整批）。 */
    public static final int RESULT_NO_MATERIAL = 6;

    private static boolean registered;

    private BatchServerLink() {
    }

    /** 目标槽：槽位号 + 期望内容（按投影填充用；清空/填满不带）。 */
    public record Target(byte slot, ItemStack stack) {
    }

    /**
     * C2S：对一个容器执行批量动作。
     *
     * <p>{@code targets}＝按投影填充的槽位目标；{@code items}＝填充容器的候选物品
     * （**批量开始时**在客户端确定好——手持模式锁"那一刻主手里的东西"，避免填到一半主手空了整批停摆）。</p>
     */
    public record Action(long pos, int action, List<Target> targets, List<ItemStack> items) implements CustomPayload {
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
            buf.writeVarInt(value.items().size());
            for (ItemStack item : value.items()) {
                ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, item);
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
            int itemCount = buf.readVarInt();
            List<ItemStack> items = new ArrayList<>(Math.min(itemCount, 64));
            for (int i = 0; i < itemCount; i++) {
                items.add(ItemStack.OPTIONAL_PACKET_CODEC.decode(buf));
            }
            return new Action(pos, action, targets, items);
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

    public static void sendAction(BlockPos pos, int action, List<Target> targets, List<ItemStack> items) {
        try {
            ClientPlayNetworking.send(new Action(pos.asLong(), action, targets, items));
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
                case ACTION_FILL_FULL -> handleFillFull(posLong, player, inv, payload.items());
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

    private static void handleFillFull(long posLong, ServerPlayerEntity player, Inventory inv, List<ItemStack> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            sendResult(player, posLong, RESULT_NO_ITEM, 0);
            return;
        }
        int missing = 0;
        int placedTotal = 0;
        // 缺料时从"背包里的潜影盒 / 精妙背包"补救（与按投影填充同一套），最多补 3 轮
        for (int round = 0; round < 3; round++) {
            missing = 0;
            placedTotal = 0;
            Map<String, Integer> needed = new LinkedHashMap<>();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack chosen = null;
                for (ItemStack candidate : candidates) {
                    if (!inv.isValid(i, candidate)) {
                        continue;
                    }
                    ItemStack current = inv.getStack(i);
                    if (current.isEmpty() || ItemMatcher.isSameItem(current, candidate)) {
                        chosen = candidate;
                        break;
                    }
                }
                if (chosen == null) {
                    continue; // 填充不清别人的东西（只填空槽和已是候选物品的槽）
                }
                int max = Math.min(chosen.getMaxCount(), Math.max(1, inv.getMaxCountPerStack()));
                ItemStack current = inv.getStack(i);
                int space = current.isEmpty() ? max : max - current.getCount();
                if (space <= 0) {
                    continue;
                }
                int taken = takeFromPlayer(player, chosen, space);
                if (taken > 0) {
                    placedTotal += taken;
                    if (current.isEmpty()) {
                        ItemStack put = chosen.copy();
                        put.setCount(taken);
                        inv.setStack(i, put);
                    } else {
                        current.increment(taken);
                    }
                }
                int still = space - taken;
                if (still > 0) {
                    missing += still;
                    needed.merge(RegistryKeyHelper.itemId(chosen), still, Integer::sum);
                }
            }
            if (needed.isEmpty() || !rescueMissing(player, needed)) {
                break;
            }
        }
        inv.markDirty();
        // 一点都没填上 ⇒ 材料耗尽：让客户端中止整批，别一格一格空跑
        sendResult(player, posLong, placedTotal == 0 && missing > 0 ? RESULT_NO_MATERIAL : RESULT_DONE, missing);
    }

    /** 「填充容器」的候选物品（服务端直接读同一份配置）：物品列表模式解析列表；手持模式用主手物品。 */
    private static List<ItemStack> resolveFillCandidates(ServerPlayerEntity player) {
        List<ItemStack> out = new ArrayList<>();
        if (Configs.BATCH_FILL_ITEM_MODE.getOptionListValue() == BatchFillItemMode.HANDHELD) {
            ItemStack held = player.getMainHandStack();
            if (!held.isEmpty()) {
                ItemStack proto = held.copy();
                proto.setCount(1);
                out.add(proto);
            }
            return out;
        }
        for (String raw : Configs.BATCH_FILL_ITEM_LIST.getStrings()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                Item item = Registries.ITEM.get(Identifier.of(raw.trim().toLowerCase()));
                if (item != Items.AIR) {
                    out.add(new ItemStack(item));
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void handleSchematicFill(long posLong, ServerPlayerEntity player, Inventory inv, List<Target> targets) {
        Set<Integer> targetSlots = new HashSet<>();
        Map<Integer, ItemStack> wantedBySlot = new LinkedHashMap<>();

        for (Target target : targets) {
            int slot = target.slot() & 0xFF;
            if (slot < 0 || slot >= inv.size() || target.stack().isEmpty()) {
                continue;
            }
            targetSlots.add(slot);
            if (inv instanceof CrafterBlockEntity crafter && crafter.isSlotDisabled(slot)) {
                continue; // 锁槽不动
            }
            wantedBySlot.put(slot, target.stack());
        }

        // 先把"模板外/不符"的旧内容退给玩家（按模板语义：容器最终 = 模板）
        for (int i = 0; i < inv.size(); i++) {
            if (inv instanceof CrafterBlockEntity crafter && crafter.isSlotDisabled(i)) {
                continue;
            }
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack wanted = wantedBySlot.get(i);
            boolean keep = wanted != null && ItemMatcher.isSameItem(stack, wanted);
            if (!keep) {
                inv.setStack(i, ItemStack.EMPTY);
                player.getInventory().offerOrDrop(stack);
            }
        }

        // 填充；缺料时按开关从"背包里的潜影盒 / 精妙背包"补救，再补一轮
        int missing = 0;
        int placedTotal = 0;
        Map<String, Integer> needed = new LinkedHashMap<>();
        for (int round = 0; round < 3; round++) {
            missing = 0;
            needed.clear();
            for (Map.Entry<Integer, ItemStack> entry : wantedBySlot.entrySet()) {
                int slot = entry.getKey();
                ItemStack wanted = entry.getValue();
                ItemStack current = inv.getStack(slot);
                int have = ItemMatcher.isSameItem(current, wanted) ? current.getCount() : 0;
                int need = wanted.getCount() - have;
                if (need <= 0) {
                    continue;
                }
                int taken = takeFromPlayer(player, wanted, need);
                if (taken > 0) {
                    placedTotal += taken;
                    if (have > 0) {
                        current.increment(taken);
                    } else {
                        ItemStack put = wanted.copy();
                        put.setCount(taken);
                        inv.setStack(slot, put);
                    }
                }
                int still = need - taken;
                if (still > 0) {
                    missing += still;
                    needed.merge(RegistryKeyHelper.itemId(wanted), still, Integer::sum);
                }
            }
            if (needed.isEmpty()) {
                break;
            }
            if (!rescueMissing(player, needed)) {
                break;
            }
        }

        inv.markDirty();
        sendResult(player, posLong, placedTotal == 0 && missing > 0 ? RESULT_NO_MATERIAL : RESULT_DONE, missing);
    }

    /**
     * 缺料补救（共用）：①玩家背包里的**潜影盒**（原版 container 组件，开关 ENABLE_QS_EXTRACTION）
     * ②精妙背包（SbpServerExtract，开关 ENABLE_SBP_EXTRACTION）。
     *
     * @return true = 有实际补救动作（值得再跑一轮填充）
     */
    private static boolean rescueMissing(ServerPlayerEntity player, Map<String, Integer> needed) {
        boolean rescued = false;
        if (Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) {
            rescued |= rescueFromInventoryShulkers(player, needed);
        }
        if (!needed.isEmpty() && Configs.ENABLE_SBP_EXTRACTION.getBooleanValue() && SbpSupport.available()) {
            for (Map.Entry<String, Integer> e : needed.entrySet()) {
                if (e.getValue() <= 0) {
                    continue;
                }
                try {
                    SbpServerExtract.extract(player, Identifier.of(e.getKey()), e.getValue());
                    rescued = true;
                } catch (Throwable ignored) {
                }
            }
        }
        return rescued;
    }

    /** 补救①：把玩家背包里**潜影盒**（原版 container 组件）里需要的物品倒出来。 */
    private static boolean rescueFromInventoryShulkers(ServerPlayerEntity player, Map<String, Integer> needed) {
        boolean any = false;
        for (int i = 0; i < 36; i++) {
            if (needed.values().stream().allMatch(v -> v <= 0)) {
                break;
            }
            ItemStack box = player.getInventory().getStack(i);
            ContainerComponent comp = box.get(DataComponentTypes.CONTAINER);
            if (comp == null) {
                continue;
            }
            List<ItemStack> inner = new ArrayList<>();
            comp.stream().forEach(inner::add);
            boolean changed = false;
            for (int j = 0; j < inner.size(); j++) {
                ItemStack in = inner.get(j);
                if (in.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, Integer> e : needed.entrySet()) {
                    if (e.getValue() <= 0) {
                        continue;
                    }
                    ItemStack want = new ItemStack(Registries.ITEM.get(Identifier.of(e.getKey())));
                    if (!ItemMatcher.isSameItem(in, want)) {
                        continue;
                    }
                    int take = Math.min(in.getCount(), e.getValue());
                    ItemStack part = in.split(take);
                    player.getInventory().offerOrDrop(part);
                    e.setValue(e.getValue() - take);
                    changed = true;
                    any = true;
                    break;
                }
            }
            if (changed) {
                box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(inner));
                player.getInventory().markDirty();
            }
        }
        return any;
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

    private static final class RegistryKeyHelper {
        static String itemId(ItemStack stack) {
            return Registries.ITEM.getId(stack.getItem()).toString();
        }
    }

    private static void sendResult(ServerPlayerEntity player, long pos, int status, int missing) {
        try {
            ServerPlayNetworking.send(player, new Result(pos, status, missing));
        } catch (Throwable t) {
            LOGGER.warn("[batch] 直连回执发送失败 pos={}", pos, t);
        }
    }
}
