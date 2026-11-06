package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.tool.BatchToolController;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.NbtQueryResponseS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「批量工具」的数据通道（Ray 2026-11-06 拍板，按二改 zhaixianyu/litematica-printer 的思路）。
 *
 * <p>核心：**不建 Screen、不挂 currentScreenHandler**，只从网络包里拿数据：</p>
 * <ul>
 *   <li>{@code onOpenScreen}：记下 syncId + 用 {@code type.create(syncId, playerInventory)} 建一个
 *       **影子菜单**（只用来读槽位、同步玩家背包），然后取消原版 ⇒ 不闪屏、不抢鼠标；</li>
 *   <li>{@code onInventory} / {@code onScreenHandlerSlotUpdate}：把服务端发来的容器内容灌进影子菜单；</li>
 *   <li>{@code onCloseScreen}：吃掉自己那次关箱的回包，避免原版 {@code player.closeScreen()} 顺手关掉
 *       玩家正开着的其它界面。</li>
 * </ul>
 *
 * <p>⚠️ 注入点都放在 {@code NetworkThreadUtils.forceMainThread(...)} **之后**：
 * 原版这个方法在非主线程时会抛 {@code OffThreadException} 并把包重新排到主线程，
 * 所以放到它后面能保证我们的状态机只在主线程被调用（HEAD 注入会在 Netty 线程上跑，造成竞态）。</p>
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    private static final String FORCE_MAIN_THREAD =
            "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/thread/ThreadExecutor;)V";

    @Inject(method = "onOpenScreen", at = @At(value = "INVOKE", target = FORCE_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void litematicaContainerFiller$batchOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        if (BatchToolController.getInstance().handleOpenScreen(packet.getSyncId(), packet.getScreenHandlerType())) {
            ci.cancel();
        }
    }

    @Inject(method = "onInventory", at = @At(value = "INVOKE", target = FORCE_MAIN_THREAD, shift = At.Shift.AFTER))
    private void litematicaContainerFiller$batchInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        BatchToolController.getInstance().handleInventory(packet);
    }

    @Inject(method = "onScreenHandlerSlotUpdate", at = @At(value = "INVOKE", target = FORCE_MAIN_THREAD, shift = At.Shift.AFTER))
    private void litematicaContainerFiller$batchSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        BatchToolController.getInstance().handleSlotUpdate(packet);
    }

    @Inject(method = "onCloseScreen", at = @At(value = "INVOKE", target = FORCE_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void litematicaContainerFiller$batchCloseScreen(CloseScreenS2CPacket packet, CallbackInfo ci) {
        if (BatchToolController.getInstance().handleCloseScreen(packet.getSyncId())) {
            ci.cancel();
        }
    }

    @Inject(method = "onNbtQueryResponse", at = @At("HEAD"))
    private void onNbtQueryResponse(NbtQueryResponseS2CPacket packet, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !RealContainerCache.hasActiveConsumers()) return;
        if (RealContainerCache.canUseOpNbtQuery()) {
            RealContainerCache.handleNbtResponse(packet.getTransactionId(), packet.getNbt());
        }
    }
}
