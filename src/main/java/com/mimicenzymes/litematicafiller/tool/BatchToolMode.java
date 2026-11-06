package com.mimicenzymes.litematicafiller.tool;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 「批量工具」执行动作（Ray 2026-11-06 定：独立页面，不沿用容器工具的模式）。
 *
 * <p>⚠️ 与 {@link ContainerToolMode} **零共享状态**：容器工具仍然是"看准星、单容器"，
 * 批量工具走自己的数据通道状态机。</p>
 */
public enum BatchToolMode implements IConfigOptionListEntry {
    CLEAR("clear", "litematica_container_filler.config.option.batchToolMode.clear"),
    FILL("fill", "litematica_container_filler.config.option.batchToolMode.fill");

    private final String configString;
    private final String translationKey;

    BatchToolMode(String configString, String translationKey) {
        this.configString = configString;
        this.translationKey = translationKey;
    }

    @Override
    public String getStringValue() {
        return this.configString;
    }

    @Override
    public String getDisplayName() {
        return StringUtils.translate(this.translationKey);
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        BatchToolMode[] values = values();
        int index = this.ordinal() + (forward ? 1 : -1);
        if (index < 0) {
            index = values.length - 1;
        } else if (index >= values.length) {
            index = 0;
        }
        return values[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (BatchToolMode mode : values()) {
            if (mode.configString.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return CLEAR;
    }
}
