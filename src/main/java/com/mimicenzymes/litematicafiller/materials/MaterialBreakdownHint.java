package com.mimicenzymes.litematicafiller.materials;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.ItemType;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.Component;
import net.minecraft.component.ComponentType;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「投影二级材料列表显示」的核心：给定材料列表某一行，算出它的**二级材料**。
 *
 * <h2>二级材料的定义（Ray 2026-11-08 定）</h2>
 * <ul>
 *   <li><b>能互相转换的 ⇒ 互为二级材料</b>：红石粉 ⇄ 红石块、铁粒 ⇄ 铁锭。
 *       材料列表要 100 红石粉 ⇒ 显示 {@code ≈12 红石块}；要 10 红石块 ⇒ {@code ≈90 红石粉}。</li>
 *   <li><b>不能互相转换的 ⇒ 用来合成它的那个材料是它的二级材料</b>：
 *       平滑石台阶 4 个 ← 3 平滑石头，而台阶变不回平滑石头 ⇒ 显示 {@code ←6 平滑石头}。</li>
 * </ul>
 * 两者用不同前缀区分：互转 {@code ≈}、单向 {@code ←}。
 *
 * <h2>只算一层</h2>
 * <p>不递归（不做完整合成树）。多原料配方（活塞＝木板＋圆石＋铁锭）和 tag 配方（任意木板）
 * 一律跳过 —— 显示出来只会误导。
 *
 * <h2>为什么必须缓存</h2>
 * <p>调用点在 {@code MaterialListHudRenderer.render} 里，**每帧每行都要算**。
 * 不缓存就得每帧全量扫一遍配方表（上千条）⇒ 打开材料列表直接卡死（v1.19.0 就是这么翻的，
 * 虽然那次主因是 mixin 签名错，但缓存同样必须）。按物品缓存，配方管理器换了（换存档 / `/reload`）才清空。
 *
 * <h2>失败必须静默</h2>
 * <p>这是画在 HUD 上的附加信息，任何异常都不能影响材料列表本身 ⇒ 全包 try/catch，出错就当没有折算。
 *
 * <h2>「备够了」怎么算</h2>
 * <p>范围＝主背包 + 护甲 + 副手，**并拆开算潜影盒/收纳袋里的内容**（走 Litematica 自带统计），
 * 外加**精妙背包这类自定义容器组件**里的内容（通用扫 {@code ContainerComponent}，不点名第三方类）。
 * 按 tick 缓存 ⇒ 拾取后下一 tick 就跟着变色。
 */
public final class MaterialBreakdownHint {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");


    /** Item → 计算结果（{@code Optional.empty()} 表示「该物品没有可用的二级材料」，也要缓存，避免反复扫）。 */
    private static final Map<Item, Optional<Hint>> CACHE = new ConcurrentHashMap<>();

    /** 缓存归属：配方管理器换实例就整表作废。 */
    private static RecipeManager cacheOwner;
    private static RecipeChooser.Index cachedRecipeIndex;

    /** 只打一次「已生效」日志，用来确认第三方 mixin 没有静默失效。 */
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    /** 库存统计按 tick 缓存（同一 tick 背包不会变）。 */
    private static long cachedTick = Long.MIN_VALUE;
    private static Object2IntMap<ItemType> cachedCounts;

    /** 「其他行单向需求」按 500 ms 缓存：HUD 每帧每行都要问，不缓存会反复扫清单。 */
    private static long demandTick = Long.MIN_VALUE;
    private static Map<Item, Long> oneWayCache = Map.of();
    /** 同一份缓存里顺带存「该物品自己的缺口」——判「二级备够了没有」要加上它（见 {@link #ownDemandOf}）。 */
    private static Map<Item, Long> ownCache = Map.of();

    /** 拆容器最多几层（精妙背包 → 潜影盒 → 材料）—— 和「材料高亮」的递归深度保持一致。 */
    private static final int MAX_CONTAINER_DEPTH = 4;

    /** 精妙背包等「Litematica 认不出的容器」里的物品统计，按 250 ms 缓存。 */
    private static long customTick = Long.MIN_VALUE;
    private static Map<Item, Integer> customCounts = Map.of();

    private MaterialBreakdownHint() {
    }

    /**
     * 算出一行折算提示（不额外查「该物品的总需求」，只按这一条折算量判断够不够）。
     *
     * <p>给「汇总 / 提取」这类不画颜色的调用方用；<b>画 HUD 必须</b>用
     * {@link #describe(MaterialListEntry, int, boolean)} 且传 {@code true}，见那个方法的注释。
     *
     * @param entry  材料列表条目（取它需要的物品）
     * @param needed 这一行当前显示的缺口数量
     * @return 折算结果（含「二级材料够不够」）；无可用二级材料时返回 {@code null}
     */
    public static Result describe(MaterialListEntry entry, int needed) {
        return describe(entry, needed, false);
    }

    /**
     * 算出一行折算提示；可选**按该物品的总备货需求**判断「备够了没有」。
     *
     * <h2>为什么必须看总需求（2026-11-12 Ray 报「HUD 显示不准」的根因）</h2>
     * <p>例：这份投影要 <b>697 个平滑石英块</b>直接放置，同时要 <b>521 个平滑石英台阶</b>
     * （折算 261 个方块）+ 若干楼梯 ⇒ 平滑石英块的备货目标是 {@code 697 + 285 = 982}，
     * HUD 数量列显示的也是 982（Ray 2026-11-08 要的「备货一次备齐」）。
     *
     * <p>但**台阶行**的折算提示原先只拿自己那条（285）和身上数量比：
     * 身上有 697 个方块时判「已备够」并显示亮白 —— 可这 697 个已经全被「直接放置」占用，
     * 一个不剩，实际还差 285 个。判据换成 {@code available >= 697 + 285} 才与现实一致。
     *
     * <p>自身需求的取数走 {@code getMaterialsAll()}：它<b>不受「隐藏已有材料」开关影响</b>，
     * 所以某个物品自己已经备够（从 HUD 列表里消失）之后，这里仍然能读到它被占用了多少。
     *
     * @param entry           材料列表条目
     * @param needed          这一行当前显示的缺口数量（只用来算折算倍数）
     * @param checkTotalDemand {@code true} ⇒ 用该物品的「自身需求 + 其他行单向需求」判断够不够
     *                        （HUD 用）；{@code false} ⇒ 只用这一条折算量（汇总/提取用，避免递归）
     */
    public static Result describe(MaterialListEntry entry, int needed, boolean checkTotalDemand) {
        if (entry == null || needed <= 0) {
            return null;
        }
        try {
            ItemStack required = entry.getStack();
            if (required == null || required.isEmpty()) {
                return null;
            }
            Hint hint = lookup(required.getItem());
            if (hint == null) {
                return null;
            }
            // 向上取整：宁可多备，不能少备
            long times = (needed + (long) hint.outputCount - 1L) / (long) hint.outputCount;

            // 每个原料各自算「要备多少 / 还差多少 / 够没够」（Ray 2026-11-13 的显示规则）：
            //   不够 ⇒ 显示缺口（灰）；够了 ⇒ 回到原始需求（白）。
            //
            // ⚠️ 只用**这一行自己的折算量**（`partTotal`），绝不跨行累加 ——
            //    Ray 2026-11-13 当场抓到反例：金合欢压力板只要 2 个木板，却显示 28，
            //    因为「整份清单对金合欢木板的需求总和」是 28（压力板/台阶/楼梯/栅栏/告示牌…各要几个）。
            //    数量列那边合并是「备货一次备齐」，但**每行的二级材料提示必须管自己那一行**。
            List<PartDemand> demands = new ArrayList<>(hint.parts.size());
            boolean allSatisfied = true;
            StringBuilder text = new StringBuilder(hint.bidirectional ? "≈" : "←");
            int firstAvailable = 0;
            long firstNeed = 0L;
            boolean firstPart = true;
            for (Part part : hint.parts) {
                long partTotal = times * (long) part.count;
                if (partTotal <= 0L || partTotal > Integer.MAX_VALUE) {
                    continue;
                }
                long need = partTotal;
                int available = countInInventory(part.item);
                long shortage = need - (long) available;
                long shown = shortage > 0L ? shortage : need;

                // ★ v1.34.6-Ray（Ray 2026-11-15「一级需求把料占满了，二级那 9 个还是白」）：
                //   判「这个原料备够了没有」要拿**总备货需求**比：该物品自己的直接缺口
                //   （平滑石英块直接放置 1729）＋ 其他行的单向合成需求（台阶 3 + 楼梯 6）
                //   ＝ 1738。身上 1729 ⇒ 不够 ⇒ 保持灰色；等凑到 1738 才变白。
                //   ⚠️ 只影响**判定**：行尾显示的数字仍是这一行自己的折算量（Ray 要的分开显示）。
                long judgeNeed = need;
                if (checkTotalDemand) {
                    long totalDemand = ownDemandOf(part.item) + oneWayDemand().getOrDefault(part.item, 0L);
                    if (totalDemand > judgeNeed) {
                        judgeNeed = totalDemand;
                    }
                }
                boolean ok = (long) available >= judgeNeed;
                allSatisfied &= ok;
                demands.add(new PartDemand(part.item, shown, need, available, ok));

                String name = new ItemStack(part.item).getName().getString();
                if (!firstPart) {
                    text.append('+');
                }
                // 数量规格跟原版材料列表一致（总数 + 组数 x 堆叠 + 余数），只是**不带括号**（Ray 2026-11-08 要的）。
                // ⭐ v1.22.4-Ray（Ray 2026-11-19）：颜色按**整单总需**判（见上面 judgeNeed），显示改成
                //   `本行用量/整单`：`←2/34锁链+6/7去皮竹块`（紧凑、无空格；总需=本行时只显示 `2锁链`）。
                text.append(shown);
                if (judgeNeed > need) {
                    text.append('/').append(judgeNeed);
                }
                text.append(name);
                if (hint.parts.size() == 1) {
                    text.append(stackBreakdown(shown, part.item));
                }
                if (firstPart) {
                    firstAvailable = available;
                    firstNeed = judgeNeed;   // 日志里报"判定需求"（含一级占用），排查假白/假灰就看它
                    firstPart = false;
                }
            }
            if (demands.isEmpty()) {
                return null;
            }
            // 首次成功只在日志里打一条：用来确认功能真的生效（第三方 mixin 最怕静默失效），
            // 顺便把「统计到多少」打出来 —— 潜影盒/精妙背包有没有算进去，一眼就能看出来。
            if (LOGGED.compareAndSet(false, true)) {
                LOGGER.info("[material-list] 二级材料折算已生效：{} × {} → {}（身上 {}，总需 {}，备够={}）",
                        required.getItem().getName().getString(), needed, text, firstAvailable, firstNeed, allSatisfied);
            }
            return new Result(text.toString(), allSatisfied, demands, hint.bidirectional, firstAvailable, firstNeed);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 其他行对某物品的单向（{@code ←}）需求汇总＝**「合成这一档」总共要多少个**。
     *
     * <p>例：平滑石英台阶 521 个要 261 个方块、楼梯缺口要 24 个 ⇒ 平滑石英块 285。
     * 数据取自 {@code getMaterialsMissingOnly()}；互转（{@code ≈}）不计入 ——
     * 那只是「也可以拿它换」，加进去会让两个方向互相抬价。
     *
     * <p>用途有两个：数量列合并（备货一次备齐）、二级材料「够没够」的判据（Ray 2026-11-13 定）。
     */
    public static Map<Item, Long> oneWayDemand() {
        ensureDemandCache();
        return oneWayCache;
    }

    /** 「总备货需求裁决已生效」日志只打一次（Mixin 里放静态字段不可靠，放这儿）。 */
    private static final AtomicBoolean FILTER_LOGGED = new AtomicBoolean();

    public static void logFilterAppliedOnce() {
        if (FILTER_LOGGED.compareAndSet(false, true)) {
            LOGGER.info(
                    "[material-list] 材料列表裁决已生效（够自身需求才隐藏、丢材料会重新出现）");
        }
    }

    /**
     * 玩家身上（含潜影盒 / 收纳袋 / **精妙背包里的盒装材料**）有多少个这种物品。
     *
     * <p>给「材料列表该不该隐藏」用：Litematica 自带的 {@code countAvailable} 认不出精妙背包，
     * 用它判隐藏会出现「HUD 说够了、列表还不隐藏」的错位 ⇒ 统一走这里。
     */
    public static int availableOf(Item item) {
        return countInInventory(item);
    }

    private static void ensureDemandCache() {
        // ⚠️ 墙钟时间做缓存键：`world.getTime()` 在客户端可能不前进 ⇒ 缓存永远命中、清单改了也读不到新值
        long now = System.currentTimeMillis() / 500L;
        if (now == demandTick) {
            return;
        }
        demandTick = now;
        Map<Item, Long> oneWay = new HashMap<>();
        Map<Item, Long> own = new HashMap<>();
        try {
            MaterialListBase list = currentList();
            if (list != null) {
                int multiplier = list.getMultiplier();
                // ★ v1.34.6-Ray（Ray 2026-11-15）：先把「每种物品**自己**的缺口」收下来 ——
                //   例：平滑石英块要 1729 个**直接放置**，加上台阶/楼梯的 9 个才是 1738。
                //   二级提示判「备够没备够」时必须用 1738，否则身上 1729 个就被判成"够合成"（变白），
                //   实际那 1729 个全被直接放置占用、一个不剩。
                for (MaterialListEntry entry : list.getMaterialsAll()) {
                    if (entry == null || entry.getStack() == null || entry.getStack().isEmpty()) {
                        continue;
                    }
                    int ownMissing = Math.max(0, entry.getCountMissing());
                    if (ownMissing > 0) {
                        own.merge(entry.getStack().getItem(), (long) ownMissing, Long::sum);
                    }
                }
                // 其他行的单向合成需求（只算还缺的行；那才是现在要备的）
                for (MaterialListEntry entry : list.getMaterialsMissingOnly(false)) {
                    if (entry == null || entry.getStack() == null || entry.getStack().isEmpty()) {
                        continue;
                    }
                    int count = multiplier == 1
                            ? entry.getCountMissing() - entry.getCountAvailable()
                            : entry.getCountTotal();
                    count *= multiplier;
                    // ⚠️ 这里必须传 false：否则 oneWayDemand → 遍历行 → describe → oneWayDemand 无限递归
                    Result result = describe(entry, count, false);
                    if (result != null && !result.bidirectional()) {
                        // 多原料配方（南瓜灯 = 雕刻南瓜 + 火把）要**每种原料各记一份**
                        for (PartDemand part : result.parts()) {
                            if (part.need() > 0L) {
                                oneWay.merge(part.item(), part.need(), Long::sum);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 清单没建好 / Litematica 改结构：当作没有额外需求，绝不影响 HUD 本身
        }
        oneWayCache = oneWay;
        ownCache = own;
    }

    /**
     * 该物品**自己**的缺口（材料列表里它自己那一行，例：平滑石英块直接放置要 1729 个）。
     * 与 {@link #oneWayDemand()} 同源同步刷新（500ms 缓存）。
     */
    public static long ownDemandOf(Item item) {
        ensureDemandCache();
        return ownCache.getOrDefault(item, 0L);
    }

    /** 当前材料清单：优先「打开过材料列表」的那份，没有就退到当前选中投影的清单。 */
    private static MaterialListBase currentList() {
        MaterialListBase list = DataManager.getMaterialList();
        if (list == null) {
            SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
            if (placement != null) {
                list = placement.getMaterialList();
            }
        }
        return list;
    }

    /**
     * 按**原版材料列表的整套规格**拆数量，但用更紧凑、更直观的写法（Ray 2026-11-08 定的）：
     * <ul>
     *   <li>够装一盒 ⇒ {@code 50 1盒+23}（原版 {@code 50 (1.85 潜影盒)} —— 小数盒数看不出到底几个）</li>
     *   <li>盒后余数还够一组 ⇒ {@code 2000 1盒+4组+16}</li>
     *   <li>不够一盒 ⇒ {@code 249 3组+57}（原版 {@code 249 (3 x 64 + 57)}）</li>
     *   <li>不足一组 ⇒ {@code 26}</li>
     * </ul>
     *
     * <p>不可堆叠物品（水桶这种 {@code maxStack == 1}）不写「组」，盒后余数直接给个数：{@code 50 1盒+23}。
     *
     * <p>判定阈值与原版一致：一盒 = 27 格 × 堆叠上限。
     */
    public static String formatCount(long count, int maxStackSize) {
        if (maxStackSize <= 0 || count <= maxStackSize) {
            return String.valueOf(count);
        }
        long boxSize = 27L * maxStackSize;
        if (count >= boxSize) {
            long boxes = count / boxSize;
            long remainder = count % boxSize;
            StringBuilder out = new StringBuilder();
            out.append(count).append(' ').append(boxes).append('盒');
            appendRemainder(out, remainder, maxStackSize);
            return out.toString();
        }
        long stacks = count / maxStackSize;
        long remainder = count % maxStackSize;
        return count + " " + stacks + "组" + (remainder > 0 ? "+" + remainder : "");
    }

    /** 盒/组后面的零头：能凑成整组的先写「N组」，剩下的写个数；不可堆叠物品只写个数。 */
    private static void appendRemainder(StringBuilder out, long remainder, int maxStackSize) {
        if (remainder <= 0) {
            return;
        }
        if (maxStackSize <= 1) {
            out.append('+').append(remainder);
            return;
        }
        long stacks = remainder / maxStackSize;
        long rest = remainder % maxStackSize;
        if (stacks > 0) {
            out.append('+').append(stacks).append('组');
        }
        if (rest > 0) {
            out.append('+').append(rest);
        }
    }

    /**
     * 二级材料那一小段的格式（跟主数量列同一套 {@link #formatCount}，只多一个前缀）。
     *
     * <p>例：684 个冰（堆叠 64）⇒ {@code " 684 10组+44"}；不足一组返回空串（不画蛇添足）。
     */
    private static String stackBreakdown(long count, Item item) {
        ItemStack stack = new ItemStack(item);
        int maxStack = stack.getMaxCount();
        if (maxStack <= 0 || count <= maxStack) {
            return "";
        }
        return formatCount(count, maxStack);
    }

    /**
     * 玩家身上有多少个这种物品 —— 范围：主背包 + 护甲 + 副手 + **潜影盒/收纳袋里的内容** +
     * **精妙背包这类自定义容器组件里的内容**（Ray 2026-11-08 要求把快捷潜影盒、快捷精妙背包算进来）。
     *
     * <p>潜影盒/收纳袋直接复用 Litematica 自己的统计（{@code getInventoryItemCounts} 已经会拆开算）；
     * 精妙背包的内容在它自己的物品组件里，Litematica 不认 ⇒ 这里通用地扫一遍所有
     * {@link ContainerComponent} 类型的组件（不点名任何第三方类，避免反射依赖）。
     *
     * <p>⚠️ **缓存键必须是墙钟时间，不能用 {@code world.getTime()}**（2026-11-12 踩到）：
     * 客户端的世界时间只在服务端每 20 tick 广播时更新一次，某些情况下干脆不前进 ⇒
     * 用 `tick != cachedTick` 判断会让缓存**永远命中**，玩家补了几百个材料，这里读到的还是 0，
     * 提示就一直是灰的（Ray 报的「够 982 了还是灰色」就是这个）。
     * 现在按 **250 ms** 刷新：背包不会在一个 tick 内被改，但补货 / 拿取 0.25 秒内就能跟上。
     */
    private static int countInInventory(Item item) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) {
            return 0;
        }
        try {
            long now = System.currentTimeMillis() / 250L;
            if (now != cachedTick || cachedCounts == null) {
                cachedCounts = MaterialListUtils.getInventoryItemCounts(mc.player.getInventory());
                cachedTick = now;
            }
            int base = cachedCounts.getInt(new ItemType(new ItemStack(item), true, false));
            return base + countInCustomContainers(mc.player, item);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 精妙背包等「自定义容器组件」里的数量（原版潜影盒已由 Litematica 统计，这里跳过避免重复）。
     *
     * <h2>为什么要递归（Ray 2026-11-13 问「精妙背包里的盒装材料算不算」）</h2>
     * <p>v1.30.10 之前只扫**一层** {@code ContainerComponent}：精妙背包里的散装材料能算，
     * 但精妙背包里再装**潜影盒**（盒装材料）时，扫到的是盒子本身、{@code inner.isOf(item)}
     * 不成立 ⇒ 盒里的材料全部漏掉。现在递归拆（精妙背包 → 潜影盒 → 材料），最多 4 层，
     * 和「材料高亮」的递归深度保持一致。
     *
     * <p>⚠️ **只跳过玩家背包顶层**的潜影盒 / 收纳袋（Litematica 的
     * {@code getInventoryItemCounts} 已经把它们拆开算过）；递归里遇到的潜影盒照拆 ——
     * 那种是「藏在精妙背包里」的，Litematica 根本看不见。
     *
     * <p>按 250 ms 缓存整张「物品 → 数量」表：HUD 上十几行都要问同一个背包，
     * 不能每行都全量扫一遍。
     */
    private static int countInCustomContainers(PlayerEntity player, Item item) {
        long now = System.currentTimeMillis() / 250L;
        if (now != customTick) {
            customTick = now;
            customCounts = scanCustomContainers(player);
        }
        return customCounts.getOrDefault(item, 0);
    }

    /** 扫一遍玩家背包，把「Litematica 认不出的容器」里的内容全部展开成「物品 → 数量」。 */
    private static Map<Item, Integer> scanCustomContainers(PlayerEntity player) {
        Map<Item, Integer> out = new HashMap<>();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            // 顶层潜影盒 / 收纳袋：Litematica 已统计过，跳过避免重复
            if (stack.getItem() instanceof BlockItem || stack.getItem() instanceof BundleItem) {
                continue;
            }
            collectContainerContents(stack, out, 0);
        }
        return out;
    }

    /**
     * 递归展开一个物品里的内容物（最多 {@link #MAX_CONTAINER_DEPTH} 层）。
     *
     * <p>没有内容物的物品按「它自己」计入 —— 精妙背包这类容器本身不是材料，
     * 但万一某个模组把物品存在自定义 NBT（不是 {@code ContainerComponent}），
     * 至少会把它自己记一个（不会凭空多算材料）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void collectContainerContents(ItemStack stack, Map<Item, Integer> out, int depth) {
        if (stack == null || stack.isEmpty() || depth > MAX_CONTAINER_DEPTH) {
            return;
        }
        boolean hasContents = false;
        for (Component<?> component : stack.getComponents()) {
            Object value;
            try {
                value = stack.get((ComponentType) component.type());
            } catch (Throwable ignored) {
                continue;
            }
            if (value instanceof ContainerComponent container) {
                for (ItemStack inner : container.iterateNonEmpty()) {
                    if (inner == null || inner.isEmpty()) {
                        continue;
                    }
                    hasContents = true;
                    collectContainerContents(inner, out, depth + 1);
                }
            }
        }
        if (!hasContents) {
            out.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private static Hint lookup(Item item) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientWorld world = mc == null ? null : mc.world;
            if (world == null) {
                return null;
            }
            RecipeManager manager = world.getRecipeManager();
            if (manager == null) {
                return null;
            }
            if (manager != cacheOwner) {
                CACHE.clear();
                cacheOwner = manager;
                cachedRecipeIndex = RecipeChooser.index(world, manager);
            }
            if (cachedRecipeIndex == null) {
                cachedRecipeIndex = RecipeChooser.index(world, manager);
            }
            Optional<Hint> cached = CACHE.get(item);
            if (cached != null) {
                return cached.orElse(null);
            }
            Hint computed = find(cachedRecipeIndex, item);
            CACHE.put(item, Optional.ofNullable(computed));
            return computed;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找这个物品的二级材料。
     *
     * <h2>支持多原料（Ray 2026-11-13 要的：南瓜灯 = 雕刻南瓜 + 火把）</h2>
     * <p>11-08 定的是「多原料配方（活塞）一律跳过，显示出来只会误导」；现在改成**列全所有原料**
     * —— 备货时确实要知道「南瓜灯还要备火把」。同一原料出现多次会合并成一项（台阶 3 个方块）。
     *
     * <p>仍然跳过：<b>tag 配方</b>（「任意木板」这类候选不止一种，写死成某一种会误导）。
     *
     * <p>多个候选（铁锭既能由 9 铁粒合、也能由 1 铁块拆）的排序：<b>能互转的优先</b>
     * → <b>原料种类少的优先</b>（显示简洁）→ <b>单次产量小的优先</b>（更像"合成"而不是"拆解"）。
     */
    private static Hint find(RecipeChooser.Index index, Item requiredItem) {
        // v1.20.16：选配方的逻辑抽到 RecipeChooser，显示端与收集/提取端**共用同一套**（P3 #5）
        // v1.20.20：改用按产出分组的 Index（旧版每次全表扫两遍，收集大箱子会卡）
        RecipeChooser.Choice choice = index == null ? null : index.choose(requiredItem);
        if (choice == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>(choice.ingredients().size());
        for (MaterialDemandCore.Ingredient ingredient : choice.ingredients()) {
            parts.add(new Part(ingredient.item(), ingredient.amount()));
        }
        return new Hint(parts, choice.outputCount(), choice.bidirectional());
    }

    /**
     * 一种原料：**一次合成**要消耗几个。
     *
     * <p>例：南瓜灯 = 1 雕刻南瓜 + 1 火把 ⇒ {@code [Part(雕刻南瓜,1), Part(火把,1)]}；
     * 台阶 = 3 平滑石英块 ⇒ {@code [Part(平滑石英块,3)]}。
     */
    public record Part(Item item, int count) {
    }

    /**
     * 一条可用的配方关系。
     *
     * @param parts         一次合成要消耗的原料（1 种或多种）
     * @param outputCount   一次合成产出几个本物品
     * @param bidirectional 能不能反向转换（true ＝ 互为二级材料；只有单一原料才可能成立）
     */
    private record Hint(List<Part> parts, int outputCount, boolean bidirectional) {
    }

    /**
     * 一种原料的「要备多少 / 还差多少」。
     *
     * @param shown     实际显示的数字：不够时是**还差多少**，够了是**总需求**（Ray 2026-11-13 定的）
     * @param need      这条折算的总需求
     * @param available 身上有多少
     * @param satisfied 这个原料是否已经够
     */
    public record PartDemand(Item item, long shown, long need, int available, boolean satisfied) {
    }

    /**
     * 一行折算结果。
     *
     * @param text          显示文本（不含颜色码）
     * @param satisfied     **所有**原料都备够了才为 true（够 ⇒ 调用方用亮白，表示可以直接合成）
     * @param parts         各原料的需求明细（调用方要用它做「多行汇总」）
     * @param bidirectional 是否互为二级材料（能互转）。**只有 {@code false} 的才并进数量列** ——
     *                      互转只是「可以用它换」，不是硬需求，并进去会让总数虚高。
     * @param available     第一种原料身上有多少（诊断日志用）
     * @param need          第一种原料的判定需求（诊断日志用）
     */
    public record Result(String text, boolean satisfied, List<PartDemand> parts, boolean bidirectional,
                         int available, long need) {
        /** 第一种原料（日志 / 单原料场景的快捷方式）。 */
        public Item inputItem() {
            return this.parts.isEmpty() ? net.minecraft.item.Items.AIR : this.parts.get(0).item();
        }

        /** 第一种原料的需求量（日志 / 单原料场景的快捷方式）。 */
        public long amount() {
            return this.parts.isEmpty() ? 0L : this.parts.get(0).need();
        }
    }
}
