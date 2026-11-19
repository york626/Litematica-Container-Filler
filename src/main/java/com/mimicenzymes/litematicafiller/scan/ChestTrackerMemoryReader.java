package com.mimicenzymes.litematicafiller.scan;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 【箱子外框·够用就停】只读：从**箱子追踪记忆**里取一个容器的内容（物品 → 数量）。
 *
 * <p>外框结果本身只有“有没有”，没有数量；要判断“两个箱子都有玻璃、亮一个就够”必须知道
 * 每个容器里有多少。优先读箱子追踪记忆（开过 / 「设置箱子追踪库存」扫过的箱子都有）；
 * 读不到时由调用方退回 {@code SearchResult.item()} 的数量。
 *
 * <p>盒装内容按**盒内**算（有内容物的容器不再把盒子本身算成材料）。
 */
public final class ChestTrackerMemoryReader {

    private static Boolean available;

    private ChestTrackerMemoryReader() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("red.jackf.chesttracker.api.memory.MemoryBank");
                Class.forName("red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl");
                available = true;
            } catch (Throwable ignored) {
                available = false;
            }
        }
        return available;
    }

    /** 容器内容（含盒内一层）；读不到返回空表。 */
    public static Map<Item, Long> contentsAt(BlockPos pos, Collection<BlockPos> otherPositions) {
        Map<Item, Long> out = new HashMap<>();
        if (pos == null || !isAvailable()) {
            return out;
        }
        try {
            MemoryBank bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
            MinecraftClient client = MinecraftClient.getInstance();
            if (bank == null || client.world == null) {
                return out;
            }
            Memory memory = bank.getMemory(client.world, pos).orElse(null);
            if (memory == null && otherPositions != null) {
                for (BlockPos other : otherPositions) {
                    memory = bank.getMemory(client.world, other).orElse(null);
                    if (memory != null) {
                        break;
                    }
                }
            }
            if (memory == null) {
                return out;
            }
            for (ItemStack stack : memory.fullItems()) {
                count(stack, out, 0);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void count(ItemStack stack, Map<Item, Long> out, int depth) {
        if (stack == null || stack.isEmpty() || depth > 1) {
            return;
        }
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        boolean hasContents = false;
        if (container != null) {
            for (ItemStack inner : container.iterateNonEmpty()) {
                hasContents = true;
                count(inner, out, depth + 1);
            }
        }
        if (!hasContents) {
            out.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
        }
    }
}
