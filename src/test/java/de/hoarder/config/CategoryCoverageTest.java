package de.hoarder.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the bundled config.yml: every listed name is a real material, no
 * material is listed in two paths (the last one would silently win), and every
 * obtainable item has a category, so a new Minecraft version cannot quietly
 * dump its new items into "misc".
 */
class CategoryCoverageTest {

    /** Blocks that exist as Material but never as an item in a chest. */
    private static final Pattern NOT_AN_ITEM = Pattern.compile(
        "^(AIR|CAVE_AIR|VOID_AIR|WATER|LAVA|FIRE|SOUL_FIRE|MOVING_PISTON|PISTON_HEAD|BUBBLE_COLUMN|END_GATEWAY|END_PORTAL|NETHER_PORTAL"
        + "|FROSTED_ICE|REDSTONE_WIRE|TRIPWIRE|POWDER_SNOW|CANDLE_CAKE|KELP_PLANT|WEEPING_VINES_PLANT|TWISTING_VINES_PLANT|CAVE_VINES_PLANT"
        + "|TALL_SEAGRASS|BIG_DRIPLEAF_STEM|BAMBOO_SAPLING|SWEET_BERRY_BUSH|COCOA|CARROTS|POTATOES|BEETROOTS|MELON_STEM|PUMPKIN_STEM"
        + "|ATTACHED_MELON_STEM|ATTACHED_PUMPKIN_STEM|TORCHFLOWER_CROP|PITCHER_CROP|WATER_CAULDRON|LAVA_CAULDRON|POWDER_SNOW_CAULDRON"
        + "|WALL_TORCH|LIGHT|KNOWLEDGE_BOOK|DEBUG_STICK|COMMAND_BLOCK_MINECART|CHAIN_COMMAND_BLOCK|REPEATING_COMMAND_BLOCK"
        + "|.*_CANDLE_CAKE|.*_WALL_SIGN|.*_WALL_HANGING_SIGN|.*_WALL_TORCH|.*_WALL_BANNER|.*_WALL_HEAD|.*_WALL_SKULL|.*_WALL_FAN"
        + "|POTTED_.*|TEST_BLOCK|TEST_INSTANCE_BLOCK|.*_SPAWN_EGG)$");

    private static YamlConfiguration bundledConfig() {
        File file = new File("src/main/resources/config.yml");
        assertTrue(file.isFile(), "bundled config.yml is missing: " + file.getAbsolutePath());
        return YamlConfiguration.loadConfiguration(file);
    }

    /** Material name -> the paths that list it. */
    private static Map<String, List<String>> listed(YamlConfiguration config) {
        Map<String, List<String>> where = new LinkedHashMap<>();
        ConfigurationSection paths = config.getConfigurationSection("paths");
        assertTrue(paths != null, "no 'paths' section");
        for (String path : paths.getKeys(false)) {
            for (String name : paths.getStringList(path)) {
                where.computeIfAbsent(name.toUpperCase(), k -> new ArrayList<>()).add(path);
            }
        }
        return where;
    }

    @Test
    void everyListedNameIsAMaterial() {
        List<String> unknown = new ArrayList<>();
        for (String name : listed(bundledConfig()).keySet()) {
            try {
                Material.valueOf(name);
            } catch (IllegalArgumentException e) {
                unknown.add(name);
            }
        }
        assertTrue(unknown.isEmpty(), "config lists names that are no Material in this API: " + unknown);
    }

    @Test
    void noMaterialIsListedTwice() {
        List<String> twice = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : listed(bundledConfig()).entrySet()) {
            if (entry.getValue().size() > 1) {
                twice.add(entry.getKey() + " in " + entry.getValue());
            }
        }
        assertTrue(twice.isEmpty(), "materials listed in more than one path (the last wins silently): " + twice);
    }

    @Test
    void everyItemHasACategory() {
        Set<String> covered = listed(bundledConfig()).keySet();
        Set<String> missing = new TreeSet<>();
        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            String name = material.name();
            if (NOT_AN_ITEM.matcher(name).matches() || covered.contains(name)) {
                continue;
            }
            missing.add(name);
        }
        assertTrue(missing.isEmpty(), missing.size() + " items without a category (they would land in misc): " + missing);
    }
}
