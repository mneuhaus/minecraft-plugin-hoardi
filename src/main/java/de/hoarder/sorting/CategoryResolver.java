package de.hoarder.sorting;

import de.hoarder.config.HoarderConfig;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkChest;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Resolves which chest an item should go into
 */
public class CategoryResolver {

    private final HoarderConfig config;
    private final ItemHierarchy hierarchy;

    public CategoryResolver(HoarderConfig config, ItemHierarchy hierarchy) {
        this.config = config;
        this.hierarchy = hierarchy;
    }

    /**
     * Find the best chest for an item stack
     * @param sourceChestLoc - the chest the item is currently in (to avoid returning same chest)
     */
    public NetworkChest findTargetChest(ChestNetwork network, ItemStack item, Location sourceChestLoc) {
        Material material = item.getType();
        String itemCategory = hierarchy.getCategory(material);

        // Strategy 1: Find chest with exact category match (not source)
        NetworkChest exactMatch = findChestWithCategory(network, itemCategory);
        if (exactMatch != null && exactMatch.hasFreeSpace() && !exactMatch.getLocation().equals(sourceChestLoc)) {
            return exactMatch;
        }

        // Strategy 2: Find chest with parent category (not source)
        String parentCategory = itemCategory;
        while ((parentCategory = hierarchy.getParentCategory(parentCategory)) != null) {
            NetworkChest parentMatch = findChestWithCategory(network, parentCategory);
            if (parentMatch != null && parentMatch.hasFreeSpace() && !parentMatch.getLocation().equals(sourceChestLoc)) {
                return parentMatch;
            }
        }

        // Strategy 3: Find chest that already contains this item type (not source)
        NetworkChest sameItemChest = findChestWithSameItem(network, material);
        if (sameItemChest != null && sameItemChest.hasFreeSpace() && !sameItemChest.getLocation().equals(sourceChestLoc)) {
            return sameItemChest;
        }

        // Strategy 4: Find chest with items from same category (not source)
        NetworkChest sameCategoryChest = findChestWithSameCategoryItems(network, itemCategory);
        if (sameCategoryChest != null && sameCategoryChest.hasFreeSpace() && !sameCategoryChest.getLocation().equals(sourceChestLoc)) {
            return sameCategoryChest;
        }

        // Strategy 5: Use any other chest with free space
        for (NetworkChest chest : network.getChestsInOrder()) {
            if (chest.hasFreeSpace() && !chest.getLocation().equals(sourceChestLoc)) {
                return chest;
            }
        }

        // No other chest available - return null (keep in source)
        return null;
    }

    /**
     * Find the best chest for an item stack (legacy - uses first available)
     */
    public NetworkChest findTargetChest(ChestNetwork network, ItemStack item) {
        return findTargetChest(network, item, null);
    }

    /**
     * Find chest assigned to specific category
     */
    private NetworkChest findChestWithCategory(ChestNetwork network, String category) {
        List<NetworkChest> chests = network.getChestsForCategory(category);
        for (NetworkChest chest : chests) {
            if (chest.hasFreeSpace()) {
                return chest;
            }
        }
        return null;
    }

    /**
     * Find chest that already contains the same item type
     */
    private NetworkChest findChestWithSameItem(ChestNetwork network, Material material) {
        for (NetworkChest chest : network.getChestsInOrder()) {
            var inventory = chest.getInventory();
            if (inventory == null) continue;

            for (ItemStack item : inventory.getContents()) {
                if (item != null && item.getType() == material) {
                    return chest;
                }
            }
        }
        return null;
    }

    /**
     * Find chest with items from the same category
     */
    private NetworkChest findChestWithSameCategoryItems(ChestNetwork network, String targetCategory) {
        String targetRoot = hierarchy.getRootCategory(targetCategory);

        for (NetworkChest chest : network.getChestsInOrder()) {
            var inventory = chest.getInventory();
            if (inventory == null || !chest.hasFreeSpace()) continue;

            // Check if chest has items from same root category
            for (ItemStack item : inventory.getContents()) {
                if (item != null) {
                    String itemCategory = hierarchy.getCategory(item.getType());
                    String itemRoot = hierarchy.getRootCategory(itemCategory);

                    if (targetRoot != null && targetRoot.equals(itemRoot)) {
                        return chest;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Analyze chest contents and suggest category assignment
     */
    public String suggestCategoryForChest(NetworkChest chest) {
        var inventory = chest.getInventory();
        if (inventory == null) return null;

        Map<String, Integer> categoryCounts = new HashMap<>();

        for (ItemStack item : inventory.getContents()) {
            if (item == null) continue;

            String category = hierarchy.getCategory(item.getType());
            categoryCounts.merge(category, item.getAmount(), Integer::sum);
        }

        if (categoryCounts.isEmpty()) return null;

        // Find most common category
        return categoryCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Check if items in chest are consistent with assigned category
     */
    public boolean isChestCategoryConsistent(NetworkChest chest) {
        String assignedCategory = chest.getAssignedCategory();
        if (assignedCategory == null) return true;

        var inventory = chest.getInventory();
        if (inventory == null) return true;

        for (ItemStack item : inventory.getContents()) {
            if (item == null) continue;

            String itemCategory = hierarchy.getCategory(item.getType());

            // Item should match assigned category or be a subcategory
            if (!itemCategory.equals(assignedCategory) &&
                !itemCategory.startsWith(assignedCategory + "/")) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get items that don't belong in their current chest
     */
    public List<ItemStack> getMisplacedItems(NetworkChest chest) {
        List<ItemStack> misplaced = new ArrayList<>();
        String assignedCategory = chest.getAssignedCategory();

        if (assignedCategory == null) return misplaced;

        var inventory = chest.getInventory();
        if (inventory == null) return misplaced;

        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null) continue;

            String itemCategory = hierarchy.getCategory(item.getType());

            if (!itemCategory.equals(assignedCategory) &&
                !itemCategory.startsWith(assignedCategory + "/")) {
                misplaced.add(item.clone());
            }
        }

        return misplaced;
    }
}
