package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「批量工具」的**远距离开箱 / 远距离点击**（Ray 2026-11-06 需求：二改能清完一整排，我们也要能）。
 *
 * <p>原版服务端有两道距离闸：</p>
 * <ol>
 *   <li>{@code ServerPlayNetworkHandler#onPlayerInteractBlock} 里
 *       {@code ServerPlayerEntity.canInteractWithBlockAt(pos, 1.0)}（约 4.5 格）——超出的右键包直接丢弃；</li>
 *   <li>{@code onClickSlot} 里的 {@code ScreenHandler.canUse(player)} —— 容器菜单用
 *       {@code Inventory.canPlayerUse} 的默认值 **4 格**，超出的槽位点击一律不执行。</li>
 * </ol>
 *
 * <p>这里把两道都放行，但都**只对容器菜单/容器方块实体生效**：普通方块、玩家背包菜单仍按原版规则。
 * 只放行第二道而不放行第一道的话，容器能开但点不动（1.9.3 的老坑）。</p>
 *
 * <p>⚠️ 这是**服务端**逻辑：单人 / 装了本模组的服务器有效；纯客户端连别人的服务器仍受对端限制。
 * 开关：{@code Configs.BATCH_TOOL_FAR_REACH}（批量工具页，默认开）。</p>
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerUseItemRangeMixin {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");
    private static long lcf$lastProbe;

    @Redirect(
            method = "onPlayerInteractBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayerEntity;canInteractWithBlockAt(Lnet/minecraft/util/math/BlockPos;D)Z"
            )
    )
    private boolean litematicaContainerFiller$allowFarContainerUse(ServerPlayerEntity player, BlockPos pos, double margin) {
        boolean original = player.canInteractWithBlockAt(pos, margin);
        boolean bypass = Configs.BATCH_TOOL_FAR_REACH.getBooleanValue() && pos != null
                && player.getWorld().getBlockState(pos).hasBlockEntity();
        long now = System.currentTimeMillis();
        if (!original && bypass && now - lcf$lastProbe > 1000L) {
            lcf$lastProbe = now;
            LOGGER.info("[batch-farreach] 放行远距离容器交互 pos={} margin={}", pos, margin);
        }
        return bypass || original;
    }

    @Redirect(
            method = "onClickSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/ScreenHandler;canUse(Lnet/minecraft/entity/player/PlayerEntity;)Z"
            )
    )
    private boolean litematicaContainerFiller$allowFarContainerClicks(ScreenHandler handler, PlayerEntity player) {
        if (handler.canUse(player)) {
            return true;
        }
        if (!Configs.BATCH_TOOL_FAR_REACH.getBooleanValue() || !lcf$isContainerMenu(handler)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lcf$lastProbe > 1000L) {
            lcf$lastProbe = now;
            LOGGER.info("[batch-farreach] 放行远距离容器点击 menu={}", handler.getType());
        }
        return true;
    }

    /** 只认容器类菜单，避免把玩家背包/合成/交易等菜单的距离限制一起改掉。 */
    private static boolean lcf$isContainerMenu(ScreenHandler handler) {
        ScreenHandlerType<?> type = handler.getType();
        return type == ScreenHandlerType.GENERIC_9X1
                || type == ScreenHandlerType.GENERIC_9X2
                || type == ScreenHandlerType.GENERIC_9X3
                || type == ScreenHandlerType.GENERIC_9X4
                || type == ScreenHandlerType.GENERIC_9X5
                || type == ScreenHandlerType.GENERIC_9X6
                || type == ScreenHandlerType.GENERIC_3X3
                || type == ScreenHandlerType.CRAFTER_3X3
                || type == ScreenHandlerType.SHULKER_BOX
                || type == ScreenHandlerType.HOPPER
                || type == ScreenHandlerType.FURNACE
                || type == ScreenHandlerType.BLAST_FURNACE
                || type == ScreenHandlerType.SMOKER
                || type == ScreenHandlerType.BREWING_STAND;
    }
}
