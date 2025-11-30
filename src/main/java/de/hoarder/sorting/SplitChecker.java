package de.hoarder.sorting;

import de.hoarder.HoarderPlugin;
import de.hoarder.config.HoarderConfig;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkChest;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Checks if chests should be split into subcategories
 * and performs the split
 */
public class SplitChecker {

    private final HoarderPlugin plugin;
    private final ChestNetwork network;
    private final HoarderConfig config;
    private final ItemHierarchy hierarchy;

    public SplitChecker(HoarderPlugin plugin, ChestNetwork network) {
        this.plugin = plugin;
        this.network = network;
        this.config = plugin.getHoarderConfig();
        this.hierarchy = plugin.getItemHierarchy();
    }

    /**
     * Check if a chest should be split
     */
    public boolean shouldSplit(NetworkChest chest) {
        // Check fill percentage
        double fillPercentage = chest.getFillPercentage() * 100;
        if (fillPercentage < config.getSplitThreshold()) {
            return false;
        }

        // Check minimum items
        int totalItems = chest.getTotalItems();
        if (totalItems < config.getMinItemsForSplit()) {
            return false;
        }

        // Check if there are multiple subcategories
        Map<String, Integer> subcategories = getSubcategoryDistribution(chest);
        return subcategories.size() > 1;
    }

    /**
     * Get distribution of subcategories in a chest
     */
    public Map<String, Integer> getSubcategoryDistribution(NetworkChest chest) {
        Map<String, Integer> distribution = new HashMap<>();
        String currentCategory = chest.getAssignedCategory();

        Inventory inv = chest.getInventory();
        if (inv == null) return distribution;

        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;

            String itemCategory = hierarchy.getCategory(item.getType());

            // Determine the relevant subcategory
            String subcat;
            if (currentCategory == null) {
                // No category assigned - use root category
                subcat = hierarchy.getRootCategory(itemCategory);
            } else if (itemCategory.equals(currentCategory)) {
                subcat = currentCategory;
            } else if (itemCategory.startsWith(currentCategory + "/")) {
                // Get next level subcategory
                String remainder = itemCategory.substring(currentCategory.length() + 1);
                int slash = remainder.indexOf('/');
                subcat = currentCategory + "/" + (slash > 0 ? remainder.substring(0, slash) : remainder);
            } else {
                subcat = itemCategory;
            }

            distribution.merge(subcat, item.getAmount(), Integer::sum);
        }

        return distribution;
    }

    /**
     * Perform the split - reassign category to more specific subcategory
     */
    public void performSplit(NetworkChest chest) {
        Map<String, Integer> distribution = getSubcategoryDistribution(chest);

        if (distribution.isEmpty()) return;

        // Find the dominant subcategory
        String dominantSubcat = distribution.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        if (dominantSubcat == null) return;

        String oldCategory = chest.getAssignedCategory();

        // Assign the more specific category
        network.assignCategory(chest.getLocation(), dominantSubcat);

        if (config.isDebug()) {
            plugin.getLogger().info("Split chest at " + formatLocation(chest.getLocation()) +
                " from '" + (oldCategory != null ? oldCategory : "any") +
                "' to '" + dominantSubcat + "'");
        }

        // Items that don't match the new category will be moved in the next sort pass
    }

    /**
     * Analyze entire network and suggest optimal category assignments
     */
    public Map<NetworkChest, String> suggestOptimalAssignments() {
        Map<NetworkChest, String> suggestions = new HashMap<>();

        // Collect all items by category
        Map<String, Integer> globalCategoryCounts = new HashMap<>();

        for (NetworkChest chest : network.getChestsInOrder()) {
            Inventory inv = chest.getInventory();
            if (inv == null) continue;

            for (ItemStack item : inv.getContents()) {
                if (item == null) continue;
                String category = hierarchy.getCategory(item.getType());
                globalCategoryCounts.merge(category, item.getAmount(), Integer::sum);
            }
        }

        // Sort categories by item count
        List<String> sortedCategories = new ArrayList<>(globalCategoryCounts.keySet());
        sortedCategories.sort((a, b) -> globalCategoryCounts.get(b) - globalCategoryCounts.get(a));

        // Assign categories to chests in position order
        List<NetworkChest> chests = network.getChestsInOrder();
        int chestIndex = 0;

        for (String category : sortedCategories) {
            if (chestIndex >= chests.size()) break;

            int itemCount = globalCategoryCounts.get(category);
            int chestsNeeded = Math.max(1, (itemCount + 1728 - 1) / 1728); // 27 slots * 64 items

            for (int i = 0; i < chestsNeeded && chestIndex < chests.size(); i++) {
                suggestions.put(chests.get(chestIndex), category);
                chestIndex++;
            }
        }

        return suggestions;
    }

    /**
     * Calculate how many chests a category needs
     */
    public int calculateChestsNeeded(String category, int totalItems) {
        // Assume average stack size of 64, single chest = 27 slots
        int itemsPerChest = 27 * 64;
        return Math.max(1, (totalItems + itemsPerChest - 1) / itemsPerChest);
    }

    /**
     * Format location for logging
     */
    private String formatLocation(org.bukkit.Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
