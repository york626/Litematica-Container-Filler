package com.mimicenzymes.litematicafiller.compat.sbp;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.inventory.ITrackedContentsItemHandler;
import net.p3pp3rf1y.sophisticatedcore.util.InventoryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【精妙背包支持】服务端。
 *
 * <p>两个方向：
 * <ul>
 *   <li>{@link #extract}：材料在背包里 → 搬到玩家背包。<b>背包里没有散装时，会把"装着它的潜影盒"
 *       整盒搬到玩家背包</b>，之后交给填充机原生的「快捷潜影盒提取」去开盒取物
 *       （Ray 2026-11-04 指定的行为：盒装材料优先整盒搬出来，与玩家背包里的潜影盒走同一条路）。</li>
 *   <li>{@link #store}：背包满时把多余物品塞回精妙背包（"有序存放"的背包版）。</li>
 * </ul>
 * 空间不足时回 reason=NO_SPACE，客户端会提示玩家清理背包。
 *
 * <p>精妙 jar 是 intermediary 命名，槽位读写用反射（{@code InventoryHandler.getSlotCount/getSlotStack/extractItem}）。
 */
public final class SbpServerExtract {
    private SbpServerExtract() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    /** 容器内容缓冲：原版潜影盒 27 格，但「增强潜影盒」下界合金/钻石是 108 格 ⇒ 不能按 27 写死（2026-11-04 踩过）。 */
    private static final int CONTAINER_BUFFER = 256;
    private static final int RECENT_PROTECT_TICKS = 600;
    private static final Map<UUID, Map<Item, Long>> recentRequests = new HashMap<>();

    private static Method slotCountGetter;
    private static Method slotStackGetter;
    private static Method extractSlotItem;

    // ===================== 取货（背包 → 玩家背包） =====================

    public static void extract(ServerPlayerEntity player, Identifier itemId, int count) {
        int reason = SbpPayloads.NOT_FOUND;
        try {
            Item item = Registries.ITEM.get(itemId);
            if (item != null) {
                remember(player, item);
                reason = doExtract(player, item, Math.max(1, count));
            }
        } catch (Throwable error) {
            LOGGER.warn("[sbp] 取料 {} 失败", itemId, error);
            reason = SbpPayloads.NOT_FOUND;
        }
        ServerPlayNetworking.send(player, new SbpPayloads.Result(itemId, reason));
    }

    private static int doExtract(ServerPlayerEntity player, Item item, int count) {
        int capacity = capacity(player, item);
        if (capacity <= 0) {
            makeRoom(player, item);
            capacity = capacity(player, item);
            if (capacity <= 0) {
                LOGGER.info("[sbp] 玩家背包已满，取料 {} 失败", Registries.ITEM.getId(item));
                return SbpPayloads.NO_SPACE;
            }
        }

        // ① 背包里有散装材料：直接抽
        int toExtract = Math.min(Math.max(count, item.getMaxCount()), capacity);
        boolean[] extracted = {false};
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, invIdentifier, identifier, slot) -> {
            ITrackedContentsItemHandler handler = handlerOf(backpackStack);
            ItemStack simulated = InventoryHelper.extractFromInventory(item, toExtract, handler, true);
            if (simulated.isEmpty()) {
                return false;
            }
            ItemStack moved = InventoryHelper.extractFromInventory(item, simulated.getCount(), handler, false);
            if (moved.isEmpty()) {
                return false;
            }
            ItemStack remaining = InventoryHelper.mergeIntoPlayerInventory(player, moved, 0);
            if (!remaining.isEmpty()) {
                return false;
            }
            extracted[0] = true;
            LOGGER.info("[sbp] 已从背包取 {} x{}", Registries.ITEM.getId(item), moved.getCount());
            return true;
        });
        if (extracted[0]) {
            return SbpPayloads.OK;
        }

        // ② 没有散装 ⇒ 把"装着该材料的潜影盒"整盒搬到玩家背包（之后交给填充机原生的快捷潜影盒提取）
        return moveShulkerContaining(player, item);
    }

    /** 在精妙背包里找"含该材料的潜影盒"，整盒搬到玩家背包。 */
    private static int moveShulkerContaining(ServerPlayerEntity player, Item item) {
        int[] result = {SbpPayloads.NOT_FOUND};
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, invIdentifier, identifier, slot) -> {
            ITrackedContentsItemHandler handler = handlerOf(backpackStack);
            Integer shulkerSlot = findShulkerSlot(handler, item);
            if (shulkerSlot == null) {
                return false;
            }
            try {
                ItemStack simulated = (ItemStack) extractSlotItem.invoke(handler, shulkerSlot, 1, true);
                if (simulated == null || simulated.isEmpty()) {
                    return false;
                }
                if (!hasRoomFor(player, simulated)) {
                    LOGGER.info("[sbp] 玩家背包没有 1 格空间放下潜影盒 ⇒ 请清理背包");
                    result[0] = SbpPayloads.NO_SPACE;
                    return true; // 停止扫描：明确是空间问题
                }
                ItemStack moved = (ItemStack) extractSlotItem.invoke(handler, shulkerSlot, 1, false);
                if (moved == null || moved.isEmpty()) {
                    return false;
                }
                ItemStack leftover = InventoryHelper.mergeIntoPlayerInventory(player, moved, 0);
                if (leftover != null && !leftover.isEmpty()) {
                    InventoryHelper.insertIntoInventory(leftover, handler, false);
                    result[0] = SbpPayloads.NO_SPACE;
                    return true;
                }
                LOGGER.info("[sbp] 已把装着 {} 的潜影盒搬到玩家背包（背包槽位{}）", Registries.ITEM.getId(item), slot);
                result[0] = SbpPayloads.OK;
                return true;
            } catch (Throwable error) {
                LOGGER.warn("[sbp] 搬潜影盒失败", error);
                return false;
            }
        });
        return result[0];
    }

    private static Integer findShulkerSlot(ITrackedContentsItemHandler handler, Item item) {
        try {
            ensureReflection();
            int slots = (Integer) slotCountGetter.invoke(handler);
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = (ItemStack) slotStackGetter.invoke(handler, slot);
                if (stack == null || stack.isEmpty() || !isShulker(stack)) {
                    continue;
                }
                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container == null) {
                    continue;
                }
                DefaultedList<ItemStack> contents = DefaultedList.ofSize(CONTAINER_BUFFER, ItemStack.EMPTY);
                container.copyTo(contents);
                for (ItemStack inner : contents) {
                    if (!inner.isEmpty() && inner.isOf(item)) {
                        return slot;
                    }
                }
            }
        } catch (Throwable error) {
            LOGGER.warn("[sbp] 扫描背包内潜影盒失败", error);
        }
        return null;
    }

    // ===================== 回存（玩家背包 → 精妙背包） =====================

    public static void store(ServerPlayerEntity player, Identifier itemId, int count) {
        int reason = SbpPayloads.NOT_FOUND;
        try {
            Item item = Registries.ITEM.get(itemId);
            if (item != null) {
                int want = Math.max(1, count);
                // 「有序存放」优先：塞进背包里**已经装着同种物品**的潜影盒
                int moved = insertIntoMatchingShulker(player, item, want);
                if (moved < want) {
                    moved += insertIntoBackpacks(player, item, want - moved);
                }
                if (moved > 0) {
                    int taken = removeFromPlayerInventory(player, item, moved);
                    LOGGER.info("[sbp] 已把 {} x{} 存回精妙背包", itemId, taken);
                    reason = SbpPayloads.OK;
                } else {
                    reason = SbpPayloads.NO_SPACE;
                }
            }
        } catch (Throwable error) {
            LOGGER.warn("[sbp] 回存 {} 失败", itemId, error);
        }
        ServerPlayNetworking.send(player, new SbpPayloads.Result(itemId, reason));
    }

    private static Method slotStackSetter;

    /**
     * 「有序存放」的背包版：把物品塞进背包里**已经装着同种物品**的潜影盒（先补未满的堆，再放空格）。
     * 找不到同内容盒就返回 0，由调用方退回"散放进背包"。
     */
    private static int insertIntoMatchingShulker(ServerPlayerEntity player, Item item, int count) {
        int[] moved = {0};
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, invIdentifier, identifier, slot) -> {
            ITrackedContentsItemHandler handler = handlerOf(backpackStack);
            try {
                ensureReflection();
                if (slotStackSetter == null) {
                    slotStackSetter = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler")
                            .getMethod("setSlotStack", int.class, ItemStack.class);
                }
                int slots = (Integer) slotCountGetter.invoke(handler);
                for (int i = 0; i < slots && moved[0] < count; i++) {
                    ItemStack shulker = (ItemStack) slotStackGetter.invoke(handler, i);
                    if (shulker == null || shulker.isEmpty() || !isShulker(shulker)) {
                        continue;
                    }
                    ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
                    if (container == null) {
                        continue;
                    }
                    DefaultedList<ItemStack> contents = DefaultedList.ofSize(CONTAINER_BUFFER, ItemStack.EMPTY);
                    container.copyTo(contents);

                    // 先确认这个盒子里确实有同种物品（有序存放的"同类归位"）
                    boolean sameContent = false;
                    for (ItemStack inner : contents) {
                        if (!inner.isEmpty() && inner.isOf(item)) {
                            sameContent = true;
                            break;
                        }
                    }
                    if (!sameContent) {
                        continue;
                    }

                    int placed = 0;
                    // ① 补未满的同类堆
                    for (int i2 = 0; i2 < contents.size() && placed < count - moved[0]; i2++) {
                        ItemStack inner = contents.get(i2);
                        if (inner.isEmpty() || !inner.isOf(item) || inner.getCount() >= inner.getMaxCount()) {
                            continue;
                        }
                        int add = Math.min(inner.getMaxCount() - inner.getCount(), count - moved[0] - placed);
                        inner.increment(add);
                        placed += add;
                    }
                    // ② 再放空格
                    for (int i2 = 0; i2 < contents.size() && placed < count - moved[0]; i2++) {
                        if (!contents.get(i2).isEmpty()) {
                            continue;
                        }
                        int add = Math.min(item.getMaxCount(), count - moved[0] - placed);
                        contents.set(i2, new ItemStack(item, add));
                        placed += add;
                    }
                    if (placed <= 0) {
                        continue;
                    }
                    ItemStack updated = shulker.copy();
                    updated.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
                    slotStackSetter.invoke(handler, i, updated);
                    moved[0] += placed;
                    LOGGER.info("[sbp] 有序存放：{} x{} → 背包里的同类潜影盒", Registries.ITEM.getId(item), placed);
                }
            } catch (Throwable error) {
                LOGGER.warn("[sbp] 有序存放（同类潜影盒）失败", error);
            }
            return moved[0] >= count;
        });
        return moved[0];
    }

    private static int insertIntoBackpacks(ServerPlayerEntity player, Item item, int count) {
        int[] moved = {0};
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, invIdentifier, identifier, slot) -> {
            ITrackedContentsItemHandler handler = handlerOf(backpackStack);
            int remaining = count - moved[0];
            if (remaining <= 0) {
                return true;
            }
            ItemStack leftover = InventoryHelper.insertIntoInventory(new ItemStack(item, remaining), handler, false);
            moved[0] += remaining - (leftover == null ? 0 : leftover.getCount());
            return moved[0] >= count;
        });
        return moved[0];
    }

    private static int removeFromPlayerInventory(ServerPlayerEntity player, Item item, int count) {
        int removed = 0;
        for (int slot = 0; slot < player.getInventory().main.size() && removed < count; slot++) {
            ItemStack stack = player.getInventory().main.get(slot);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            int take = Math.min(stack.getCount(), count - removed);
            player.getInventory().removeStack(slot, take);
            removed += take;
        }
        return removed;
    }

    // ===================== 工具 =====================

    private static void ensureReflection() throws ReflectiveOperationException {
        if (slotCountGetter != null && slotStackGetter != null && extractSlotItem != null) {
            return;
        }
        Class<?> handlerClass = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler");
        slotCountGetter = handlerClass.getMethod("getSlotCount");
        slotStackGetter = handlerClass.getMethod("getSlotStack", int.class);
        extractSlotItem = handlerClass.getMethod("extractItem", int.class, int.class, boolean.class);
    }

    private static ITrackedContentsItemHandler handlerOf(ItemStack backpackStack) {
        IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpackStack);
        return wrapper.getInventoryForUpgradeProcessing();
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** 玩家主背包里能不能再放下这一堆（空格或同类未满堆）。 */
    private static boolean hasRoomFor(ServerPlayerEntity player, ItemStack stack) {
        for (int slot = 0; slot < player.getInventory().main.size(); slot++) {
            ItemStack existing = player.getInventory().main.get(slot);
            if (existing.isEmpty()) {
                return true;
            }
            if (existing.isOf(stack.getItem()) && existing.getCount() < existing.getMaxCount()) {
                return true;
            }
        }
        return false;
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
                removeFromPlayerInventory(player, item, count);
                LOGGER.info("[sbp] 背包已满，回存 {} x{} 腾位置", Registries.ITEM.getId(item), count);
                return;
            }
        }
    }
}
