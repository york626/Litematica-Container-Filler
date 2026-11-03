package com.mimicenzymes.litematicafiller.compat.sbp;

import java.util.HashMap;
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
