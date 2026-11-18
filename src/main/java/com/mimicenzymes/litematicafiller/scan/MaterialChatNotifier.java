package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.MaterialBreakdownHint;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 【野外材料感知】聊天提示（汇总版）。
 *
 * <p>Ray 2026-11-18：「显示的太长有点乱，特别是原木」⇒ 改成**一来源一行**：
 * <pre>
 * 附近 32 格：
 * • 原木 ×172=2组+44｜16 种共缺 236=3组+44 [拉黑全部]
 * • 沙砾 ×40｜2 种共缺 8383=4盒+22组+59 [拉黑全部]
 * </pre>
 *
 * <ul>
 *   <li>不再逐种列“橡木/白桦/深色橡木…”，只报这类来源**共缺多少**、包含几种；</li>
 *   <li>某来源的合计需求 &lt; {@code materialScanMinNeed}（默认 16）⇒ 不显示，过滤 1~2 个的展示样品；</li>
 *   <li>最多显示 6 个来源，其余折叠成“另有 N 种来源”；</li>
 *   <li>[拉黑全部] 一次把该来源下所有缺料加入黑名单；</li>
 *   <li>重发判据只看「来源 + 合计缺口」，附近数量变化不再每 10 秒刷一条。</li>
 * </ul>
 */
public final class MaterialChatNotifier {

    private record Group(String source, List<MaterialWatchlist.WatchItem> items, long nearby, long needSum) {
    }

    private static String lastSignature = "";
    private static long lastSentMs = 0L;

    private MaterialChatNotifier() {
    }

    public static void reset() {
        lastSignature = "";
    }

    public static void maybeNotify(MinecraftClient client,
                                   List<MaterialWatchlist.WatchItem> items,
                                   Map<Item, Long> nearby,
                                   long intervalMs) {
        if (client == null || client.player == null) {
            return;
        }
        if (items == null || items.isEmpty() || nearby == null || nearby.isEmpty()) {
            lastSignature = "";
            return;
        }

        Map<String, List<MaterialWatchlist.WatchItem>> bySource = new LinkedHashMap<>();
        for (MaterialWatchlist.WatchItem item : items) {
            long count = nearby.getOrDefault(item.item(), 0L);
            if (count <= 0) {
                continue;
            }
            bySource.computeIfAbsent(item.target().source(), k -> new ArrayList<>()).add(item);
        }
        if (bySource.isEmpty()) {
            lastSignature = "";
            return;
        }

        int minNeed = Configs.MATERIAL_SCAN_MIN_NEED.getIntegerValue();
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<MaterialWatchlist.WatchItem>> entry : bySource.entrySet()) {
            long needSum = 0L;
            for (MaterialWatchlist.WatchItem item : entry.getValue()) {
                needSum += item.need();
            }
            if (needSum < minNeed) {
                continue;
            }
            long nearbyCount = nearby.getOrDefault(entry.getValue().get(0).item(), 0L);
            groups.add(new Group(entry.getKey(), entry.getValue(), nearbyCount, needSum));
        }
        if (groups.isEmpty()) {
            lastSignature = "";
            return;
        }
        groups.sort(Comparator.comparingLong(Group::needSum).reversed());

        StringBuilder signature = new StringBuilder();
        for (Group group : groups) {
            signature.append(group.source()).append('=').append(group.needSum()).append(':');
            for (MaterialWatchlist.WatchItem item : group.items()) {
                signature.append(Registries.ITEM.getId(item.item())).append(',');
            }
            signature.append(';');
        }
        String sig = signature.toString();
        long now = System.currentTimeMillis();
        if (sig.equals(lastSignature) || now - lastSentMs < intervalMs) {
            return;
        }
        lastSignature = sig;
        lastSentMs = now;

        int shown = Math.min(6, groups.size());
        MutableText message = Text.literal("附近 " + Configs.MATERIAL_SCAN_RADIUS.getIntegerValue() + " 格：");
        for (int i = 0; i < shown; i++) {
            Group group = groups.get(i);
            if (i > 0) {
                message.append(Text.literal("\n"));
            }
            message.append(Text.literal("• " + group.source() + " ×" + group.nearby()
                    + breakdown(group.nearby(), group.items().get(0).item())
                    + "｜" + group.items().size() + " 种共缺 " + group.needSum()
                    + breakdown(group.needSum(), group.items().get(0).item()) + " "));

            StringJoiner ids = new StringJoiner(",");
            for (MaterialWatchlist.WatchItem item : group.items()) {
                ids.add(Registries.ITEM.getId(item.item()).toString());
            }
            message.append(Text.literal("[拉黑全部]").styled(style -> style
                    .withColor(0xFF5555)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/lcf scan blacklist add " + ids))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Text.literal("把 " + group.source() + " 下的 " + group.items().size()
                                    + " 种缺料一起加入黑名单")))));
        }
        if (groups.size() > shown) {
            message.append(Text.literal("\n…另有 " + (groups.size() - shown) + " 种来源可采（可看 GUI 或调低过滤）"));
        }
        client.player.sendMessage(message, false);
    }

    /** 材料列表同款「盒/组/个」尾缀；不足一组返回空串。 */
    private static String breakdown(long count, Item item) {
        if (count <= 0 || item == null) {
            return "";
        }
        int max = new ItemStack(item).getMaxCount();
        if (max <= 0) {
            return "";
        }
        String formatted = MaterialBreakdownHint.formatCount(count, max);
        int space = formatted.indexOf(' ');
        return space < 0 ? "" : "=" + formatted.substring(space + 1);
    }
}
