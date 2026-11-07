package com.mimicenzymes.litematicafiller.tool;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 「批量工具」执行动作（Ray 2026-11-07 定名，与容器工具的既有语义对齐）：
 *
 * <ul>
 *   <li>{@link #CLEAR}：清空容器（输出方式沿用「清空容器输出」）；</li>
 *   <li>{@link #SCHEMATIC_FILL}：**按投影填充** —— 按每格自己的投影模板填（= 批量版的「投影填充」）；
 *       配置串沿用旧的 {@code fill}（1.12.x 它曾叫「填满容器」，Ray 指出那名字应该是"不看模板填满"）；</li>
 *   <li>{@link #FILL_FULL}：**填满容器** —— 不看模板，用背包里的物品把容器填满
 *       （与容器工具「填满容器」同一套选物规则）。</li>
 * </ul>
 *
 * <p>⚠️ 与 {@link ContainerToolMode} 零共享状态：容器工具仍然是"看准星、单容器"。</p>
 */
public enum BatchToolMode implements IConfigOptionListEntry {
    CLEAR("clear", "litematica_container_filler.config.option.batchToolMode.clear"),
    SCHEMATIC_FILL("fill", "litematica_container_filler.config.option.batchToolMode.fill"),
    FILL_FULL("fill_full", "litematica_container_filler.config.option.batchToolMode.fillFull");

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
