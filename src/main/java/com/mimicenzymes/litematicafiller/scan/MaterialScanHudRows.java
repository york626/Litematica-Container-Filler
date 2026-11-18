package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.block.Block;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【野外材料感知】把 HUD 材料列表里「附近能补」的行提到最上面。
 *
 * <p>Ray 2026-11-18 定稿：不新开一块“附近可采”，而是**复用原清单的行**——
 * 原清单里某行的原料在附近有货 ⇒ 这行**排到最上面**，行尾加 `← 金合欢原木 ×172`；
 * 原位置不再重复出现（隐藏重复）。这样收集完附近这批，行自己会消失，正好对上。
 *
 * <p>原木类按**附近数量最多的树种**显示（金合欢林 → `← 金合欢原木`，白桦林 → `← 白桦原木`）。
 * 只看暴露方块（扫描器配置），默认最多 6 行。
 */
public final class MaterialScanHudRows {

    private static final int MAX_ROWS = 6;

    /** 缓存：按「清单内容 + 附近内容 + 快照版本」判断，内容没变就复用同一批对象（HUD 才不会闪）。 */
    private static long cachedKey = Long.MIN_VALUE;
    private static List<MaterialListEntry> cached = List.of();
    private static long cachedCombinedKey = Long.MIN_VALUE;
    private static long cachedCombinedTick = Long.MIN_VALUE;
    private static List<MaterialListEntry> cachedCombined = List.of();
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    private MaterialScanHudRows() {
    }

    /** 直接把「提前行 + 其余原清单」拼好；同一 tick 内强制复用同一个 list 实例（HUD 每帧会问两次）。 */
    public static List<MaterialListEntry> combined(List<MaterialListEntry> normal) {
        if (!Configs.MATERIAL_SCAN_HUD.getBooleanValue() || normal == null || normal.isEmpty()) {
            return normal;
        }
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        long tick = client.world == null ? 0L : client.world.getTime();
        if (cachedCombinedTick == tick && !cachedCombined.isEmpty()) {
            return cachedCombined;
        }
        List<MaterialListEntry> promoted = promoted(normal);
        if (promoted.isEmpty()) {
            // ⛔ 不要退回原列表：否则 HUD 会在「提前行」和「原列表」两态间跳。
            // 没数据时短暂复用上一版；只有从来没成功过才用原列表。
            if (!cachedCombined.isEmpty()) {
                cachedCombinedTick = tick;
                return cachedCombined;
            }
            return normal;
        }
        if (cachedCombinedKey == cachedKey && !cachedCombined.isEmpty()) {
            cachedCombinedTick = tick;
            return cachedCombined;
        }
        Set<Item> promotedItems = new HashSet<>();
        for (MaterialListEntry entry : promoted) {
            promotedItems.add(entry.getStack().getItem());
        }
        List<MaterialListEntry> out = new ArrayList<>(promoted.size() + normal.size());
        out.addAll(promoted);
        for (MaterialListEntry entry : normal) {
            if (!promotedItems.contains(entry.getStack().getItem())) {
                out.add(entry);
            }
        }
        cachedCombinedKey = cachedKey;
        cachedCombinedTick = tick;
        cachedCombined = out;
        StringBuilder detail = new StringBuilder();
        for (MaterialListEntry entry : promoted) {
            if (detail.length() > 0) {
                detail.append(" ｜ ");
            }
            detail.append(entry.getStack().getName().getString());
        }
        LOGGER.info("[material-scan] HUD 提前 {} 行：{}", promoted.size(), detail);
        return out;
    }

    /** 返回需要提前的“原清单行”（已复制并改过显示名）；调用方把这些行放最前、原位置跳过。 */
    public static List<MaterialListEntry> promoted(List<MaterialListEntry> normal) {
        MaterialWatchlist.Snapshot snapshot = MaterialWatchlist.snapshot();
        if (!Configs.MATERIAL_SCAN_HUD.getBooleanValue()
                || snapshot == null || snapshot.rawItems().isEmpty()
                || normal == null || normal.isEmpty()) {
            cached = List.of();
            cachedKey = Long.MIN_VALUE;
            return cached;
        }
        Map<Item, Long> nearby = new HashMap<>(NearbyMaterialScanner.lastBlockCounts());
        NearbyMaterialScanner.lastEntityCounts().forEach((item, count) -> nearby.merge(item, count, Long::sum));
        if (nearby.isEmpty()) {
            cached = List.of();
            cachedKey = Long.MIN_VALUE;
            return cached;
        }

        long key = snapshot.version();
        for (MaterialListEntry entry : normal) {
            if (entry == null || entry.getStack().isEmpty()) {
                continue;
            }
            key = key * 31 + entry.getStack().getItem().hashCode();
            key = key * 31 + entry.getCountMissing();
        }
        for (MaterialWatchlist.WatchItem item : snapshot.rawItems()) {
            long count = nearby.getOrDefault(item.item(), 0L);
            if (count <= 0) {
                continue;
            }
            key = key * 31 + item.item().hashCode();
            key = key * 31 + count;
        }
        if (key == cachedKey) {
            return cached;
        }

        Map<Item, MaterialWatchlist.WatchItem> watchByItem = new HashMap<>();
        for (MaterialWatchlist.WatchItem item : snapshot.rawItems()) {
            watchByItem.put(item.item(), item);
        }

        List<MaterialListEntry> promoted = new ArrayList<>();
        Set<MaterialListEntry> seen = new HashSet<>();
        for (MaterialListEntry entry : normal) {
            if (promoted.size() >= MAX_ROWS) {
                break;
            }
            if (entry == null || entry.getStack().isEmpty()) {
                continue;
            }
            Set<Item> raws = snapshot.primaryRaw().get(entry.getStack().getItem());
            if (raws == null || raws.isEmpty()) {
                continue;
            }
            long nearbySum = 0L;
            String source = null;
            for (Item raw : raws) {
                long count = nearby.getOrDefault(raw, 0L);
                if (count <= 0) {
                    continue;
                }
                nearbySum += count;
                if (source == null) {
                    source = displaySource(raw, watchByItem);
                }
            }
            if (nearbySum <= 0L || source == null) {
                continue;
            }
            if (!seen.add(entry)) {
                continue;
            }
            promoted.add(withSuffix(entry, source, nearbySum));
        }
        cachedKey = key;
        cached = promoted;
        return promoted;
    }

    /** 行尾加 `→ 金合欢原木 ×172`；用副本，绝不改原清单的数据。 */
    private static MaterialListEntry withSuffix(MaterialListEntry entry, String source, long nearbyCount) {
        ItemStack stack = entry.getStack().copy();
        String name = stack.getName().getString();
        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal(name + " → " + source + " ×" + nearbyCount));
        return new MaterialListEntry(stack, entry.getCountTotal(), entry.getCountMissing(),
                entry.getCountMismatched(), entry.getCountAvailable());
    }

    /**
     * 通用原木（橡木代表“任意木板”配方）⇒ 取附近最多的树种；
     * 白桦/丛林/竹块等具体树种 ⇒ 用它自己的名字，绝不跟附近树种变。
     */
    private static String displaySource(Item raw, Map<Item, MaterialWatchlist.WatchItem> watchByItem) {
        MaterialWatchlist.WatchItem watch = watchByItem.get(raw);
        if (watch == null) {
            return new ItemStack(raw).getName().getString();
        }
        boolean genericLog = raw == net.minecraft.item.Items.OAK_LOG
                || raw == net.minecraft.item.Items.OAK_WOOD
                || raw == net.minecraft.item.Items.STRIPPED_OAK_LOG
                || raw == net.minecraft.item.Items.STRIPPED_OAK_WOOD;
        if (genericLog) {
            Map<Block, Long> byBlock = NearbyMaterialScanner.lastBlockByBlock();
            Block best = null;
            long bestCount = -1L;
            for (Block block : watch.target().blocks()) {
                long count = byBlock.getOrDefault(block, 0L);
                if (count > bestCount) {
                    best = block;
                    bestCount = count;
                }
            }
            if (best != null && bestCount > 0L) {
                return new ItemStack(best.asItem()).getName().getString();
            }
        }
        return watch.target().source();
    }
}
