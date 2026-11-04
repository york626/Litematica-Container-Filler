package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.List;

public class ItemMatcher {
    /** 容器内容缓冲：原版潜影盒 27 格，但「增强潜影盒」下界合金/钻石是 108 格 ⇒ 不能按 27 写死（2026-11-04 踩过）。 */
    private static final int CONTAINER_BUFFER = 256;


    public static boolean isSameItem(ItemStack current, ItemStack required) {
        if (current.isEmpty() || required.isEmpty()) return false;
        if (ItemStack.areItemsAndComponentsEqual(current, required)) return true;
        // 忽略"自定义名称/描述"这类纯装饰组件：改了名的物品也当成同一种材料（默认关）
        if (Configs.MATCH_IGNORE_CUSTOM_NAME.getBooleanValue()
                && current.isOf(required.getItem())
                && ItemStack.areItemsAndComponentsEqual(stripCosmetic(current), stripCosmetic(required))) {
            return true;
        }
        return Configs.MATCH_SHULKER_BOXES_BY_CONTENT.getBooleanValue()
                && isShulkerBox(current)
                && isShulkerBox(required)
                && hasSameContainerContents(current, required);
    }

    /** 去掉纯装饰组件（自定义名称 / 描述 / 物品名）后的副本，用于"忽略改名"匹配。 */
    private static ItemStack stripCosmetic(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(DataComponentTypes.CUSTOM_NAME);
        copy.remove(DataComponentTypes.LORE);
        copy.remove(DataComponentTypes.ITEM_NAME);
        return copy;
    }

    public static int matchingHash(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (Configs.MATCH_IGNORE_CUSTOM_NAME.getBooleanValue()) {
            return stripCosmetic(stack).hashCode();
        }
        if (Configs.MATCH_SHULKER_BOXES_BY_CONTENT.getBooleanValue() && isShulkerBox(stack)) {
            int hash = ShulkerBoxBlock.class.hashCode();
            for (ItemStack inner : getContainerSlots(stack)) {
                hash = 31 * hash + matchingHash(inner);
                hash = 31 * hash + inner.getCount();
            }
            return hash;
        }
        return ItemStack.hashCode(stack);
    }

    private static boolean hasSameContainerContents(ItemStack current, ItemStack required) {
        List<ItemStack> currentSlots = getContainerSlots(current);
        List<ItemStack> requiredSlots = getContainerSlots(required);
        if (currentSlots.size() != requiredSlots.size()) return false;

        for (int i = 0; i < currentSlots.size(); i++) {
            ItemStack currentSlot = currentSlots.get(i);
            ItemStack requiredSlot = requiredSlots.get(i);
            if (currentSlot.isEmpty() != requiredSlot.isEmpty()) return false;
            if (currentSlot.isEmpty()) continue;
            if (currentSlot.getCount() != requiredSlot.getCount()) return false;
            if (!isSameItem(currentSlot, requiredSlot)) return false;
        }
        return true;
    }

    private static List<ItemStack> getContainerSlots(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return List.of();

        DefaultedList<ItemStack> copiedSlots = DefaultedList.ofSize(CONTAINER_BUFFER, ItemStack.EMPTY);
        container.copyTo(copiedSlots);

        List<ItemStack> slots = new ArrayList<>(copiedSlots.size());
        copiedSlots.forEach(slot -> {
            ItemStack copy = slot.copy();
            copy.setCount(slot.getCount());
            slots.add(copy);
        });
        return slots;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }
}
