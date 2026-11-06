package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.tool.BatchToolState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「批量工具」远距离操作的**第四道闸**（1.12.7 诊断版调用链实锤）。
 *
 * <p>原版**父类** {@code PlayerEntity#tick()} 里有一段：</p>
 * <pre>
 * if (this.currentScreenHandler != null &amp;&amp; !this.currentScreenHandler.canUse(this)) {
 *     this.closeHandledScreen();
 *     this.currentScreenHandler = this.playerScreenHandler;
 * }
 * </pre>
 *
 * <p>{@code ServerPlayerEntity#tick()} 里还有**一模一样的一份**（1.12.4 已放行），但真正在跑的
 * 是父类这份 ⇒ 远容器刚被打开，下一个 tick 就被关掉，随后发的 {@code ClickSlotC2SPacket}
 * 因 syncId 失效被丢弃（服务端日志 `当前菜单=0`）。</p>
 *
 * <p>批量工具运行期间（且开了 {@code BATCH_TOOL_FAR_REACH}）对**容器菜单**放行；
 * 批量结束立即恢复原版距离规则。</p>
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
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
            LOGGER.info("[batch-farreach] 阻止 PlayerEntity.tick 超距自动关箱 menu={}", handler.getType());
        }
        return true;
    }
}
