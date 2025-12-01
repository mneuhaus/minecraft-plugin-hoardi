package de.hoarder.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Manages plugin configuration and item hierarchy
 */
public class HoarderConfig {

    private final JavaPlugin plugin;

    // Settings
    private int networkRadius;
    private int splitThreshold;
    private int minItemsForSplit;
    private int sortDelayTicks;
    private boolean debug;

    // Spatial settings
    private int stackHeight;
    private boolean counterClockwise;
    private boolean bottomToTop;

    // Performance settings
    private boolean quickSortOnClose;
    private int fullReorganizeInterval;
    private int itemsPerTick;

    // Item hierarchy: material -> category path
    private final Map<Material, String> materialToCategory = new HashMap<>();

    // All category paths sorted by config order
    private final List<String> sortedCategories = new ArrayList<>();

    // Category order from config (root categories only)
    private final List<String> categoryOrder = new ArrayList<>();

    // Display names for categories
    private final Map<String, String> displayNames = new HashMap<>();

    public HoarderConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Load configuration from file
     */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // Settings
        ConfigurationSection settings = config.getConfigurationSection("settings");
        if (settings != null) {
            networkRadius = settings.getInt("network_radius", 32);
            splitThreshold = settings.getInt("split_threshold", 50);
            minItemsForSplit = settings.getInt("min_items_for_split", 32);
            sortDelayTicks = settings.getInt("sort_delay_ticks", 10);
            debug = settings.getBoolean("debug", false);
        }

        // Spatial settings
        ConfigurationSection spatial = config.getConfigurationSection("spatial");
        if (spatial != null) {
            stackHeight = spatial.getInt("stack_height", 3);
            String direction = spatial.getString("spiral_direction", "COUNTER_CLOCKWISE");
            counterClockwise = direction.equalsIgnoreCase("COUNTER_CLOCKWISE");
            String verticalOrder = spatial.getString("vertical_order", "BOTTOM_TO_TOP");
            bottomToTop = verticalOrder.equalsIgnoreCase("BOTTOM_TO_TOP");
        }

        // Performance settings
        ConfigurationSection performance = config.getConfigurationSection("performance");
        if (performance != null) {
            quickSortOnClose = performance.getBoolean("quick_sort_on_close", true);
            fullReorganizeInterval = performance.getInt("full_reorganize_interval", 12000);
            itemsPerTick = performance.getInt("items_per_tick", 64);
        }

        // Load category order
        loadCategoryOrder(config);

        // Load item hierarchy
        loadItemHierarchy(config);

        // Load display names
        loadDisplayNames(config);

        if (debug) {
            plugin.getLogger().info("Loaded " + materialToCategory.size() + " material mappings");
            plugin.getLogger().info("Loaded " + sortedCategories.size() + " categories");
        }
    }

    /**
     * Load category order from config
     */
    private void loadCategoryOrder(FileConfiguration config) {
        categoryOrder.clear();
        List<String> order = config.getStringList("category_order");
        if (order != null && !order.isEmpty()) {
            categoryOrder.addAll(order);
        }
        if (debug) {
            plugin.getLogger().info("Loaded category order: " + categoryOrder);
        }
    }

    /**
     * Load item hierarchy from config
     */
    private void loadItemHierarchy(FileConfiguration config) {
        materialToCategory.clear();
        sortedCategories.clear();

        ConfigurationSection paths = config.getConfigurationSection("paths");
        if (paths == null) {
            plugin.getLogger().warning("No 'paths' section found in config!");
            return;
        }

        Set<String> categories = new HashSet<>();

        for (String path : paths.getKeys(false)) {
            List<String> materials = paths.getStringList(path);

            // Add all parent categories
            String[] parts = path.split("/");
            StringBuilder currentPath = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) currentPath.append("/");
                currentPath.append(parts[i]);
                categories.add(currentPath.toString());
            }

            // Map materials to this path
            for (String materialName : materials) {
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    materialToCategory.put(material, path);
                } catch (IllegalArgumentException e) {
                    if (debug) {
                        plugin.getLogger().warning("Unknown material: " + materialName);
                    }
                }
            }
        }

        // Sort categories by config order, subcategories alphabetically within their parent
        sortedCategories.addAll(categories);
        sortedCategories.sort((a, b) -> {
            String rootA = getRootFromPath(a);
            String rootB = getRootFromPath(b);

            // Get order index for root categories
            int indexA = categoryOrder.indexOf(rootA);
            int indexB = categoryOrder.indexOf(rootB);

            // Categories not in order go to end (before misc)
            if (indexA < 0) indexA = Integer.MAX_VALUE - 1;
            if (indexB < 0) indexB = Integer.MAX_VALUE - 1;

            // Compare by root order first
            if (indexA != indexB) {
                return Integer.compare(indexA, indexB);
            }

            // Same root category - sort alphabetically
            return a.compareTo(b);
        });
    }

    /**
     * Get the root category from a path (e.g., "wood/oak" -> "wood")
     */
    private String getRootFromPath(String path) {
        int slash = path.indexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }

    /**
     * Load display names from config
     */
    private void loadDisplayNames(FileConfiguration config) {
        displayNames.clear();

        ConfigurationSection names = config.getConfigurationSection("display_names");
        if (names == null) return;

        for (String path : names.getKeys(false)) {
            String displayName = names.getString(path);
            if (displayName != null) {
                displayNames.put(path, displayName);
            }
        }
    }

    /**
     * Get the category path for a material
     * Returns "misc" if no specific category is defined
     */
    public String getCategoryForMaterial(Material material) {
        return materialToCategory.getOrDefault(material, "misc");
    }

    /**
     * Get display name for a category path
     */
    public String getDisplayName(String categoryPath) {
        // Try exact match first
        if (displayNames.containsKey(categoryPath)) {
            return displayNames.get(categoryPath);
        }

        // Try parent categories
        String[] parts = categoryPath.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            StringBuilder parentPath = new StringBuilder();
            for (int j = 0; j <= i; j++) {
                if (j > 0) parentPath.append("/");
                parentPath.append(parts[j]);
            }
            String parent = parentPath.toString();
            if (displayNames.containsKey(parent)) {
                return displayNames.get(parent);
            }
        }

        // Fallback: use the last part of the path
        return parts[parts.length - 1].substring(0, 1).toUpperCase() + parts[parts.length - 1].substring(1);
    }

    /**
     * Get all categories sorted (misc last)
     */
    public List<String> getSortedCategories() {
        return Collections.unmodifiableList(sortedCategories);
    }

    /**
     * Check if a category path is a parent of another
     */
    public boolean isParentCategory(String parent, String child) {
        return child.startsWith(parent + "/");
    }

    /**
     * Get the parent category of a path
     */
    public String getParentCategory(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            return path.substring(0, lastSlash);
        }
        return null;
    }

    /**
     * Get the depth of a category path
     */
    public int getCategoryDepth(String path) {
        if (path == null || path.isEmpty()) return 0;
        return (int) path.chars().filter(c -> c == '/').count() + 1;
    }

    /**
     * Get the category order list
     */
    public List<String> getCategoryOrder() {
        return Collections.unmodifiableList(categoryOrder);
    }

    /**
     * Compare two categories by config order
     * @return negative if a comes before b, positive if b comes before a, 0 if equal
     */
    public int compareCategoriesByOrder(String a, String b) {
        String rootA = getRootFromPath(a);
        String rootB = getRootFromPath(b);

        int indexA = categoryOrder.indexOf(rootA);
        int indexB = categoryOrder.indexOf(rootB);

        // Categories not in order go to end (before misc)
        if (indexA < 0) indexA = Integer.MAX_VALUE - 1;
        if (indexB < 0) indexB = Integer.MAX_VALUE - 1;

        // Compare by root order first
        if (indexA != indexB) {
            return Integer.compare(indexA, indexB);
        }

        // Same root category - sort alphabetically
        return a.compareTo(b);
    }

    // Getters

    public int getNetworkRadius() {
        return networkRadius;
    }

    public int getSplitThreshold() {
        return splitThreshold;
    }

    public int getMinItemsForSplit() {
        return minItemsForSplit;
    }

    public int getSortDelayTicks() {
        return sortDelayTicks;
    }

    public boolean isDebug() {
        return debug;
    }

    public int getStackHeight() {
        return stackHeight;
    }

    public boolean isCounterClockwise() {
        return counterClockwise;
    }

    public boolean isBottomToTop() {
        return bottomToTop;
    }

    public boolean isQuickSortOnClose() {
        return quickSortOnClose;
    }

    public int getFullReorganizeInterval() {
        return fullReorganizeInterval;
    }

    public int getItemsPerTick() {
        return itemsPerTick;
    }
}
