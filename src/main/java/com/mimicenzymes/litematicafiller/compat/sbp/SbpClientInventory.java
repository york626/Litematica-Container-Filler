package com.mimicenzymes.litematicafiller.compat.sbp;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.network.RequestBackpackInventoryContentsPayload;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.inventory.ITrackedContentsItemHandler;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 【已有量口径】客户端只读：把**身上所有精妙背包**（穿戴 + 携带）里的物品数量汇总出来
 * （Ray 2026-11-19：「外框高亮了木棍，但自动取货说已收完，两边对不上」）。
 *
 * <p>原因：材料高亮 / 箱子外框用的是「总量 − 散装（+身上盒内一层）」，**不算精妙背包**；
 * 而自动取货在服务端会扣 {@code SbpServerExtract.countInBackpacks} 的存量 ⇒ 一边说缺、
 * 一边说已收完。这里把背包内容补进“已有”，让高亮/外框与自动取货同口径。
 *
 * <p>精妙背包的客户端有一份内容副本（{@code BackpackStorage.clientStorageCopy}），
 * {@code PlayerInventoryProvider} 在客户端也覆盖穿戴/携带的背包；槽位读写沿用
 * {@link SbpServerExtract} 的反射方式（精妙 jar 是 intermediary 命名）。
 */
public final class SbpClientInventory {

    private static Method slotCountGetter;
    private static Method slotStackGetter;
    private static boolean failed;

    private SbpClientInventory() {
    }

    /** 玩家所有精妙背包的内容汇总（物品 → 数量，含盒内一层）。 */
    public static Map<Item, Long> snapshot(PlayerEntity player) {
        Map<Item, Long> out = new HashMap<>();
        if (player == null || !SbpSupport.available() || failed) {
            return out;
        }
        try {
            ensureReflection();
        } catch (Throwable t) {
            failed = true;
            return out;
        }
        try {
            PlayerInventoryProvider.get().runOnBackpacks(player,
                    (backpackStack, invIdentifier, identifier, slot) -> {
                        try {
                            IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpackStack);
                            ITrackedContentsItemHandler handler = wrapper.getInventoryForUpgradeProcessing();
                            int slots = (Integer) slotCountGetter.invoke(handler);
                            for (int i = 0; i < slots; i++) {
                                ItemStack stack = (ItemStack) slotStackGetter.invoke(handler, i);
                                if (stack == null || stack.isEmpty()) {
                                    continue;
                                }
                                out.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                                if (container != null) {
                                    for (ItemStack inner : container.iterateNonEmpty()) {
                                        if (inner != null && !inner.isEmpty()) {
                                            out.merge(inner.getItem(), (long) inner.getCount(), Long::sum);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return false;   // false = 继续遍历下一个背包
                    });
        } catch (Throwable t) {
            failed = true;
        }
        return out;
    }

    private static void ensureReflection() throws ReflectiveOperationException {
        if (slotCountGetter != null && slotStackGetter != null) {
            return;
        }
        Class<?> handlerClass = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler");
        slotCountGetter = handlerClass.getMethod("getSlotCount");
        slotStackGetter = handlerClass.getMethod("getSlotStack", int.class);
    }

    // ---------------------------------------------------------------- 客户端镜像同步

    /**
     * 切维度后精妙背包的**客户端内容镜像**会失效（Ray 2026-11-19：下界回主世界后材料列表认不到
     * 背包里的材料，打开一次背包才恢复）。这里主动向服务端要一次背包内容：
     * <ul>
     *   <li>维度变化 ⇒ 立即请求（不管镜像空不空，可能只是旧数据）；</li>
     *   <li>平时每 5 秒兜底检查一次：镜像还是空的就再请求（3 秒节流）。</li>
     * </ul>
     * 请求是 SBP 自己的 `RequestBackpackInventoryContentsPayload`（客户端悬停/开背包时发的同款），
     * 服务端回包后 SBP 会更新 {@code BackpackStorage.clientStorageCopy} ⇒ 材料列表/高亮恢复识别。
     */
    public static void tick(MinecraftClient client) {
        if (!SbpSupport.available() || client == null || client.player == null || client.world == null) {
            return;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        boolean dimensionChanged = lastDimension != null && !lastDimension.equals(dimension);
        lastDimension = dimension;
        long now = System.currentTimeMillis();

        // 每次尽量更新「穿戴背包的 UUID」；切维度刚开始 trinket 数据可能还没同步 ⇒ 用上一次的兜底。
        UUID worn = findWornUuid(client);
        if (worn != null) {
            lastWornUuid = worn;
        }

        // ⭐ v1.22.9：SBP 的客户端镜像会**反复闪空**（切维度、周期重同步都会清一下；日志实测
        //   20:24:09 / 20:24:59 两次空窗）。材料列表每次重算正好撞上空窗就显示“认不到”。
        //   这里缓存“上次完整内容”，一发现镜像变空就**立即补回去**；真正的新包到了会覆盖。
        if (lastWornUuid != null) {
            keepOrRestoreCopy(lastWornUuid);
        }

        if (dimensionChanged) {
            // ⭐ v1.22.7：切维度后头 30 秒**每 2 秒重试**（v1.22.6 只发一次，服务端/trinket 还没就绪
        //   ⇒ 要等很久的 5 秒兜底才成功；Ray 实测切回主世界仍是“认不到”）。
            forceSyncUntil = now + 30000L;
            lastPeriodicMs = now;
            diagUntil = now + 30000L;   // v1.22.8：切维度后 30 秒只读诊断
            lastDiagAt = 0L;
            requestSync(lastWornUuid);
        } else if (now < forceSyncUntil) {
            requestSync(lastWornUuid);
        } else if (now - lastPeriodicMs >= 5000L) {
            lastPeriodicMs = now;
            if (lastWornUuid != null && contentsEmpty(lastWornUuid)) {
                requestSync(lastWornUuid);
            }
        }
        // 【只读诊断】切维度后 30 秒，每 2 秒看一眼：客户端镜像有没有内容、
        // Litematica 自己的统计口径（getInventoryItemCounts）认不认背包、我们自己的快照认不认。
        if (now < diagUntil && now - lastDiagAt >= 2000L) {
            lastDiagAt = now;
            logDiagnostics(client);
        }
    }

    /** 镜像有内容 ⇒ 缓存（2 秒最多拷一次，背包 NBT 可能很大）；镜像被清空 ⇒ 用上次内容补回去。 */
    private static void keepOrRestoreCopy(UUID uuid) {
        try {
            NbtCompound current = BackpackStorage.get().getOrCreateBackpackContents(uuid);
            long now = System.currentTimeMillis();
            if (current != null && !current.isEmpty()) {
                if (now - lastCopyCacheMs >= 2000L) {
                    lastCopyCacheMs = now;
                    lastGoodCopy.put(uuid, current.copy());
                }
                return;
            }
            NbtCompound cached = lastGoodCopy.get(uuid);
            if (cached != null) {
                BackpackStorage.get().setBackpackContents(uuid, cached.copy());
                LOGGER.info("[sbp] 镜像被清空，已用上次内容兜底（{} 个键）uuid={}", cached.getSize(), uuid);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logDiagnostics(MinecraftClient client) {
        try {
            UUID uuid = lastWornUuid;
            boolean copyHas = uuid != null && !contentsEmpty(uuid);
            int copyKeys = 0;
            if (uuid != null) {
                NbtCompound contents = BackpackStorage.get().getOrCreateBackpackContents(uuid);
                copyKeys = contents == null ? 0 : contents.getSize();
            }
            int sticksApi = countApi(client, net.minecraft.item.Items.STICK);
            int planksApi = countApi(client, net.minecraft.item.Items.ACACIA_PLANKS);
            Map<Item, Long> snapshot = snapshot(client.player);
            LOGGER.info("[sbp-diag] 镜像={}（{} 个键）｜Litematica 统计算术(木棍)={}、金合欢木板={}｜SBP 快照(木棍)={}、金合欢木板={}",
                    copyHas ? "有" : "空", copyKeys,
                    sticksApi, planksApi,
                    snapshot.getOrDefault(net.minecraft.item.Items.STICK, 0L),
                    snapshot.getOrDefault(net.minecraft.item.Items.ACACIA_PLANKS, 0L));
        } catch (Throwable t) {
            LOGGER.warn("[sbp-diag] 诊断输出失败：{}", t.toString());
        }
    }

    private static int countApi(MinecraftClient client, Item item) {
        try {
            var counts = fi.dy.masa.litematica.materials.MaterialListUtils
                    .getInventoryItemCounts(client.player.getInventory());
            return counts.getInt(new fi.dy.masa.malilib.util.ItemType(new ItemStack(item), true, false));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 发一次 SBP 自己的「背包内容同步请求」（2 秒节流）。 */
    private static void requestSync(UUID uuid) {
        if (uuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastRequestAt.get(uuid);
        if (last != null && now - last < 2000L) {
            return;
        }
        lastRequestAt.put(uuid, now);
        try {
            ClientPlayNetworking.send(new RequestBackpackInventoryContentsPayload(uuid));
            LOGGER.info("[sbp] 请求同步穿戴背包内容（切维度/镜像为空）uuid={}", uuid);
        } catch (Throwable ignored) {
        }
    }

    private static boolean contentsEmpty(UUID uuid) {
        try {
            NbtCompound contents = BackpackStorage.get().getOrCreateBackpackContents(uuid);
            return contents == null || contents.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 找穿戴在 trinkets 槽里的背包 UUID（找不到返回 null）。 */
    private static UUID findWornUuid(MinecraftClient client) {
        UUID[] found = {null};
        try {
            PlayerInventoryProvider.get().runOnBackpacks(client.player,
                    (backpackStack, invIdentifier, identifier, slot) -> {
                        if (invIdentifier == null || !"trinkets".equals(invIdentifier)) {
                            return false;   // 只处理穿戴的那一个（携带在主背包里的切维度不受影响）
                        }
                        try {
                            IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpackStack);
                            Optional<UUID> uuid = contentsUuid(wrapper);
                            if (uuid.isPresent()) {
                                found[0] = uuid.get();
                                return true;   // 找到一个就够了
                            }
                        } catch (Throwable ignored) {
                        }
                        return false;
                    });
        } catch (Throwable ignored) {
        }
        return found[0];
    }

    /** `BackpackWrapper.getContentsUuid()`（IBackpackWrapper 上没有 ⇒ 对它反射）。 */
    private static Optional<UUID> contentsUuid(IBackpackWrapper wrapper) {
        try {
            Method method = wrapper.getClass().getMethod("getContentsUuid");
            Object value = method.invoke(wrapper);
            if (value instanceof Optional<?> optional && optional.isPresent()
                    && optional.get() instanceof UUID uuid) {
                return Optional.of(uuid);
            }
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");
    private static String lastDimension;
    private static long lastPeriodicMs = 0L;
    /** 切维度后强制重试的截止时间（30 秒内每 2 秒发一次）。 */
    private static long forceSyncUntil = 0L;
    /** 【只读诊断】切维度后 60 秒内的诊断截止时间 / 上次输出时刻。 */
    private static long diagUntil = 0L;
    private static long lastDiagAt = 0L;
    /** 上一次找到的穿戴背包 UUID（切维度刚开始 trinket 数据没同步时用它兜底）。 */
    private static UUID lastWornUuid;
    /** 每个背包最后一次「非空」的客户端镜像内容；镜像被人清空时用它补回去（v1.22.9）。 */
    private static final Map<UUID, NbtCompound> lastGoodCopy = new HashMap<>();
    private static long lastCopyCacheMs = 0L;
    private static final Map<UUID, Long> lastRequestAt = new HashMap<>();
}
