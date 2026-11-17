package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【统一材料列表入口】Ray 2026-11-17 定稿：**L 热键打开的材料列表 = 配置里打开的材料列表**。
 *
 * <h2>原版行为（为什么 L 有时"不对劲"）</h2>
 * {@code KeyCallbacks.KeyCallbackHotkeys#onKeyAction} 里：
 * <pre>
 *   MaterialListBase materialList = DataManager.getMaterialList();   // 全局缓存：上次打开的那份，且**不重算**
 *   if (materialList == null) { 选中放置.getMaterialList(); reCreateMaterialList(); }
 * </pre>
 * 因此 L 可能打开：① 别的放置的列表；② 旧数据（不重新统计）；
 * ③ 不绑定放置的列表（「加载原理图」界面设的 MaterialListSchematic、
 * 「区域选择编辑器」设的 MaterialListAreaAnalyzer）。
 * 而「配置→原理图放置→该放置→材料列表」永远是 {@code placement.getMaterialList() + reCreateMaterialList()}。
 *
 * <h2>本 mixin</h2>
 * 在热键处理 HEAD 拦下 {@code openGuiMaterialList}：有选中放置 ⇒ 按配置那条路重建并打开（与原版同一对象、
 * 同一份统计）；没有选中放置 ⇒ 不干预，保持原版行为。
 */
@Mixin(targets = "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys", remap = false)
public abstract class MixinKeyCallbackHotkeys {

    @Inject(method = "onKeyAction", at = @At("HEAD"), cancellable = true, remap = false)
    private void lcf$openSelectedPlacementMaterialList(KeyAction action, IKeybind key,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (!Configs.MATERIAL_LIST_HOTKEY_PLACEMENT.getBooleanValue()) {
            return;
        }
        if (key != Hotkeys.OPEN_GUI_MATERIAL_LIST.getKeybind()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            return; // 原版这里也会直接 return false
        }
        try {
            SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
            if (placement == null) {
                return; // 没选中放置 ⇒ 走原版（缓存/报错）
            }
            MaterialListBase materialList = placement.getMaterialList();
            materialList.reCreateMaterialList();
            DataManager.setMaterialList(materialList);
            GuiBase.openGui(new GuiMaterialList(materialList));
            cir.setReturnValue(true);
        } catch (Throwable ignored) {
            // 任何异常都退回原版路径
        }
    }
}
