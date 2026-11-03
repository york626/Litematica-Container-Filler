package com.mimicenzymes.litematicafiller.compat.sbp;

import net.fabricmc.loader.api.FabricLoader;

/** 精妙背包（Sophisticated Backpacks）是否可用。所有对 SBP 类的接触都要先过这里。 */
public final class SbpSupport {
    private SbpSupport() {
    }

    private static final boolean AVAILABLE = FabricLoader.getInstance().isModLoaded("sophisticatedbackpacks");

    public static boolean available() {
        return AVAILABLE;
    }
}
