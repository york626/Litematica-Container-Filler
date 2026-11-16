package com.mimicenzymes.litematicafiller.materials;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/**
 * 「材料列表 → 容器槽位高亮」的数据中心（Ray 2026-11-12 要的；2026-11-16 从工具箱迁入，批次 2）。
 *
 * <p>由 {@link FillMaterialCalculator#getCurrentViewMissingForHighlight()} 把**当前视图**里
 * 「还缺的」材料数量灌进来（三视图各算各的，不互串）；打开容器界面时
 * {@code MixinHandledScreenMaterialHighlight} 按**需求数量**逐个槽位扣减，只高亮「够这次用」的那几个
 * （Ray 2026-11-12：一箱子 40 盒激活铁轨、只要 574 个 ⇒ 只闪够数的盒子）。
 *
 * <p>⚠️ 本类**不引用任何 Litematica 类型**（只存 {@link Item} / {@link ItemStack}）⇒
 * 数据源重建失败时也能安全降级，不会 NoClassDefFoundError。
 *
 * <h2>嵌套容器（Ray 2026-11-12 追加）</h2>
 * 盒装材料再装进精妙背包（背包 → 潜影盒 → 材料）也要能算：
 * {@link #consume} 递归拆 **潜影盒 / 收纳袋 / 精妙背包**，最多 4 层。
 * <p><b>有内容物的容器只按内容结算，绝不再用盒子本身顶需求</b> ——
 * 否则材料列表里只要有余「空潜影盒」，一箱装满东西的盒子会全被当成空盒闪起来。
 */
public final class MaterialHighlightState {
    /** 当前材料列表里「还缺的」物品及数量（由数据源刷新，打开容器时冻结）。 */
    private static volatile Map<Item, Long> needed = Map.of();

    private static final int MAX_DEPTH = 4;
    private static final String SB_NAMESPACE = "sophisticatedbackpacks";
    private static Method sbFromExistingData;
    private static Method sbGetInventoryHandler;
    private static Method sbGetSlots;
    private static Method sbGetStackInSlot;
    private static boolean sbLookupFailed;

    private MaterialHighlightState() {
    }

    /** 用材料列表里还缺的「物品 → 数量」刷新（打开容器那一刻调用一次）。 */
    public static void update(Map<Item, Long> counts) {
        if (counts == needed) {
            return;
        }
        needed = counts == null || counts.isEmpty() ? Map.of() : new HashMap<>(counts);
    }

    public static boolean isEmpty() {
        return needed.isEmpty();
    }

    /** 需求条目数（诊断日志用）。 */
    public static int neededCount() {
        return needed.size();
    }

    /** 每帧拿一份可扣减的副本（不污染冻结的原表）。 */
    public static Map<Item, Long> copyCounts() {
        return new HashMap<>(needed);
    }

    /**
     * 这个槽位对剩余需求的贡献：够就扣减并返回 {@code true}（该高亮），不够也照样扣减。
     *
     * @param remaining 本帧的工作副本，调用方逐槽位传入
     */
    public static boolean consume(Map<Item, Long> remaining, ItemStack stack) {
        return consumeStack(remaining, stack, 0);
    }

    private static boolean consumeStack(Map<Item, Long> remaining, ItemStack stack, int depth) {
        if (remaining.isEmpty() || stack == null || stack.isEmpty() || depth > MAX_DEPTH) {
            return false;
        }

        boolean hasContents = false;
        boolean fromContents = false;

        // ① 潜影盒 / 其他容器物品：递归内容物（盒装材料再套一层也认）
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            for (ItemStack inner : container.streamNonEmpty().toList()) {
                hasContents = true;
                fromContents |= consumeStack(remaining, inner, depth + 1);
            }
        }

        // ② 收纳袋
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack inner : bundle.iterate()) {
                hasContents = true;
                fromContents |= consumeStack(remaining, inner, depth + 1);
            }
        }

        // ③ 精妙背包（内容存在世界存档里，只能走它的 API；反射读）
        if (Registries.ITEM.getId(stack.getItem()).getNamespace().equals(SB_NAMESPACE)) {
            List<ItemStack> inner = sophisticatedBackpackContents(stack);
            if (inner != null && !inner.isEmpty()) {
                hasContents = true;
                for (ItemStack stackIn : inner) {
                    fromContents |= consumeStack(remaining, stackIn, depth + 1);
                }
            }
        }

        // 容器**有内容物**时只按内容结算：
        // 即使内容物里没有需要的材料，也不能拿「盒子本身」去顶需求 ——
        // 否则材料列表里只要有余「空潜影盒」这一项，一箱装满东西的盒子会全部被当成空盒闪起来。
        if (hasContents) {
            return fromContents;
        }

        // ④ 空容器 / 普通物品 ⇒ 按物品本身扣减
        Long need = remaining.get(stack.getItem());
        if (need != null && need > 0L) {
            remaining.put(stack.getItem(), need - Math.min(need, stack.getCount()));
            return true;
        }
        return false;
    }

    /** 读精妙背包里的物品；不是背包/读不到时返回 null。 */
    private static List<ItemStack> sophisticatedBackpackContents(ItemStack stack) {
        if (!FabricLoader.getInstance().isModLoaded(SB_NAMESPACE) || sbLookupFailed) {
            return null;
        }
        try {
            if (sbFromExistingData == null) {
                initSophisticatedBackpacks();
            }
            Object optional = sbFromExistingData.invoke(null, stack);
            if (!(optional instanceof Optional<?> opt) || opt.isEmpty()) {
                return null;
            }
            Object wrapper = opt.get();
            Object handler = sbGetInventoryHandler.invoke(wrapper);
            int slots = (Integer) sbGetSlots.invoke(handler);
            List<ItemStack> contents = new ArrayList<>();
            for (int i = 0; i < slots; i++) {
                ItemStack innerStack = (ItemStack) sbGetStackInSlot.invoke(handler, i);
                if (innerStack != null && !innerStack.isEmpty()) {
                    contents.add(innerStack);
                }
            }
            return contents;
        } catch (Throwable ignored) {
            // 反射失配（精妙背包改 API）就当作读不到，别影响界面渲染
            return null;
        }
    }

    private static void initSophisticatedBackpacks() throws ReflectiveOperationException {
        try {
            Class<?> wrapperClass = Class.forName(
                    "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Class<?> handlerClass = Class.forName(
                    "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler");
            sbFromExistingData = wrapperClass.getMethod("fromExistingData", ItemStack.class);
            sbGetInventoryHandler = wrapperClass.getMethod("getInventoryHandler");
            sbGetSlots = handlerClass.getMethod("getSlots");
            sbGetStackInSlot = handlerClass.getMethod("getStackInSlot", int.class);
        } catch (Throwable t) {
            sbLookupFailed = true;
            throw t;
        }
    }
}
