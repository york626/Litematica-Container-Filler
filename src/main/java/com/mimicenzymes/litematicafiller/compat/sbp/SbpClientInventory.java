package com.mimicenzymes.litematicafiller.compat.sbp;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.inventory.ITrackedContentsItemHandler;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

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
}
