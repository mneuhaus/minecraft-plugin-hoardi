package de.hoarder.demo;

/** Item tables for the demo: a lived-in survival hoard and one mining trip's loot. "MATERIAL:count". */
final class Hoard {

    private Hoard() {
    }

    static final String[] HOARD = {
        // stone & earth
        "cobblestone:5120", "cobbled_deepslate:3200", "stone:1280", "deepslate:640", "andesite:1600",
        "diorite:1024", "granite:1152", "tuff:896", "calcite:192", "gravel:512", "sand:1024",
        "sandstone:384", "dirt:1920", "coarse_dirt:128", "clay_ball:160", "netherrack:1600",
        "blackstone:448", "basalt:320", "smooth_stone:256", "stone_bricks:896", "polished_andesite:512",
        "bricks:192", "mossy_cobblestone:96", "obsidian:48",
        // wood
        "oak_log:1600", "spruce_log:1280", "birch_log:768", "dark_oak_log:512", "cherry_log:256",
        "mangrove_log:128", "oak_planks:1536", "spruce_planks:1024", "birch_planks:384", "stick:448",
        "oak_slab:256", "spruce_stairs:192", "oak_fence:96", "ladder:128", "chest:24", "barrel:16",
        "crafting_table:6", "oak_sapling:48", "spruce_sapling:32", "birch_sapling:20", "oak_leaves:128",
        "bamboo:192",
        // ores & minerals
        "coal:1152", "charcoal:192", "raw_iron:576", "iron_ingot:832", "iron_nugget:120", "raw_copper:640",
        "copper_ingot:448", "raw_gold:160", "gold_ingot:288", "gold_nugget:200", "redstone:1088",
        "lapis_lazuli:512", "diamond:47", "emerald:38", "quartz:384", "amethyst_shard:96", "iron_block:18",
        "copper_block:24", "coal_block:32", "glowstone_dust:256", "netherite_scrap:3",
        // food & farming
        "wheat:576", "wheat_seeds:832", "bread:128", "carrot:448", "potato:512", "baked_potato:192",
        "beetroot:128", "beetroot_seeds:96", "apple:54", "golden_carrot:96", "cooked_beef:160",
        "cooked_porkchop:96", "cooked_chicken:64", "cooked_mutton:48", "cooked_cod:40", "sweet_berries:120",
        "glow_berries:64", "melon_slice:192", "pumpkin:64", "sugar_cane:384", "sugar:96", "egg:48",
        "cactus:64", "kelp:128", "dried_kelp:96", "cocoa_beans:64", "honey_bottle:12",
        // mob drops
        "rotten_flesh:448", "bone:384", "bone_meal:320", "string:256", "spider_eye:48", "gunpowder:192",
        "arrow:256", "ender_pearl:28", "slime_ball:64", "feather:128", "leather:96", "ink_sac:64",
        "blaze_rod:24", "phantom_membrane:12",
        // redstone
        "redstone_torch:64", "repeater:48", "comparator:24", "piston:40", "sticky_piston:16", "observer:24",
        "hopper:36", "dispenser:12", "dropper:12", "lever:32", "stone_button:48", "rail:320",
        "powered_rail:64", "minecart:4", "target:8",
        // decoration
        "torch:512", "lantern:48", "glass:640", "glass_pane:256", "white_wool:192", "red_wool:64",
        "blue_wool:64", "yellow_wool:64", "white_terracotta:128", "orange_terracotta:64", "flower_pot:12",
        "painting:6", "item_frame:32", "white_candle:16", "cyan_dye:32", "red_dye:48", "yellow_dye:48",
        "blue_dye:24", "poppy:48", "dandelion:64", "cornflower:24", "oxeye_daisy:20", "bookshelf:24",
        "book:64", "paper:192",
        // tools & armor
        "iron_pickaxe:3", "diamond_pickaxe:1", "iron_axe:2", "iron_shovel:2", "diamond_sword:1",
        "iron_sword:2", "bow:2", "crossbow:1", "shield:2", "fishing_rod:1", "shears:2", "flint_and_steel:1",
        "bucket:4", "water_bucket:2", "iron_helmet:1", "iron_chestplate:1", "iron_leggings:1",
        "iron_boots:1", "diamond_helmet:1", "chainmail_boots:1", "saddle:2", "name_tag:3",
        // nether
        "nether_wart:96", "soul_sand:192", "magma_cream:8", "blaze_powder:16", "glowstone:64",
        "nether_bricks:128", "crimson_stem:128", "warped_stem:64",
    };

    /** What comes back from one trip to the mines; exactly one chest worth (27 slots). */
    static final String[] LOOT = {
        "cobblestone:64", "raw_iron:31", "cobbled_deepslate:64", "coal:38", "tuff:37", "rotten_flesh:9",
        "raw_copper:47", "cobblestone:64", "andesite:29", "redstone:26", "diorite:18", "bone:6",
        "cobbled_deepslate:41", "lapis_lazuli:14", "granite:23", "string:4", "diamond:4",
        "amethyst_shard:9", "arrow:13", "pointed_dripstone:7", "moss_block:12", "glow_berries:11",
        "gunpowder:3", "torch:17", "bread:5", "iron_pickaxe:1", "emerald:1",
    };
}
