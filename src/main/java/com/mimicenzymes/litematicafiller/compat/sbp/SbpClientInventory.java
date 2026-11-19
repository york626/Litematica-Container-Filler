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
        if (dimensionChanged) {
            lastPeriodicMs = now;
            requestWornSync(client);
        } else if (now - lastPeriodicMs >= 5000L) {
            lastPeriodicMs = now;
            requestWornSync(client, true);
        }
    }

    private static void requestWornSync(MinecraftClient client) {
        requestWornSync(client, false);
    }

    /**
     * @param onlyIfEmpty true = 只在客户端镜像为空时请求（平时兜底）；false = 强制请求（切维度时）
     */
    private static void requestWornSync(MinecraftClient client, boolean onlyIfEmpty) {
        try {
            PlayerInventoryProvider.get().runOnBackpacks(client.player,
                    (backpackStack, invIdentifier, identifier, slot) -> {
                        if (invIdentifier == null || !"trinkets".equals(invIdentifier)) {
                            return false;   // 只处理穿戴的那一个（携带的在主背包里，切维度不受影响）
                        }
                        try {
                            IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpackStack);
                            Optional<UUID> uuid = contentsUuid(wrapper);
                            if (uuid.isEmpty()) {
                                return false;
                            }
                            if (onlyIfEmpty) {
                                NbtCompound contents = BackpackStorage.get()
                                        .getOrCreateBackpackContents(uuid.get());
                                if (contents != null && !contents.isEmpty()) {
                                    return false;
                                }
                            }
                            long now = System.currentTimeMillis();
                            Long last = lastRequestAt.get(uuid.get());
                            if (last != null && now - last < 3000L) {
                                return false;
                            }
                            lastRequestAt.put(uuid.get(), now);
                            ClientPlayNetworking.send(
                                    new RequestBackpackInventoryContentsPayload(uuid.get()));
                            LOGGER.info("[sbp] 请求同步穿戴背包内容（切维度/镜像为空）uuid={}", uuid.get());
                        } catch (Throwable ignored) {
                        }
                        return false;
                    });
        } catch (Throwable ignored) {
        }
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
    private static final Map<UUID, Long> lastRequestAt = new HashMap<>();
}
