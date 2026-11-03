package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.compat.sbp.SbpSupport;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.ItemType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.compat.litematica.LitematicaCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 材料列表把「精妙背包里的潜影盒/收纳袋内容」也算进"已有"。
 *
 * <p>根因：上游精妙核心（sophisticatedcore）的 Litematica 兼容只遍历背包**每一格**并计入统计，
 * <b>不会展开背包里的原版潜影盒</b>（原版展开只对玩家 36 格顶层生效）⇒ 盒子在背包里时内容物被判缺失。
 *
 * <p>做法：{@code getInventoryItemCounts} 返回前，再遍历一遍背包里的精妙存储，对其中原版容器
 * 调 {@code getStoredItemCounts} / {@code getBundleItemCounts} 把内容物加回去。
 *
 * <p>用接口反射读精妙存储：{@code libs/} 里的精妙 jar 是 intermediary 命名，编译期直接调
 * {@code ITrackedContentsItemHandler} 的方法会解析不到 MC 类；反射可绕开。
 */
@Mixin(value = MaterialListUtils.class, remap = false)
public abstract class SbpMaterialListUtilsMixin {
    private static final String STORAGE_IFACE = "net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper";
    private static final String HANDLER_CLASS = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler";

    @Inject(method = "getInventoryItemCounts", at = @At("RETURN"), require = 0)
    private static void lcf$countContainerContentsInBackpacks(
            Inventory inv, CallbackInfoReturnable<Object2IntOpenHashMap<ItemType>> cir) {
        if (!SbpSupport.available() || !Configs.ENABLE_SBP_SHULKER_COUNT.getBooleanValue()) {
            return;
        }
        Object2IntOpenHashMap<ItemType> counts = cir.getReturnValue();
        if (counts == null || inv == null) {
            return;
        }
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            try {
                Optional<?> wrapper = LitematicaCompat.getWrapper(stack);
                if (wrapper.isEmpty()) {
                    continue;
                }
                Object storage = wrapper.get().getClass().getMethod("wrapper").invoke(wrapper.get());
                lcf$addStorageContents(counts, storage);
            } catch (Throwable ignored) {
                // 内容还没同步 / API 变动 —— 跳过这一格
            }
        }
    }

    private static void lcf$addStorageContents(Object2IntOpenHashMap<ItemType> counts, Object storage)
            throws ReflectiveOperationException {
        Class<?> storageIface = Class.forName(STORAGE_IFACE);
        storageIface.getMethod("onContentsNbtUpdated").invoke(storage);
        Object handler = storageIface.getMethod("getInventoryHandler").invoke(storage);
        if (handler == null) {
            return;
        }
        Class<?> handlerClass = Class.forName(HANDLER_CLASS);
        int slots = (Integer) handlerClass.getMethod("getSlotCount").invoke(handler);
        Method getSlotStack = handlerClass.getMethod("getSlotStack", int.class);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack inner = (ItemStack) getSlotStack.invoke(handler, slot);
            if (inner == null || inner.isEmpty()) {
                continue;
            }
            try {
                if (lcf$isShulkerWithItems(inner)) {
                    MaterialListUtils.getStoredItemCounts(inner)
                            .forEach((type, count) -> counts.addTo(type, count));
                } else if (inner.getItem() instanceof BundleItem && InventoryUtils.bundleHasItems(inner)) {
                    MaterialListUtils.getBundleItemCounts(inner)
                            .forEach((type, count) -> counts.addTo(type, count));
                }
            } catch (Throwable ignored) {
                // 单个物品解析失败不影响其他物品
            }
        }
    }

    private static boolean lcf$isShulkerWithItems(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock
                && InventoryUtils.shulkerBoxHasItems(stack);
    }
}
