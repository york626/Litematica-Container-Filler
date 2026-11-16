package com.mimicenzymes.litematicafiller.materials;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **把「同一种材料的各种形状」归到一组**（Ray 2026-11-13 要的：
 * 「平滑石英块、平滑石英台阶、平滑石英楼梯尽量排在一起」）。
 *
 * <p>2026-11-16 从 Ray 工具箱迁入（批次 1：材料列表显示类功能）。
 *
 * <h2>为什么按物品 ID 而不是翻译名</h2>
 * <p>中文名做后缀匹配要维护一堆「台阶 / 楼梯 / 墙 / 栅栏」的译名，换语言就失效；
 * 物品 ID 是固定的：
 * <pre>
 * minecraft:smooth_quartz
 * minecraft:smooth_quartz_slab     → smooth_quartz
 * minecraft:smooth_quartz_stairs   → smooth_quartz
 * minecraft:polished_diorite_wall  → polished_diorite
 * </pre>
 *
 * <h2>⚠️ 故意不去掉的后缀</h2>
 * <p>{@code _block} / {@code _ore} 不参与归一 —— 去了会把 {@code iron_block} 和 {@code iron_ore}
 * 硬凑成一组，反而更乱。{@code _bars}（铁栏杆）同理。
 */
public final class MaterialListGroups {

    /** 按长度降序排好，先匹配长的（{@code _hanging_sign} 要在 {@code _sign} 之前）。 */
    private static final String[] SHAPE_SUFFIXES = {
            "_hanging_sign",
            "_pressure_plate",
            "_fence_gate",
            "_trapdoor",
            "_stairs",
            "_button",
            "_carpet",
            "_fence",
            "_slab",
            "_wall",
            "_door",
            "_sign",
            "_pane",
    };

    private static final Map<Item, String> CACHE = new ConcurrentHashMap<>();

    private MaterialListGroups() {
    }

    /** 物品的「组名」——去掉形状后缀的 ID；取不到时返回空串（调用方当作"无分组"）。 */
    public static String groupKey(Item item) {
        if (item == null) {
            return "";
        }
        String cached = CACHE.get(item);
        if (cached != null) {
            return cached;
        }
        String key;
        try {
            String path = Registries.ITEM.getId(item).getPath();
            key = path;
            for (String suffix : SHAPE_SUFFIXES) {
                if (path.length() > suffix.length() && path.endsWith(suffix)) {
                    key = path.substring(0, path.length() - suffix.length());
                    break;
                }
            }
        } catch (Throwable ignored) {
            key = "";
        }
        CACHE.put(item, key);
        return key;
    }
}
