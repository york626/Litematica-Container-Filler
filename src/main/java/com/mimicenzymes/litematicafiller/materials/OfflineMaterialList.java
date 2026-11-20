package com.mimicenzymes.litematicafiller.materials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.ItemType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【离线材料列表】（Ray 2026-11-20 立项）：
 * 远离投影 / 投影区块没加载时，打开 GUI 材料列表**不再等待区块加载**——
 * 总数直接从投影文件（{@link LitematicaBlockStateContainer}）数出来；
 * missing/mismatch 用在附近时**最近一次正常统计的缓存**（没有缓存的行按 total 显示，并在日志里注明）。
 *
 * <h2>为什么需要</h2>
 * <p>Litematica 的 {@code TaskCountBlocksPlacement} 每处理一个区块前都要求
 * {@code clientWorld} 周围区块已加载（{@code areSurroundingChunksLoaded}），远处任务一直等 ⇒
 * 材料列表出不来。客户端区块只会在真实玩家附近加载，所以“挂假人”也解决不了（假人只影响服务端）。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>总数：和原版 {@code MaterialListUtils.getMaterialList} 同一套转换（含水方块算水桶、多物品状态展开）；</li>
 *   <li>missing/mismatch：来自缓存；缓存由「正常统计任务完成时」采集（见 {@link #capture}）；</li>
 *   <li>缓存里没有的物品：missing = total（全部当没放），日志只提示一次；</li>
 *   <li>available：按原版 {@code getInventoryItemCounts} 实时算。</li>
 * </ul>
 */
public final class OfflineMaterialList {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    private static final String FILE_NAME = "offline_material_cache.json";
    private static final int DATA_VERSION = 1;

    /** placement 键 → (物品 → [missing, mismatch])。 */
    private static final Map<String, Map<Item, int[]>> CACHE = new HashMap<>();
    private static boolean cacheLoaded;
    private static boolean cacheDirty;
    private static final AtomicBoolean LOGGED_BUILD = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_NO_CACHE = new AtomicBoolean();

    private OfflineMaterialList() {
    }

    // ---------------------------------------------------------------- 判定

    /** 放置区域（客户端）区块没加载 ⇒ 用离线列表。 */
    public static boolean shouldUseOffline(SchematicPlacement placement) {
        if (!Configs.MATERIAL_LIST_OFFLINE_ENABLED.getBooleanValue() || placement == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            return false;
        }
        try {
            var boxes = placement.getSubRegionBoxes(
                    fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.ANY);
            if (boxes == null || boxes.isEmpty()) {
                return false;
            }
            for (var box : boxes.values()) {
                if (box == null || box.getPos1() == null || box.getPos2() == null) {
                    continue;
                }
                BlockPos p1 = box.getPos1();
                BlockPos p2 = box.getPos2();
                int minX = Math.min(p1.getX(), p2.getX());
                int maxX = Math.max(p1.getX(), p2.getX());
                int minZ = Math.min(p1.getZ(), p2.getZ());
                int maxZ = Math.max(p1.getZ(), p2.getZ());
                for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                    for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                        if (!client.world.getChunkManager().isChunkLoaded(cx, cz)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---------------------------------------------------------------- 构建

    /** 用投影数据 + 缓存构建条目；失败返回 false（调用方走原版逻辑）。 */
    public static boolean build(SchematicPlacement placement, MaterialListBase list) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (placement == null || list == null || client == null || client.player == null) {
                return false;
            }
            LitematicaSchematic schematic = placement.getSchematic();
            if (schematic == null) {
                return false;
            }
            ensureCacheLoaded();
            MaterialCache materialCache = MaterialCache.getInstance();
            Object2IntOpenHashMap<ItemType> totals = new Object2IntOpenHashMap<>();

            for (String region : schematic.getAreas().keySet()) {
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
                if (container == null) {
                    continue;
                }
                Vec3i size = container.getSize();
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        for (int x = 0; x < size.getX(); x++) {
                            BlockState state = container.get(x, y, z);
                            if (state == null || state.isAir()) {
                                continue;
                            }
                            addState(totals, state, 1, materialCache);
                        }
                    }
                }
            }
            if (totals.isEmpty()) {
                return false;
            }

            Map<Item, int[]> cached = CACHE.getOrDefault(key(placement), Map.of());
            var inventoryCounts = MaterialListUtils.getInventoryItemCounts(client.player.getInventory());

            // 按物品分配 missing（同一物品多个 ItemType 时，先给数量大的）
            Map<Item, List<Map.Entry<ItemType, Integer>>> byItem = new LinkedHashMap<>();
            for (var entry : totals.object2IntEntrySet()) {
                byItem.computeIfAbsent(entry.getKey().getStack().getItem(), k -> new ArrayList<>())
                        .add(Map.entry(entry.getKey(), entry.getIntValue()));
            }
            List<MaterialListEntry> entries = new ArrayList<>(totals.size());
            boolean anyCache = false;
            for (var listEntry : byItem.entrySet()) {
                Item item = listEntry.getKey();
                List<Map.Entry<ItemType, Integer>> types = listEntry.getValue();
                types.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                int[] cachedValues = cached.get(item);
                int remainingMissing = cachedValues == null ? -1 : Math.max(0, cachedValues[0]);
                int remainingMismatch = cachedValues == null ? 0 : Math.max(0, cachedValues[1]);
                if (remainingMissing >= 0) {
                    anyCache = true;
                }
                for (var typeEntry : types) {
                    ItemType type = typeEntry.getKey();
                    int total = typeEntry.getValue();
                    int missing;
                    if (remainingMissing < 0) {
                        missing = total;    // 没缓存：全部当没放
                    } else {
                        missing = Math.min(total, Math.max(0, remainingMissing));
                        remainingMissing -= missing;
                    }
                    int mismatch = Math.min(total, Math.max(0, remainingMismatch));
                    remainingMismatch -= mismatch;
                    entries.add(new MaterialListEntry(type.getStack().copy(), total, missing, mismatch,
                            inventoryCounts.getInt(type)));
                }
            }
            if (entries.isEmpty()) {
                return false;
            }
            list.setMaterialListEntries(entries);
            if (LOGGED_BUILD.compareAndSet(false, true)) {
                LOGGER.info("[material-list] 离线材料列表：{} 种（缓存覆盖={}，放置 {}）",
                        entries.size(), anyCache ? "是" : "否", placement.getName());
            }
            if (!anyCache && LOGGED_NO_CACHE.compareAndSet(false, true)) {
                LOGGER.info("[material-list] 离线材料列表：该放置没有缓存 ⇒ missing 按 total 显示（去附近打开一次即可建立缓存）");
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[material-list] 离线材料列表构建失败：{}", t.toString());
            return false;
        }
    }

    /** 和原版 {@code MaterialListUtils.convertStatesToStacks} 同一套口径。 */
    private static void addState(Object2IntOpenHashMap<ItemType> totals, BlockState state, int count,
                                 MaterialCache cache) {
        try {
            boolean waterlogged = state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED);
            BlockState base = waterlogged ? state.with(Properties.WATERLOGGED, false) : state;
            if (waterlogged) {
                totals.addTo(new ItemType(new ItemStack(net.minecraft.item.Items.WATER_BUCKET), false, false), count);
            }
            if (cache.requiresMultipleItems(base)) {
                for (ItemStack stack : cache.getItems(base)) {
                    if (!stack.isEmpty()) {
                        ItemType type = new ItemType(stack, true, false);
                        totals.addTo(type, count * stack.getCount());
                    }
                }
            } else {
                ItemStack stack = cache.getRequiredBuildItemForState(base);
                if (!stack.isEmpty()) {
                    ItemType type = new ItemType(stack, true, false);
                    totals.addTo(type, count * stack.getCount());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 缓存采集 / 持久化

    /** 正常统计任务完成时调用：记下这个放置的 missing/mismatch。 */
    public static void capture(SchematicPlacement placement, MaterialListBase list) {
        if (placement == null || list == null || !Configs.MATERIAL_LIST_OFFLINE_ENABLED.getBooleanValue()) {
            return;
        }
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null) {
                return;
            }
            // 只在「区块已加载」时采集（否则任务数据本身就不完整）
            if (shouldUseOffline(placement)) {
                return;
            }
            ensureCacheLoaded();
            Map<Item, int[]> collected = new HashMap<>();
            for (MaterialListEntry entry : list.getMaterialsAll()) {
                if (entry == null || entry.getStack().isEmpty()) {
                    continue;
                }
                Item item = entry.getStack().getItem();
                int[] values = collected.computeIfAbsent(item, k -> new int[2]);
                values[0] += Math.max(0, entry.getCountMissing());
                values[1] += Math.max(0, entry.getCountMismatched());
            }
            if (collected.isEmpty()) {
                return;
            }
            CACHE.put(key(placement), collected);
            cacheDirty = true;
            saveCache();
        } catch (Throwable ignored) {
        }
    }

    private static String key(SchematicPlacement placement) {
        try {
            return placement.getName() + "@" + placement.getOrigin().asLong();
        } catch (Throwable t) {
            return String.valueOf(System.identityHashCode(placement));
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("litematica_container_filler").resolve(FILE_NAME);
    }

    private static void ensureCacheLoaded() {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        try {
            Path path = file();
            if (!Files.isRegularFile(path)) {
                return;
            }
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) {
                return;
            }
            JsonElement placements = root.getAsJsonObject().get("placements");
            if (placements == null || !placements.isJsonObject()) {
                return;
            }
            for (var placementEntry : placements.getAsJsonObject().entrySet()) {
                Map<Item, int[]> items = new HashMap<>();
                for (var itemEntry : placementEntry.getValue().getAsJsonObject().entrySet()) {
                    try {
                        Item item = Registries.ITEM.get(net.minecraft.util.Identifier.of(itemEntry.getKey()));
                        JsonArray values = itemEntry.getValue().getAsJsonArray();
                        items.put(item, new int[]{values.get(0).getAsInt(), values.get(1).getAsInt()});
                    } catch (Throwable ignored) {
                    }
                }
                if (!items.isEmpty()) {
                    CACHE.put(placementEntry.getKey(), items);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveCache() {
        if (!cacheDirty) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", DATA_VERSION);
            JsonObject placements = new JsonObject();
            for (var placementEntry : CACHE.entrySet()) {
                JsonObject items = new JsonObject();
                for (var itemEntry : placementEntry.getValue().entrySet()) {
                    JsonArray values = new JsonArray();
                    values.add(itemEntry.getValue()[0]);
                    values.add(itemEntry.getValue()[1]);
                    items.add(Registries.ITEM.getId(itemEntry.getKey()).toString(), values);
                }
                placements.add(placementEntry.getKey(), items);
            }
            root.add("placements", placements);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
            cacheDirty = false;
        } catch (Throwable ignored) {
        }
    }
}
