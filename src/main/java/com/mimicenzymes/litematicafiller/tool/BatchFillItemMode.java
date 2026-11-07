package com.mimicenzymes.litematicafiller.tool;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 「填充容器」的取物模式（对齐打印机「填充」页的做法）：
 *
 * <ul>
 *   <li>{@link #BLOCKLIST}：按「填充物品列表」里的物品填；</li>
 *   <li>{@link #HANDHELD}：按**手持物品**填。</li>
 * </ul>
 */
public enum BatchFillItemMode implements IConfigOptionListEntry {
    BLOCKLIST("blocklist", "litematica_container_filler.config.option.batchFillItemMode.blocklist"),
    HANDHELD("handheld", "litematica_container_filler.config.option.batchFillItemMode.handheld");

    private final String configString;
    private final String translationKey;

    BatchFillItemMode(String configString, String translationKey) {
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
        BatchFillItemMode[] values = values();
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
        for (BatchFillItemMode mode : values()) {
            if (mode.configString.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return BLOCKLIST;
    }
}
