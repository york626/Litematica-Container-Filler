package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.tool.BatchToolState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「批量工具」的**远距离开箱 / 远距离点击**（Ray 2026-11-06 需求：二改能清完一整排，我们也要能）。
 *
 * <p>原版服务端在这里有两道距离闸：</p>
 * <ol>
 *   <li>{@code ServerPlayNetworkHandler#onPlayerInteractBlock} 里
 *       {@code ServerPlayerEntity.canInteractWithBlockAt(pos, 1.0)}（约 4.5 格）——超出的右键包直接丢弃；</li>
 *   <li>{@code onClickSlot} 里的 {@code ScreenHandler.canUse(player)} —— 容器菜单用
 *       {@code Inventory.canPlayerUse} 的默认值 **4 格**，超出的槽位点击一律不执行。</li>
 * </ol>
 *
 * <p>另外两道"每 tick 自动关箱"的闸在 {@link ServerPlayerEntityMixin} / {@link PlayerEntityMixin}。
 * 四道都放行，才真正能做到远距离清空（1.12.8 实测通过）。</p>
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
        boolean bypass = Configs.BATCH_TOOL_FAR_REACH.getBooleanValue()
                && BatchToolState.isActive()
                && pos != null
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
        if (!Configs.BATCH_TOOL_FAR_REACH.getBooleanValue()
                || !BatchToolState.isActive()
                || !BatchToolState.isContainerMenu(handler)) {
            return false;
        }
        return true;
    }
}
