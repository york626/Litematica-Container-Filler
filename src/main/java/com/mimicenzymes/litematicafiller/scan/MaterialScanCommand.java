package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 【野外材料感知】黑名单命令：聊天里的 [拉黑] 点一下就是执行这个。
 *
 * <pre>
 * /lcf scan blacklist add &lt;item&gt;
 * /lcf scan blacklist remove &lt;item&gt;
 * /lcf scan blacklist list
 * </pre>
 */
public final class MaterialScanCommand {

    private MaterialScanCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("lcf")
                        .then(ClientCommandManager.literal("scan")
                                .then(ClientCommandManager.literal("blacklist")
                                        .then(ClientCommandManager.literal("add")
                                                .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                                        .executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "item")))))
                                        .then(ClientCommandManager.literal("remove")
                                                .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "item")))))
                                        .then(ClientCommandManager.literal("list")
                                                .executes(ctx -> list(ctx.getSource())))))));
    }

    private static int add(FabricClientCommandSource source, String raw) {
        List<String> ids = normalizeMany(raw);
        if (ids == null) {
            source.sendError(Text.literal("无效物品 ID：" + raw));
            return 0;
        }
        List<String> strings = new ArrayList<>(Configs.MATERIAL_SCAN_BLACKLIST.getStrings());
        int added = 0;
        for (String id : ids) {
            if (!strings.contains(id)) {
                strings.add(id);
                added++;
            }
        }
        if (added == 0) {
            source.sendFeedback(Text.literal("已在黑名单：" + String.join(", ", ids)));
            return 1;
        }
        Configs.MATERIAL_SCAN_BLACKLIST.setStrings(strings);
        Configs.MATERIAL_SCAN_BLACKLIST.setModified();
        Configs.saveToFile();
        MaterialWatchlist.markDirty();
        source.sendFeedback(Text.literal("已拉黑 " + added + " 种：" + String.join(", ", ids)));
        return 1;
    }

    private static int remove(FabricClientCommandSource source, String raw) {
        List<String> ids = normalizeMany(raw);
        if (ids == null) {
            source.sendError(Text.literal("无效物品 ID：" + raw));
            return 0;
        }
        List<String> strings = new ArrayList<>(Configs.MATERIAL_SCAN_BLACKLIST.getStrings());
        int removed = 0;
        for (String id : ids) {
            if (strings.remove(id)) {
                removed++;
            }
        }
        if (removed == 0) {
            source.sendFeedback(Text.literal("不在黑名单：" + String.join(", ", ids)));
            return 1;
        }
        Configs.MATERIAL_SCAN_BLACKLIST.setStrings(strings);
        Configs.MATERIAL_SCAN_BLACKLIST.setModified();
        Configs.saveToFile();
        MaterialWatchlist.markDirty();
        source.sendFeedback(Text.literal("已移出黑名单 " + removed + " 种：" + String.join(", ", ids)));
        return 1;
    }

    private static int list(FabricClientCommandSource source) {
        List<String> strings = Configs.MATERIAL_SCAN_BLACKLIST.getStrings();
        if (strings.isEmpty()) {
            source.sendFeedback(Text.literal("黑名单为空"));
            return 1;
        }
        source.sendFeedback(Text.literal("黑名单（" + strings.size() + "）：" + String.join(", ", strings)));
        return 1;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        Identifier id = value.contains(":") ? Identifier.tryParse(value) : Identifier.of("minecraft", value);
        if (id == null) {
            return null;
        }
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) {
            return null;
        }
        return Registries.ITEM.getId(item).toString();
    }

    /** 支持逗号分隔的批量 ID（聊天里的 [拉黑全部] 用）。 */
    private static List<String> normalizeMany(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = normalize(part);
            if (id == null) {
                return null;
            }
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out.isEmpty() ? null : out;
    }
}
