package com.mimicenzymes.litematicafiller.scan;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【野外材料感知】可采集原料表 + 物品 → 世界载体（方块/实体）映射。
 *
 * <p>Ray 2026-11-18 的 P1：外出手持材料清单，走到哪提示附近有什么缺料。
 * 这里只负责两件事：
 * <ol>
 *   <li>{@link #isCuratedGatherable(Item)}：哪些物品算“可以直接从世界拿”，递归展开到这里就停
 *       （原木/沙子/黏土/圆石/矿石/作物/怪掉落…）；</li>
 *   <li>{@link #targetFor(Item)}：这个物品在世界上长什么样（方块还是实体），给扫描器/聊天用。</li>
 * </ol>
 *
 * <p>⚠️ 配方判断（有没有合成/熔炼配方）在 {@link DeepMaterialPlanner} 里做；这里只放
 * “有配方但也是野外直接拿”的例外（石头、羊毛、西瓜、红石这类），以及 raw/ore/crop/mob 的映射。
 */
public final class MaterialScanTargets {

    /** 一个物品的世界载体；source 是聊天里显示的名字（“沙子”“牛”）。 */
    public record Target(String source, List<Block> blocks, List<EntityType<?>> entities) {
        public boolean hasBlock() {
            return !blocks.isEmpty();
        }

        public boolean hasEntity() {
            return !entities.isEmpty();
        }
    }

    private static final Map<Item, Target> SPECIAL = new HashMap<>();
    private static final Set<String> CURATED_GATHERABLE = new HashSet<>();

    /** 所有**自然**原木/菌柄（去皮/木头变种不算：避免把玩家建筑里的去皮原木当资源）。 */
    private static final List<Block> NATURAL_LOGS = List.of(
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
            Blocks.CRIMSON_STEM, Blocks.WARPED_STEM, Blocks.BAMBOO_BLOCK);

    private MaterialScanTargets() {
    }

    // ---------------------------------------------------------------- 可采集原料（无配方/有配方但野外能拿）

    private static void gatherable(String... paths) {
        for (String path : paths) {
            CURATED_GATHERABLE.add(path);
        }
    }

    static {
        // 原木（所有木材）
        gatherable("oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
                "mangrove_log", "cherry_log", "bamboo_block", "crimson_stem", "warped_stem");
        // 沙/砾/黏土/冰雪
        gatherable("sand", "red_sand", "gravel", "clay", "ice", "packed_ice", "blue_ice",
                "snow_block", "powder_snow", "snow");
        // 石头家族（有熔炼/合成配方，但野外直接挖）
        gatherable("stone", "cobblestone", "deepslate", "cobbled_deepslate", "tuff", "calcite",
                "dripstone_block", "pointed_dripstone", "andesite", "diorite", "granite",
                "basalt", "smooth_basalt", "blackstone", "gilded_blackstone", "netherrack",
                "soul_sand", "soul_soil", "end_stone", "obsidian", "crying_obsidian", "ancient_debris",
                "sandstone", "red_sandstone", "dirt", "grass_block", "podzol", "mycelium",
                "coarse_dirt", "rooted_dirt", "mud", "moss_block", "moss_carpet",
                "azalea", "flowering_azalea", "azalea_leaves", "flowering_azalea_leaves",
                "spore_blossom", "big_dripleaf", "small_dripleaf", "hanging_roots",
                "vine", "glow_lichen", "sea_pickle", "kelp", "seagrass", "lily_pad",
                "sugar_cane", "bamboo", "cactus", "chorus_plant", "chorus_flower",
                "sculk", "sculk_vein", "sculk_catalyst", "sculk_shrieker",
                "amethyst_block", "amethyst_cluster", "budding_amethyst",
                "magma_block", "glowstone", "shroomlight", "bee_nest", "beehive", "bell",
                "sponge", "wet_sponge", "cobweb", "dead_bush", "fern", "large_fern",
                "short_grass", "tall_grass", "sweet_berry_bush", "cocoa", "nether_wart",
                "crimson_nylium", "warped_nylium", "crimson_roots", "warped_roots",
                "nether_sprouts", "twisting_vines", "weeping_vines", "nether_wart_block",
                "warped_wart_block", "soul_fire", "soul_torch", "torchflower", "pitcher_plant",
                "pink_petals", "melon", "pumpkin", "carved_pumpkin", "jack_o_lantern",
                "cocoa", "torchflower_crop", "pitcher_crop", "wheat", "beetroots",
                "carrots", "potatoes", "oak_sapling", "spruce_sapling", "birch_sapling",
                "jungle_sapling", "acacia_sapling", "dark_oak_sapling", "mangrove_propagule",
                "cherry_sapling", "bamboo_sapling",
                // 染料/糖/纸等原料（有配方但野外/作物直接拿）
                "sugar", "paper", "brick", "nether_brick", "redstone", "glowstone_dust",
                "bone_meal", "white_dye", "black_dye", "red_dye", "yellow_dye", "blue_dye",
                "brown_dye", "green_dye", "cocoa_beans", "ink_sac", "glow_ink_sac",
                "wheat_seeds", "beetroot_seeds", "pumpkin_seeds", "melon_seeds",
                "torchflower_seeds", "pitcher_pod", "sweet_berries", "glow_berries",
                "chorus_fruit", "popped_chorus_fruit", "kelp", "dried_kelp", "dried_kelp_block");
        // 生怪掉落（无配方；或有配方但直接刷怪）
        gatherable("beef", "porkchop", "chicken", "mutton", "rabbit", "rabbit_hide", "rabbit_foot",
                "leather", "feather", "egg", "milk_bucket", "wool", "white_wool", "orange_wool",
                "magenta_wool", "light_blue_wool", "yellow_wool", "lime_wool", "pink_wool",
                "gray_wool", "light_gray_wool", "cyan_wool", "purple_wool", "blue_wool",
                "brown_wool", "green_wool", "red_wool", "black_wool",
                "string", "spider_eye", "gunpowder", "bone", "rotten_flesh", "slime_ball",
                "magma_cream", "blaze_rod", "ender_pearl", "ghast_tear", "phantom_membrane",
                "nautilus_shell", "prismarine_shard", "prismarine_crystals", "shulker_shell",
                "nether_star", "dragon_breath", "totem_of_undying", "echo_shard",
                "disc_fragment_5", "honeycomb", "honey_bottle", "turtle_scute", "armadillo_scute",
                // 矿石直接掉落/原料
                "raw_iron", "raw_gold", "raw_copper", "coal", "diamond", "emerald",
                "lapis_lazuli", "quartz", "netherite_scrap", "flint", "clay_ball",
                "charcoal", "glow_ink_sac");
        buildSpecialTargets();
    }

    // ---------------------------------------------------------------- 物品 → 世界载体

    private static void buildSpecialTargets() {
        // 矿石/raw → 矿石方块
        special(Items.RAW_IRON, blocks(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
        special(Items.RAW_GOLD, blocks(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE));
        special(Items.RAW_COPPER, blocks(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
        special(Items.COAL, blocks(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
        special(Items.DIAMOND, blocks(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE));
        special(Items.EMERALD, blocks(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE));
        special(Items.LAPIS_LAZULI, blocks(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE));
        special(Items.REDSTONE, blocks(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE));
        special(Items.QUARTZ, blocks(Blocks.NETHER_QUARTZ_ORE));
        special(Items.NETHERITE_SCRAP, blocks(Blocks.ANCIENT_DEBRIS));
        special(Items.FLINT, blocks(Blocks.GRAVEL));
        special(Items.CLAY_BALL, blocks(Blocks.CLAY));
        special(Items.BRICK, blocks(Blocks.CLAY));
        special(Items.NETHER_BRICK, blocks(Blocks.NETHERRACK));
        special(Items.SUGAR, blocks(Blocks.SUGAR_CANE));
        special(Items.PAPER, blocks(Blocks.SUGAR_CANE));
        special(Items.CHARCOAL, blocks(Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG,
                Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG,
                Blocks.CHERRY_LOG));
        special(Items.GLOWSTONE_DUST, blocks(Blocks.GLOWSTONE));
        special(Items.DRIED_KELP, blocks(Blocks.KELP, Blocks.KELP_PLANT));
        special(Items.DRIED_KELP_BLOCK, blocks(Blocks.KELP, Blocks.KELP_PLANT));
        special(Items.BONE_MEAL, entities(EntityType.SKELETON, EntityType.WITHER_SKELETON));

        // 种子/作物
        special(Items.WHEAT_SEEDS, blocks(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.WHEAT));
        special(Items.BEETROOT_SEEDS, blocks(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.BEETROOTS));
        special(Items.PUMPKIN_SEEDS, blocks(Blocks.PUMPKIN));
        special(Items.MELON_SEEDS, blocks(Blocks.MELON));
        special(Items.TORCHFLOWER_SEEDS, blocks(Blocks.TORCHFLOWER_CROP));
        special(Items.PITCHER_POD, blocks(Blocks.PITCHER_CROP));
        special(Items.COCOA_BEANS, blocks(Blocks.COCOA));
        special(Items.NETHER_WART, blocks(Blocks.NETHER_WART));
        special(Items.SWEET_BERRIES, blocks(Blocks.SWEET_BERRY_BUSH));
        special(Items.GLOW_BERRIES, blocks(Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT));
        special(Items.CHORUS_FRUIT, blocks(Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER));
        special(Items.POPPED_CHORUS_FRUIT, blocks(Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER));

        // 染料原料
        special(Items.WHITE_DYE, blocks(Blocks.LILY_OF_THE_VALLEY));
        special(Items.BLACK_DYE, both(blocks(Blocks.WITHER_ROSE), entities(EntityType.SQUID, EntityType.GLOW_SQUID)));
        special(Items.RED_DYE, blocks(Blocks.POPPY, Blocks.BEETROOTS, Blocks.ROSE_BUSH));
        special(Items.YELLOW_DYE, blocks(Blocks.DANDELION, Blocks.SUNFLOWER));
        special(Items.BLUE_DYE, blocks(Blocks.CORNFLOWER, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE));
        special(Items.BROWN_DYE, blocks(Blocks.COCOA));
        special(Items.GREEN_DYE, blocks(Blocks.CACTUS));
        special(Items.INK_SAC, entities(EntityType.SQUID));
        special(Items.GLOW_INK_SAC, entities(EntityType.GLOW_SQUID));

        // 羊毛/皮革/奶/蛋/羽毛/肉 —— 被动实体
        special(Items.WHITE_WOOL, entities(EntityType.SHEEP));
        special(Items.ORANGE_WOOL, entities(EntityType.SHEEP));
        special(Items.MAGENTA_WOOL, entities(EntityType.SHEEP));
        special(Items.LIGHT_BLUE_WOOL, entities(EntityType.SHEEP));
        special(Items.YELLOW_WOOL, entities(EntityType.SHEEP));
        special(Items.LIME_WOOL, entities(EntityType.SHEEP));
        special(Items.PINK_WOOL, entities(EntityType.SHEEP));
        special(Items.GRAY_WOOL, entities(EntityType.SHEEP));
        special(Items.LIGHT_GRAY_WOOL, entities(EntityType.SHEEP));
        special(Items.CYAN_WOOL, entities(EntityType.SHEEP));
        special(Items.PURPLE_WOOL, entities(EntityType.SHEEP));
        special(Items.BLUE_WOOL, entities(EntityType.SHEEP));
        special(Items.BROWN_WOOL, entities(EntityType.SHEEP));
        special(Items.GREEN_WOOL, entities(EntityType.SHEEP));
        special(Items.RED_WOOL, entities(EntityType.SHEEP));
        special(Items.BLACK_WOOL, entities(EntityType.SHEEP));
        special(Items.LEATHER, entities(EntityType.COW, EntityType.HORSE, EntityType.DONKEY,
                EntityType.MULE, EntityType.LLAMA));
        special(Items.BEEF, entities(EntityType.COW));
        special(Items.MILK_BUCKET, entities(EntityType.COW));
        special(Items.PORKCHOP, entities(EntityType.PIG));
        special(Items.CHICKEN, entities(EntityType.CHICKEN));
        special(Items.FEATHER, entities(EntityType.CHICKEN));
        special(Items.EGG, entities(EntityType.CHICKEN));
        special(Items.MUTTON, entities(EntityType.SHEEP));
        special(Items.RABBIT, entities(EntityType.RABBIT));
        special(Items.RABBIT_HIDE, entities(EntityType.RABBIT));
        special(Items.RABBIT_FOOT, entities(EntityType.RABBIT));
        special(Items.TURTLE_SCUTE, entities(EntityType.TURTLE));
        special(Items.ARMADILLO_SCUTE, entities(EntityType.ARMADILLO));
        special(Items.STRING, both(blocks(Blocks.COBWEB), entities(EntityType.SPIDER)));
        special(Items.HONEYCOMB, blocks(Blocks.BEE_NEST, Blocks.BEEHIVE));
        special(Items.HONEY_BOTTLE, blocks(Blocks.BEE_NEST, Blocks.BEEHIVE));

        // 敌对掉落也做映射（扫描器只提示被动/资源实体，敌对不会进聊天；箱子搜索仍可用）
        special(Items.BONE, entities(EntityType.SKELETON, EntityType.WITHER_SKELETON));
        special(Items.GUNPOWDER, entities(EntityType.CREEPER, EntityType.WITCH));
        special(Items.SPIDER_EYE, entities(EntityType.SPIDER, EntityType.CAVE_SPIDER));
        special(Items.ROTTEN_FLESH, entities(EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER));
        special(Items.SLIME_BALL, entities(EntityType.SLIME));
        special(Items.MAGMA_CREAM, entities(EntityType.MAGMA_CUBE));
        special(Items.BLAZE_ROD, entities(EntityType.BLAZE));
        special(Items.ENDER_PEARL, entities(EntityType.ENDERMAN));
        special(Items.GHAST_TEAR, entities(EntityType.GHAST));
        special(Items.PHANTOM_MEMBRANE, entities(EntityType.PHANTOM));
        special(Items.NAUTILUS_SHELL, entities(EntityType.DROWNED));
        special(Items.PRISMARINE_SHARD, entities(EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN));
        special(Items.PRISMARINE_CRYSTALS, entities(EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN));
        special(Items.SHULKER_SHELL, entities(EntityType.SHULKER));
        special(Items.TOTEM_OF_UNDYING, entities(EntityType.EVOKER));

        // 特殊方块/结构
        special(Items.AMETHYST_SHARD, blocks(Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD,
                Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD));
        special(Items.SPONGE, blocks(Blocks.WET_SPONGE, Blocks.SPONGE));
        special(Items.HEAVY_CORE, blocks(Blocks.VAULT));
        special(Items.OMINOUS_BOTTLE, blocks(Blocks.VAULT));
        special(Items.TRIAL_KEY, blocks(Blocks.VAULT));
        special(Items.WITHER_SKELETON_SKULL, entities(EntityType.WITHER_SKELETON));
        special(Items.SKELETON_SKULL, entities(EntityType.SKELETON));
        special(Items.ZOMBIE_HEAD, entities(EntityType.ZOMBIE));
        special(Items.CREEPER_HEAD, entities(EntityType.CREEPER));
        special(Items.PIGLIN_HEAD, entities(EntityType.PIGLIN));
        special(Items.DRAGON_HEAD, blocks());
        special(Items.DRAGON_EGG, blocks());
        special(Items.ELYTRA, blocks());
        special(Items.NETHER_STAR, blocks());
        special(Items.DRAGON_BREATH, blocks());
        special(Items.ECHO_SHARD, blocks());
        special(Items.DISC_FRAGMENT_5, blocks());
    }

    private static void special(Item item, Target target) {
        if (item != null && item != Items.AIR && target != null) {
            SPECIAL.put(item, target);
        }
    }

    private static Target blocks(Block... blocks) {
        List<Block> list = new ArrayList<>();
        for (Block block : blocks) {
            if (block != null) list.add(block);
        }
        String source = list.isEmpty() ? "?" : blockName(list.get(0));
        return new Target(source, List.copyOf(list), List.of());
    }

    private static Target entities(EntityType<?>... types) {
        List<EntityType<?>> list = new ArrayList<>();
        for (EntityType<?> type : types) {
            if (type != null) list.add(type);
        }
        String source = list.isEmpty() ? "?" : entityName(list.get(0));
        return new Target(source, List.of(), List.copyOf(list));
    }

    private static Target both(Target blockTarget, Target entityTarget) {
        List<Block> blocks = new ArrayList<>(blockTarget.blocks());
        List<EntityType<?>> entities = new ArrayList<>(entityTarget.entities());
        String source = !blocks.isEmpty() ? blockName(blocks.get(0))
                : (!entities.isEmpty() ? entityName(entities.get(0)) : "?");
        return new Target(source, List.copyOf(blocks), List.copyOf(entities));
    }

    private static String blockName(Block block) {
        return Text.translatable(block.getTranslationKey()).getString();
    }

    private static String entityName(EntityType<?> type) {
        return type.getName().getString();
    }

    // ---------------------------------------------------------------- 对外

    /** 这个物品是否在“可直接采集”白名单里（有配方但野外也能直接拿的例外）。 */
    public static boolean isCuratedGatherable(Item item) {
        if (item == null || item == Items.AIR) return false;
        return CURATED_GATHERABLE.contains(Registries.ITEM.getId(item).getPath());
    }

    /** 物品在世界的载体；没有世界载体（结构/合成品/NBT）返回 null。 */
    public static Target targetFor(Item item) {
        if (item == null || item == Items.AIR) return null;
        Target target = SPECIAL.get(item);
        if (target != null) {
            return target;
        }
        // 原木类：材料列表里“任意木板”的通用配方会落到某一个具体树种（通常橡木），
        // 但玩家在热带草原看到的金合欢同样是有效来源 ⇒ 统一归成“原木”，所有自然原木都算。
        if (isLogItem(item)) {
            return new Target("原木", List.copyOf(NATURAL_LOGS), List.of());
        }
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            return new Target(blockName(block), List.of(block), List.of());
        }
        return null;
    }

    private static boolean isLogItem(Item item) {
        if (!(item instanceof BlockItem)) {
            return false;
        }
        String path = Registries.ITEM.getId(item).getPath();
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem")
                || path.endsWith("_hyphae") || path.equals("bamboo_block");
    }
}
