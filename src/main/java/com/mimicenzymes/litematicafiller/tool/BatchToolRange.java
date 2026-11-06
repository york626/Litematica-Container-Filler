package com.mimicenzymes.litematicafiller.tool;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 「批量工具」作用范围（Ray 2026-11-06 定）。
 *
 * <ul>
 *   <li>{@link #SELECTION}：Litematica 投影选区内的所有容器（Ray 的口径：框选到哪就处理哪）；</li>
 *   <li>{@link #CROSSHAIR}：准星指向的单个容器（先用它验证数据通道可行）。</li>
 * </ul>
 */
public enum BatchToolRange implements IConfigOptionListEntry {
    SELECTION("selection", "litematica_container_filler.config.option.batchToolRange.selection"),
    CROSSHAIR("crosshair", "litematica_container_filler.config.option.batchToolRange.crosshair");

    private final String configString;
    private final String translationKey;

    BatchToolRange(String configString, String translationKey) {
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
        BatchToolRange[] values = values();
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
        for (BatchToolRange range : values()) {
            if (range.configString.equalsIgnoreCase(value)) {
                return range;
            }
        }
        return SELECTION;
    }
}
