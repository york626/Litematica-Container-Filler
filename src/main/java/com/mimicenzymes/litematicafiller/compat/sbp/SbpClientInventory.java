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
import java.util.Set;
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

        // Ray 2026-11-19：身上**穿戴**一个、玩家背包里还**携带**一个精妙背包 ⇒ 两个都要同步，
        // 只处理 trinkets 会把携带那个漏掉（材料列表统计两个都算）。
        Set<UUID> uuids = findBackpackUuids(client);
        if (!uuids.isEmpty()) {
            lastBackpackUuids = uuids;
        }

        boolean restoredAny = false;
        for (UUID uuid : lastBackpackUuids) {
            restoredAny |= keepOrRestoreCopy(uuid);
        }

        if (dimensionChanged) {
            forceSyncUntil = now + 30000L;
            lastPeriodicMs = now;
            diagUntil = now + 30000L;
            lastDiagAt = 0L;
            requestSyncAll();
            // 切维度后先按现有（可能是缓存兜底的）内容刷一次，别等下一次自然重算
            refreshMaterialListCounts(client);
        } else if (now < forceSyncUntil) {
            requestSyncAll();
        } else if (now - lastPeriodicMs >= 5000L) {
            lastPeriodicMs = now;
            for (UUID uuid : lastBackpackUuids) {
                if (contentsEmpty(uuid)) {
                    requestSync(uuid);
                }
            }
        }
        // 兜底补回内容之后，主动让材料列表按新内容重算一次（v1.22.10 的触发条件之前写错了：
        // 兜底发生在同一 tick，检测不到“空→有”；现在直接看恢复动作有没有发生）。
        if (restoredAny) {
            refreshMaterialListCounts(client);
        }
        if (now < diagUntil && now - lastDiagAt >= 2000L) {
            lastDiagAt = now;
            logDiagnostics(client);
        }
    }

    private static void requestSyncAll() {
        for (UUID uuid : lastBackpackUuids) {
            requestSync(uuid);
        }
    }

    /** 主动让材料列表的行按当前背包内容重算（只在镜像恢复时调，1 秒最多一次）。 */
    private static void refreshMaterialListCounts(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastListRefreshMs < 1000L) {
            return;
        }
        lastListRefreshMs = now;
        try {
            fi.dy.masa.litematica.materials.MaterialListBase list =
                    fi.dy.masa.litematica.data.DataManager.getMaterialList();
            if (list == null || client.player == null) {
                return;
            }
            fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(
                    list.getMaterialsAll(), client.player);
            list.recreateFilteredList();
            LOGGER.info("[sbp] 已强制刷新材料列表计数（背包镜像恢复后）");
        } catch (Throwable ignored) {
        }
    }

    /** 镜像有内容 ⇒ 缓存（2 秒最多拷一次，背包 NBT 可能很大）；镜像被清空 ⇒ 用上次内容补回去。
     *  @return true = 发生了“兜底补回” */
    private static boolean keepOrRestoreCopy(UUID uuid) {
        try {
            NbtCompound current = BackpackStorage.get().getOrCreateBackpackContents(uuid);
            long now = System.currentTimeMillis();
            if (current != null && !current.isEmpty()) {
                if (now - lastCopyCacheMs >= 2000L) {
                    lastCopyCacheMs = now;
                    lastGoodCopy.put(uuid, current.copy());
                }
                return false;
            }
            NbtCompound cached = lastGoodCopy.get(uuid);
            if (cached != null) {
                BackpackStorage.get().setBackpackContents(uuid, cached.copy());
                LOGGER.info("[sbp] 镜像被清空，已用上次内容兜底（{} 个键）uuid={}", cached.getSize(), uuid);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void logDiagnostics(MinecraftClient client) {
        try {
            int total = lastBackpackUuids.size();
            int withContent = 0;
            int keys = 0;
            for (UUID uuid : lastBackpackUuids) {
                try {
                    NbtCompound contents = BackpackStorage.get().getOrCreateBackpackContents(uuid);
                    if (contents != null && !contents.isEmpty()) {
                        withContent++;
                        keys += contents.getSize();
                    }
                } catch (Throwable ignored) {
                }
            }
            int sticksApi = countApi(client, net.minecraft.item.Items.STICK);
            int planksApi = countApi(client, net.minecraft.item.Items.ACACIA_PLANKS);
            Map<Item, Long> snapshot = snapshot(client.player);
            int rowAvailable = -1;
            int rowMissing = -1;
            String listName = "?";
            int listRows = -1;
            try {
                fi.dy.masa.litematica.materials.MaterialListBase list =
                        fi.dy.masa.litematica.data.DataManager.getMaterialList();
                if (list != null) {
                    listName = String.valueOf(list.getName());
                    listRows = list.getMaterialsAll().size();
                    for (fi.dy.masa.litematica.materials.MaterialListEntry entry : list.getMaterialsAll()) {
                        if (entry != null && entry.getStack().getItem() == net.minecraft.item.Items.ACACIA_PLANKS) {
                            rowAvailable = entry.getCountAvailable();
                            rowMissing = entry.getCountMissing();
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            LOGGER.info("[sbp-diag] 背包={}/{} 有内容（共 {} 键）｜Litematica 统计(木棍)={}、金合欢木板={}｜SBP 快照(木棍)={}、金合欢木板={}｜清单「{}」{} 行，清单行(金合欢木板) 可用={} 缺={}",
                    withContent, total, keys,
                    sticksApi, planksApi,
                    snapshot.getOrDefault(net.minecraft.item.Items.STICK, 0L),
                    snapshot.getOrDefault(net.minecraft.item.Items.ACACIA_PLANKS, 0L),
                    listName, listRows, rowAvailable, rowMissing);
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

    /** 收集**所有**精妙背包（穿戴 + 携带）的内容 UUID。 */
    private static Set<UUID> findBackpackUuids(MinecraftClient client) {
        Set<UUID> found = new java.util.HashSet<>();
        try {
            PlayerInventoryProvider.get().runOnBackpacks(client.player,
                    (backpackStack, invIdentifier, identifier, slot) -> {
                        try {
                            IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpackStack);
                            contentsUuid(wrapper).ifPresent(found::add);
                        } catch (Throwable ignored) {
                        }
                        return false;
                    });
        } catch (Throwable ignored) {
        }
        return found;
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
    /** 上一次找到的全部背包 UUID（穿戴 + 携带；切维度刚开始数据没同步时用它兜底）。 */
    private static Set<UUID> lastBackpackUuids = Set.of();
    /** 每个背包最后一次「非空」的客户端镜像内容；镜像被人清空时用它补回去（v1.22.9）。 */
    private static final Map<UUID, NbtCompound> lastGoodCopy = new HashMap<>();
    private static long lastCopyCacheMs = 0L;
    private static long lastListRefreshMs = 0L;
    private static final Map<UUID, Long> lastRequestAt = new HashMap<>();
}
