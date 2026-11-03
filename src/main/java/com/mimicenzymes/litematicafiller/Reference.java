package com.mimicenzymes.litematicafiller;

public class Reference {
    public static final String MOD_ID = "litematica_container_filler";
    public static final String MOD_NAME = "Litematica Container Filler";
    public static final String MOD_SHORT_NAME = "Container Filler";

    /** 运行期读 fabric.mod.json 的版本（标题栏显示用）。 */
    public static final String MOD_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("?");
}
