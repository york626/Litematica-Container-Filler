package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.MaterialReplacementUi;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.materials.MaterialListReplacementRefresh;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import com.google.common.collect.ImmutableList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class GuiMaterialListMixin extends GuiBase implements MaterialListReplacementRefresh {

    @Shadow @Final private MaterialListBase materialList;

    @Unique private static java.lang.reflect.Field mimic_materialListAllField = null;
    @Unique private static boolean mimic_reflectionInit = false;
    // Weak keys let closed material lists be collected; MaterialListBase keeps identity equality.
    @Unique private static final Map<MaterialListBase, List<MaterialListEntry>> mimic_originalVanillaLists = new WeakHashMap<>();

    @Unique private boolean mimic_isMonitorRunning = false;
    @Unique private boolean mimic_needsCalculation = true;
    @Unique private List<MaterialListEntry> mimic_cachedVanillaList = null;
    @Unique private ImmutableList<MaterialListEntry> mimic_lastInjectedRef = null;
    /** 上一次**我们自己写进** materialListAll 的那些行（按对象身份记），用于剔除自污染。 */
    @Unique private List<MaterialListEntry> mimic_lastInjectedRows = new java.util.ArrayList<>();
    @Unique private boolean mimic_isInjecting = false;
    @Unique private boolean mimic_forceReplacementRefresh = false;
    @Unique private int mimic_seenReplacementVersion = -1;

    @Override
    public void lcf$refreshMaterialReplacementList() {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (FillMaterialCalculator.listMode == 0) return;

        mimic_needsCalculation = true;
        mimic_forceReplacementRefresh = true;
        mimic_injectSilently();
    }

    @Inject(method = "initGui", at = @At("RETURN"))
    private void onInitGui(CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;

        mimic_addToggleButton();

        if (mimic_isInjecting) return;

        mimic_needsCalculation = true;

        if (FillMaterialCalculator.listMode != 0) {
            mimic_injectSilently();
            mimic_startMonitorIfNeeded();
        }
    }

    @Unique
    private void mimic_addToggleButton() {
        String text = StringUtils.translate("litematica_container_filler.gui.button.mode_blocks_only");
        if (FillMaterialCalculator.listMode == 1) text = StringUtils.translate("litematica_container_filler.gui.button.mode_containers_only");
        else if (FillMaterialCalculator.listMode == 2) text = StringUtils.translate("litematica_container_filler.gui.button.mode_both");

        int maxX = 10;
        int targetY = 26;

        try {
            java.util.List<?> buttons = null;
            for (java.lang.reflect.Field f : GuiBase.class.getDeclaredFields()) {
                if (f.getType() == java.util.List.class && f.getName().toLowerCase().contains("button")) {
                    f.setAccessible(true);
                    buttons = (java.util.List<?>) f.get(this);
                    break;
                }
            }
            if (buttons != null) {
                for (Object btnObj : buttons) {
                    if (btnObj instanceof fi.dy.masa.malilib.gui.button.ButtonBase btn) {
                        if (btn.getY() < 50) {
                            int rightEdge = btn.getX() + btn.getWidth();
                            if (rightEdge > maxX) {
                                maxX = rightEdge;
                                targetY = btn.getY();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        String clearText = StringUtils.translate("litematica_container_filler.gui.button.clear_schematic_replacements");
        int clearWidth = mimic_getButtonWidth(clearText, 44);
        ButtonGeneric clearBtn = new ButtonGeneric(maxX + 1, targetY, clearWidth, 20, clearText);
        clearBtn.setEnabled(MaterialReplacementUi.findSchematicKey((GuiMaterialList) (Object) this) != null);
        clearBtn.setHoverStrings(StringUtils.translate("litematica_container_filler.gui.tooltip.clear_schematic_replacements"));

        this.addButton(clearBtn, (button, mouseButton) -> {
            MaterialReplacementUi.clearCurrentSchematicReplacementRules((GuiMaterialList) (Object) this);
            mimic_needsCalculation = true;
            mimic_forceReplacementRefresh = true;
            mimic_lastInjectedRef = null;
            mimic_injectViaApi();
        });

        int toggleWidth = mimic_getButtonWidth(text, 44);
        ButtonGeneric toggleBtn = new ButtonGeneric(maxX + clearWidth + 2, targetY, toggleWidth, 20, text);
        toggleBtn.setHoverStrings(StringUtils.translate("litematica_container_filler.gui.tooltip.material_list_mode"));

        this.addButton(toggleBtn, (button, mouseButton) -> {
            FillMaterialCalculator.listMode = (FillMaterialCalculator.listMode + 1) % 3;
            FillMaterialCalculator.isFillMode = (FillMaterialCalculator.listMode != 0);
            mimic_needsCalculation = true;
            mimic_lastInjectedRef = null;

            mimic_injectViaApi();
            mimic_startMonitorIfNeeded();
        });
    }

    @Unique
    private void mimic_startMonitorIfNeeded() {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (mimic_isMonitorRunning || FillMaterialCalculator.listMode == 0) return;

        mimic_isMonitorRunning = true;
        Thread monitor = new Thread(() -> {
            int tickCount = 0;
            while (Configs.ENABLE_MOD.getBooleanValue() &&
                    MinecraftClient.getInstance().currentScreen == this &&
                    FillMaterialCalculator.listMode != 0) {
                try {
                    Thread.sleep(50);
                    tickCount++;

                    final boolean forceRefresh = (tickCount % 10 == 0) && FillMaterialCalculator.hasMissingData;
                    MinecraftClient.getInstance().execute(() -> {
                        if (Configs.ENABLE_MOD.getBooleanValue() &&
                                MinecraftClient.getInstance().currentScreen == this &&
                                FillMaterialCalculator.listMode != 0) {
                            if (forceRefresh) mimic_needsCalculation = true;
                            mimic_injectSilently();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
            mimic_isMonitorRunning = false;
        });
        monitor.setDaemon(true);
        monitor.setName("LitematicaFiller-InjectionWatchdog");
        monitor.start();
    }

    @Unique
    private int mimic_getButtonWidth(String text, int minWidth) {
        return Math.max(minWidth, this.getStringWidth(text) + 12);
    }

    @Unique
    private void mimic_injectViaApi() {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        try {
            if (!(materialList instanceof IMaterialList iMatList)) return;
            FillMaterialCalculator.syncMaterialReplacementRules();
            mimic_seenReplacementVersion = FillMaterialCalculator.getMaterialReplacementVersion();
            if (mimic_cachedVanillaList == null) {
                ImmutableList<MaterialListEntry> current = mimic_readMaterialListAll();
                mimic_cachedVanillaList = mimic_getOriginalVanillaList(current);
            }
            if (mimic_cachedVanillaList == null) return;

            if (FillMaterialCalculator.listMode != 0) {
                FillMaterialCalculator.calculate(this, true, mimic_cachedVanillaList);
                mimic_needsCalculation = false;
            }

            List<MaterialListEntry> targetList = mimic_buildTargetList();

            // ⛔ v1.18.1-Ray：视图算出来是空的、但清单里有东西 ⇒ **不覆盖**（等统计/缓存就绪）。
            if (targetList.isEmpty()) {
                ImmutableList<MaterialListEntry> now = mimic_readMaterialListAll();
                if (now != null && !now.isEmpty()) {
                    return;
                }
            }

            // ⛔ v1.16.16（Ray 实测「容器视图把方块视图的箱子/台阶/楼梯都显示出来了」）：
            //    只写**视图行**——界面渲染读的正是 materialListAll，写 concat（原生+视图）等于把
            //    原生行也显示进容器视图。原生数据由 mimic_originalVanillaLists 缓存兜底，
            //    切回方块视图时恢复（injectSilently 的 listMode==0 分支）。
            List<MaterialListEntry> writeBack = targetList;
            int widgetBefore = mimic_widgetEntryCount();

            mimic_isInjecting = true;
            try {
                iMatList.setMaterialListEntries(writeBack);
            } catch (Throwable t) {
                // 诊断（v1.20.6）：写列表中途抛异常时 materialListAll 已换、控件没刷 ⇒ 会闪旧列表
                FillMaterialCalculator.logViewDebugError("setMaterialListEntries 抛异常（列表写了但控件没刷）", t);
            } finally {
                mimic_isInjecting = false;
            }

            ImmutableList<MaterialListEntry> newRef = mimic_readMaterialListAll();
            mimic_lastInjectedRef = newRef;
            mimic_lastInjectedRows = new ArrayList<>(writeBack);

            // ⭐ v1.20.5-Ray（Ray 2026-11-17「切 3 个视图时会跳一下才显示对应视图」）：
            //   写完**立刻**刷新控件——以前这里不刷，要等看门狗下一拍（≤50ms）才重建列表，
            //   中间那几帧还是旧视图 ⇒ 看起来"跳一下"。injectSilently 一直有这个刷新，按钮这条路漏了。
            //   滚动位置照 injectSilently 的做法保留。
            int scroll = mimic_getScrollPosition();
            mimic_refreshWidget();
            mimic_setScrollPosition(scroll);
            FillMaterialCalculator.logViewDebug("切视图：mode={} 目标={}行｜控件前={}行 刷新后={}行｜列表={}行",
                    FillMaterialCalculator.listMode, writeBack.size(), widgetBefore,
                    mimic_widgetEntryCount(),
                    mimic_readMaterialListAll() == null ? -1 : mimic_readMaterialListAll().size());

        } catch (Exception ignored) {}
    }

    /** 诊断（v1.20.6）：控件当前显示多少行（读 WidgetListBase.listContents）。 */
    @Unique
    private int mimic_widgetEntryCount() {
        try {
            Object widget = mimic_getWidget();
            if (widget == null) return -1;
            Class<?> c = widget.getClass();
            while (c != null) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getName().equals("listContents")) {
                        f.setAccessible(true);
                        Object v = f.get(widget);
                        return v instanceof java.util.List<?> list ? list.size() : -1;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }


    @Unique
    @SuppressWarnings("unchecked")
    private void mimic_injectSilently() {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (FillMaterialCalculator.listMode == 0) {
            // ⛔ **方块视图：恢复原生行**（v1.16.15，Ray 报「方块视图平滑石英块显示 1 而不是 697」）：
            // 之前容器/全部视图注入时把过滤后的行写回了 materialListAll，**697 行的原生数据被覆盖丢失**；
            // 切回方块视图时读到的就是被污染的数据。现在切视图时把原生行写回去，数据不再丢。
            if (mimic_cachedVanillaList != null && !mimic_cachedVanillaList.isEmpty()) {
                try {
                    mimic_writeMaterialListAll(ImmutableList.copyOf(mimic_cachedVanillaList));
                    materialList.refreshPreFilteredList();
                    materialList.updateCounts();
                    mimic_lastInjectedRef = mimic_readMaterialListAll();
                } catch (Exception ignored) {}
            }
            return;
        }

        try {
            ImmutableList<MaterialListEntry> currentRef = mimic_readMaterialListAll();
            if (currentRef == null) return;

            FillMaterialCalculator.syncMaterialReplacementRules();
            int replacementVersion = FillMaterialCalculator.getMaterialReplacementVersion();
            if (replacementVersion != mimic_seenReplacementVersion) {
                mimic_seenReplacementVersion = replacementVersion;
                mimic_needsCalculation = true;
                mimic_forceReplacementRefresh = true;
            }

            if (mimic_cachedVanillaList == null) {
                mimic_cachedVanillaList = mimic_getOriginalVanillaList(currentRef);
                if (mimic_cachedVanillaList == null) {
                    // 清单还没统计出来（放置关闭后首次打开就是这种）⇒ 先别动界面
                    return;
                }
                mimic_needsCalculation = true;
            } else if (!mimic_forceReplacementRefresh && currentRef != mimic_lastInjectedRef) {
                // ⛔ v1.16.20-Ray（Ray 2026-11-15：「打开一下全部视图，平滑石英块就多一行」）：
                //   旧写法把 currentRef **整份**当成"原生行"收下。但 currentRef 里混着**我们自己上次写进去的
                //   容器行**（引用判据只有在没人换过 materialListAll 时才成立；切视图/onTaskCompleted 重建后
                //   引用就变了）⇒ 每次刷新都把上次的合并结果再叠一遍 ⇒ 行数 1、2、3、4… 一路长。
                //   修：按**对象身份**剔除我们写进去的行，只收 Litematica 自己造的行；剔完为空就保持旧缓存。
                java.util.IdentityHashMap<MaterialListEntry, Boolean> ours = new java.util.IdentityHashMap<>();
                for (MaterialListEntry injected : mimic_lastInjectedRows) {
                    ours.put(injected, Boolean.TRUE);
                }
                List<MaterialListEntry> captured = new ArrayList<>();
                for (MaterialListEntry candidate : currentRef) {
                    if (!ours.containsKey(candidate)) {
                        captured.add(candidate);
                    }
                }
                if (!captured.isEmpty()) {
                    mimic_cachedVanillaList = captured;
                    mimic_originalVanillaLists.put(materialList, captured);
                    mimic_needsCalculation = true;
                }
            }

            if (mimic_needsCalculation) {
                FillMaterialCalculator.calculate(this, true, mimic_cachedVanillaList);
                mimic_needsCalculation = false;
            } else if (!FillMaterialCalculator.hasMissingData) {
                return;
            }

            List<MaterialListEntry> targetList = mimic_buildTargetList();

            // ⛔ v1.18.1-Ray：视图算出来是空的、但清单里有东西 ⇒ **不覆盖**（等统计/缓存就绪）。
            if (targetList.isEmpty() && !currentRef.isEmpty()) {
                return;
            }

            int scroll = mimic_getScrollPosition();

            // ⛔ **只写视图行**（v1.16.16，Ray 实测「容器视图混进方块视图的箱子/台阶/楼梯」）：
            // 界面渲染读的就是 materialListAll ⇒ 写什么显示什么。v1.16.15 写 concat（原生+视图）
            // 保住了数据却把原生行也显示进了容器视图。原生数据改由 mimic_originalVanillaLists
            // 缓存兜底：方块视图（listMode==0）走上面的恢复分支写回缓存的原生行。
            // ⚠️ 自污染免疫：下面 lastInjectedRef 记住我们写的引用，line ~247 的
            //    「currentRef != lastInjectedRef ⇒ 收进缓存」只会收 Litematica 自己重算的原生行。
            mimic_writeMaterialListAll(ImmutableList.copyOf(targetList));

            materialList.refreshPreFilteredList();
            materialList.updateCounts();

            mimic_lastInjectedRef = mimic_readMaterialListAll();
            mimic_lastInjectedRows = new ArrayList<>(targetList);
            mimic_forceReplacementRefresh = false;

            mimic_refreshWidget();

            mimic_setScrollPosition(scroll);

        } catch (Exception ignored) {}
    }

    @Unique
    private List<MaterialListEntry> mimic_buildTargetList() {
        if (FillMaterialCalculator.listMode == 1) {
            return FillMaterialCalculator.getCustomMaterialList(materialList);
        } else if (FillMaterialCalculator.listMode == 2) {
            return FillMaterialCalculator.mergeLists(
                    mimic_cachedVanillaList,
                    FillMaterialCalculator.getCustomMaterialList(materialList));
        } else {
            return new ArrayList<>(mimic_cachedVanillaList);
        }
    }

    @Unique
    private List<MaterialListEntry> mimic_getOriginalVanillaList(ImmutableList<MaterialListEntry> currentRef) {
        if (currentRef == null) return null;

        List<MaterialListEntry> original = mimic_originalVanillaLists.get(materialList);
        if (original != null && !original.isEmpty()) {
            return new ArrayList<>(original);
        }
        // ⛔ v1.18.1-Ray（Ray 2026-11-16「关掉放置启用后打开材料列表就空了」的根因）：
        //   统计任务还没跑完时 materialListAll 是**空的**（放置关闭时首次打开尤其如此）；
        //   旧写法把这份空表当"原生行"缓存下来 ⇒ 之后每 50ms 的注入看门狗一直拿空原生行
        //   构建视图，把统计出来的真清单覆盖成空 ⇒ 界面一直「被清空」。
        //   空表**不缓存、也不当有效原生行**，等统计结果到了再收。
        if (currentRef.isEmpty()) {
            mimic_originalVanillaLists.remove(materialList);
            return null;
        }
        original = new ArrayList<>(currentRef);
        mimic_originalVanillaLists.put(materialList, original);
        return new ArrayList<>(original);
    }

    @Unique
    private static void mimic_ensureReflection() {
        if (mimic_reflectionInit) return;
        mimic_reflectionInit = true;
        try {
            mimic_materialListAllField = MaterialListBase.class.getDeclaredField("materialListAll");
            mimic_materialListAllField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    @Unique
    @SuppressWarnings("unchecked")
    private ImmutableList<MaterialListEntry> mimic_readMaterialListAll() {
        mimic_ensureReflection();
        if (mimic_materialListAllField == null) return null;
        try {
            return (ImmutableList<MaterialListEntry>) mimic_materialListAllField.get(materialList);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private void mimic_writeMaterialListAll(ImmutableList<MaterialListEntry> list) {
        mimic_ensureReflection();
        if (mimic_materialListAllField == null) return;
        try {
            mimic_materialListAllField.set(materialList, list);
        } catch (Exception ignored) {}
    }

    @Unique
    private void mimic_refreshWidget() {
        try {
            Object widget = mimic_getWidget();
            if (widget != null) {
                widget.getClass().getMethod("refreshEntries").invoke(widget);
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private Object mimic_getWidget() {
        try {
            Class<?> curr = this.getClass();
            while (curr != null) {
                for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                    if (f.getName().equals("m_widget") || f.getName().equals("widget")) {
                        f.setAccessible(true);
                        return f.get(this);
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Unique
    private int mimic_getScrollPosition() {
        try {
            Object widget = mimic_getWidget();
            if (widget != null) {
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                return (int) scrollbar.getClass().getMethod("getValue").invoke(scrollbar);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    @Unique
    private void mimic_setScrollPosition(int scroll) {
        if (scroll <= 0) return;
        try {
            Object widget = mimic_getWidget();
            if (widget != null) {
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                scrollbar.getClass().getMethod("setValue", int.class).invoke(scrollbar, scroll);
            }
        } catch (Exception ignored) {}
    }
}
