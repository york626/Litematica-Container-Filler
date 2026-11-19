package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.network.BatchServerLink;
import com.mimicenzymes.litematicafiller.tool.ContainerBatchQueue;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import red.jackf.whereisit.api.search.ConnectedBlocksGrabber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 【设置箱子追踪库存】（Ray 2026-11-19；移植自二改的「打印机库存」）。
 *
 * <p>按热键 → 取当前 **Litematica 选区**里的容器 → 通过 {@link BatchServerLink} 的
 * 服务端直连只读通道拿内容（不弹界面、不闪屏）→ 写入 **箱子追踪记忆库**
 * （{@link ChestTrackerMemoryWriter}）→ 之后「箱子外框」搜索就能找到这些箱子，
 * 不用手动一个个开箱。
 *
 * <ul>
 *   <li>只扫**已加载区块**里的容器；类型跟批量工具同一套过滤（{@code CONTAINER_FILTER_LIST}）。</li>
 *   <li>双箱按主半归一（{@code ContainerBatchQueue} 已处理），记忆里带另一半位置 ⇒ 两半都亮。</li>
 *   <li>只在「单人 / 集成服务端有本模组」时可用；纯客户端连别人的服会提示不支持。</li>
 *   <li>每 tick 最多 8 个在途请求；单个请求 60 tick 没回包就记失败跳过。</li>
 * </ul>
 */
public final class ChestTrackerInventoryScanner {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    /** 同时在途的读取请求数。 */
    private static final int MAX_IN_FLIGHT = 8;
    /** 单个请求超时（tick）。 */
    private static final int TIMEOUT_TICKS = 60;

    private static boolean lastHotkey;
    private static boolean running;

    private static final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private static final Map<BlockPos, Integer> inFlight = new HashMap<>();
    private static int total;
    private static int written;
    private static int empty;
    private static int failed;

    private ChestTrackerInventoryScanner() {
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.player == null) {
            reset();
            return;
        }
        boolean held = Hotkeys.CHEST_TRACKER_INVENTORY_SET.getKeybind().isKeybindHeld();
        if (held && !lastHotkey) {
            start(client);
        }
        lastHotkey = held;
        if (!running) {
            return;
        }

        // 超时：服务端没回（没加载 / 没有这个容器）
        Iterator<Map.Entry<BlockPos, Integer>> iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() >= TIMEOUT_TICKS) {
                iterator.remove();
                failed++;
            } else {
                entry.setValue(entry.getValue() + 1);
            }
        }

        // 补发在途请求
        while (inFlight.size() < MAX_IN_FLIGHT && !queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (pos == null) {
                continue;
            }
            inFlight.put(pos, 0);
            BatchServerLink.sendReadContents(pos);
        }

        if (queue.isEmpty() && inFlight.isEmpty()) {
            running = false;
            String text = "箱子追踪库存完成：" + written + " 个已写入"
                    + (empty > 0 ? "，" + empty + " 个空箱" : "")
                    + (failed > 0 ? "，" + failed + " 个失败" : "");
            client.player.sendMessage(Text.literal(text), true);
            LOGGER.info("[chest-inventory] 完成：写入 {}，空 {}，失败 {}（共 {}）", written, empty, failed, total);
        }
    }

    private static void start(MinecraftClient client) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return;
        }
        if (running) {
            return;
        }
        if (!BatchServerLink.isSupported()) {
            client.player.sendMessage(Text.literal("设置箱子追踪库存：需要单人游戏（或装了本模组的服务端）"), true);
            return;
        }
        if (!ChestTrackerMemoryWriter.isAvailable()) {
            client.player.sendMessage(Text.literal("设置箱子追踪库存：没装【箱子追踪】，无法写入"), true);
            return;
        }
        if (SelectionFilter.boxCount() == 0) {
            client.player.sendMessage(Text.literal("设置箱子追踪库存：先用选区工具框住要扫描的容器"), true);
            return;
        }
        List<BlockPos> positions = ContainerBatchQueue.build(client);
        if (positions.isEmpty()) {
            client.player.sendMessage(Text.literal("设置箱子追踪库存：选区里没有可用容器"), true);
            return;
        }
        queue.clear();
        queue.addAll(positions);
        inFlight.clear();
        total = positions.size();
        written = 0;
        empty = 0;
        failed = 0;
        running = true;
        client.player.sendMessage(Text.literal("设置箱子追踪库存：开始扫描 " + total + " 个容器"), true);
        LOGGER.info("[chest-inventory] 开始：{} 个容器（选区 {} 个盒）", total, SelectionFilter.boxCount());
    }

    /** 网络回包（客户端线程）：写入箱子追踪记忆。 */
    public static void onContents(BatchServerLink.Contents payload) {
        if (payload == null) {
            return;
        }
        BlockPos pos = BlockPos.fromLong(payload.pos());
        if (!inFlight.containsKey(pos)) {
            return;   // 不是本次请求 / 已超时
        }
        inFlight.remove(pos);
        List<ItemStack> items = payload.items() == null ? List.of() : payload.items();
        if (items.isEmpty()) {
            empty++;
            return;
        }
        List<BlockPos> others = otherPositions(pos);
        boolean ok = ChestTrackerMemoryWriter.add(pos, payload.blockId(), items, others);
        if (ok) {
            written++;
        } else {
            failed++;
        }
        if (written > 0 && written % 20 == 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("添加库存中… " + written + "/" + total), true);
            }
        }
    }

    /** 双箱的另一半（WhereIsIt 的连体判定，客户端世界足够用）。 */
    private static List<BlockPos> otherPositions(BlockPos pos) {
        List<BlockPos> out = new ArrayList<>();
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                return out;
            }
            BlockState state = client.world.getBlockState(pos);
            for (BlockPos other : ConnectedBlocksGrabber.getConnected(client.world, state, pos)) {
                if (other != null && !other.equals(pos)) {
                    out.add(other.toImmutable());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void reset() {
        running = false;
        queue.clear();
        inFlight.clear();
    }
}
