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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【野外材料感知】聊天提示：`附近 32 格：• 沙砾 ×104=1组+40｜沙砾 还缺 8511=4盒+… [拉黑]`。
 *
 * <p>数量格式直接复用材料列表的 {@link MaterialBreakdownHint#formatCount(long, int)}
 * （一盒 = 27 组；组 = 堆叠上限；余数按个），所以和 GUI 材料列表同一套写法。
 *
 * <p>同一个世界载体（沙砾、原木）只占一行：附近数量只报一次，后面把依赖它的缺料用 `/` 串起来，
 * 避免“沙砾 ×104”刷两遍、“原木 ×322”刷四遍。
 */
public final class MaterialChatNotifier {

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

        StringBuilder signature = new StringBuilder();
        Map<String, List<MaterialWatchlist.WatchItem>> groups = new LinkedHashMap<>();
        int visibleItems = 0;
        for (MaterialWatchlist.WatchItem item : items) {
            long count = nearby.getOrDefault(item.item(), 0L);
            if (count <= 0) {
                continue;
            }
            visibleItems++;
            signature.append(Registries.ITEM.getId(item.item())).append('=').append(count)
                    .append('/').append(item.need()).append(';');
            groups.computeIfAbsent(item.target().source(), k -> new ArrayList<>()).add(item);
        }
        if (groups.isEmpty()) {
            lastSignature = "";
            return;
        }

        String sig = signature.toString();
        long now = System.currentTimeMillis();
        if (sig.equals(lastSignature) || now - lastSentMs < intervalMs) {
            return;
        }
        lastSignature = sig;
        lastSentMs = now;

        MutableText message = Text.literal("附近 " + Configs.MATERIAL_SCAN_RADIUS.getIntegerValue() + " 格：");
        int shownSources = 0;
        int shownItems = 0;
        for (Map.Entry<String, List<MaterialWatchlist.WatchItem>> entry : groups.entrySet()) {
            if (shownSources >= 10) {
                break;
            }
            List<MaterialWatchlist.WatchItem> group = entry.getValue();
            MaterialWatchlist.WatchItem first = group.get(0);
            long count = nearby.getOrDefault(first.item(), 0L);
            if (shownSources > 0) {
                message.append(Text.literal("\n"));
            }
            shownSources++;
            message.append(Text.literal("• " + entry.getKey() + " ×" + count
                    + breakdown(count, first.item()) + "｜"));

            boolean firstItem = true;
            for (MaterialWatchlist.WatchItem item : group) {
                if (!firstItem) {
                    message.append(Text.literal(" / "));
                }
                firstItem = false;
                shownItems++;
                String id = Registries.ITEM.getId(item.item()).toString();
                String name = new ItemStack(item.item()).getName().getString();
                message.append(Text.literal(name + " 还缺 " + item.need()
                        + breakdown(item.need(), item.item()) + " "));
                message.append(Text.literal("[拉黑]").styled(style -> style
                        .withColor(0xFF5555)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/lcf scan blacklist add " + id))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("把 " + name + " 加入野外扫描黑名单")))));
            }
        }
        int remaining = visibleItems - shownItems;
        if (remaining > 0) {
            message.append(Text.literal("\n…另有 " + remaining + " 种可采（可[拉黑]或看 GUI）"));
        }
        client.player.sendMessage(message, false);
    }

    /**
     * 材料列表同款「盒/组/个」尾缀：{@code formatCount(104, 64) = "104 1组+40"} ⇒ {@code "=1组+40"}。
     * 不足一组时 formatCount 只返回数字，这里返回空串（不重复写个数）。
     */
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
