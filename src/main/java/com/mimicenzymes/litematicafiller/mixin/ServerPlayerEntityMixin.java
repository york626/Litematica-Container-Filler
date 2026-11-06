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
 * 「批量工具」远距离操作的**第二道闸**：{@code ServerPlayerEntity#tick()} 里的
 * {@code !currentScreenHandler.canUse(this)} ⇒ 每 tick 自动 {@code closeHandledScreen()}。
 *
 * <p>容器菜单的 {@code canUse} 走 {@code Inventory.canPlayerUse} 的默认值 **4 格**；
 * 批量工具运行期间放行（只对容器菜单、且开了 {@code BATCH_TOOL_FAR_REACH}）。</p>
 *
 * <p>⚠️ 父类 {@link PlayerEntityMixin} 里还有一模一样的一份，两份都要放行。</p>
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
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
        return Configs.BATCH_TOOL_FAR_REACH.getBooleanValue()
                && BatchToolState.isActive()
                && BatchToolState.isContainerMenu(handler);
    }
}
