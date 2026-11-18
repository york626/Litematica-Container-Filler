package com.mimicenzymes.litematicafiller.scan;

/**
 * 材料列表 HUD 本帧的**实际绘制矩形**（由 {@code MixinMaterialListHudRenderer} 捕获）。
 *
 * <p>Ray 2026-11-18：「附近可补」要贴在材料列表**正上方**，不是屏幕左上角。
 * 材料列表的最终坐标是 Litematica 在 {@code render} 内部按对齐方式 + 缩放 + 其他 HUD
 * 行高算出来的，外部无法可靠复刻 ⇒ 直接记它画背景时用的那个矩形。
 *
 * <p>坐标是**缩放前**的 HUD 逻辑坐标（Litematica 在该坐标系里 {@code scale}，我们的面板也
 * 用同一套坐标系，直接对齐即可）。
 */
public final class MaterialScanHudAnchor {

    private static volatile boolean valid = false;
    private static volatile long updatedAt = 0L;
    private static volatile int left = 0;
    private static volatile int top = 0;
    private static volatile int right = 0;
    private static volatile int bottom = 0;

    private MaterialScanHudAnchor() {
    }

    public static void set(int x1, int y1, int x2, int y2) {
        left = x1;
        top = y1;
        right = x2;
        bottom = y2;
        updatedAt = System.currentTimeMillis();
        valid = true;
    }

    /** 超过 1 秒没更新（清单关掉/没有缺料）⇒ 认为不可用，面板回退到默认位置。 */
    public static boolean isFresh() {
        return valid && System.currentTimeMillis() - updatedAt < 1000L;
    }

    public static int left() {
        return left;
    }

    public static int top() {
        return top;
    }

    public static int right() {
        return right;
    }

    public static int bottom() {
        return bottom;
    }
}
