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
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
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
    public static final int ACTION_COLLECT_MISSING = 3;

    public static final int RESULT_DONE = 1;
    public static final int RESULT_NO_CONTAINER = 2;
    public static final int RESULT_MISSING = 3;
    public static final int RESULT_NO_ITEM = 4;
    public static final int RESULT_FAILED = 5;
    /** 材料耗尽：这一格一点都没填上（客户端据此中止整批）。 */
    public static final int RESULT_NO_MATERIAL = 6;
    public static final int RESULT_BACKPACK_FULL = 7;

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
     * （**批量开始时**在客户端确定好——手持模式锁"那一刻主手里的东西"，避免填到一半主手空了整批停摆）；
     * {@code plan}＝自动收集的**二级计划**（一级成品 → 它的原料），服务端据此在「成品已经够」时跳过原料。</p>
     */
    public record Action(long pos, int action, List<Target> targets, List<ItemStack> items,
                         List<SecondaryPlanEntry> plan) implements CustomPayload {
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
            buf.writeVarInt(value.plan() == null ? 0 : value.plan().size());
            if (value.plan() != null) {
                for (SecondaryPlanEntry entry : value.plan()) {
                    buf.writeIdentifier(Registries.ITEM.getId(entry.primary()));
                    buf.writeVarInt(entry.need());
                    buf.writeVarInt(entry.ingredients().size());
                    for (ItemStack ingredient : entry.ingredients()) {
                        ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, ingredient);
                    }
                }
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
            int planCount = buf.readVarInt();
            List<SecondaryPlanEntry> plan = new ArrayList<>(Math.min(planCount, 256));
            for (int i = 0; i < planCount; i++) {
                Item primary = Registries.ITEM.get(buf.readIdentifier());
                int need = buf.readVarInt();
                int ingCount = buf.readVarInt();
                List<ItemStack> ingredients = new ArrayList<>(Math.min(ingCount, 32));
                for (int j = 0; j < ingCount; j++) {
                    ingredients.add(ItemStack.OPTIONAL_PACKET_CODEC.decode(buf));
                }
                plan.add(new SecondaryPlanEntry(primary, need, ingredients));
            }
            return new Action(pos, action, targets, items, plan);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** 【自动收集】二级计划条目：一级成品、本行缺量、本行原料（原始量）。 */
    public record SecondaryPlanEntry(Item primary, int need, List<ItemStack> ingredients) {
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
                context.client().execute(() -> {
                    // 【自动收集】的回包也走这条共享通道：pos 匹配最近一次收集请求才算它的
                    com.mimicenzymes.litematicafiller.tool.AutoCollectController.getInstance()
                            .onPossibleCollectResult(payload.pos(), payload.status(), payload.missing());
                    com.mimicenzymes.litematicafiller.tool.BatchToolController.getInstance()
                            .onServerResult(payload.pos(), payload.status(), payload.missing());
                }));
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

    /** 【自动收集投影材料】发送（客户端长按触发；缺料清单来自材料列表「全部」视图缓存）。 */
    public static void sendCollectMissing(BlockPos pos, List<ItemStack> missing,
                                          List<com.mimicenzymes.litematicafiller.materials.MaterialDemandCore.SecondaryPlan> plan) {
        List<SecondaryPlanEntry> entries = new ArrayList<>();
        if (plan != null) {
            for (com.mimicenzymes.litematicafiller.materials.MaterialDemandCore.SecondaryPlan p : plan) {
                entries.add(new SecondaryPlanEntry(p.primary(), p.need(), p.ingredients()));
            }
        }
        sendAction(pos, ACTION_COLLECT_MISSING, List.of(), missing, entries);
    }

    public static void sendAction(BlockPos pos, int action, List<Target> targets, List<ItemStack> items) {
        sendAction(pos, action, targets, items, List.of());
    }

    public static void sendAction(BlockPos pos, int action, List<Target> targets, List<ItemStack> items,
                                  List<SecondaryPlanEntry> plan) {
        try {
            ClientPlayNetworking.send(new Action(pos.asLong(), action, targets, items, plan));
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
            if (!Configs.ENABLE_MOD.getBooleanValue()) {
                return;
            }
            if (payload.action() == ACTION_COLLECT_MISSING) {
                // 【自动收集投影材料】独立闸（Ray 2026-11-14，参考 QC 的自动收集重写）：不依赖批量批激活
                if (!Configs.AUTO_COLLECT_ENABLED.getBooleanValue()) {
                    return;
                }
            } else if (!BatchToolState.isActive()
                    || !Configs.BATCH_TOOL_ENABLED.getBooleanValue()) {
                return; // 批量已经结束/被取消：丢弃迟到请求
            }
            BlockPos pos = BlockPos.fromLong(posLong);
            BlockEntity blockEntity = player.getServerWorld().getBlockEntity(pos);
            if (!(blockEntity instanceof Inventory inv)) {
                sendResult(player, posLong, RESULT_NO_CONTAINER, 0);
                return;
            }
            // **大型箱子合并**（Ray 2026-11-14：「不能识别双箱一起的大箱子吗」）：
            // 大箱 = 两个 ChestBlockEntity，直接拿 BE 只有半边（27 格）——
            // 另一半里的熔浆膏/海晶碎片/线就这样漏收了。用原版 ChestBlock.getInventory
            // 合成 54 格的 DoubleInventory；单箱/其他容器返回自身，行为不变。
            if (blockEntity instanceof net.minecraft.block.entity.ChestBlockEntity
                    && player.getServerWorld().getBlockState(pos).getBlock()
                            instanceof net.minecraft.block.ChestBlock chestBlock) {
                Inventory merged = net.minecraft.block.ChestBlock.getInventory(
                        chestBlock,
                        player.getServerWorld().getBlockState(pos),
                        player.getServerWorld(), pos, true);
                if (merged != null) {
                    inv = merged;
                }
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
                case ACTION_COLLECT_MISSING -> handleCollectToPlayer(posLong, player, inv, payload.items(), payload.plan());
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
     * 【自动收集投影材料】（Ray 2026-11-14 定稿方向：**从准星容器把清单缺料收进玩家背包**）。
     *
     * <h2>方向（v1.16.1 修正）</h2>
     * <p>他的工作流＝材料列表（全部视图，含投影容器材料）显示缺什么 → 到**仓库大箱**（准星容器）
     * 把缺料**收进身上** → 再用填充/批量工具装进投影容器。⇒ 数据流是 **容器 → 背包**，
     * 与 QuickCraft 原版（背包→容器）相反；v1.16.0 做反了（把背包的东西塞进容器、
     * 连已收集好的和潜影盒都被吐出去），v1.16.1 整个反向。</p>
     *
     * <h2>规则</h2>
     * <ul>
     *   <li><b>散装缺料</b>：容器里的物品是清单缺料 ⇒ 按**清单缺量**拿（缺 2 拿 2，不超拿），放回**容器**不丢；</li>
     *   <li><b>盒装缺料</b>（v1.16.3 改）：容器里装着缺料的潜影盒/收纳袋 ⇒ **原地开盒取量**——
     *       直接从盒内容拿清单缺的量放进背包，盒子（剩余内容）留在容器原位。
     *       QC 要「先搬背包→提取→放回」是因为它走客户端点击（容器里的盒子打不开）；
     *       CF 服务端直连可以直接改盒内容 ⇒ 不搬、不闪界面；</li>
     *   <li>**收集够清单量即止**（v1.16.6，Ray：「收集够了就不需要在收集了」）：
     *       每种缺料的量先**减去玩家背包里已有的同物品**——身上备好的部分算已收集，再按也不重复收；</li>
     *   <li><b>背包满保护</b>：放不下就立即停 + 回报 {@link #RESULT_BACKPACK_FULL}，
     *       已收的保留、没收的留在容器（绝不 offerOrDrop 丢东西）。</li>
     * </ul>
     */
    /**
     * 见 {@link #handleCollectToPlayer}：按容器里一级成品的**可用量**折算二级原料该收多少。
     *
     * <p>Ray 2026-11-16：「材料列表缺 2 个箱子，自动收集把 16 个橡木木板也收来了——有成品就不用收原料了」。
     * <ul>
     *   <li>成品够整行 ⇒ 这行原料全不收（缺 2 箱子、容器有 2 箱子 ⇒ 木板 16 全跳过）；</li>
     *   <li>成品只够一部分 ⇒ 只收**不足部分**对应的原料（缺 3 箱子、容器有 2 ⇒ 木板按 1 个箱子算）；</li>
     *   <li>原料本身也是一级行 ⇒ 一律不动（那是直接放置要用的）。</li>
     * </ul>
     * 折算用「原料量 × 缺额 / 本行需求量，向上取整」——展开量本来就是按整份需求线性算出来的，误差只在最后一份。
     */
    private static void applySecondaryPlan(Inventory inv, Map<Item, Integer> wantedCount,
                                           List<SecondaryPlanEntry> plan) {
        if (plan == null || plan.isEmpty() || wantedCount.isEmpty()) {
            return;
        }
        try {
            Set<Item> primaryItems = new HashSet<>();
            for (SecondaryPlanEntry entry : plan) {
                if (entry.primary() != null) {
                    primaryItems.add(entry.primary());
                }
            }
            Map<Item, Integer> drop = new LinkedHashMap<>();
            for (SecondaryPlanEntry entry : plan) {
                if (entry.primary() == null || entry.need() <= 0) {
                    continue;
                }
                int available = countAvailableInContainer(inv, entry.primary());
                int shortfall = Math.max(0, entry.need() - available);
                for (ItemStack ingredient : entry.ingredients()) {
                    if (ingredient == null || ingredient.isEmpty() || primaryItems.contains(ingredient.getItem())) {
                        continue;   // 原料本身也是一级行：不动
                    }
                    int keep = (int) Math.ceil(ingredient.getCount() * (double) shortfall / (double) entry.need());
                    int skip = ingredient.getCount() - keep;
                    if (skip > 0) {
                        drop.merge(ingredient.getItem(), skip, Integer::sum);
                    }
                }
            }
            if (drop.isEmpty()) {
                return;
            }
            List<String> skipped = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : drop.entrySet()) {
                Integer cur = wantedCount.get(e.getKey());
                if (cur == null || cur <= 0) {
                    continue;
                }
                int next = Math.max(0, cur - e.getValue());
                if (next <= 0) {
                    wantedCount.remove(e.getKey());
                } else {
                    wantedCount.put(e.getKey(), next);
                }
                skipped.add(net.minecraft.registry.Registries.ITEM.getId(e.getKey()).getPath() + "×" + e.getValue());
            }
            if (!skipped.isEmpty()) {
                LOGGER.info("[auto-collect] 容器里一级成品已够（或够一部分）⇒ 跳过二级原料：{}",
                        String.join(", ", skipped));
            }
        } catch (Throwable t) {
            LOGGER.warn("[auto-collect] 二级原料折算出错（按原清单继续）", t);
        }
    }

    /** 容器（含盒内一层）里某种物品可用的数量。 */
    private static int countAvailableInContainer(Inventory inv, Item item) {
        int total = 0;
        if (inv == null || item == null) {
            return 0;
        }
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
            ContainerComponent comp = stack.get(DataComponentTypes.CONTAINER);
            if (comp != null) {
                for (ItemStack inner : comp.iterateNonEmpty()) {
                    if (inner != null && !inner.isEmpty() && inner.isOf(item)) {
                        total += inner.getCount();
                    }
                }
            }
        }
        return total;
    }

    private static void handleCollectToPlayer(long posLong, ServerPlayerEntity player, Inventory inv, List<ItemStack> wanted,
                                              List<SecondaryPlanEntry> plan) {
        try {
            StringBuilder detail = new StringBuilder();
            for (ItemStack want : wanted) {
                if (!detail.isEmpty()) detail.append(", ");
                detail.append(net.minecraft.registry.Registries.ITEM.getId(want.getItem()).getPath()).append('×').append(want.getCount());
            }
            LOGGER.info("[auto-collect] 服务端收到清单：{}", detail);
        } catch (Throwable ignored) {}
        if (wanted == null || wanted.isEmpty()) {
            sendResult(player, posLong, RESULT_NO_ITEM, 0);
            return;
        }
        // 缺量索引（物品 → 还缺多少）：ItemMatcher 口径与填充一致
        Map<Item, Integer> wantedCount = new LinkedHashMap<>();
        for (ItemStack want : wanted) {
            if (want.isEmpty()) {
                continue;
            }
            wantedCount.merge(want.getItem(), want.getCount(), Integer::sum);
        }
        // ⭐ v1.19.1-Ray（Ray 2026-11-16：「材料列表缺 2 个箱子，自动收集把 16 个橡木木板也收来了——
        //   有成品就不用收原料了，成品直接一步到位」）：**先看容器里一级成品够不够**；
        //   够整行需求 ⇒ 这行展开出来的二级原料不再收（原料本身也是一级行的、或别的成品不够的行还要用的，不动）。
        applySecondaryPlan(inv, wantedCount, plan);
        // 【v1.16.21-Ray（Ray 2026-11-15）】**客户端已经发净缺口了**（按按热键那一刻的背包实时算：
        // 「总量 − 身上现有」），所以服务端**不能再扣原版背包 / 盒装**——那是双扣：
        // 实测「还差 9 个二级材料」时，客户端发 9，服务端拿身上 1729 去扣 ⇒ 9 被扣成 0 ⇒ 一个都不收。
        // 这里只扣**客户端看不见的来源**：精妙背包（Litematica 的已有统计里没有它的内容）。
        Map<Item, Integer> invisibleHeld = new LinkedHashMap<>();
        if (SbpSupport.available()) {
            try {
                for (Item item : new HashSet<>(wantedCount.keySet())) {
                    int inSbp = SbpServerExtract.countInBackpacks(player, item);
                    if (inSbp > 0) {
                        invisibleHeld.merge(item, inSbp, Integer::sum);
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[auto-collect] 数精妙背包存量失败（跳过）", t);
            }
        }
        wantedCount.replaceAll((item, need) -> Math.max(0, need - invisibleHeld.getOrDefault(item, 0)));
        wantedCount.values().removeIf(need -> need <= 0);
        LOGGER.info("[auto-collect] 客户端净缺口扣掉精妙背包已有后的待收：{} 种", wantedCount.size());
        int collectedSlots = 0;
        int collectedItems = 0;
        boolean backpackFull = false;
        backpackFullFlag = false;
        // 【v1.16.29-Ray 修】**三遍扫描：先整盒 → 再开盒 → 最后散装**。
        // ⛔ 旧写法单遍按槽位顺序走：散装排在盒子前面时先把需要量吃掉，
        //   轮到盒子变成「剩余 1727 < 盒内 1728」⇒ 不整搬、改成开盒取 1727 个散货。
        //   Ray 2026-11-16 实测：仓库箱子 11 散 + 1 盒(1728)，需要 1738
        //   ⇒ 期望「整盒 1728 + 散货 10」，实际拿到 1737 个散货。根因就是顺序。
        // 先整盒（盒内容越多越优先，避免小盒先被开掉、把大盒的整搬机会吃掉）：
        //   ① 需要量 ≥ 盒内该材料总量（且盒里全是缺料）⇒ **整盒搬进背包**；
        //   ② 需要量 < 盒内量 ⇒ 留到第二遍**原地开盒按需取**（盒子留容器原位）。
        List<Integer> boxSlots = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && mimic(stack, wantedCount)) {
                boxSlots.add(i);
            }
        }
        boxSlots.sort((a, b) -> Integer.compare(boxWantedTotal(inv.getStack(b), wantedCount),
                boxWantedTotal(inv.getStack(a), wantedCount)));
        for (int i : boxSlots) {
            if (backpackFull) {
                break;
            }
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int takenWhole = collectWholeBoxIfWorth(stack, wantedCount, player, inv, i);
            if (takenWhole > 0) {
                inv.markDirty();
                collectedSlots++;
                collectedItems += takenWhole;
            }
        }
        // 第二遍：没整搬的盒子 ⇒ 原地开盒按需取
        for (int i : boxSlots) {
            if (backpackFull) {
                break;
            }
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty() || !mimic(stack, wantedCount)) {
                continue;
            }
            int takenFromBox = mimic(stack, wantedCount, player);
            if (takenFromBox > 0) {
                inv.markDirty();
                collectedSlots++;
                collectedItems += takenFromBox;
            }
        }
        // 第三遍：散装 —— 是清单缺料 ⇒ 按缺量拿
        for (int i = 0; i < inv.size() && !backpackFull; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Integer stillNeeded = wantedCount.get(stack.getItem());
            if (stillNeeded == null || stillNeeded <= 0) {
                continue;
            }
            int take = Math.min(stillNeeded, stack.getCount());
            ItemStack grab = stack.copy();
            grab.setCount(take);
            if (player.getInventory().insertStack(grab)) {
                int rest = stack.getCount() - take;
                if (rest <= 0) {
                    inv.setStack(i, ItemStack.EMPTY);
                } else {
                    stack.setCount(rest);
                }
                wantedCount.merge(stack.getItem(), -take, Integer::sum);
                inv.markDirty();
                collectedSlots++;
                collectedItems += take;
            } else {
                backpackFull = true;
            }
        }
        inv.markDirty();
        player.getInventory().markDirty();
        // **客户端副本同步**（自 v1.17.1/2 移植：服务端直改背包后 sendContentUpdates 的变更检测
        // 在 server.execute 路径不可靠 ⇒ 显式逐槽 SetSlot 包，收集后物品栏立刻刷新）
        syncPlayerInventoryToClient(player);
        if (backpackFullFlag) {
            backpackFull = true;
        }
        if (backpackFull && collectedItems == 0) {
            sendResult(player, posLong, RESULT_BACKPACK_FULL, 0);
        } else if (backpackFull) {
            sendResult(player, posLong, RESULT_BACKPACK_FULL, collectedItems);
        } else if (collectedItems == 0) {
            sendResult(player, posLong, RESULT_NO_MATERIAL, 0);
        } else {
            sendResult(player, posLong, RESULT_DONE, collectedItems);
        }
    }

    /**
     * 【v1.16.16 盒装语义①】需要量 ≥ 盒内缺料量（且盒里装的全是缺料）⇒ **整盒搬进玩家背包**：
     * 盒子原样（连同内容）进背包、容器槽位清空、每种缺料按盒内量记账（剩余缺量继续从后面的槽位收）。
     *
     * <p>不满足条件（盒里混着非缺料 / 某种缺料需要量小于盒内量 / 背包放不下 / 盒内数量异常）
     * ⇒ 返回 0，调用方退回「原地开盒按需取」。</p>
     *
     * @return 整盒搬走时记入账的缺料总个数（>0 = 已整盒搬走）；0 = 不整搬
     */
    private static int collectWholeBoxIfWorth(ItemStack stack, Map<Item, Integer> wantedCount,
                                              ServerPlayerEntity player, Inventory inv, int slot) {
        net.minecraft.component.type.ContainerComponent comp =
                stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
        if (comp == null) {
            return 0;
        }
        // 【v1.16.18-Ray 修】按**物品**汇总盒内数量再比较。
        // ⛔ 旧写法拿「单个栈的数量」比（`need < inner.getCount()`）：盒里 27 栈×64 = 1728 时，
        //   需要 1153 也满足「1153 ≥ 64」⇒ 整盒被搬走（Ray 2026-11-15 实测：清单缺 18组+1，
        //   却收来 1 整盒 + 1 个）。正确语义＝「需要量 ≥ **盒内该材料总量**」。
        Map<Item, Integer> boxCounts = new LinkedHashMap<>();
        int total = 0;
        for (ItemStack inner : comp.iterateNonEmpty()) {
            if (inner == null || inner.isEmpty() || inner.getCount() <= 0) {
                continue;   // ⚠️ 数量 ≤0 的异常栈不参与判定（存档脏数据防御）
            }
            if (!wantedCount.containsKey(inner.getItem())) {
                return 0;   // 盒里混着非缺料 ⇒ 按需开盒更精确，不整搬
            }
            boxCounts.merge(inner.getItem(), inner.getCount(), Integer::sum);
            total += inner.getCount();
        }
        if (total <= 0) {
            return 0;
        }
        for (Map.Entry<Item, Integer> e : boxCounts.entrySet()) {
            int still = wantedCount.getOrDefault(e.getKey(), 0);
            if (still < e.getValue()) {
                LOGGER.info("[auto-collect] 盒内 {}×{} > 剩余缺量 {} ⇒ 不整搬，改成开盒按需取",
                        Registries.ITEM.getId(e.getKey()).getPath(), e.getValue(), still);
                return 0;   // 需要量 < 盒内该材料总量 ⇒ 开盒按需取（盒子留容器原位）
            }
        }
        ItemStack boxMove = stack.copy();
        if (!player.getInventory().insertStack(boxMove)) {
            return 0;   // 背包放不下 ⇒ 退回开盒取量
        }
        for (Map.Entry<Item, Integer> e : boxCounts.entrySet()) {
            wantedCount.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        inv.setStack(slot, ItemStack.EMPTY);
        LOGGER.info("[auto-collect] 需要量足够 ⇒ 整盒搬进背包（{}，盒内 {} 个），剩余缺量继续从容器其他槽位收",
                net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath(), total);
        return total;
    }

    /** 盒内**缺料**总量（v1.16.29 排序用：盒内容越多越先整搬）。 */
    private static int boxWantedTotal(ItemStack box, Map<Item, Integer> wantedCount) {
        if (box == null || box.isEmpty()) {
            return 0;
        }
        ContainerComponent comp = box.get(DataComponentTypes.CONTAINER);
        if (comp == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack inner : comp.iterateNonEmpty()) {
            if (inner != null && !inner.isEmpty() && wantedCount.containsKey(inner.getItem())) {
                total += inner.getCount();
            }
        }
        return total;
    }

    /** 客户端背包副本同步：显式逐槽 SetSlot 包（sendContentUpdates 的变更检测在直改路径不可靠）。 */
    public static void syncPlayerInventoryToClient(ServerPlayerEntity player) {
        try {
            ScreenHandler handler = player.playerScreenHandler;
            for (int i = 0; i < handler.slots.size(); i++) {
                player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                        handler.syncId,
                        handler.getRevision(),
                        i,
                        handler.slots.get(i).getStack()));
            }
        } catch (Throwable t) {
            LOGGER.warn("[batch] 推送玩家背包同步包失败", t);
        }
    }

    /**
     * 原地开盒取料：从 {@code stack}（潜影盒/收纳袋）的内容里按缺量取，直接给玩家背包；
     * 盒子留在原地（剩余内容写回 CONTAINER 组件）。背包放不下 ⇒ 停止拿取、绝不丢。
     *
     * @return 实际取走的总个数（0 = 盒里没有可取的/背包满）
     */
    private static int mimic(ItemStack stack, Map<Item, Integer> wantedCount,
                                                   ServerPlayerEntity player) {
        net.minecraft.component.type.ContainerComponent comp =
                stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
        if (comp == null) {
            return 0;
        }
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack inner : comp.iterateNonEmpty()) {
            contents.add(inner.copy());
        }
        int takenTotal = 0;
        boolean changed = false;
        for (int ci = 0; ci < contents.size(); ci++) {
            ItemStack inner = contents.get(ci);
            if (inner.isEmpty()) {
                continue;
            }
            Integer stillNeeded = wantedCount.get(inner.getItem());
            if (stillNeeded == null || stillNeeded <= 0) {
                continue;
            }
            int take = Math.min(stillNeeded, inner.getCount());
            int got = 0;
            for (int k = 0; k < take; k++) {
                ItemStack one = inner.copy();
                one.setCount(1);
                if (player.getInventory().insertStack(one)) {
                    got++;
                } else {
                    backpackFullFlag = true;
                    break;
                }
            }
            if (got > 0) {
                takenTotal += got;
                wantedCount.merge(inner.getItem(), -got, Integer::sum);
                changed = true;
                int rest = inner.getCount() - got;
                if (rest <= 0) {
                    contents.set(ci, ItemStack.EMPTY);
                } else {
                    inner.setCount(rest);
                }
            }
            if (backpackFullFlag) {
                break;
            }
        }
        if (changed) {
            stack.set(net.minecraft.component.DataComponentTypes.CONTAINER,
                    net.minecraft.component.type.ContainerComponent.fromStacks(contents));
        }
        return takenTotal;
    }

    private static boolean backpackFullFlag;

    /** 容器物品（潜影盒/收纳袋）的内容里有没有清单缺料。 */
    private static boolean mimic(ItemStack stack, Map<Item, Integer> wantedCount) {
        net.minecraft.component.type.ContainerComponent comp =
                stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
        if (comp == null) {
            return false;
        }
        for (ItemStack inner : comp.iterateNonEmpty()) {
            if (!inner.isEmpty() && wantedCount.containsKey(inner.getItem())) {
                return true;
            }
        }
        return false;
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
