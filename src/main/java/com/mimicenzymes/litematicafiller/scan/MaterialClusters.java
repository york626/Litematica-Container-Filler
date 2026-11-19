package com.mimicenzymes.litematicafiller.scan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【野外材料感知 P2】资源簇数据层（Ray 2026-11-19 定稿）。
 *
 * <ul>
 *   <li>扫描器每扫完一个 16³ section，把「缺料原料 → 该 section 内数量」喂进来；</li>
 *   <li>**相邻 section（6 邻接）同种合并**，合计 ≥ {@link #THRESHOLD}（64）算一个资源簇；</li>
 *   <li>簇位置取「数量最多的那个 section 里第一个命中方块」；</li>
 *   <li>数据按**维度**存 JSON：`config/litematica_container_filler/material_clusters.json`，
 *       重扫会覆盖 section 计数（采空 ⇒ 计数变 0 ⇒ 簇消失）；</li>
 *   <li>显示上限每维度 {@link #MAX_POINTS}（256）个点，按离玩家由近到远取。</li>
 * </ul>
 */
public final class MaterialClusters {

    public record Cluster(Item item, int x, int y, int z, int count) {
    }

    /** 一簇至少多少同类原料。 */
    public static final int THRESHOLD = 64;
    /** 每维度最多显示多少个点。 */
    public static final int MAX_POINTS = 256;

    private static final String FILE_NAME = "material_clusters.json";
    private static final int DATA_VERSION = 1;

    private static final class SectionEntry {
        int count;
        int sampleX;
        int sampleY;
        int sampleZ;

        SectionEntry(int count, int x, int y, int z) {
            this.count = count;
            this.sampleX = x;
            this.sampleY = y;
            this.sampleZ = z;
        }
    }

    private static String dimension;
    /** 当前维度：物品 → (section 键 → 条目)。 */
    private static final Map<Item, Map<Long, SectionEntry>> sections = new HashMap<>();
    private static boolean dirty;

    private MaterialClusters() {
    }

    // ---------------------------------------------------------------- 扫描输入

    /** 扫描器每扫完一个 section 调用（同 section 重扫会覆盖计数，采空自然消失）。 */
    public static void onSection(String dim, Item item, int sectionX, int sectionY, int sectionZ,
                                 int count, BlockPos sample) {
        if (dim == null || item == null) {
            return;
        }
        if (!dim.equals(dimension)) {
            switchDimension(dim);
        }
        Map<Long, SectionEntry> map = sections.computeIfAbsent(item, k -> new HashMap<>());
        long key = BlockPos.asLong(sectionX, sectionY, sectionZ);
        if (count <= 0) {
            if (map.remove(key) != null) {
                dirty = true;
            }
            return;
        }
        SectionEntry entry = map.get(key);
        if (entry == null) {
            entry = new SectionEntry(count, sample == null ? (sectionX << 4) : sample.getX(),
                    sample == null ? (sectionY << 4) : sample.getY(),
                    sample == null ? (sectionZ << 4) : sample.getZ());
            map.put(key, entry);
        } else {
            entry.count = count;
            if (sample != null) {
                entry.sampleX = sample.getX();
                entry.sampleY = sample.getY();
                entry.sampleZ = sample.getZ();
            }
        }
        dirty = true;
    }

    // ---------------------------------------------------------------- 计算

    /** 重新计算当前维度的资源簇（相邻 section 同种合并 ≥64）。 */
    public static List<Cluster> recompute() {
        List<Cluster> all = new ArrayList<>();
        for (Map.Entry<Item, Map<Long, SectionEntry>> itemEntry : sections.entrySet()) {
            Item item = itemEntry.getKey();
            Map<Long, SectionEntry> map = itemEntry.getValue();
            if (map.isEmpty()) {
                continue;
            }
            Set<Long> visited = new HashSet<>();
            for (Long start : map.keySet()) {
                if (!visited.add(start)) {
                    continue;
                }
                // BFS 6 邻接
                int sum = 0;
                SectionEntry best = null;
                ArrayDeque<Long> queue = new ArrayDeque<>();
                queue.add(start);
                while (!queue.isEmpty()) {
                    long key = queue.poll();
                    SectionEntry entry = map.get(key);
                    if (entry == null) {
                        continue;
                    }
                    sum += entry.count;
                    if (best == null || entry.count > best.count) {
                        best = entry;
                    }
                    int sx = BlockPos.unpackLongX(key);
                    int sy = BlockPos.unpackLongY(key);
                    int sz = BlockPos.unpackLongZ(key);
                    for (int[] d : NEIGHBORS) {
                        long nk = BlockPos.asLong(sx + d[0], sy + d[1], sz + d[2]);
                        if (map.containsKey(nk) && visited.add(nk)) {
                            queue.add(nk);
                        }
                    }
                }
                if (best != null && sum >= THRESHOLD) {
                    all.add(new Cluster(item, best.sampleX, best.sampleY, best.sampleZ, sum));
                }
            }
        }
        dirty = true;
        return all;
    }

    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** 取离玩家最近的至多 {@link #MAX_POINTS} 个簇（地图显示用）。 */
    public static List<Cluster> nearest(MinecraftClient client, List<Cluster> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            return List.of();
        }
        if (client == null || client.player == null || clusters.size() <= MAX_POINTS) {
            return clusters;
        }
        BlockPos center = client.player.getBlockPos();
        List<Cluster> sorted = new ArrayList<>(clusters);
        sorted.sort((a, b) -> Long.compare(sq(a, center), sq(b, center)));
        return sorted.subList(0, MAX_POINTS);
    }

    private static long sq(Cluster c, BlockPos center) {
        long dx = (long) c.x() - center.getX();
        long dy = (long) c.y() - center.getY();
        long dz = (long) c.z() - center.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ---------------------------------------------------------------- 维度 / 持久化

    /** 需要时切换到某维度（先把旧维度写盘，再读新维度）。 */
    public static void switchDimension(String dim) {
        if (dim == null || dim.equals(dimension)) {
            return;
        }
        save();
        dimension = dim;
        sections.clear();
        load(dim);
    }

    public static String dimension() {
        return dimension;
    }

    public static boolean isDirty() {
        return dirty;
    }

    public static void clearAll() {
        sections.clear();
        dirty = true;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("litematica_container_filler").resolve(FILE_NAME);
    }

    /** 把当前维度的 section 数据写进 JSON（保留其它维度）。 */
    public static void save() {
        if (!dirty) {
            return;
        }
        try {
            Path path = file();
            JsonObject root = new JsonObject();
            JsonObject dims = new JsonObject();
            if (Files.isRegularFile(path)) {
                try {
                    JsonElement old = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                    if (old != null && old.isJsonObject()) {
                        JsonElement oldDims = old.getAsJsonObject().get("dimensions");
                        if (oldDims != null && oldDims.isJsonObject()) {
                            dims = oldDims.getAsJsonObject();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (dimension != null) {
                JsonObject items = new JsonObject();
                for (Map.Entry<Item, Map<Long, SectionEntry>> itemEntry : sections.entrySet()) {
                    JsonArray array = new JsonArray();
                    for (Map.Entry<Long, SectionEntry> e : itemEntry.getValue().entrySet()) {
                        SectionEntry entry = e.getValue();
                        JsonObject obj = new JsonObject();
                        obj.addProperty("x", BlockPos.unpackLongX(e.getKey()));
                        obj.addProperty("y", BlockPos.unpackLongY(e.getKey()));
                        obj.addProperty("z", BlockPos.unpackLongZ(e.getKey()));
                        obj.addProperty("count", entry.count);
                        obj.addProperty("px", entry.sampleX);
                        obj.addProperty("py", entry.sampleY);
                        obj.addProperty("pz", entry.sampleZ);
                        array.add(obj);
                    }
                    if (!array.isEmpty()) {
                        items.add(Registries.ITEM.getId(itemEntry.getKey()).toString(), array);
                    }
                }
                dims.add(dimension, items);
            }
            root.addProperty("version", DATA_VERSION);
            root.add("dimensions", dims);
            Files.createDirectories(path.getParent());
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Throwable ignored) {
        }
    }

    /** 读某维度的 section 数据。 */
    public static void load(String dim) {
        sections.clear();
        dirty = false;
        if (dim == null) {
            return;
        }
        try {
            Path path = file();
            if (!Files.isRegularFile(path)) {
                return;
            }
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) {
                return;
            }
            JsonElement dims = root.getAsJsonObject().get("dimensions");
            if (dims == null || !dims.isJsonObject()) {
                return;
            }
            JsonElement itemsEl = dims.getAsJsonObject().get(dim);
            if (itemsEl == null || !itemsEl.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : itemsEl.getAsJsonObject().entrySet()) {
                Identifier id;
                try {
                    id = Identifier.tryParse(entry.getKey());
                } catch (Throwable t) {
                    continue;
                }
                if (id == null) {
                    continue;
                }
                Item item = Registries.ITEM.get(id);
                if (item == null) {
                    continue;
                }
                Map<Long, SectionEntry> map = new LinkedHashMap<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = el.getAsJsonObject();
                    try {
                        long key = BlockPos.asLong(obj.get("x").getAsInt(), obj.get("y").getAsInt(),
                                obj.get("z").getAsInt());
                        map.put(key, new SectionEntry(obj.get("count").getAsInt(),
                                obj.get("px").getAsInt(), obj.get("py").getAsInt(),
                                obj.get("pz").getAsInt()));
                    } catch (Throwable ignored) {
                    }
                }
                if (!map.isEmpty()) {
                    sections.put(item, map);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
