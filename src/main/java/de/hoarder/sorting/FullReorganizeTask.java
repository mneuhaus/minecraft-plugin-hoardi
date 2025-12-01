package de.hoarder.sorting;

import de.hoarder.HoarderPlugin;
import de.hoarder.config.HoarderConfig;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkChest;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Full network reorganization task
 * Redistributes all items optimally across all chests using hierarchical splitting
 *
 * Algorithm:
 * 1. Collect all items grouped by leaf category
 * 2. Start with root categories (wood, stone, etc.)
 * 3. For each category:
 *    - Calculate needed chests
 *    - If <= 1 chest: keep together
 *    - If > 1 chest: split into subcategories and repeat
 * 4. If not enough chests: merge back up the hierarchy
 * 5. Overflow cramming as last resort
 */
public class FullReorganizeTask extends BukkitRunnable {

    private final HoarderPlugin plugin;
    private final HoarderConfig config;
    private final ItemHierarchy hierarchy;

    // Chest capacity: 27 slots * 64 items (approximation for mixed stacks)
    private static final int CHEST_CAPACITY = 27 * 64;

    public FullReorganizeTask(HoarderPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getHoarderConfig();
        this.hierarchy = plugin.getItemHierarchy();
    }

    @Override
    public void run() {
        for (ChestNetwork network : plugin.getNetworkManager().getAllNetworks()) {
            if (network.isEmpty()) continue;
            reorganizeNetwork(network);
        }
    }

    /**
     * Reorganize a single network
     */
    public void reorganizeNetwork(ChestNetwork network) {
        plugin.getLogger().info("[Hoardi] ========== FULL REORGANIZE ==========");
        plugin.getLogger().info("[Hoardi] Network has " + network.size() + " chests");

        // Step 1: Collect ALL items from all chests, grouped by leaf category
        Map<String, List<ItemStack>> itemsByLeafCategory = collectAllItems(network);

        if (itemsByLeafCategory.isEmpty()) {
            plugin.getLogger().info("[Hoardi] No items to sort.");
            return;
        }

        // Debug: Show collected items
        logCollectedItems(itemsByLeafCategory);

        // Step 2: Clear all category assignments
        network.clearCategoryAssignments();

        // Step 3: Build distribution plan using hierarchical splitting
        int availableChests = network.size();
        List<CategoryGroup> distributionPlan = buildDistributionPlan(itemsByLeafCategory, availableChests);

        // Debug: Show distribution plan
        plugin.getLogger().info("[Hoardi] Distribution plan (" + distributionPlan.size() + " groups for " + availableChests + " chests):");
        for (CategoryGroup group : distributionPlan) {
            plugin.getLogger().info("[Hoardi]   " + group.displayName + ": " + group.itemCount + " items (~" +
                String.format("%.1f", group.chestsNeeded) + " chests)");
        }

        // Step 4: Distribute items to chests
        List<NetworkChest> chestsInOrder = network.getChestsInOrder();
        List<ItemStack> overflowItems = distributeItems(distributionPlan, chestsInOrder, network);

        // Step 5: Handle overflow by cramming
        if (!overflowItems.isEmpty()) {
            handleOverflow(overflowItems, chestsInOrder, network);
        }

        // Save network state
        plugin.getNetworkManager().save();

        plugin.getLogger().info("[Hoardi] Full reorganize complete!");
    }

    /**
     * Build distribution plan using hierarchical splitting
     *
     * Start with root categories, split if they need > 1 chest
     */
    private List<CategoryGroup> buildDistributionPlan(Map<String, List<ItemStack>> itemsByLeafCategory, int availableChests) {
        // Group leaf categories by root
        Map<String, Map<String, List<ItemStack>>> itemsByRoot = new LinkedHashMap<>();

        for (Map.Entry<String, List<ItemStack>> entry : itemsByLeafCategory.entrySet()) {
            String leafCategory = entry.getKey();
            String root = hierarchy.getRootCategory(leafCategory);
            if (root == null) root = "misc";

            itemsByRoot.computeIfAbsent(root, k -> new LinkedHashMap<>())
                       .put(leafCategory, entry.getValue());
        }

        // Sort roots by config order
        List<String> sortedRoots = new ArrayList<>(itemsByRoot.keySet());
        sortedRoots.sort(config::compareCategoriesByOrder);

        // Build groups with splitting
        List<CategoryGroup> groups = new ArrayList<>();

        for (String root : sortedRoots) {
            Map<String, List<ItemStack>> rootItems = itemsByRoot.get(root);
            List<CategoryGroup> rootGroups = splitCategory(root, rootItems, 1);
            groups.addAll(rootGroups);
        }

        // Check if we have too many groups for available chests
        if (groups.size() > availableChests) {
            plugin.getLogger().info("[Hoardi] Too many groups (" + groups.size() + ") for " + availableChests + " chests, merging...");
            groups = mergeSmallGroups(groups, availableChests);
        }

        return groups;
    }

    /**
     * Recursively split a category if it needs more than 1 chest
     *
     * @param categoryPath Current category path (e.g., "wood" or "wood/oak")
     * @param itemsByLeaf Map of leaf categories under this category to their items
     * @param depth Current depth in hierarchy
     * @return List of CategoryGroups (either this category as one group, or split into subcategories)
     */
    private List<CategoryGroup> splitCategory(String categoryPath, Map<String, List<ItemStack>> itemsByLeaf, int depth) {
        // Calculate total items in this category
        int totalItems = 0;
        List<ItemStack> allItems = new ArrayList<>();
        for (List<ItemStack> items : itemsByLeaf.values()) {
            for (ItemStack item : items) {
                totalItems += item.getAmount();
                allItems.add(item);
            }
        }

        double chestsNeeded = (double) totalItems / CHEST_CAPACITY;

        // If fits in 1 chest, don't split
        if (chestsNeeded <= 1.0) {
            CategoryGroup group = new CategoryGroup(categoryPath, allItems, totalItems, chestsNeeded);
            return List.of(group);
        }

        // Need to split - group items by next level subcategory
        Map<String, Map<String, List<ItemStack>>> bySubcategory = new LinkedHashMap<>();

        for (Map.Entry<String, List<ItemStack>> entry : itemsByLeaf.entrySet()) {
            String leafCategory = entry.getKey();
            String subcategory = getSubcategoryAtDepth(leafCategory, categoryPath, depth + 1);

            bySubcategory.computeIfAbsent(subcategory, k -> new LinkedHashMap<>())
                         .put(leafCategory, entry.getValue());
        }

        // If we can't split further (only one subcategory or at leaf level), return as single group
        if (bySubcategory.size() <= 1) {
            // Can't split further - this becomes a multi-chest group for a single item type
            CategoryGroup group = new CategoryGroup(categoryPath, allItems, totalItems, chestsNeeded);
            return List.of(group);
        }

        // Sort subcategories by config order
        List<String> sortedSubs = new ArrayList<>(bySubcategory.keySet());
        sortedSubs.sort(config::compareCategoriesByOrder);

        // Recursively split each subcategory
        List<CategoryGroup> result = new ArrayList<>();
        for (String sub : sortedSubs) {
            Map<String, List<ItemStack>> subItems = bySubcategory.get(sub);
            result.addAll(splitCategory(sub, subItems, depth + 1));
        }

        return result;
    }

    /**
     * Get the subcategory at a specific depth relative to parent
     * e.g., getSubcategoryAtDepth("wood/oak/planks", "wood", 2) returns "wood/oak"
     */
    private String getSubcategoryAtDepth(String fullPath, String parentPath, int targetDepth) {
        String[] parts = fullPath.split("/");
        if (targetDepth >= parts.length) {
            return fullPath;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targetDepth && i < parts.length; i++) {
            if (i > 0) sb.append("/");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * Merge small groups when we have more groups than chests
     * Merge by parent category (go up the hierarchy)
     */
    private List<CategoryGroup> mergeSmallGroups(List<CategoryGroup> groups, int availableChests) {
        // Sort by item count ascending (smallest first to merge)
        List<CategoryGroup> sortedGroups = new ArrayList<>(groups);
        sortedGroups.sort(Comparator.comparingInt(g -> g.itemCount));

        Map<String, CategoryGroup> mergedGroups = new LinkedHashMap<>();

        for (CategoryGroup group : sortedGroups) {
            // Determine merge key based on how many groups we have
            String mergeKey;
            if (mergedGroups.size() < availableChests - 3) {
                // Still have room, keep more specific grouping
                mergeKey = group.categoryPath;
            } else {
                // Running low, merge to parent or root
                String parent = hierarchy.getParentCategory(group.categoryPath);
                mergeKey = parent != null ? parent : hierarchy.getRootCategory(group.categoryPath);
                if (mergeKey == null) mergeKey = "misc";
            }

            CategoryGroup existing = mergedGroups.get(mergeKey);
            if (existing != null) {
                // Merge into existing group
                existing.items.addAll(group.items);
                existing.itemCount += group.itemCount;
                existing.chestsNeeded = (double) existing.itemCount / CHEST_CAPACITY;
            } else {
                // Create new group with merge key
                CategoryGroup newGroup = new CategoryGroup(mergeKey, new ArrayList<>(group.items), group.itemCount, group.chestsNeeded);
                mergedGroups.put(mergeKey, newGroup);
            }
        }

        // Sort result by config order
        List<CategoryGroup> result = new ArrayList<>(mergedGroups.values());
        result.sort((a, b) -> config.compareCategoriesByOrder(a.categoryPath, b.categoryPath));

        return result;
    }

    /**
     * Distribute items from groups to chests
     */
    private List<ItemStack> distributeItems(List<CategoryGroup> groups, List<NetworkChest> chests, ChestNetwork network) {
        List<ItemStack> overflow = new ArrayList<>();
        int chestIndex = 0;

        plugin.getLogger().info("[Hoardi] Distributing items to " + chests.size() + " chests...");

        for (CategoryGroup group : groups) {
            if (chestIndex >= chests.size()) {
                // No more chests, all remaining items are overflow
                overflow.addAll(group.items);
                continue;
            }

            plugin.getLogger().info("[Hoardi] Placing '" + group.displayName + "' (" + group.itemCount + " items)...");

            int startChestIndex = chestIndex;

            // Place all items from this group
            for (ItemStack item : group.items) {
                ItemStack remaining = item.clone();

                while (remaining != null && remaining.getAmount() > 0) {
                    if (chestIndex >= chests.size()) {
                        overflow.add(remaining.clone());
                        break;
                    }

                    NetworkChest chest = chests.get(chestIndex);
                    Inventory inv = chest.getInventory();
                    if (inv == null) {
                        chestIndex++;
                        continue;
                    }

                    HashMap<Integer, ItemStack> leftover = inv.addItem(remaining);

                    if (leftover.isEmpty()) {
                        remaining = null;
                    } else {
                        remaining = leftover.values().iterator().next();
                        chestIndex++;
                    }
                }
            }

            // Assign category to all chests used by this group
            for (int i = startChestIndex; i <= chestIndex && i < chests.size(); i++) {
                NetworkChest chest = chests.get(i);
                network.assignCategory(chest.getLocation(), group.categoryPath);
                plugin.getLogger().info("[Hoardi]   Chest #" + i + " -> " + group.displayName);
            }

            // Move to next chest for next category (unless we're out of space)
            if (chestIndex < chests.size() && overflow.isEmpty()) {
                chestIndex++;
            }
        }

        return overflow;
    }

    /**
     * Handle overflow items by cramming into any available space
     */
    private void handleOverflow(List<ItemStack> overflow, List<NetworkChest> chests, ChestNetwork network) {
        plugin.getLogger().warning("[Hoardi] Cramming " + overflow.size() + " overflow stacks into available space...");

        for (ItemStack item : overflow) {
            ItemStack remaining = item.clone();

            for (NetworkChest chest : chests) {
                if (remaining == null || remaining.getAmount() <= 0) break;

                Inventory inv = chest.getInventory();
                if (inv == null) continue;

                HashMap<Integer, ItemStack> leftover = inv.addItem(remaining);
                if (leftover.isEmpty()) {
                    remaining = null;
                } else {
                    remaining = leftover.values().iterator().next();
                }
            }

            // If still can't place, drop at root
            if (remaining != null && remaining.getAmount() > 0) {
                plugin.getLogger().severe("[Hoardi] CRITICAL: Dropping " + remaining.getAmount() + "x " + remaining.getType() + " at root!");
                network.getRoot().getWorld().dropItemNaturally(network.getRoot(), remaining);
            }
        }
    }

    /**
     * Collect all items from network grouped by leaf category
     */
    private Map<String, List<ItemStack>> collectAllItems(ChestNetwork network) {
        Map<String, List<ItemStack>> itemsByCategory = new HashMap<>();

        for (NetworkChest chest : network.getChestsInOrder()) {
            Inventory inv = chest.getInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack item = inv.getItem(i);
                if (item == null) continue;

                String category = hierarchy.getCategory(item.getType());
                itemsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(item.clone());

                inv.setItem(i, null);
            }
        }

        // Merge stacks and sort by material
        for (Map.Entry<String, List<ItemStack>> entry : itemsByCategory.entrySet()) {
            entry.setValue(mergeAndSortStacks(entry.getValue()));
        }

        return itemsByCategory;
    }

    /**
     * Merge similar item stacks and sort by material name
     */
    private List<ItemStack> mergeAndSortStacks(List<ItemStack> items) {
        Map<Material, Integer> totals = new HashMap<>();
        Map<Material, ItemStack> samples = new HashMap<>();

        for (ItemStack item : items) {
            totals.merge(item.getType(), item.getAmount(), Integer::sum);
            if (!samples.containsKey(item.getType())) {
                samples.put(item.getType(), item);
            }
        }

        List<ItemStack> merged = new ArrayList<>();
        List<Material> sortedMaterials = new ArrayList<>(totals.keySet());
        sortedMaterials.sort(Comparator.comparing(Material::name));

        for (Material material : sortedMaterials) {
            int total = totals.get(material);
            ItemStack sample = samples.get(material);
            int maxStack = sample.getMaxStackSize();

            while (total > 0) {
                ItemStack stack = sample.clone();
                int amount = Math.min(total, maxStack);
                stack.setAmount(amount);
                merged.add(stack);
                total -= amount;
            }
        }

        return merged;
    }

    /**
     * Log collected items for debugging
     */
    private void logCollectedItems(Map<String, List<ItemStack>> itemsByCategory) {
        plugin.getLogger().info("[Hoardi] Collected items by category:");

        List<String> sortedCategories = new ArrayList<>(itemsByCategory.keySet());
        sortedCategories.sort(config::compareCategoriesByOrder);

        for (String cat : sortedCategories) {
            List<ItemStack> items = itemsByCategory.get(cat);
            int totalAmount = items.stream().mapToInt(ItemStack::getAmount).sum();

            Set<Material> seen = new LinkedHashSet<>();
            for (ItemStack item : items) {
                seen.add(item.getType());
            }

            String itemTypes = seen.size() <= 5
                ? String.join(", ", seen.stream().map(Material::name).toList())
                : seen.stream().limit(5).map(Material::name).toList() + "...(+" + (seen.size() - 5) + " more)";

            plugin.getLogger().info("[Hoardi]   " + cat + ": " + totalAmount + " items [" + itemTypes + "]");
        }
    }

    /**
     * Manually trigger reorganization for a network
     */
    public static void trigger(HoarderPlugin plugin, ChestNetwork network) {
        FullReorganizeTask task = new FullReorganizeTask(plugin);
        task.reorganizeNetwork(network);
    }

    /**
     * Helper class to hold a category group for distribution
     */
    private static class CategoryGroup {
        final String categoryPath;
        final String displayName;
        final List<ItemStack> items;
        int itemCount;
        double chestsNeeded;

        CategoryGroup(String categoryPath, List<ItemStack> items, int itemCount, double chestsNeeded) {
            this.categoryPath = categoryPath;
            this.displayName = categoryPath; // Could use hierarchy.getDisplayName() if needed
            this.items = items;
            this.itemCount = itemCount;
            this.chestsNeeded = chestsNeeded;
        }
    }
}
