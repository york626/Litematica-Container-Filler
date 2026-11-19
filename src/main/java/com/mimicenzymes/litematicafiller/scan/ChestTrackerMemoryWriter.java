package com.mimicenzymes.litematicafiller.scan;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.providers.MemoryBuilder;
import red.jackf.chesttracker.api.providers.ProviderUtils;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;

import java.util.List;
import java.util.Optional;

/**
 * 【设置箱子追踪库存】把服务端读到的容器内容写进 ChestTracker 记忆库（Ray 2026-11-19）。
 *
 * <p>移植自二改（zhaixianyu/litematica-printer）的「打印机库存」写法：
 * {@code MemoryBuilder.create(items).inContainer(block).otherPositions(双箱另一半).build()}
 * → {@code MemoryBank.addMemory(维度 id, 位置, memory)}。写进去之后：
 * <ul>
 *   <li>箱子追踪搜得到、<b>箱子外框</b>会亮（不用手动开箱）；</li>
 *   <li>记忆会被箱子追踪持久化，下次进世界仍在。</li>
 * </ul>
 *
 * <p>⚠️ 软依赖：所有 ChestTracker 类型只在 {@link #isAvailable()} 通过后才碰；
 * 外层再包 try/catch，没装箱子追踪时整个功能静默跳过。
 */
public final class ChestTrackerMemoryWriter {

    private static Boolean available;

    private ChestTrackerMemoryWriter() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("red.jackf.chesttracker.api.providers.MemoryBuilder");
                Class.forName("red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl");
                Class.forName("red.jackf.chesttracker.api.providers.ProviderUtils");
                available = true;
            } catch (Throwable ignored) {
                available = false;
            }
        }
        return available;
    }

    /**
     * 写入一条容器记忆。
     *
     * @param pos           容器位置（双箱已归一到主半）
     * @param blockId       方块 id（服务端读到的真实方块）
     * @param items         容器内容（按槽位原样，含潜影盒组件）
     * @param otherPositions 连体容器的其它位置（双箱另一半；没有就空表）
     * @return true = 已写入
     */
    public static boolean add(BlockPos pos, Identifier blockId, List<ItemStack> items,
                              List<BlockPos> otherPositions) {
        if (pos == null || blockId == null || items == null || !isAvailable()) {
            return false;
        }
        try {
            MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
            Optional<Identifier> key = ProviderUtils.getPlayersCurrentKey();
            if (bank == null || key.isEmpty()) {
                return false;
            }
            Block block = Registries.BLOCK.get(blockId);
            Memory memory = MemoryBuilder.create(items)
                    .inContainer(block)
                    .otherPositions(otherPositions == null ? List.of() : otherPositions)
                    .build();
            if (memory == null) {
                return false;   // 内容为空 / 构建失败
            }
            bank.addMemory(key.get(), pos.toImmutable(), memory);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
