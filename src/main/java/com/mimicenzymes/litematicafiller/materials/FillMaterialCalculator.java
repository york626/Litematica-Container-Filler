package com.mimicenzymes.litematicafiller.materials;

import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData;
import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import com.mimicenzymes.litematicafiller.core.MaterialReplacementScope;
import com.mimicenzymes.litematicafiller.core.MaterialReplacementUi;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.core.SchematicMaterialReplacementContext;
import com.mimicenzymes.litematicafiller.mixin.MaterialListPlacementAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.malilib.util.LayerRange;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.ComponentChanges;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class FillMaterialCalculator {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");
    public static boolean isFillMode = false;
    public static int listMode = 0;

    public static volatile boolean hasMissingData = false;
    private static volatile int materialReplacementVersion = 0;
    private static int seenGlobalReplacementVersion = -1;
    private static int seenSchematicReplacementVersion = -1;

    private static final Map<SchematicPlacement, List<NbtContext>> PLACEMENT_NBT_CACHE = new IdentityHashMap<>();
    public static class ItemStackKey {
        public final Item item;
        public final String customName;
        public final int containerHash;
        public final ComponentChanges componentChanges;

        public ItemStackKey(ItemStack stack) {
            this.item = stack.getItem();
            net.minecraft.text.Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
            this.customName = name != null ? name.getString() : "";
            this.containerHash = computeContainerHash(stack);
            this.componentChanges = stack.getComponentChanges();
        }

        private static int computeContainerHash(ItemStack stack) {
            if (!(stack.getItem() instanceof BlockItem blockItem) ||
                !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
                return 0;
            }

            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) return 0;

            int hash = 0;
            int slot = 0;
            for (ItemStack contained : container.iterateNonEmpty()) {
                hash = 31 * hash + Registries.ITEM.getId(contained.getItem()).hashCode();
                hash = 31 * hash + contained.getCount();
                hash = 31 * hash + slot;
                net.minecraft.text.Text cName = contained.get(DataComponentTypes.CUSTOM_NAME);
                if (cName != null) hash = 31 * hash + cName.getString().hashCode();
                slot++;
            }
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ItemStackKey)) return false;
            ItemStackKey that = (ItemStackKey) o;
            return item.equals(that.item)
                    && customName.equals(that.customName)
                    && containerHash == that.containerHash
                    && componentChanges.equals(that.componentChanges);
        }

        @Override
        public int hashCode() {
            int result = 31 * item.hashCode() + customName.hashCode();
            result = 31 * result + containerHash;
            return 31 * result + componentChanges.hashCode();
        }
    }

    private static class ItemStats {
        int totalAll = 0, missingAll = 0, availableAll = 0, mismatchAll = 0;
        int totalLayer = 0, missingLayer = 0, availableLayer = 0, mismatchLayer = 0;
        ItemStack representative = ItemStack.EMPTY;
    }

    private static class NbtContext {
        final BlockPos worldPos;
        final SchematicPlacement placement;
        final Map<Integer, ItemStack> parsedRequired;
        final Map<ItemStackKey, ItemStack> replacementOrigins;
        final Map<ItemStackKey, MaterialReplacementScope> replacementScopes;

        NbtContext(BlockPos worldPos, SchematicPlacement placement, Map<Integer, ItemStack> parsedRequired,
                   Map<ItemStackKey, ItemStack> replacementOrigins, Map<ItemStackKey, MaterialReplacementScope> replacementScopes) {
            this.worldPos = worldPos;
            this.placement = placement;
            this.parsedRequired = parsedRequired;
            this.replacementOrigins = replacementOrigins;
            this.replacementScopes = replacementScopes;
        }
    }

    private static class ReplacementResult {
        final Map<Integer, ItemStack> items = new HashMap<>();
        final Map<ItemStackKey, ItemStack> origins = new HashMap<>();
        final Map<ItemStackKey, MaterialReplacementScope> scopes = new HashMap<>();
    }

    private static class SchematicSource {
        final LitematicaSchematic schematic;
        final Set<String> regions;

        SchematicSource(LitematicaSchematic schematic, Set<String> regions) {
            this.schematic = schematic;
            this.regions = regions;
        }
    }

    private static final Map<ItemStackKey, ItemStats> itemStatsCache = new HashMap<>();
    private static final Map<ItemStackKey, ItemStack> replacementSourceCache = new HashMap<>();
    private static final Map<ItemStackKey, MaterialReplacementScope> replacementScopeCache = new HashMap<>();
    private static final Set<ItemStackKey> containerMaterialKeys = new HashSet<>();
    private static final Set<ItemStackKey> replacedMaterialKeys = new HashSet<>();

    public static int getMaterialReplacementVersion() {
        return materialReplacementVersion;
    }

    public static ItemStack getOriginalReplacementSource(ItemStack displayedStack) {
        if (displayedStack == null || displayedStack.isEmpty()) return ItemStack.EMPTY;

        ItemStack source = replacementSourceCache.get(new ItemStackKey(displayedStack));
        if (source != null && !source.isEmpty()) {
            ItemStack copy = source.copy();
            copy.setCount(1);
            return copy;
        }

        ItemStack copy = displayedStack.copy();
        copy.setCount(1);
        return copy;
    }

    public static boolean isContainerMaterial(ItemStack stack) {
        return stack != null && !stack.isEmpty() && containerMaterialKeys.contains(new ItemStackKey(stack));
    }

    public static boolean isReplacementDisplay(ItemStack stack) {
        return stack != null && !stack.isEmpty() && replacedMaterialKeys.contains(new ItemStackKey(stack));
    }

    public static MaterialReplacementScope getReplacementDisplayScope(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return replacementScopeCache.get(new ItemStackKey(stack));
    }

    public static List<MaterialListEntry> getCurrentMissingMaterialsForCollection() {
        MaterialListBase materialList = DataManager.getMaterialList();
        if (materialList == null) return Collections.emptyList();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return Collections.emptyList();

        try {
            fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(materialList.getMaterialsAll(), client.player);
            return new ArrayList<>(materialList.getMaterialsMissingOnly(true));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public static int getCurrentMaterialListMultiplier() {
        MaterialListBase materialList = DataManager.getMaterialList();
        return materialList != null ? Math.max(1, materialList.getMultiplier()) : 1;
    }

    /**
     * 【自动收集投影材料】的数据源（Ray 2026-11-14 定稿：**跟随材料列表当前视图**）。
     *
     * <ul>
     *   <li>{@code listMode == 1}（容器视图）⇒ 按 {@link #getCustomMaterialList} 的缺失收（Ray 例：海晶砂粒 4+碎片 4=8）；</li>
     *   <li>{@code listMode == 2}（全部）⇒ 按「原生缺失 + 容器缺失」合并收（Ray 例：砂粒 8+4=12）；</li>
     *   <li>{@code listMode == 0}（建筑方块）⇒ 只收原生清单的缺失。</li>
     * </ul>
     *
     * <p>口径与 GUI 显示完全一致（同一套行数据），切视图再收集 ⇒ 量自动跟着变。
     * ⚠️ 材料列表没打开过时 {@code getMaterialsMissingOnly} 也能算（实时重算 available），
     * 但容器视图的缓存（{@code itemStatsCache}）要 GUI 刷新过才有 —— 没开过 ⇒ 容器部分为空。
     */
    /** 材料列表 GUI 当前显示的行（视图+搜索过滤后；{@code MixinWidgetListBaseCapture} 每次绘制刷新）。 */
    private static volatile List<MaterialListEntry> displayedMaterialRows = List.of();

    public static void setDisplayedMaterialRows(List<?> rows) {
        List<MaterialListEntry> list = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof MaterialListEntry entry && entry != null) {
                list.add(entry);
            }
        }
        displayedMaterialRows = list;
    }

    /**
     * 【自动收集】结果：要收的清单 ＋ 二级计划。
     *
     * <p>⭐ v1.19.1-Ray（Ray 2026-11-16：「材料列表缺 2 个箱子，自动收集把 16 个橡木木板也收来了——
     * 有成品就不用收原料了」）：服务端拿 {@code plan} 判断「容器里成品够整行需求 ⇒ 这行的原料不必收」。
     */
    public record AutoCollectResult(List<ItemStack> items, List<SecondaryPlan> plan) {
    }

    /** 【自动收集】一条二级计划：一级成品 + 本行缺量 + 本行展开的原料（原始量，未扣富余）。 */
    public record SecondaryPlan(net.minecraft.item.Item primary, int need, List<ItemStack> ingredients) {
    }

    public static AutoCollectResult getCurrentMissingForAutoCollect() {
        MinecraftClient client = MinecraftClient.getInstance();
        MaterialListBase materialList = DataManager.getMaterialList();
        if (client.player == null || materialList == null) {
            return new AutoCollectResult(List.of(), List.of());
        }
        // 优先用「GUI 当前显示的行」（视图+搜索过滤后）——Ray 定稿口径：按显示的材料列表收集
        List<MaterialListEntry> displayed = displayedMaterialRows;
        List<ItemStack> fromDisplayed = missingStacksFromRows(displayed);
        if (!fromDisplayed.isEmpty()) {
            List<SecondaryPlan> plan = expandCraftingIngredients(fromDisplayed, client);
            logAutoCollectDetail("displayed(" + displayed.size() + "行)", displayed, fromDisplayed, client);
            return new AutoCollectResult(fromDisplayed, plan);
        }
        List<MaterialListEntry> rows;
        String path;
        try {
            if (listMode == 1) {
                rows = getCustomMaterialList(materialList);
                path = "容器视图(实时)";
            } else if (listMode == 2) {
                List<MaterialListEntry> vanilla;
                fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(
                        materialList.getMaterialsAll(), client.player);
                vanilla = materialList.getMaterialsMissingOnly(true);
                rows = mergeLists(vanilla, getCustomMaterialList(materialList));
                path = "全部视图(实时)";
            } else {
                fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(
                        materialList.getMaterialsAll(), client.player);
                rows = materialList.getMaterialsMissingOnly(true);
                path = "方块视图(实时)";
            }
        } catch (Exception e) {
            return new AutoCollectResult(List.of(), List.of());
        }
        List<ItemStack> out = missingStacksFromRows(rows);
        List<SecondaryPlan> plan = expandCraftingIngredients(out, client);
        logAutoCollectDetail(path + "(" + rows.size() + "行)", rows, out, client);
        return new AutoCollectResult(out, plan);
    }

    /**
     * 【高亮材料列表物品】当前视图「还缺的」物品及数量（Ray 2026-11-16：「根据材料列表的视图高亮，
     * 3 个视图不要串」；批次 2 从工具箱迁入）。
     *
     * <p>口径与材料列表显示一致：**缺失 = 该行总量 − 身上现有**（含盒内一层，同 {@link #countHeldNow}）；
     * **不展开二级原料**（列表里没有的行不高亮——要一并备料用自动收集）。
     *
     * <p>视图选择与 {@link #getCurrentMissingForAutoCollect()} 完全相同：
     * GUI 显示行优先（含搜索过滤），没抓到就按 {@code listMode} 实时算（容器 / 全部 / 方块）。
     */
    public static Map<net.minecraft.item.Item, Long> getCurrentViewMissingForHighlight() {
        MinecraftClient client = MinecraftClient.getInstance();
        MaterialListBase materialList = DataManager.getMaterialList();
        Map<net.minecraft.item.Item, Long> out = new LinkedHashMap<>();
        if (client.player == null || materialList == null) {
            return out;
        }
        List<MaterialListEntry> rows = displayedMaterialRows;
        if (rows.isEmpty()) {
            try {
                if (listMode == 1) {
                    rows = getCustomMaterialList(materialList);
                } else if (listMode == 2) {
                    fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(
                            materialList.getMaterialsAll(), client.player);
                    rows = mergeLists(materialList.getMaterialsMissingOnly(true), getCustomMaterialList(materialList));
                } else {
                    fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(
                            materialList.getMaterialsAll(), client.player);
                    rows = materialList.getMaterialsMissingOnly(true);
                }
            } catch (Exception e) {
                return out;
            }
        }
        for (MaterialListEntry entry : rows) {
            if (entry == null || entry.getStack().isEmpty()) {
                continue;
            }
            net.minecraft.item.Item item = entry.getStack().getItem();
            int missing = Math.max(0, entry.getCountTotal()) - countHeldNow(client, item);
            if (missing > 0) {
                out.merge(item, (long) missing, Long::sum);
            }
        }
        return out;
    }

    /**
     * 【判据日志】收集时把「用了哪些源行、每行总量/显示缺口/身上实有、最后发送什么」打一行。
     *
     * <p>Ray 2026-11-16：「缺 10 个（主行 1 + 二级 9），一次只收 9」——两边口径对不上时，
     * 靠这一行直接看出是**源行没带主行**、还是**身上的数量与显示不一致**、还是**二级富余扣多了**。
     */
    private static void logAutoCollectDetail(String path, List<MaterialListEntry> rows, List<ItemStack> out,
                                             MinecraftClient client) {
        try {
            StringBuilder sb = new StringBuilder("[auto-collect] 计算明细：").append(path)
                    .append("｜视图=").append(listMode).append("｜源行：");
            for (MaterialListEntry entry : rows) {
                if (entry == null || entry.getStack().isEmpty()) {
                    continue;
                }
                net.minecraft.item.Item item = entry.getStack().getItem();
                sb.append(net.minecraft.registry.Registries.ITEM.getId(item).getPath())
                        .append("(总").append(entry.getCountTotal())
                        .append("/显缺").append(entry.getCountMissing())
                        .append("/持").append(countHeldNow(client, item)).append(") ");
            }
            sb.append("⇒ 发送：");
            for (ItemStack stack : out) {
                sb.append(net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath())
                        .append('×').append(stack.getCount()).append(' ');
            }
            // v1.16.27 诊断：两个「自己的总需求」来源各算出来是多少（判断 getMaterialsAll 是否被视图改写）
            MaterialListBase list = DataManager.getMaterialList();
            if (list != null) {
                sb.append("｜素材库：");
                for (ItemStack stack : out) {
                    net.minecraft.item.Item item = stack.getItem();
                    sb.append(net.minecraft.registry.Registries.ITEM.getId(item).getPath())
                            .append("{all=").append(totalOf(list.getMaterialsAll(), item))
                            .append(",custom=").append(totalOf(getCustomMaterialList(list), item)).append("} ");
                }
            }
            LOGGER.info(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 某个物品在给定行集合里的「总量」合计（诊断用）。 */
    private static int totalOf(List<MaterialListEntry> rows, net.minecraft.item.Item item) {
        int sum = 0;
        if (rows == null) {
            return 0;
        }
        for (MaterialListEntry entry : rows) {
            if (entry != null && !entry.getStack().isEmpty() && entry.getStack().getItem() == item) {
                sum += Math.max(0, entry.getCountTotal());
            }
        }
        return sum;
    }

    /**
     * ⛔ v1.16.12 撤销 v1.16.11（Ray 定稿）：**二级展开源＝当前视图的显示行**——
     * 容器视图收容器材料+它们的原料；全部视图收全部+它们的原料。切换视图各收各的，
     * 不把原生缺失行（海晶灯等建筑方块）混进容器视图的收集范围。
     */

    /** 行 → 缺失清单（missing>0 的行，count=缺失量）。 */
    private static List<ItemStack> missingStacksFromRows(List<MaterialListEntry> rows) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<ItemStack> out = new ArrayList<>();
        for (MaterialListEntry entry : rows) {
            if (entry == null || entry.getStack().isEmpty()) {
                continue;
            }
            // ★ v1.16.21-Ray：缺失量按**按热键这一刻**的背包实时算（总量 − 身上现有），
            //   不信清单缓存。好处：① 收完再按一次自然变 0（不需要服务端再扣一次）；
            //   ② 一级/二级口径统一（都是"总量 − 现有"），不会再出现「清单说缺 1153、实际按 1729 收」。
            int total = Math.max(0, entry.getCountTotal());
            int missing = total - countHeldNow(client, entry.getStack().getItem());
            if (missing <= 0) {
                continue;
            }
            ItemStack want = entry.getStack().copy();
            want.setCount(missing);
            out.add(want);
        }
        return out;
    }

    /**
     * 现在身上（36 格 + 盒内一层）有多少个该物品。
     *
     * <p>按「按热键那一刻」实时统计，和 Litematica 的 {@code countAvailable} 口径一致
     * （不含精妙背包 —— 那一层由服务端补扣，见 {@code BatchServerLink.alreadyHeld}）。
     */
    private static int countHeldNow(MinecraftClient client, net.minecraft.item.Item item) {
        if (client == null || client.player == null) {
            return 0;
        }
        int sum = 0;
        var inventory = client.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack slotStack = inventory.getStack(i);
            if (slotStack.isEmpty()) {
                continue;
            }
            if (slotStack.getItem() == item) {
                sum += slotStack.getCount();
            }
            net.minecraft.component.type.ContainerComponent comp =
                    slotStack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
            if (comp != null) {
                for (ItemStack inner : comp.iterateNonEmpty()) {
                    if (inner != null && !inner.isEmpty() && inner.getItem() == item) {
                        sum += inner.getCount();
                    }
                }
            }
        }
        return sum;
    }

    /**
     * 该物品在**整份清单**里的总需求（含"直接放置"那一部分）。
     *
     * <p>用途：判断"身上有多少能拿去合成"。例：平滑石英块总需求 1729 + 台阶/楼梯还要 9 个，
     * 身上 1729 个时富余 = 1729 − 1729 = 0 ⇒ 那 9 个仍然要收（Ray 2026-11-15 的判据）。
     */
    private static int ownTotalDemand(net.minecraft.item.Item item) {
        try {
            fi.dy.masa.litematica.materials.MaterialListBase list = DataManager.getMaterialList();
            if (list == null) {
                return 0;
            }
            int sum = 0;
            for (MaterialListEntry entry : list.getMaterialsAll()) {
                if (entry == null || entry.getStack().isEmpty() || entry.getStack().getItem() != item) {
                    continue;
                }
                sum += Math.max(0, entry.getCountTotal());
            }
            return sum;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 二级材料展开（Ray 2026-11-14：「萤石粉也闪烁了，是需要拿的材料为什么没拿过来」）：
     * 材料列表行右侧的合成提示（例：海晶石砖 1 ← 萤石粉 + 蓝色染料）里的**原料**也是要收集的。
     * 对每个缺失行找它的合成配方，按「缺失量 ÷ 单次产量」向上取整折算原料量，**再减去身上已有**
     * （玩家背包 36 格）——身上够的部分不重复收。只展开一层（原料不再递归）。
     */
    private static List<SecondaryPlan> expandCraftingIngredients(List<ItemStack> wanted, MinecraftClient client) {
        // 【带上二级材料】总开关（Ray 2026-11-15 定稿：默认开=备货带上原料；关=纯严格模式，
        // 显示多少收多少、不带原料——清点/转运场景）
        if (!com.mimicenzymes.litematicafiller.config.Configs.AUTO_COLLECT_SECONDARY_ENABLED.getBooleanValue()) {
            return List.of();
        }
        if (wanted.isEmpty() || client.player == null || client.player.networkHandler == null) {
            return List.of();
        }
        var recipeManager = client.player.networkHandler.getRecipeManager();
        var registryLookup = client.player.getWorld().getRegistryManager();
        // ⚠️ 1.21.1 的 getAllOfType 是 private ⇒ 用公开的 values() 全集再按类型过滤
        List<net.minecraft.recipe.RecipeEntry<net.minecraft.recipe.CraftingRecipe>> crafting = new ArrayList<>();
        for (var entry : recipeManager.values()) {
            if (entry.value() instanceof net.minecraft.recipe.CraftingRecipe cr) {
                crafting.add(new net.minecraft.recipe.RecipeEntry<>(entry.id(), cr));
            }
        }
        // 展开源 = 当前视图的显示行（⛔ v1.16.12：不混入原生缺失行，各视图各收各的）
        record SourceRow(ItemStack stack, int missing) {}
        List<SourceRow> sources = new ArrayList<>();
        for (ItemStack want : wanted) {
            sources.add(new SourceRow(want, want.getCount()));
        }
        List<ItemStack> additions = new ArrayList<>();
        // ⭐ v1.19.1-Ray：本行展开出来的原料（原始量）也记下来，服务端据此判断「成品够就跳过原料」
        List<SecondaryPlan> plan = new ArrayList<>();
        int secondaryThreshold = com.mimicenzymes.litematicafiller.config.Configs.AUTO_COLLECT_SECONDARY_THRESHOLD.getIntegerValue();
        // ★ v1.16.26-Ray（Ray 2026-11-16：「还缺 1 个平滑石英块，自动收集没提取过来」）：
        //   **先把所有行的原料按物品聚合，再统一扣一次「已有富余」**。
        //   旧写法在 `for (Ingredient …)` 里逐槽扣 spare ⇒ 一条 6 格配方被扣 6 次 spare：
        //   实测台阶+楼梯共需 9 个平滑石英块、身上 1737、直接放置占 1729 ⇒ 富余 8，
        //   本该收 9−8=1；逐槽扣（每槽需 1、减 8）⇒ 9 个槽全变 0 ⇒ 一个都不收。
        //   聚合后「同一物品跨行」（台阶/楼梯各扣一次 spare）也只扣一次。
        Map<net.minecraft.item.Item, Integer> totalNeeds = new LinkedHashMap<>();
        for (SourceRow source : sources) {
            int wantMissing = source.missing();
            // 【二级展开阈值】（Ray 2026-11-14：漏斗缺 1198 的铁锭原料 6000 个塞爆背包）：
            // 缺失量超过阈值的行不展开合成原料——巨量缺料走专门获取途径；小缺料（海晶灯缺 1）才合成备料。
            // ⚠️ 只挡二级展开；一级行本体照常收集。
            if (wantMissing > secondaryThreshold) {
                continue;
            }
            ItemStack want = source.stack();
            for (var recipeEntry : crafting) {
                var recipe = recipeEntry.value();
                net.minecraft.item.ItemStack output;
                try {
                    output = recipe.getResult(registryLookup);
                } catch (Throwable ignored) {
                    continue;
                }
                if (output.isEmpty() || output.getItem() != want.getItem()) {
                    continue;
                }
                int outPerCraft = Math.max(1, output.getCount());
                int times = (int) Math.ceil((double) wantMissing / outPerCraft);
                Map<net.minecraft.item.Item, Integer> rowNeeds = new LinkedHashMap<>();
                for (net.minecraft.recipe.Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == net.minecraft.recipe.Ingredient.EMPTY) {
                        continue;
                    }
                    ItemStack[] options = ingredient.getMatchingStacks();
                    if (options == null || options.length == 0 || options[0].isEmpty()) {
                        continue;
                    }
                    int need = options[0].getCount() * times;
                    if (need > 0) {
                        rowNeeds.merge(options[0].getItem(), need, Integer::sum);
                    }
                }
                if (!rowNeeds.isEmpty()) {
                    List<ItemStack> rowIngredients = new ArrayList<>();
                    rowNeeds.forEach((item, amount) -> {
                        totalNeeds.merge(item, amount, Integer::sum);
                        rowIngredients.add(new ItemStack(item, amount));
                    });
                    plan.add(new SecondaryPlan(want.getItem(), wantMissing, rowIngredients));
                }
                break; // 每行只按第一条匹配配方展开
            }
        }
        // 统一扣「已有富余」，**每个物品只扣一次**：
        //   ⚠️ 与一级行**叠加**是 Ray 2026-11-14 定稿（v1.16.8 撤销 v1.16.7 的去重）：
        //   「海晶灯之类需要二级材料正好一起拿过来，减少工作量少拿一次」。
        //   ⛔ v1.16.21-Ray（Ray 2026-11-15）：只有**富余**能算已有 = 身上 − 该物品自己的总需求（1729），
        //   否则身上 1729 个（全被直接放置占用）会把 3/6 的合成需求减成 0。
        for (Map.Entry<net.minecraft.item.Item, Integer> entry : totalNeeds.entrySet()) {
            int have = countHeldNow(client, entry.getKey());
            int reserved = ownTotalDemand(entry.getKey());
            int spare = Math.max(0, have - reserved);
            int need = Math.max(0, entry.getValue() - spare);
            LOGGER.info("[auto-collect] 二级 {}：聚合需求 {}｜已有 {}｜自用 {}｜富余 {}｜最终 {}",
                    net.minecraft.registry.Registries.ITEM.getId(entry.getKey()).getPath(),
                    entry.getValue(), have, reserved, spare, need);
            if (need > 0) {
                additions.add(new ItemStack(entry.getKey(), need));
            }
        }
        // 追加进清单（同物品合并）
        for (ItemStack add : additions) {
            boolean merged = false;
            for (ItemStack w : wanted) {
                if (w.getItem() == add.getItem()) {
                    w.increment(add.getCount());
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                wanted.add(add);
            }
        }
        return plan;
    }

    /**
     * 【提取二级材料】（Ray 2026-11-15 从工具箱移植）当前清单里**需要拿去合成**的原料：物品 → 数量。
     *
     * <p>判定与 {@link #expandCraftingIngredients} 完全同一套（只展开一层、受二级阈值限制、
     * 每行只按第一条匹配配方），区别只在于**只返回原料本身**、不改动一级清单、也不做"已有"扣减
     * （扣减交给服务端按「目标容器里已有」做预算）。
     *
     * @return 物品 → 需求数量；清单拿不到（没打开过材料列表）⇒ {@code null}
     */
    public static Map<net.minecraft.item.Item, Integer> collectSecondaryNeeds() {
        MinecraftClient client = MinecraftClient.getInstance();
        MaterialListBase materialList = DataManager.getMaterialList();
        if (client.player == null || client.player.networkHandler == null || materialList == null) {
            return null;
        }
        List<MaterialListEntry> displayed = displayedMaterialRows;
        List<MaterialListEntry> rows = displayed.isEmpty()
                ? materialList.getMaterialsMissingOnly(true)
                : displayed;
        int secondaryThreshold = com.mimicenzymes.litematicafiller.config.Configs.AUTO_COLLECT_SECONDARY_THRESHOLD.getIntegerValue();
        Map<net.minecraft.item.Item, Integer> out = new LinkedHashMap<>();
        try {
            var recipeManager = client.player.networkHandler.getRecipeManager();
            var registryLookup = client.player.getWorld().getRegistryManager();
            List<net.minecraft.recipe.RecipeEntry<net.minecraft.recipe.CraftingRecipe>> crafting = new ArrayList<>();
            for (var entry : recipeManager.values()) {
                if (entry.value() instanceof net.minecraft.recipe.CraftingRecipe cr) {
                    crafting.add(new net.minecraft.recipe.RecipeEntry<>(entry.id(), cr));
                }
            }
            for (MaterialListEntry row : rows) {
                if (row == null || row.getStack().isEmpty()) {
                    continue;
                }
                int missing = Math.max(0, row.getCountTotal() - countHeldNow(client, row.getStack().getItem()));
                if (missing <= 0 || missing > secondaryThreshold) {
                    continue;
                }
                net.minecraft.item.Item wantItem = row.getStack().getItem();
                for (var recipeEntry : crafting) {
                    net.minecraft.item.ItemStack output;
                    try {
                        output = recipeEntry.value().getResult(registryLookup);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (output.isEmpty() || output.getItem() != wantItem) {
                        continue;
                    }
                    int outPerCraft = Math.max(1, output.getCount());
                    int times = (int) Math.ceil((double) missing / outPerCraft);
                    for (net.minecraft.recipe.Ingredient ingredient : recipeEntry.value().getIngredients()) {
                        if (ingredient == net.minecraft.recipe.Ingredient.EMPTY) {
                            continue;
                        }
                        ItemStack[] options = ingredient.getMatchingStacks();
                        if (options == null || options.length == 0 || options[0].isEmpty()) {
                            continue;
                        }
                        int need = options[0].getCount() * times;
                        if (need > 0) {
                            out.merge(options[0].getItem(), need, Integer::sum);
                        }
                    }
                    break;   // 每行只按第一条匹配配方展开（与收集逻辑一致）
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void requestMaterialReplacementRefresh() {
        seenGlobalReplacementVersion = MaterialReplacer.getGlobalReplacementVersion();
        seenSchematicReplacementVersion = MaterialReplacer.getSchematicReplacementVersion();
        PLACEMENT_NBT_CACHE.clear();
        materialReplacementVersion++;
    }

    public static void syncMaterialReplacementRules() {
        int globalVersion = MaterialReplacer.getGlobalReplacementVersion();
        int schematicVersion = MaterialReplacer.getSchematicReplacementVersion();
        if (globalVersion != seenGlobalReplacementVersion || schematicVersion != seenSchematicReplacementVersion) {
            seenGlobalReplacementVersion = globalVersion;
            seenSchematicReplacementVersion = schematicVersion;
            requestMaterialReplacementRefresh();
        }
    }

    public static void calculate(Object input, boolean silent) {
        calculate(input, silent, null);
    }

    public static void calculate(Object input, boolean silent, List<MaterialListEntry> fallbackEntries) {
        syncMaterialReplacementRules();

        itemStatsCache.clear();
        replacementSourceCache.clear();
        replacementScopeCache.clear();
        containerMaterialKeys.clear();
        replacedMaterialKeys.clear();

        if (!silent) {
            PLACEMENT_NBT_CACHE.clear();
        }

        int foundContainersAll = 0, foundContainersLayer = 0;
        boolean waitingForData = false;

        MinecraftClient client = MinecraftClient.getInstance();

        List<SchematicPlacement> placementsToScan = new ArrayList<>();
        List<SchematicSource> schematicSourcesToScan = new ArrayList<>();

        MaterialListBase sourceMaterialList = null;

        if (input instanceof SchematicPlacement sp) {
            placementsToScan.add(sp);
        } else if (input instanceof Collection<?> coll) {
            for (Object obj : coll) {
                if (obj instanceof SchematicPlacement sp) placementsToScan.add(sp);
            }
        } else {
            MaterialListBase matList = extractMaterialList(input);
            sourceMaterialList = matList;

            if (matList != null) {
                if (matList instanceof MaterialListPlacement mlp) {
                    SchematicPlacement sp = ((MaterialListPlacementAccessor) mlp).getPlacement();
                    if (sp != null) {
                        placementsToScan.add(sp);
                        schematicSourcesToScan.add(new SchematicSource(sp.getSchematic(), null));
                    }
                } else {
                    SchematicSource source = extractSchematicSource(matList);
                    if (source != null) schematicSourcesToScan.add(source);
                }
            }
        }

        if (placementsToScan.isEmpty()) {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            if (manager != null) {
                for (SchematicPlacement p : manager.getAllSchematicsPlacements()) {
                    if (p.isEnabled()) placementsToScan.add(p);
                }
            }
        }

        if (placementsToScan.isEmpty()) {
            if (calculateFromSchematicContainers(schematicSourcesToScan)) {
                hasMissingData = false;
            } else if (calculateFromMaterialListContainers(sourceMaterialList, fallbackEntries)) {
                hasMissingData = false;
            }
            return;
        }

        Map<BlockPos, NbtContext> nbtMap = new HashMap<>();
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();

        for (SchematicPlacement placement : placementsToScan) {
            List<NbtContext> cachedCtx = PLACEMENT_NBT_CACHE.get(placement);

            if (cachedCtx == null) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic == null) continue;
                String schematicKey = SchematicMaterialReplacementContext.keyForPlacement(placement);

                int totalTEs = 0;
                for (String regionName : schematic.getAreaPositions().keySet()) {
                    Map<BlockPos, NbtCompound> teMap = schematic.getBlockEntityMapForRegion(regionName);
                    if (teMap != null) totalTEs += teMap.size();
                }

                if (totalTEs == 0) {
                    PLACEMENT_NBT_CACHE.put(placement, Collections.emptyList());
                    continue;
                }

                Map<BlockPos, NbtContext> bestMap = new HashMap<>();

                if (schematicWorld != null) {
                    // Visit only the placement's stored container block entities instead of every block in its boxes.
                    for (BlockPos worldPos : LitematicaPlacementContainerData.getContainerPositions(placement)) {
                        BlockState state = schematicWorld.getBlockState(worldPos);
                        if (state == null || !state.hasBlockEntity()) continue;

                        BlockEntity be = schematicWorld.getBlockEntity(worldPos);
                        if (be == null) continue;

                        NbtCompound nbt = LitematicaContainerReader.createRawNbt(be, client.world.getRegistryManager());
                        if (nbt != null && nbt.contains("Items")) {
                            ReplacementResult parsedReq = replaceInventoryMapWithOrigins(
                                    RealContainerCache.parseNbtInventory(nbt, client.world.getRegistryManager()),
                                    schematicKey);

                            NbtContext existing = bestMap.get(worldPos);
                            if (existing == null || getItemsCount(nbt) > getItemsCount(existing.parsedRequired)) {
                                bestMap.put(worldPos, new NbtContext(worldPos, placement, parsedReq.items, parsedReq.origins, parsedReq.scopes));
                            }
                        }
                    }
                }
                cachedCtx = new ArrayList<>(bestMap.values());
                PLACEMENT_NBT_CACHE.put(placement, cachedCtx);
            }

            for (NbtContext ctx : cachedCtx) {
                nbtMap.put(ctx.worldPos, ctx);
            }
        }

        if (nbtMap.isEmpty()) {
            if (calculateFromSchematicContainers(schematicSourcesToScan)) {
                hasMissingData = false;
            } else if (calculateFromMaterialListContainers(sourceMaterialList, fallbackEntries)) {
                hasMissingData = false;
            }
            return;
        }

        Set<BlockPos> globalVisited = new java.util.HashSet<>();

        LayerRange layerRange = DataManager.getRenderLayerRange();

        for (Map.Entry<BlockPos, NbtContext> entry : nbtMap.entrySet()) {
            BlockPos pos = entry.getKey();
            NbtContext ctx = entry.getValue();
            replacementSourceCache.putAll(ctx.replacementOrigins);
            addReplacementScopes(ctx.replacementScopes);

            if (globalVisited.contains(pos)) continue;

            boolean inLayer = true;
            if (layerRange != null) {
                inLayer = layerRange.isPositionWithinRange(pos);
            }

            Map<Integer, ItemStack> required = new HashMap<>();
            for (Map.Entry<Integer, ItemStack> e : ctx.parsedRequired.entrySet()) {
                required.put(e.getKey(), e.getValue().copy());
            }

            BlockPos mainPos = pos;
            BlockState realState = client.world.getBlockState(pos);
            BlockState schState = schematicWorld != null ? schematicWorld.getBlockState(pos) : null;

            boolean useSch = !realState.hasBlockEntity() && schState != null && schState.hasBlockEntity();
            BlockState stateForHalves = useSch ? schState : realState;
            net.minecraft.world.World worldForHalves = useSch ? (net.minecraft.world.World) (Object) schematicWorld : client.world;

            BlockPos[] halves = null;
            if (stateForHalves.hasBlockEntity()) {
                halves = LitematicaContainerReader.getDoubleContainerHalves(worldForHalves, pos, stateForHalves);
                if (halves != null) {
                    mainPos = halves[0];
                    globalVisited.add(halves[0]);
                    globalVisited.add(halves[1]);

                    boolean isPrimary = pos.equals(halves[0]);
                    BlockPos otherPos = isPrimary ? halves[1] : halves[0];

                    if (nbtMap.containsKey(otherPos)) {
                        Map<Integer, ItemStack> otherReq = nbtMap.get(otherPos).parsedRequired;
                        replacementSourceCache.putAll(nbtMap.get(otherPos).replacementOrigins);
                        addReplacementScopes(nbtMap.get(otherPos).replacementScopes);

                        if (isPrimary) {
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                required.put(e.getKey() + 27, e.getValue().copy());
                            }
                        } else {
                            Map<Integer, ItemStack> shifted = new HashMap<>();
                            for (Map.Entry<Integer, ItemStack> e : required.entrySet()) {
                                shifted.put(e.getKey() + 27, e.getValue());
                            }
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                shifted.put(e.getKey(), e.getValue().copy());
                            }
                            required = shifted;
                        }
                    } else {
                        if (!isPrimary) {
                            Map<Integer, ItemStack> shifted = new HashMap<>();
                            for (Map.Entry<Integer, ItemStack> e : required.entrySet()) {
                                shifted.put(e.getKey() + 27, e.getValue());
                            }
                            required = shifted;
                        }
                    }
                } else {
                    globalVisited.add(pos);
                }
            } else {
                globalVisited.add(pos);
            }

            if (required.isEmpty()) continue;

            foundContainersAll++;
            if (inLayer) foundContainersLayer++;

            Map<Integer, ItemStack> realItems = RealContainerCache.getCachedItems(mainPos);

            if (realItems == null && halves != null) {
                BlockPos otherHalf = pos.equals(halves[0]) ? halves[1] : halves[0];
                realItems = RealContainerCache.getCachedItems(otherHalf);
                if (realItems != null) mainPos = otherHalf;
            }

            if (realItems == null) {
                if (client.world != null) {
                    waitingForData = true;
                    RealContainerCache.requestContainerData(mainPos);

                    if (halves != null) {
                        BlockPos otherHalf = pos.equals(halves[0]) ? halves[1] : halves[0];
                        RealContainerCache.requestContainerData(otherHalf);
                    }
                }
                realItems = new HashMap<>();
            }

            Map<ItemStackKey, Integer> reqAgg = new HashMap<>();
            Map<ItemStackKey, Integer> realAgg = new HashMap<>();

            for (ItemStack s : required.values()) {
                ItemStackKey key = new ItemStackKey(s);
                reqAgg.merge(key, s.getCount(), Integer::sum);
                ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
                if (stats.representative.isEmpty()) stats.representative = s.copy();
                containerMaterialKeys.add(key);
            }

            for (ItemStack s : realItems.values()) {
                ItemStackKey key = new ItemStackKey(s);
                realAgg.merge(key, s.getCount(), Integer::sum);
            }

            for (Map.Entry<ItemStackKey, Integer> e : reqAgg.entrySet()) {
                ItemStackKey key = e.getKey();
                int req = e.getValue();
                int real = realAgg.getOrDefault(key, 0);

                ItemStats stats = itemStatsCache.get(key);

                int matched = Math.min(req, real);

                stats.totalAll += req;
                stats.availableAll += real;
                stats.missingAll += Math.max(0, req - matched);

                if (inLayer) {
                    stats.totalLayer += req;
                    stats.availableLayer += real;
                    stats.missingLayer += Math.max(0, req - matched);
                }
            }

            for (Map.Entry<ItemStackKey, Integer> e : realAgg.entrySet()) {
                ItemStackKey key = e.getKey();
                int real = e.getValue();
                int req = reqAgg.getOrDefault(key, 0);

                if (real > req) {
                    ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
                    if (stats.representative.isEmpty()) stats.representative = new ItemStack(key.item);

                    stats.mismatchAll += (real - req);
                    if (inLayer) {
                        stats.mismatchLayer += (real - req);
                    }
                }
            }
        }

        hasMissingData = waitingForData;
    }

    private static MaterialListBase extractMaterialList(Object input) {
        if (input instanceof MaterialListBase mlb) {
            return mlb;
        }
        if (input instanceof GuiMaterialList gui) {
            return gui.getMaterialList();
        }
        try {
            if (input != null && input.getClass().getSimpleName().contains("GuiMaterialList")) {
                java.lang.reflect.Method m = input.getClass().getMethod("getMaterialList");
                Object result = m.invoke(input);
                if (result instanceof MaterialListBase mlb) return mlb;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int getItemsCount(NbtCompound nbt) {
        if (nbt != null && nbt.contains("Items")) {
            NbtElement el = nbt.get("Items");
            if (el instanceof NbtList list) {
                return list.size();
            }
        }
        return 0;
    }

    private static int getItemsCount(Map<Integer, ItemStack> parsedMap) {
        return parsedMap != null ? parsedMap.size() : 0;
    }

    private static SchematicSource extractSchematicSource(Object input) {
        if (input == null) return null;

        LitematicaSchematic schematic = null;
        Set<String> regions = null;
        Class<?> current = input.getClass();

        while (current != null) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(input);

                    if (schematic == null && value instanceof LitematicaSchematic litematic) {
                        schematic = litematic;
                    }

                    if (regions == null && "regions".equals(field.getName()) && value instanceof Collection<?> collection) {
                        Set<String> readRegions = new LinkedHashSet<>();
                        for (Object region : collection) {
                            if (region instanceof String name) readRegions.add(name);
                        }
                        if (!readRegions.isEmpty()) regions = readRegions;
                    }
                } catch (Exception ignored) {}
            }

            current = current.getSuperclass();
        }

        return schematic != null ? new SchematicSource(schematic, regions) : null;
    }

    private static boolean calculateFromSchematicContainers(Collection<SchematicSource> sources) {
        if (sources == null || sources.isEmpty()) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;

        boolean foundContainers = false;

        for (SchematicSource source : sources) {
            if (source == null || source.schematic == null) continue;

            LitematicaSchematic schematic = getSchematicWithBlockEntities(source);
            if (schematic == null) continue;

            for (String regionName : getSchematicRegions(schematic, source.regions)) {
                Map<BlockPos, NbtCompound> teMap = schematic.getBlockEntityMapForRegion(regionName);
                if (teMap == null || teMap.isEmpty()) continue;

                for (NbtCompound nbt : teMap.values()) {
                    if (nbt == null || !nbt.contains("Items")) continue;

                    ReplacementResult parsed = replaceInventoryMapWithOrigins(
                            RealContainerCache.parseNbtInventory(nbt, client.world.getRegistryManager()),
                            SchematicMaterialReplacementContext.keyForSchematic(source.schematic));
                    if (parsed.items.isEmpty()) continue;

                    replacementSourceCache.putAll(parsed.origins);
                    addReplacementScopes(parsed.scopes);
                    addSchematicContainerItems(parsed.items);
                    foundContainers = true;
                }
            }
        }

        return foundContainers;
    }

    private static LitematicaSchematic getSchematicWithBlockEntities(SchematicSource source) {
        LitematicaSchematic schematic = source.schematic;
        if (hasBlockEntityData(schematic, source.regions)) return schematic;

        try {
            LitematicaSchematic loaded = reloadSchematicFromFile(schematic.getFile());
            if (loaded != null && hasBlockEntityData(loaded, source.regions)) return loaded;
        } catch (Throwable ignored) {}

        return schematic;
    }

    private static LitematicaSchematic reloadSchematicFromFile(Object fileObject) {
        try {
            if (fileObject instanceof Path file) {
                if (!Files.isRegularFile(file)) return null;

                Path parent = file.getParent();
                Path fileName = file.getFileName();
                if (parent == null || fileName == null) return null;

                return invokeCreateFromFile(Path.class, parent, fileName.toString());
            }

            if (fileObject instanceof File file) {
                if (!file.isFile()) return null;

                File parent = file.getParentFile();
                if (parent == null) return null;

                return invokeCreateFromFile(File.class, parent, file.getName());
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static LitematicaSchematic invokeCreateFromFile(Class<?> directoryType, Object directory, String fileName) throws Exception {
        java.lang.reflect.Method method = LitematicaSchematic.class.getMethod("createFromFile", directoryType, String.class);
        Object result = method.invoke(null, directory, fileName);
        return result instanceof LitematicaSchematic schematic ? schematic : null;
    }

    private static boolean hasBlockEntityData(LitematicaSchematic schematic, Set<String> selectedRegions) {
        if (schematic == null) return false;

        for (String regionName : getSchematicRegions(schematic, selectedRegions)) {
            Map<BlockPos, NbtCompound> teMap = schematic.getBlockEntityMapForRegion(regionName);
            if (teMap != null && !teMap.isEmpty()) return true;
        }

        return false;
    }

    private static Collection<String> getSchematicRegions(LitematicaSchematic schematic, Set<String> selectedRegions) {
        if (schematic == null) return Collections.emptyList();

        Set<String> allRegions = schematic.getAreaPositions().keySet();
        if (selectedRegions == null || selectedRegions.isEmpty()) return allRegions;

        List<String> regions = new ArrayList<>();
        for (String regionName : selectedRegions) {
            if (allRegions.contains(regionName)) regions.add(regionName);
        }

        return regions;
    }

    private static void addSchematicContainerItems(Map<Integer, ItemStack> required) {
        for (ItemStack s : required.values()) {
            if (s == null || s.isEmpty()) continue;

            ItemStackKey key = new ItemStackKey(s);
            containerMaterialKeys.add(key);
            ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
            if (stats.representative.isEmpty()) stats.representative = s.copy();

            int count = s.getCount();
            stats.totalAll += count;
            stats.missingAll += count;
            stats.totalLayer += count;
            stats.missingLayer += count;
        }
    }

    private static boolean calculateFromMaterialListContainers(MaterialListBase materialList, List<MaterialListEntry> fallbackEntries) {
        List<MaterialListEntry> entries = fallbackEntries != null ? fallbackEntries : readMaterialListEntries(materialList);
        if (entries.isEmpty()) return false;

        boolean foundContainers = false;
        String schematicKey = MaterialReplacementUi.findSchematicKey(materialList);

        for (MaterialListEntry entry : entries) {
            if (entry == null || entry.getStack().isEmpty()) continue;

            ContainerComponent container = entry.getStack().get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            foundContainers = true;

            int totalMultiplier = Math.max(0, entry.getCountTotal());
            int missingMultiplier = Math.max(0, entry.getCountMissing());
            int availableMultiplier = Math.max(0, entry.getCountAvailable());
            int mismatchMultiplier = Math.max(0, entry.getCountMismatched());

            for (ItemStack contained : container.iterateNonEmpty()) {
                if (contained.isEmpty()) continue;

                ItemStack original = contained.copy();
                ItemStack stack = MaterialReplacer.replaceSingleStack(original, schematicKey);
                if (stack.isEmpty()) continue;

                registerReplacementOrigin(original, stack, schematicKey);

                int count = stack.getCount();
                addMaterialListContainerItem(
                        stack,
                        count * totalMultiplier,
                        count * missingMultiplier,
                        count * availableMultiplier,
                        count * mismatchMultiplier
                );
            }
        }

        return foundContainers;
    }

    @SuppressWarnings("unchecked")
    private static List<MaterialListEntry> readMaterialListEntries(MaterialListBase materialList) {
        if (materialList == null) return Collections.emptyList();

        for (String fieldName : List.of("materialListAll", "materialListPreFiltered")) {
            try {
                java.lang.reflect.Field field = MaterialListBase.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(materialList);
                if (value instanceof List<?> list) {
                    return (List<MaterialListEntry>) list;
                }
            } catch (Exception ignored) {}
        }

        return Collections.emptyList();
    }

    private static void addMaterialListContainerItem(ItemStack stack, int total, int missing, int available, int mismatch) {
        if (total == 0 && missing == 0 && available == 0 && mismatch == 0) return;

        ItemStackKey key = new ItemStackKey(stack);
        containerMaterialKeys.add(key);
        ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
        if (stats.representative.isEmpty()) {
            stats.representative = stack.copy();
            stats.representative.setCount(1);
        }

        stats.totalAll += total;
        stats.missingAll += missing;
        stats.availableAll += available;
        stats.mismatchAll += mismatch;

        stats.totalLayer += total;
        stats.missingLayer += missing;
        stats.availableLayer += available;
        stats.mismatchLayer += mismatch;
    }

    private static ReplacementResult replaceInventoryMapWithOrigins(Map<Integer, ItemStack> inventory, String schematicKey) {
        ReplacementResult result = new ReplacementResult();
        if (inventory == null || inventory.isEmpty()) return result;

        for (Map.Entry<Integer, ItemStack> entry : inventory.entrySet()) {
            ItemStack original = entry.getValue();
            if (original == null || original.isEmpty()) continue;

            ItemStack replaced = MaterialReplacer.replaceSingleStack(original.copy(), schematicKey);
            if (replaced == null || replaced.isEmpty()) continue;

            MaterialReplacementScope scope = MaterialReplacer.getReplacementScope(original, schematicKey).orElse(MaterialReplacementScope.GLOBAL);
            recordReplacementOrigin(original, replaced, result.origins, result.scopes, scope);
            result.items.put(entry.getKey(), replaced);
        }

        return result;
    }

    private static void registerReplacementOrigin(ItemStack original, ItemStack displayed, String schematicKey) {
        if (original == null || original.isEmpty() || displayed == null || displayed.isEmpty()) return;

        MaterialReplacementScope scope = MaterialReplacer.getReplacementScope(original, schematicKey).orElse(MaterialReplacementScope.GLOBAL);
        recordReplacementOrigin(original, displayed, replacementSourceCache, replacementScopeCache, scope);
    }

    private static void recordReplacementOrigin(ItemStack original, ItemStack displayed,
                                                Map<ItemStackKey, ItemStack> origins,
                                                Map<ItemStackKey, MaterialReplacementScope> scopes,
                                                MaterialReplacementScope scope) {
        if (original == null || original.isEmpty() || displayed == null || displayed.isEmpty()) return;

        ItemStack origin = normalizeOrigin(original);
        ItemStack current = normalizeOrigin(displayed);
        if (!ItemStack.areItemsAndComponentsEqual(origin, current)) {
            ItemStackKey key = new ItemStackKey(current);
            origins.put(key, origin);
            scopes.put(key, scope);
        }
    }

    private static void addReplacementScopes(Map<ItemStackKey, MaterialReplacementScope> scopes) {
        if (scopes == null || scopes.isEmpty()) return;

        replacementScopeCache.putAll(scopes);
        replacedMaterialKeys.addAll(scopes.keySet());
    }

    private static ItemStack normalizeOrigin(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    private static boolean isItemIgnored(MaterialListBase materialList, Item item) {
        return false;
    }

    public static List<MaterialListEntry> getCustomMaterialList(Object materialListObj) {
        boolean limitToLayer = true;

        if (materialListObj instanceof IMaterialList iMatList) {
            BlockInfoListType type = iMatList.getMaterialListType();
            if (type != null) {
                limitToLayer = type.name().contains("LAYER");
            }
        }

        List<MaterialListEntry> list = new ArrayList<>();

        for (Map.Entry<ItemStackKey, ItemStats> entry : itemStatsCache.entrySet()) {
            ItemStats stats = entry.getValue();

            // 【容器视图只显示容器材料】（Ray 2026-11-14：容器选项的材料列表应只含投影容器里的东西；
            // 之前遍历全缓存 ⇒ 957 行混着建筑方块，收集也跟着超范围）。建筑方块走「全部/方块」视图。
            if (!containerMaterialKeys.contains(entry.getKey())) {
                continue;
            }

            int total = limitToLayer ? stats.totalLayer : stats.totalAll;
            int missing = limitToLayer ? stats.missingLayer : stats.missingAll;
            int available = limitToLayer ? stats.availableLayer : stats.availableAll;
            int mismatch = limitToLayer ? stats.mismatchLayer : stats.mismatchAll;

            if (total == 0 && missing == 0 && available == 0 && mismatch == 0) continue;

            ItemStack stack = stats.representative;
            if (stack.isEmpty()) stack = new ItemStack(entry.getKey().item);

            MaterialListEntry matEntry = new MaterialListEntry(stack, total, missing, mismatch, available);
            list.add(matEntry);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && !list.isEmpty()) {
            fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(list, client.player);
        }

        // ⛔ v1.16.19-Ray（2026-11-15）**撤回** v1.16.18 在这里的「缺失量 = 总量 − 已有」重算：
        //   服务端 `handleCollectToPlayer` 已经按「身上已有」扣过一次（v1.16.6 起的防重复收集），
        //   客户端再扣一次 ⇒ **双扣**：缺 1153、身上已有 576 时只收 577。
        //   存档实测（2026-11-15）：玩家背包确实有 576 个平滑石英块，服务端扣减是对的；
        //   真正的 bug 在盒装判定（见 `BatchServerLink.collectWholeBoxIfWorth`，已按物品汇总修）。
        //   ⇒ 这里保持原样：清单/请求发「总量」，由服务端统一扣「身上已有」。
        return list;
    }

    public static List<MaterialListEntry> mergeLists(List<MaterialListEntry> vanillaList, List<MaterialListEntry> containerList) {
        // ⛔ v1.16.14（Ray 2026-11-14：「各自管理各自的材料列表不要串一块」）：
        // 之前的实现把同物品的数量**相加**（例：原生缺 8 + 容器缺 4 = 显示 12）⇒ 数量串了。
        // 现在**不合并数量**：同物品各自一行、各显示各的。
        // ⛔ v1.16.20-Ray（Ray 2026-11-15：「打开一下全部视图，平滑石英块就多一行」）：
        // 但也不能**重复行**——同一物品（同一个 ItemStackKey：物品 + 自定义名 + 盒内容 + 组件）
        // 在「原生行 + 容器行」里只保留**第一行**（原生优先），否则切几次视图就叠出 2、3、4 行，
        // 底部「总计」也跟着涨（实测 4×1729＝6916）。
        List<MaterialListEntry> out = new ArrayList<>();
        java.util.Set<ItemStackKey> seen = new java.util.HashSet<>();

        for (MaterialListEntry entry : vanillaList) {
            if (entry != null && seen.add(new ItemStackKey(entry.getStack()))) {
                out.add(entry);
            }
        }
        for (MaterialListEntry entry : containerList) {
            if (entry != null && seen.add(new ItemStackKey(entry.getStack()))) {
                out.add(entry);
            }
        }
        return out;
    }
}
