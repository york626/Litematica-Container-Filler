package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.tool.BatchToolState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「批量工具」远距离操作的**第三道闸**（Ray 2026-11-06 实测：远处容器"摸不到、扔不出来"的真凶）。
 *
 * <p>{@code ServerPlayerEntity#tick()} 里有一段原版逻辑：只要
 * {@code !currentScreenHandler.canUse(this)} 就 **每 tick 自动 closeHandledScreen()**。
 * 容器菜单的 {@code canUse} 走 {@code Inventory.canPlayerUse} 的默认值 **4 格** ⇒
 * 远处的容器被批量工具打开后，**下一个服务端 tick 就被自动关掉**，我们随后发出的
 * `ClickSlotC2SPacket` 因为 syncId 已失效被直接丢弃 —— 表现就是"开得出来但东西扔不掉"。</p>
 *
 * <p>这里把这一处判断在**批量工具运行期间**放行（且只对容器菜单、且开了
 * {@code BATCH_TOOL_FAR_REACH}），让服务端把远容器一直保持打开，直到我们主动开下一格/收尾。</p>
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");
    private static long lcf$lastProbe;

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/ScreenHandler;canUse(Lnet/minecraft/entity/player/PlayerEntity;)Z"
            )
    )
    private boolean litematicaContainerFiller$keepFarContainerOpen(ScreenHandler handler, PlayerEntity player) {
        if (handler.canUse(player)) {
            return true;
        }
        if (!Configs.BATCH_TOOL_FAR_REACH.getBooleanValue()
                || !BatchToolState.isActive()
                || !BatchToolState.isContainerMenu(handler)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lcf$lastProbe > 1000L) {
            lcf$lastProbe = now;
            LOGGER.info("[batch-farreach] 阻止服务端超距自动关箱 menu={}", handler.getType());
        }
        return true;
    }
}
