package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.tool.BatchClearController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★ 1.11.0-Ray「批量清空容器（独立实现）」：**界面不弹，只挂菜单**。
 *
 * <p>批量清空运行时，服务端发来的 {@code OpenScreen} 由这里接管：用同一个 syncId 建出
 * ScreenHandler 挂到 {@code player.currentScreenHandler}，**不创建 Screen** ⇒
 * 不闪屏、不抢鼠标，`clickSlot` 也照常能发（它只认 handler 的 syncId）。</p>
 *
 * <p>非批量运行时不插手，界面行为与原来一致。</p>
 */
@Mixin(ClientPlayNetworkHandler.class)
public class BatchClearScreenMixin {
    @Inject(method = "onOpenScreen", at = @At("HEAD"), cancellable = true)
    private void lcf$attachMenuWithoutScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        if (!BatchClearController.getInstance().isRunning()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        ScreenHandler handler = packet.getScreenHandlerType()
                .create(packet.getSyncId(), client.player.getInventory());
        if (handler != null) {
            client.player.currentScreenHandler = handler;
        }
        ci.cancel();
    }
}
