package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.GlowSquidEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【野外材料感知】附近扫描器。
 *
 * <ul>
 *   <li>热键开关（{@link Hotkeys#MATERIAL_SCAN_TOGGLE}，默认未绑定）；</li>
 *   <li>32 格、全 3D；只扫已加载区块；每 tick 最多 8 个 section（1 秒内跑完一轮，不卡主线程）；</li>
 *   <li>方块：按 section 逐格读 block state（P1 先求稳；有性能问题再换调色板计数）；</li>
 *   <li>实体：只算被动/资源实体（含鱿鱼/发光鱿鱼），敌对不在聊天里刷；</li>
 *   <li>一轮结束 → 聊天提示 + 箱子外框搜索（WhereIsIt）。</li>
 * </ul>
 */
public final class NearbyMaterialScanner {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    /** 每 tick 扫多少个 section（Ray 2026-11-18 反馈 MSPT 偏高：8 → 4，并加一轮后冷却）。 */
    private static final int SECTION_BUDGET = 4;
    /** 一轮结束后冷却多少 tick（20 = 1 秒），降低平均占用。 */
    private static final int CYCLE_COOLDOWN_TICKS = 20;
    /** 清单刷新间隔（tick）：材料列表/GUI 会变，不用每 tick 重算。 */
    private static final int REFRESH_INTERVAL_TICKS = 60;

    private record SectionRef(int chunkX, int sectionIndex, int sectionY, int chunkZ) {
    }

    private static boolean lastHotkey = false;
    private static int refreshTimer = 0;
    private static int chestTimer = 0;
    private static int scanCooldown = 0;

    private static List<SectionRef> sections = List.of();
    private static int sectionCursor = 0;
    private static int sectionsRadius = -1;
    private static int centerChunkX = Integer.MIN_VALUE;
    private static int centerChunkZ = Integer.MIN_VALUE;
    private static int centerSectionY = Integer.MIN_VALUE;

    private static final Map<Item, Long> cycleBlockCounts = new HashMap<>();
    private static final Map<Block, Long> cycleBlockByBlock = new HashMap<>();
    private static final Map<Item, Long> cycleEntityCounts = new HashMap<>();

    private static volatile Map<Item, Long> lastBlockCounts = Map.of();
    private static volatile Map<Block, Long> lastBlockByBlock = Map.of();
    private static volatile Map<Item, Long> lastEntityCounts = Map.of();
    private static volatile long cycleVersion = 0L;

    private static long targetVersion = -1L;
    private static Map<Block, List<MaterialWatchlist.WatchItem>> blockTargets = Map.of();
    private static Map<EntityType<?>, List<MaterialWatchlist.WatchItem>> entityTargets = Map.of();

    private NearbyMaterialScanner() {
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            resetAll();
            return;
        }

        // 热键：按下沿切换（IKeybind 只有 isKeybindHeld/isPressed，没有 wasPressed）
        boolean held = Hotkeys.MATERIAL_SCAN_TOGGLE.getKeybind().isKeybindHeld();
        if (held && !lastHotkey) {
            boolean enabled = !Configs.MATERIAL_SCAN_ENABLED.getBooleanValue();
            Configs.MATERIAL_SCAN_ENABLED.setBooleanValue(enabled);
            Configs.saveToFile();
            client.player.sendMessage(Text.literal(enabled ? "野外材料感知：开" : "野外材料感知：关"), true);
            if (enabled) {
                MaterialWatchlist.markDirty();
                refreshTimer = 0;
                chestTimer = 0;
            } else {
                resetCycle();
                MaterialChatNotifier.reset();
            }
        }
        lastHotkey = held;

        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.MATERIAL_SCAN_ENABLED.getBooleanValue()) {
            resetCycle();
            return;
        }

        // 清单刷新
        if (refreshTimer-- <= 0) {
            refreshTimer = Math.max(20, Configs.MATERIAL_SCAN_REFRESH_INTERVAL.getIntegerValue());
            if (MaterialWatchlist.needsRefresh(3000L)) {
                MaterialWatchlist.refresh(client);
            }
            ensureTargets(MaterialWatchlist.snapshot());
        }

        // 区块 section 列表
        int radius = Configs.MATERIAL_SCAN_RADIUS.getIntegerValue();
        BlockPos center = client.player.getBlockPos();
        if (sections.isEmpty()
                || (center.getX() >> 4) != centerChunkX
                || (center.getZ() >> 4) != centerChunkZ
                || (center.getY() >> 4) != centerSectionY
                || radius != sectionsRadius) {
            buildSections(client, center, radius);
        }

        // 一轮扫描（4 section/tick；一轮结束冷却 1 秒）
        if (scanCooldown > 0) {
            scanCooldown--;
        } else if (sectionCursor < sections.size()) {
            int budget = Math.min(SECTION_BUDGET, sections.size() - sectionCursor);
            for (int i = 0; i < budget; i++) {
                scanSection(client, sections.get(sectionCursor++));
            }
        } else {
            finishCycle(client);
            scanCooldown = CYCLE_COOLDOWN_TICKS;
        }

        // 箱子外框（独立计时）
        if (Configs.MATERIAL_SCAN_CHEST_SEARCH.getBooleanValue()) {
            if (chestTimer-- <= 0) {
                chestTimer = Math.max(40, Configs.MATERIAL_SCAN_CHEST_INTERVAL.getIntegerValue());
                List<ItemStack> searchItems = MaterialWatchlist.snapshot().searchItems();
                if (!searchItems.isEmpty()) {
                    WhereIsItBridge.search(searchItems);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 扫描

    private static void buildSections(MinecraftClient client, BlockPos center, int radius) {
        sections = new ArrayList<>();
        sectionCursor = 0;
        scanCooldown = 0;
        cycleBlockCounts.clear();
        cycleEntityCounts.clear();
        centerChunkX = center.getX() >> 4;
        centerChunkZ = center.getZ() >> 4;
        centerSectionY = center.getY() >> 4;
        sectionsRadius = radius;

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        int minSectionY = Math.max(client.world.getBottomY() >> 4, (center.getY() - radius) >> 4);
        int maxSectionY = Math.min((client.world.getTopY() - 1) >> 4, (center.getY() + radius) >> 4);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                WorldChunk chunk = client.world.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                ChunkSection[] array = chunk.getSectionArray();
                if (array == null || array.length == 0) {
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int index = chunk.getSectionIndex(sectionY << 4);
                    if (index < 0 || index >= array.length) {
                        continue;
                    }
                    ChunkSection section = array[index];
                    if (section == null || section.isEmpty()) {
                        continue;
                    }
                    sections.add(new SectionRef(chunkX, index, sectionY, chunkZ));
                }
            }
        }
        LOGGER.info("[material-scan] 扫描 {} 个 section（半径 {}，中心 {},{},{}）",
                sections.size(), radius, center.getX(), center.getY(), center.getZ());
    }

    private static void scanSection(MinecraftClient client, SectionRef ref) {
        WorldChunk chunk = client.world.getChunk(ref.chunkX(), ref.chunkZ());
        if (chunk == null) {
            return;
        }
        ChunkSection[] array = chunk.getSectionArray();
        if (array == null || ref.sectionIndex() < 0 || ref.sectionIndex() >= array.length) {
            return;
        }
        ChunkSection section = array[ref.sectionIndex()];
        if (section == null || section.isEmpty()) {
            return;
        }
        // P1：逐格读 state（blockTargets 只含清单里的方块，miss 就是一次 HashMap 查询）
        int baseX = ref.chunkX() << 4;
        int baseY = ref.sectionY() << 4;
        int baseZ = ref.chunkZ() << 4;
        boolean exposedOnly = Configs.MATERIAL_SCAN_EXPOSED_ONLY.getBooleanValue();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    Block block = state.getBlock();
                    List<MaterialWatchlist.WatchItem> targets = blockTargets.get(block);
                    if (targets == null) {
                        continue;
                    }
                    if (exposedOnly && !isExposed(client.world, baseX + x, baseY + y, baseZ + z)) {
                        continue;
                    }
                    for (MaterialWatchlist.WatchItem target : targets) {
                        cycleBlockCounts.merge(target.item(), 1L, Long::sum);
                    }
                    cycleBlockByBlock.merge(block, 1L, Long::sum);
                }
            }
        }
    }

    /** 至少一面接触空气/水 ⇒ 算“暴露”（水下沙子/海边黏土也算）。 */
    private static boolean isExposed(ClientWorld world, int x, int y, int z) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = world.getBlockState(new BlockPos(
                    x + direction.getOffsetX(), y + direction.getOffsetY(), z + direction.getOffsetZ()));
            if (neighbor.isAir() || neighbor.isOf(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static void finishCycle(MinecraftClient client) {
        long started = System.nanoTime();

        // 实体：只算被动/资源，好友/敌对不刷
        if (Configs.MATERIAL_SCAN_ENTITIES.getBooleanValue()) {
            cycleEntityCounts.clear();
            double radius = Configs.MATERIAL_SCAN_RADIUS.getIntegerValue();
            Box box = client.player.getBoundingBox().expand(radius);
            for (Entity entity : client.world.getEntitiesByClass(Entity.class, box, NearbyMaterialScanner::isResourceEntity)) {
                List<MaterialWatchlist.WatchItem> targets = entityTargets.get(entity.getType());
                if (targets == null) {
                    continue;
                }
                for (MaterialWatchlist.WatchItem target : targets) {
                    cycleEntityCounts.merge(target.item(), 1L, Long::sum);
                }
            }
        }

        Map<Item, Long> nearby = new HashMap<>(cycleBlockCounts);
        cycleEntityCounts.forEach((item, count) -> nearby.merge(item, count, Long::sum));
        lastBlockCounts = Map.copyOf(cycleBlockCounts);
        lastBlockByBlock = Map.copyOf(cycleBlockByBlock);
        lastEntityCounts = Map.copyOf(cycleEntityCounts);
        cycleVersion++;

        if (Configs.MATERIAL_SCAN_CHAT.getBooleanValue()) {
            long intervalMs = Math.max(2000L, Configs.MATERIAL_SCAN_CHAT_INTERVAL.getIntegerValue() * 50L);
            MaterialChatNotifier.maybeNotify(client, MaterialWatchlist.snapshot().rawItems(), nearby, intervalMs);
        }

        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        if (elapsed > 40L) {
            LOGGER.info("[material-scan] 一轮完成：{} 种方块、{} 种实体，收尾 {} ms",
                    cycleBlockCounts.size(), cycleEntityCounts.size(), elapsed);
        }

        // 下一轮
        cycleBlockCounts.clear();
        cycleBlockByBlock.clear();
        cycleEntityCounts.clear();
        sectionCursor = 0;
        // 下一轮会自动 rebuild（sections 不为空、判据不触发 ⇒ 保留列表），但玩家移动后 buildSections 会重建
    }

    private static boolean isResourceEntity(Entity entity) {
        if (entity == null || entity instanceof PlayerEntity) {
            return false;
        }
        return entity instanceof PassiveEntity
                || entity instanceof SquidEntity
                || entity instanceof GlowSquidEntity;
    }

    // ---------------------------------------------------------------- 上一轮结果（HUD / 聊天用）

    /** 最近一轮完成后的「物品 → 附近方块数」。 */
    public static Map<Item, Long> lastBlockCounts() {
        return lastBlockCounts;
    }

    /** 最近一轮完成后的「方块 → 附近数量」（HUD 按附近最多的树种显示“金合欢原木”）。 */
    public static Map<Block, Long> lastBlockByBlock() {
        return lastBlockByBlock;
    }

    /** 最近一轮完成后的「物品 → 附近实体数」。 */
    public static Map<Item, Long> lastEntityCounts() {
        return lastEntityCounts;
    }

    /** 轮次版本：HUD 用它做缓存，避免每帧重建 promoted 行导致闪烁。 */
    public static long cycleVersion() {
        return cycleVersion;
    }

    // ---------------------------------------------------------------- 缓存

    private static void ensureTargets(MaterialWatchlist.Snapshot snapshot) {
        if (snapshot.version() == targetVersion) {
            return;
        }
        Map<Block, List<MaterialWatchlist.WatchItem>> blocks = new HashMap<>();
        Map<EntityType<?>, List<MaterialWatchlist.WatchItem>> entities = new HashMap<>();
        for (MaterialWatchlist.WatchItem item : snapshot.rawItems()) {
            for (Block block : item.target().blocks()) {
                blocks.computeIfAbsent(block, k -> new ArrayList<>()).add(item);
            }
            for (EntityType<?> type : item.target().entities()) {
                entities.computeIfAbsent(type, k -> new ArrayList<>()).add(item);
            }
        }
        blockTargets = blocks;
        entityTargets = entities;
        targetVersion = snapshot.version();
        LOGGER.info("[material-scan] 目标索引：{} 个方块、{} 个实体", blocks.size(), entities.size());
    }

    private static void resetCycle() {
        sections = List.of();
        sectionCursor = 0;
        scanCooldown = 0;
        sectionsRadius = -1;
        centerChunkX = Integer.MIN_VALUE;
        centerChunkZ = Integer.MIN_VALUE;
        centerSectionY = Integer.MIN_VALUE;
        cycleBlockCounts.clear();
        cycleBlockByBlock.clear();
        cycleEntityCounts.clear();
        lastBlockCounts = Map.of();
        lastBlockByBlock = Map.of();
        lastEntityCounts = Map.of();
    }

    private static void resetAll() {
        resetCycle();
        MaterialChatNotifier.reset();
    }
}
