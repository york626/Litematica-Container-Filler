package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★ 1.9.6-Ray 诊断探针：**服务端到底有没有把"打开界面"发回来**。
 *
 * <p>批量清空第 11 个起开箱超时的排查用：这一行出现 ⇒ 服务端发了界面；
 * 没出现 ⇒ 服务端那边就没开（跟距离/命中点无关）。定位完可整段删除（grep `[openscreen]`）。</p>
 */
@Mixin(ClientPlayNetworkHandler.class)
public class OpenScreenProbeMixin {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");
    private static long lcf$last;

    @Inject(method = "onOpenScreen", at = @At("HEAD"))
    private void lcf$probeOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - lcf$last < 200L) {
            return;
        }
        lcf$last = now;
        LOGGER.info("[openscreen] 收到打开界面 syncId={} type={} 工具运行中={} 自动填充运行中={}",
                packet.getSyncId(), packet.getScreenHandlerType(),
                ContainerToolStateMachine.getInstance().isWorking(),
                AutoFillerStateMachine.getInstance().isWorking());
    }
}
