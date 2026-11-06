package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「批量容器操作」的**远距离开箱**（Ray 2026-11-06 需求）。
 *
 * <p>原版服务端在 {@code ServerPlayNetworkHandler#onPlayerInteractBlock} 里用
 * {@code ServerPlayerEntity.canInteractWithBlockAt(pos, 1.0)} 卡距离（约 4.5 格）——
 * 超出的容器，我们合成的右键包会被直接丢掉，表现就是"一排容器清到第 7、8 个就卡住/变慢"。</p>
 *
 * <p>这里把这一处判断放行，但**只对"带方块实体的方块"（容器）生效**：普通方块的远距离交互
 * 仍按原版规则，避免把整个游戏的交互距离改掉。</p>
 *
 * <p>⚠️ 这是**服务端**逻辑：单人 / 装了本模组的服务器有效；纯客户端连别人的服务器仍受对端限制。
 * 开关：{@code Configs.TOOL_FAR_REACH}（工具页，默认开）。</p>
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
        boolean bypass = Configs.TOOL_FAR_REACH.getBooleanValue() && pos != null
                && player.getWorld().getBlockState(pos).hasBlockEntity();
        long now = System.currentTimeMillis();
        if (now - lcf$lastProbe > 500L) {
            lcf$lastProbe = now;
            LOGGER.info("[farreach] 容器交互 pos={} margin={} 原判={} farReach={} → {}",
                    pos, margin, original, Configs.TOOL_FAR_REACH.getBooleanValue(), bypass ? "放行" : "按原判");
        }
        return bypass || original;
    }
}
