package com.mimicenzymes.litematicafiller.compat.sbp;

import java.lang.reflect.Method;
import java.util.HashMap;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.util.collection.DefaultedList;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.inventory.ITrackedContentsItemHandler;
import net.p3pp3rf1y.sophisticatedcore.util.InventoryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【快捷精妙背包提取】服务端：把 N 个物品从玩家佩戴/手持的精妙背包抽到玩家背包。
 *
 * <p>逻辑沿用打印机兼容里验证过的做法（先模拟、再真取；背包满时先回存腾位；绝不掉地上）。
 */
public final class SbpServerExtract {
    private SbpServerExtract() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    /** 最近被请求过的物品：腾位时不许把它们回存（防边取边存死循环）。 */
    private static final int RECENT_PROTECT_TICKS = 600;
    private static final Map<UUID, Map<Item, Long>> recentRequests = new HashMap<>();

    public static void extract(ServerPlayerEntity player, Identifier itemId, int count) {
        boolean success = false;
        try {
            Item item = Registries.ITEM.get(itemId);
            if (item != null) {
                remember(player, item);
                success = extractFromAnyBackpack(player, item, Math.max(1, count));
            }
        } catch (Throwable error) {
            LOGGER.warn("[sbp] 取料 {} 失败", itemId, error);
        }
        ServerPlayNetworking.send(player, new SbpPayloads.ExtractResponse(itemId, success));
    }

    private static void remember(ServerPlayerEntity player, Item item) {
        long now = player.getWorld().getTime();
        Map<Item, Long> requests = recentRequests.computeIfAbsent(player.getUuid(), uuid -> new HashMap<>());
        requests.put(item, now);
        requests.values().removeIf(tick -> now - tick > RECENT_PROTECT_TICKS);
    }

    private static boolean recentlyRequested(ServerPlayerEntity player, Item item) {
        Map<Item, Long> requests = recentRequests.get(player.getUuid());
        if (requests == null) {
            return false;
        }
        Long tick = requests.get(item);
        return tick != null && player.getWorld().getTime() - tick <= RECENT_PROTECT_TICKS;
    }

    private static boolean extractFromAnyBackpack(ServerPlayerEntity player, Item item, int count) {
        int capacity = capacity(player, item);
        if (capacity <= 0) {
            makeRoom(player, item);
            capacity = capacity(player, item);
            if (capacity <= 0) {
                LOGGER.info("[sbp] 个人背包已满且腾不出位置，取料 {} 失败", Registries.ITEM.getId(item));
                return false;
            }
        }
        int toExtract = Math.min(Math.max(count, item.getMaxCount()), capacity);

        boolean[] extracted = {false};
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, invIdentifier, identifier, slot) -> {
            IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpackStack);
            ITrackedContentsItemHandler handler = wrapper.getInventoryForUpgradeProcessing();

            ItemStack simulated = InventoryHelper.extractFromInventory(item, toExtract, handler, true);
            if (simulated.isEmpty()) {
                // 背包里没有散装材料 ⇒ 看看是不是装在某个潜影盒里（2026-11-04 Ray 报：
                // 材料装潜影盒、潜影盒放背包 ⇒ 填充机认不出）
                int nested = extractFromShulkerInsideBackpack(player, item, handler, toExtract);
                if (nested > 0) {
                    extracted[0] = true;
                    LOGGER.info("[sbp] 已从背包内潜影盒取出 {} x{}", Registries.ITEM.getId(item), nested);
                    return true;
                }
                return false;
            }
            ItemStack moved = InventoryHelper.extractFromInventory(item, simulated.getCount(), handler, false);
            if (moved.isEmpty()) {
                return false;
            }
            ItemStack remaining = InventoryHelper.mergeIntoPlayerInventory(player, moved, 0);
            if (!remaining.isEmpty()) {
                ItemStack leftover = InventoryHelper.insertIntoInventory(remaining, handler, false);
                if (!leftover.isEmpty()) {
                    player.dropItem(leftover, false);
                }
            }
            extracted[0] = true;
            LOGGER.info("[sbp] 已从背包槽位{} 取出 {} x{}", slot, Registries.ITEM.getId(item), moved.getCount());
            return true;
        });
        return extracted[0];
    }

    private static Method slotCountGetter;
    private static Method slotStackGetter;
    private static Method slotStackSetter;

    /**
     * 从"背包里的潜影盒"中取材料：直接改盒内内容，把材料塞进玩家背包。
     *
     * <p>用反射调 {@code InventoryHandler.getSlotStack / setSlotStack}（精妙 jar 是 intermediary 命名）。
     */
    @SuppressWarnings("unchecked")
    private static int extractFromShulkerInsideBackpack(ServerPlayerEntity player, Item item,
                                                        ITrackedContentsItemHandler handler, int limit) {
        if (limit <= 0) {
            return 0;
        }
        try {
            if (slotStackGetter == null || slotStackSetter == null) {
                Class<?> handlerClass = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler");
                slotCountGetter = handlerClass.getMethod("getSlotCount");
                slotStackGetter = handlerClass.getMethod("getSlotStack", int.class);
                slotStackSetter = handlerClass.getMethod("setSlotStack", int.class, ItemStack.class);
            }
            int slots = (Integer) slotCountGetter.invoke(handler);
            int collected = 0;
            for (int slot = 0; slot < slots && collected < limit; slot++) {
                ItemStack shulker = (ItemStack) slotStackGetter.invoke(handler, slot);
                if (shulker == null || shulker.isEmpty() || !isShulker(shulker)) {
                    continue;
                }
                ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
                if (container == null) {
                    continue;
                }
                DefaultedList<ItemStack> contents = DefaultedList.ofSize(27, ItemStack.EMPTY);
                container.copyTo(contents);
                int taken = 0;
                for (int i = 0; i < contents.size() && collected + taken < limit; i++) {
                    ItemStack inner = contents.get(i);
                    if (inner.isEmpty() || !inner.isOf(item)) {
                        continue;
                    }
                    int take = Math.min(inner.getCount(), limit - collected - taken);
                    inner.decrement(take);
                    if (inner.isEmpty()) {
                        contents.set(i, ItemStack.EMPTY);
                    }
                    taken += take;
                }
                if (taken <= 0) {
                    continue;
                }
                ItemStack updated = shulker.copy();
                updated.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
                slotStackSetter.invoke(handler, slot, updated);

                ItemStack give = new ItemStack(item, taken);
                ItemStack leftover = InventoryHelper.mergeIntoPlayerInventory(player, give, 0);
                collected += taken - (leftover == null ? 0 : leftover.getCount());
                if (leftover != null && !leftover.isEmpty()) {
                    // 背包空间按 capacity 事先算过，理论上不该有残留 —— 放回盒里
                    innerReturn(shulker, item, leftover.getCount());
                }
            }
            return collected;
        } catch (Throwable error) {
            LOGGER.warn("[sbp] 从背包内潜影盒取料失败", error);
            return 0;
        }
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** 兜底：把没塞进背包的残留放回原潜影盒（理论上不可达）。 */
    private static void innerReturn(ItemStack shulker, Item item, int count) {
        try {
            ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
            if (container == null) {
                return;
            }
            DefaultedList<ItemStack> contents = DefaultedList.ofSize(27, ItemStack.EMPTY);
            container.copyTo(contents);
            for (int i = 0; i < contents.size(); i++) {
                if (contents.get(i).isEmpty()) {
                    contents.set(i, new ItemStack(item, count));
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 放不回去就算了，绝不掉地上以外的手段
        }
    }

    private static int capacity(ServerPlayerEntity player, Item item) {
        int capacity = 0;
        for (int slot = 0; slot < 36 && slot < player.getInventory().main.size(); slot++) {
            ItemStack stack = player.getInventory().main.get(slot);
            if (stack.isEmpty()) {
                capacity += item.getMaxCount();
            } else if (stack.isOf(item)) {
                capacity += Math.max(0, stack.getMaxCount() - stack.getCount());
            }
        }
        return capacity;
    }

    /** 主背包（9..35）里最大的一堆，回存进背包腾位置。 */
    private static void makeRoom(ServerPlayerEntity player, Item neededItem) {
        for (int slot = 9; slot < 36 && slot < player.getInventory().main.size(); slot++) {
            ItemStack stack = player.getInventory().main.get(slot);
            if (stack.isEmpty() || stack.isOf(neededItem) || recentlyRequested(player, stack.getItem())) {
                continue;
            }
            Item item = stack.getItem();
            int count = stack.getCount();
            if (insertIntoBackpacks(player, item, count) > 0) {
                player.getInventory().removeStack(slot, count);
                LOGGER.info("[sbp] 背包已满，回存 {} x{} 腾位置", Registries.ITEM.getId(item), count);
                return;
            }
        }
    }

    private static int insertIntoBackpacks(ServerPlayerEntity player, Item item, int count) {
        int[] moved = {0};
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, invIdentifier, identifier, slot) -> {
            ITrackedContentsItemHandler handler =
                    BackpackWrapper.fromStack(backpackStack).getInventoryForUpgradeProcessing();
            ItemStack leftover = InventoryHelper.insertIntoInventory(new ItemStack(item, count - moved[0]), handler, false);
            moved[0] = count - leftover.getCount();
            return moved[0] >= count;
        });
        return moved[0];
    }
}
