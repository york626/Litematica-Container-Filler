package com.mimicenzymes.litematicafiller.tool;

import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;

/**
 * 批量工具的跨线程状态（服务端 mixin 也要读）。
 *
 * <p>⚠️ 这个类**故意不引用任何客户端类**：`ServerPlayerEntity` / `ServerPlayNetworkHandler`
 * 的 mixin 会在服务端线程读它，别把 {@code MinecraftClient} 拖进来。</p>
 */
public final class BatchToolState {
    /** 批量工具是否正在跑（客户端线程写、服务端线程读 ⇒ volatile）。 */
    private static volatile boolean active;

    private BatchToolState() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * 只认容器类菜单，避免把玩家背包/合成/交易等菜单的距离限制一起改掉。
     * （`ServerPlayerEntity` 与 `ServerPlayNetworkHandler` 的 mixin 共用。）
     */
    public static boolean isContainerMenu(ScreenHandler handler) {
        if (handler == null) {
            return false;
        }
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
