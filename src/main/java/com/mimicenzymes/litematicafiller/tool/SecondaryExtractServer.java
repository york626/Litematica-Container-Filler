package com.mimicenzymes.litematicafiller.tool;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **服务端直连提取**（Ray 2026-11-13：「加服务端，我只玩单人」）。
 *
 * <h2>为什么需要它</h2>
 * <p>纯客户端 {@code clickSlot} 只能操作**当前打开的界面**：闭合的潜影盒 / 精妙背包里的东西取不出来，
 * 只能「把盒子换到手上 → 右键 → 点 → 关」走一遍（界面会闪，还容易卡）。服务端可以直接读写
 * {@code Inventory}，一步到位 ⇒ 不开任何界面、不闪、没有延迟。
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>客户端对准目标容器按热键 → 发 {@link ExtractPayload}（目标坐标 + 材料 ID 与数量预算）；</li>
 *   <li>服务端校验距离/区块/权限，拿目标容器的 {@link Inventory}，先扣掉目标里已有的（负数按 0 计）；</li>
 *   <li>遍历玩家背包：散装材料**按预算**塞进容器；潜影盒 / 收纳袋拆 {@code ContainerComponent} 再塞
 *       （装过材料的盒子搬进玩家背包）；</li>
 *   <li>搬完自动同步（原版容器改动会发包给客户端，不用额外做什么）。</li>
 * </ol>
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>只搬**玩家自己的**东西，不复制、不凭空生成 ⇒ 不存在刷物品风险；</li>
 *   <li>距离 &gt; 8 格、容器不是 {@code Inventory}、区块没加载 ⇒ 直接忽略；</li>
 *   <li>目标容器装不下 ⇒ 剩下的留在原处（不会丢）。</li>
 * </ul>
 */
public final class SecondaryExtractServer {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    private SecondaryExtractServer() {
    }

    /** 本轮从精妙背包里搬了多少个盒子到玩家背包。 */
    private static int movedBoxes;

    /** 「没搬到东西」的诊断日志只打一次（否则连按几次就刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NO_MATCH_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * **单人/内置服务端直调**（Ray 2026-11-13：网络包在单人下没送到服务端，干脆绕开它）。
     *
     * <p>单人游戏的客户端和服务端在**同一个 JVM** ⇒ 客户端可以直接拿到 {@code MinecraftServer}
     * 并 {@code execute} 服务端逻辑，不用发网络包、不受连接/注册影响。
     *
     * @return 是否已经接上服务端（false ＝ 连的是别人的服务器，得退回网络包/点击方式）
     */
    public static boolean handleDirect(net.minecraft.client.MinecraftClient mc, BlockPos pos,
                                       Map<Item, Long> demand) {
        try {
            net.minecraft.server.MinecraftServer server = mc.getServer();
            if (server == null || mc.player == null) {
                return false;
            }
            var serverPlayer = server.getPlayerManager().getPlayer(mc.player.getUuid());
            if (serverPlayer == null) {
                return false;
            }
            server.execute(() -> handleWithItems(serverPlayer, pos, demand));
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[extract] 直调服务端失败，改走网络包", t);
            return false;
        }
    }

    private static void handleWithItems(ServerPlayerEntity player, BlockPos targetPos, Map<Item, Long> demand) {
        if (player == null || targetPos == null || demand == null || demand.isEmpty()) {
            return;
        }
        movedBoxes = 0;
        try {
            ServerWorld world = player.getServerWorld();
            if (world == null || !world.isChunkLoaded(targetPos.getX() >> 4, targetPos.getZ() >> 4)) {
                return;
            }
            // 距离校验：只能对自己附近的容器动手
            if (player.squaredDistanceTo(Vec3d.ofCenter(targetPos)) > 64.0) {
                return;
            }
            BlockEntity blockEntity = world.getBlockEntity(targetPos);
            if (!(blockEntity instanceof Inventory target)) {
                LOGGER.info("[extract] 服务端提取：目标不是容器（{}）", targetPos.toShortString());
                return;
            }
            // 数量预算（v1.34.2）：每种材料只搬「需求 − 目标容器里已有」，旧版只传物品种类不带数量，
            // 按需提取变成全搬（2026-11-15 实测踩到：需求 9 个平滑石英块，把身上的 640 个全搬进了容器）。
            // ⚠️ 目标存量**负数按 0 计**（v1.34.2 二次实锤）：存档里被此前实验写坏的容器藏着负数量栈
            //    （GUI 里看不见），9−(−81)=90、9−(−9)=18——正着算反而把预算放大成超搬。
            Map<Item, Long> remaining = new HashMap<>(demand);
            Map<Item, Long> inTarget = new LinkedHashMap<>();
            for (Item item : new HashSet<>(remaining.keySet())) {
                long have = countInTarget(target, item);
                inTarget.put(item, have);
                long left = remaining.get(item) - have;
                if (left <= 0L) {
                    remaining.remove(item);
                } else {
                    remaining.put(item, left);
                }
            }
            LOGGER.info("[extract] 服务端提取预算：需求 {}，目标已有 {}，实搬预算 {}（目标 {}）",
                    demand, inTarget, remaining, targetPos.toShortString());
            if (remaining.isEmpty()) {
                LOGGER.info("[extract] 服务端提取：需求材料目标容器里已备够，不用搬（{}）",
                        targetPos.toShortString());
                return;
            }
            int moved = 0;
            // ① 先扫 Trinkets 装备槽：**穿在身上的精妙背包**不在 player.getInventory() 里
            //    （Ray 2026-11-14 要求覆盖）；Trinkets 没装 ⇒ 静默跳过。
            moved += drainEquippedTrinkets(player, target, remaining);
            // ② 再遍历玩家背包（main + 盔甲 + 副手）
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (remaining.containsKey(stack.getItem())) {
                    // 散装材料：按预算塞进容器（预算内有多少搬多少，超出预算的不动）
                    moved += takeInto(target, stack, remaining);
                } else if (isSophisticatedBackpack(stack)) {
                    // 精妙背包（内容在 sbp 自己的服务端数据里）⇒ 反射拆开
                    moved += drainBackpack(player, target, stack, remaining);
                    // 盒子里剩下的（不是本次要的材料）还能再拆一层
                    moved += drainContainer(target, stack, remaining);
                } else {
                    // 盒子（潜影盒 / 收纳袋）：拆内容，把里面的材料塞进容器
                    moved += drainContainer(target, stack, remaining);
                }
            }
            if (moved > 0 || movedBoxes > 0) {
                player.getInventory().markDirty();
                if (blockEntity instanceof Inventory inv) {
                    inv.markDirty();
                }
                // 客户端副本同步：直调路径通常是「没开任何界面」时走的，服务端改了
                // player.getInventory() 之后客户端不会自动知道（ScreenHandler 只在自己
                // tick 时发增量）—— 必须手动 sendContentUpdates，否则盒子里客户端要等
                // 重开背包界面才出现，看起来就像「没搬进去」。
                // ⛔ v1.16.23-Ray：CF 的既有教训 —— `sendContentUpdates()` 的变更检测在「直改背包」路径
                //    不可靠（v1.16.16 / v1.16.21 都踩过，症状＝物品栏还是旧数据、看起来像没搬）。
                //    这里改用**显式逐槽 SetSlot**（与收集侧同一个方法）。
                com.mimicenzymes.litematicafiller.network.BatchServerLink.syncPlayerInventoryToClient(player);
                if (player.currentScreenHandler != player.playerScreenHandler) {
                    player.currentScreenHandler.sendContentUpdates();   // 开着的容器界面照旧
                }
                long demandTotal = demand.values().stream().mapToLong(Long::longValue).sum();
                LOGGER.info("[extract] 服务端提取：{} 个材料放进 {}（需求共 {}），另有 {} 个盒子搬进背包（玩家 {}）",
                        moved, targetPos.toShortString(), demandTotal, movedBoxes, player.getName().getString());
            } else if (NO_MATCH_LOGGED.compareAndSet(false, true)) {
                // 诊断：一次都没搬成功时，把背包里的物品 ID 打出来（限一次，避免刷屏）
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < player.getInventory().size(); i++) {
                    ItemStack st = player.getInventory().getStack(i);
                    if (st.isEmpty()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(Registries.ITEM.getId(st.getItem()));
                }
                LOGGER.info("[extract] 服务端提取：没搬到东西，背包物品 = {}", sb);
            }
            // ★ v1.16.24-Ray：把**实际结果**用 actionbar 报给玩家（直调服务端是同一个 JVM，
            //   服务端可以直接发消息）——否则玩家只看到"按需进准星容器"那句，不知道到底搬了几个、
            //   哪个材料没找到（Ray 2026-11-15 就因此以为"箱子没收到二级材料"）。
            try {
                StringBuilder summary = new StringBuilder();
                for (java.util.Map.Entry<Item, Long> e : demand.entrySet()) {
                    long left = Math.max(0L, remaining.getOrDefault(e.getKey(), 0L));
                    long got = Math.max(0L, e.getValue() - left);
                    if (summary.length() > 0) {
                        summary.append("，");
                    }
                    summary.append(e.getKey().getName().getString()).append(' ')
                            .append(got).append('/').append(e.getValue());
                }
                String extra = movedBoxes > 0 ? "（另有 " + movedBoxes + " 个盒子进背包）" : "";
                player.sendMessage(net.minecraft.text.Text.literal("提取二级材料：" + summary + extra), true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            LOGGER.warn("[extract] 服务端提取失败", t);
        }
    }

    /**
     * 是不是精妙背包（内容在 sbp 自己的服务端数据里，不在 `ContainerComponent`）。
     *
     * <p>⚠️ **用物品 ID 的命名空间判断，不要用类名**（2026-11-13 踩过：`getClass().getName()`
     * 在某些加载器/重定位下拿到的名字对不上，导致整条分支被跳过、还静默无日志）。
     */
    private static boolean isSophisticatedBackpack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            Identifier id = Registries.ITEM.getId(stack.getItem());
            return id != null && "sophisticatedbackpacks".equals(id.getNamespace());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 精妙背包反射（sbp 没有公开 API，按类名 + 方法名反射；服务器换版本时失配就跳过） ──

    private static boolean sbInitTried;
    private static boolean sbAvailable;
    private static java.lang.reflect.Method sbFromExistingData;
    private static java.lang.reflect.Method sbGetInventoryHandler;
    private static java.lang.reflect.Method sbGetSlots;
    private static java.lang.reflect.Method sbGetStackInSlot;
    private static java.lang.reflect.Method sbSetStackInSlot;

    // ── Trinkets 反射（穿在身上的背包；Trinkets 没装就静默跳过）──
    // 签名以 mods 里的 trinkets-3.10.0.jar javap 为准（2026-11-14）：
    //   TrinketsApi.getTrinketComponent(LivingEntity) → Optional<TrinketComponent>
    //   TrinketComponent.getAllEquipped() → List<Pair<SlotReference, ItemStack>>
    // ⚠️ 写回不用额外处理：装备槽里的 ItemStack 实例引用不变，精妙背包内容在服务端
    //    BackpackStorage（SavedData），drainBackpack 的 saveHandler 已负责 markDirty。

    private static boolean tkInitTried;
    private static boolean tkAvailable;
    private static java.lang.reflect.Method tkGetTrinketComponent;
    private static java.lang.reflect.Method tkGetAllEquipped;
    private static java.lang.reflect.Method tkPairGetSecond;

    private static void initTrinketsReflection() {
        if (tkInitTried) {
            return;
        }
        tkInitTried = true;
        try {
            Class<?> api = Class.forName("dev.emi.trinkets.api.TrinketsApi");
            Class<?> component = Class.forName("dev.emi.trinkets.api.TrinketComponent");
            tkGetTrinketComponent = api.getMethod("getTrinketComponent",
                    net.minecraft.entity.LivingEntity.class);
            tkGetAllEquipped = component.getMethod("getAllEquipped");
            tkAvailable = true;
            LOGGER.info("[extract] Trinkets 反射可用（覆盖穿在身上的精妙背包）");
        } catch (Throwable t) {
            tkAvailable = false;
            LOGGER.info("[extract] Trinkets 不可用，跳过装备槽（{}）", t.toString());
        }
    }

    /**
     * 扫 Trinkets 装备槽：穿在身上的精妙背包 / 盒子也参与提取，返回搬过去的材料数。
     *
     * <p>⚠️ `Method` 从**实际对象**取（装备组件是 CCA 实现，不是 `TrinketComponent` 本身）；
     * `getTrinketComponent` 返回 `Optional` 要先拆 —— 同 {@link #drainBackpack} 的规矩。
     */
    private static int drainEquippedTrinkets(ServerPlayerEntity player, Inventory target, Map<Item, Long> remaining) {
        initTrinketsReflection();
        if (!tkAvailable) {
            return 0;
        }
        int moved = 0;
        try {
            Object raw = tkGetTrinketComponent.invoke(null, player);
            Object component = unwrap(raw);
            if (component == null) {
                return 0;
            }
            java.lang.reflect.Method getAllEquipped = component.getClass().getMethod("getAllEquipped");
            Object pairs = getAllEquipped.invoke(component);
            if (!(pairs instanceof java.util.List<?> list)) {
                return 0;
            }
            for (Object pair : list) {
                if (tkPairGetSecond == null) {
                    tkPairGetSecond = resolvePairSecondGetter(pair.getClass());
                }
                ItemStack stack = (ItemStack) tkPairGetSecond.invoke(pair);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (isSophisticatedBackpack(stack)) {
                    moved += drainBackpack(player, target, stack, remaining);
                    moved += drainContainer(target, stack, remaining);
                } else {
                    moved += drainContainer(target, stack, remaining);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[extract] 扫装备槽失败（Trinkets 反射失配？）", t);
        }
        return moved;
    }

    /**
     * 取 Trinkets 装备 Pair 的「物品」那一侧。
     *
     * <p>⚠️ 2026-11-14 两次实锤（成品日志 {@code NoSuchMethodException}）：`getAllEquipped()`
     * 返回的是 **MC 原版 {@code net.minecraft.util.Pair}**（Yarn 名；运行时类名 intermediary
     * {@code class_3545}），不是 Mojang 的 {@code com.mojang.datafixers.util.Pair}。
     * **Fabric 运行时对 MC 类的类名和方法名都 remap**（intermediary 映射连方法名也有
     * `method_XXXX` 名，intermediary-1.21.1.jar 的 tiny 实锤：`b` → {@code method_15441}）⇒
     * 反射字符串用 Yarn/official 名都炸，成品实例上真名是 {@code method_15441}。
     * 依次试 intermediary → Yarn（dev 环境）。
     */
    private static java.lang.reflect.Method resolvePairSecondGetter(Class<?> pairClass) throws Throwable {
        try {
            return pairClass.getMethod("method_15441");
        } catch (NoSuchMethodException ignored) {
            // dev 环境（Yarn 名）走兜底
        }
        return pairClass.getMethod("getSecond");
    }

    private static void initBackpackReflection() {
        if (sbInitTried) {
            return;
        }
        sbInitTried = true;
        try {
            Class<?> wrapperClass = Class.forName(
                    "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Class<?> handlerClass = Class.forName(
                    "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler");
            sbFromExistingData = wrapperClass.getMethod("fromExistingData", ItemStack.class);
            sbGetInventoryHandler = wrapperClass.getMethod("getInventoryHandler");
            sbGetSlots = handlerClass.getMethod("getSlots");
            sbGetStackInSlot = handlerClass.getMethod("getStackInSlot", int.class);
            sbSetStackInSlot = handlerClass.getMethod("setStackInSlot", int.class, ItemStack.class);
            sbAvailable = true;
            LOGGER.info("[extract] 精妙背包反射可用（可拆背包里的盒装材料）");
        } catch (Throwable t) {
            sbAvailable = false;
            LOGGER.info("[extract] 精妙背包反射不可用，跳过背包内容（{}）", t.toString());
        }
    }

    /**
     * 把精妙背包里的「所需材料」和「装着所需材料的盒子」搬出来，材料直接塞进目标容器。
     *
     * <p>Ray 2026-11-13：至少要把盒装材料从精妙背包里搬到能用的地方 —— 这里直接拆到容器里，
     * 连玩家背包都不用经过。
     *
     * @return 搬过去的材料数量
     */
    private static int drainBackpack(ServerPlayerEntity player, Inventory target, ItemStack backpack, Map<Item, Long> remaining) {
        initBackpackReflection();
        if (!sbAvailable) {
            return 0;
        }
        int moved = 0;
        try {
            // ⚠️ `fromExistingData` 返回的是 **Optional**（2026-11-13 实锤：
            //    `NoSuchMethodException: java.util.Optional.getInventoryHandler()`），先拆开
            Object raw = sbFromExistingData.invoke(null, backpack);
            Object wrapper = raw instanceof java.util.Optional<?> optional ? optional.orElse(null) : raw;
            if (wrapper == null) {
                return 0;
            }
            // ⚠️ 同样**从实际对象取方法**：`fromExistingData` 返回的实现类不一定就是
            //    `BackpackWrapper` 本身（可能是它的包装/子类），用预缓存的 Method 去 invoke 会抛
            //    `object is not an instance of declaring class`（2026-11-13 实锤，269 行）。
            Object handler = unwrap(wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper));
            if (handler == null) {
                return 0;
            }
            // ⚠️ **必须从实际对象的类取方法**（2026-11-13 实锤）：
            //    `getInventoryHandler()` 返回的实现类不一定就是
            //    `net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler`（可能是包装/子类），
            //    拿 InventoryHandler 上的 Method 去 invoke 它会抛
            //    `IllegalArgumentException: object is not an instance of declaring class`。
            Class<?> handlerClass = handler.getClass();
            java.lang.reflect.Method getStackInSlot = handlerClass.getMethod("getStackInSlot", int.class);
            java.lang.reflect.Method setStackInSlot =
                    handlerClass.getMethod("setStackInSlot", int.class, ItemStack.class);
            int slots = resolveSlotCount(handler);
            for (int i = 0; i < slots; i++) {
                ItemStack inner = (ItemStack) getStackInSlot.invoke(handler, i);
                if (inner == null || inner.isEmpty()) {
                    continue;
                }
                if (remaining.containsKey(inner.getItem())) {
                    // 散装材料：按预算塞进容器，剩下的写回背包槽
                    int before = inner.getCount();
                    moved += takeInto(target, inner, remaining);
                    if (inner.getCount() != before) {
                        setStackInSlot.invoke(handler, i, inner.isEmpty() ? ItemStack.EMPTY : inner);
                    }
                } else if (inner.get(DataComponentTypes.CONTAINER) != null) {
                    // 盒子（潜影盒 / 收纳袋）：**一条龙**（Ray 2026-11-14：「按一次热键走完流程」）——
                    // ① 先把盒子里要的材料直接抽进目标容器（盒里混着不要的东西时只抽要的）；
                    // ② 盒子整组搬进玩家背包（Ray 2026-11-13 要的「盒子出现在背包」）。
                    // ⚠️ v1.34.2 加门槛：**只搬「确实抽到过材料」的盒子**——旧版不管盒里有没有
                    //    要的材料都整盒搬走，别的投影留下的盒子（如岩浆膏）也被拽进玩家背包
                    //    （2026-11-15 实测踩到）。没抽到材料 ⇒ 盒子原样留在精妙背包里。
                    int movedHere = drainContainer(target, inner, remaining);
                    if (movedHere > 0) {
                        moved += movedHere;
                        ItemStack toMove = inner.copy();
                        if (player.getInventory().insertStack(toMove)) {
                            movedBoxes++;
                            setStackInSlot.invoke(handler, i, toMove.isEmpty() ? ItemStack.EMPTY : toMove);
                        } else {
                            setStackInSlot.invoke(handler, i, inner.isEmpty() ? ItemStack.EMPTY : inner);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[extract] 拆精妙背包失败（反射失配？）", t);
        }
        return moved;
    }

    /**
     * 解析 handler 的槽数。
     *
     * <p>⚠️ 不能拿 {@code getSlots()} 当「Forge 式返回槽数的 int」用（2026-11-14 实锤，
     * v1.31.0 因此整条拆背包被吞：日志 {@code ClassCastException: ArrayList cannot be
     * cast to Integer}，盒子一个都搬不出）。这条继承链是 porting_lib 的 Fabric Transfer
     * API 风格 —— {@code InventoryHandler extends porting_lib ItemStackHandler implements
     * SlottedStackStorage}，从实际类反射取到的 {@code getSlots()} 返回的是 **List**。
     * 正解是 {@code getSlotCount()}（Fabric {@code SlottedStorage} 接口，返回 int，
     * sophisticatedcore 源码自己遍历槽位用的就是它）；万一哪个版本没这个方法，
     * 再退 {@code getSlots()} 的 {@code size()}。
     */
    private static int resolveSlotCount(Object handler) throws Throwable {
        Class<?> cls = handler.getClass();
        try {
            Object result = cls.getMethod("getSlotCount").invoke(handler);
            if (result instanceof Integer count) {
                return count;
            }
        } catch (NoSuchMethodException ignored) {
            // 老版本没有 getSlotCount ⇒ 走下面的兜底
        }
        Object result = cls.getMethod("getSlots").invoke(handler);
        if (result instanceof java.util.List<?> list) {
            return list.size();
        }
        if (result instanceof Integer count) {
            return count;
        }
        throw new IllegalStateException("取不到槽数（getSlotCount/getSlots 都不认识，返回 " + result + "）");
    }

    /** 拆掉可能的 {@code Optional} 外壳（sbp 的 API 经常返回 Optional）。 */
    private static Object unwrap(Object value) {
        return value instanceof java.util.Optional<?> optional ? optional.orElse(null) : value;
    }

    /** 按预算把 {@code stack} 塞进容器（塞多少扣多少预算），返回实际搬过去的数量。 */
    private static int takeInto(Inventory target, ItemStack stack, Map<Item, Long> remaining) {
        // ⛔ v1.16.25-Ray（Ray 2026-11-15 实测「箱子里明明有 9 个，却报 平滑石英块 0/9」）：
        //   **物品 id 必须在 insertInto 之前取**！insertInto 会把源栈 setCount(0)，而 1.21 的
        //   `ItemStack.getItem()` 对空栈返回 **AIR** ⇒ 旧写法把这笔账写进 "air" 这个键、
        //   原物品的预算**一点没减**（日志里那行「air 预算 9 实搬 9 剩余预算 0」就是它）。
        //   后果：① 报告永远 0/N；② 下一次按热键以为还没搬，会重复搬。
        Item item = stack.getItem();
        long budget = remaining.getOrDefault(item, 0L);
        if (budget <= 0L) {
            return 0;
        }
        if (stack.isEmpty() || item == net.minecraft.item.Items.AIR) {
            return 0;   // 空/异常栈不参与（既不搬也不扣预算）
        }
        int movedNow = insertInto(target, stack, budget);
        remaining.put(item, budget - movedNow);
        // 逐次诊断：每搬一次记一行（预算/实搬/剩余）
        LOGGER.info("[extract] 提取预算执行：{} 预算 {} 实搬 {} 剩余预算 {}",
                Registries.ITEM.getId(item).getPath(), budget, movedNow,
                remaining.getOrDefault(item, 0L));
        return movedNow;
    }

    /**
     * 目标容器散装层里已经有多少个该物品（不算容器里再嵌套的盒子内部）。
     *
     * <p>⚠️ **负数按 0 计**（v1.34.2 实锤）：存档里被此前实验写坏的容器藏着负数量栈
     * （GUI 里看不见），2026-11-15 三次实测 9−(−81)=90、9−(−9)=18——正着算反而把
     * 预算放大成超搬。负数栈本身不动它（不动存档数据），只不让它参与预算。
     */
    private static long countInTarget(Inventory target, Item item) {
        long total = 0L;
        for (int i = 0; i < target.size(); i++) {
            ItemStack stack = target.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                total += Math.max(0, stack.getCount());
            }
        }
        return total;
    }

    /** 把 {@code stack} 塞进容器（最多 {@code max} 个），返回实际搬过去的数量。 */
    private static int insertInto(Inventory target, ItemStack stack, long max) {
        // ⚠️ 「想搬的量」和「源栈剩下的量」必须分开算——v1.34.1/2 把它们混成了同一个局部变量，
        //    尾部 setCount(remaining) 写的是「还想搬的量(=0)」⇒ **源栈被整个清零**
        //    （Ray 2026-11-15 实测：搬 9 个进容器、源栈剩余 55 个凭空删除）。
        int toMove = (int) Math.min(stack.getCount(), Math.max(0L, max));
        int moved = 0;
        for (int i = 0; i < target.size() && toMove > 0; i++) {
            ItemStack slotStack = target.getStack(i);
            if (slotStack.isEmpty()) {
                int put = Math.min(toMove, stack.getMaxCount());
                target.setStack(i, new ItemStack(stack.getItem(), put));
                toMove -= put;
                moved += put;
            } else if (ItemStack.areItemsAndComponentsEqual(slotStack, stack)
                    && slotStack.getCount() < slotStack.getMaxCount()) {
                int put = Math.min(toMove, slotStack.getMaxCount() - slotStack.getCount());
                slotStack.increment(put);
                toMove -= put;
                moved += put;
            }
        }
        // 按「实际搬走的量」从源栈扣——源栈剩余 = 原数量 − 实搬（绝不整栈清零）
        stack.setCount(stack.getCount() - moved);
        return moved;
    }

    /**
     * 拆开潜影盒 / 收纳袋，把里面的所需材料塞进容器。
     *
     * <p>⚠️ 精妙背包的内容**不在 {@code ContainerComponent} 里**（它有自己的服务端数据），
     * 这里读不到 —— 那种情况需要先把盒子挪到玩家背包（客户端那套逻辑还在）。
     */
    private static int drainContainer(Inventory target, ItemStack box, Map<Item, Long> remaining) {
        ContainerComponent container = box.get(DataComponentTypes.CONTAINER);
        if (container == null) {
            return 0;
        }
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack inner : container.iterateNonEmpty()) {
            contents.add(inner);
        }
        int moved = 0;
        boolean changed = false;
        for (ItemStack inner : contents) {
            if (inner.isEmpty() || !remaining.containsKey(inner.getItem())) {
                continue;
            }
            int before = inner.getCount();
            moved += takeInto(target, inner, remaining);
            if (inner.getCount() != before) {
                changed = true;
            }
        }
        if (changed) {
            box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
        }
        return moved;
    }
}
