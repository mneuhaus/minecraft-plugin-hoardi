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
import java.util.Map.Entry;

/**
 * Full network reorganization task
 * Redistributes all items optimally across all chests
 * Runs periodically or on manual trigger
 */
public class FullReorganizeTask extends BukkitRunnable {

    private final HoarderPlugin plugin;
    private final HoarderConfig config;
    private final ItemHierarchy hierarchy;

    public FullReorganizeTask(HoarderPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getHoarderConfig();
        this.hierarchy = plugin.getItemHierarchy();
    }

    @Override
    public void run() {
        // Reorganize all networks
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

        // Debug: Show network layout
        logNetworkLayout(network);

        // Step 1: Collect ALL items from all chests
        Map<String, List<ItemStack>> itemsByCategory = collectAllItems(network);

        // Debug: Show all items collected by category
        plugin.getLogger().info("[Hoardi] Collected items by category:");
        for (Map.Entry<String, List<ItemStack>> entry : itemsByCategory.entrySet()) {
            String cat = entry.getKey();
            List<ItemStack> items = entry.getValue();
            int totalAmount = items.stream().mapToInt(ItemStack::getAmount).sum();
            StringBuilder itemTypes = new StringBuilder();
            Set<Material> seen = new HashSet<>();
            for (ItemStack item : items) {
                if (!seen.contains(item.getType())) {
                    if (itemTypes.length() > 0) itemTypes.append(", ");
                    itemTypes.append(item.getType().name());
                    seen.add(item.getType());
                }
            }
            plugin.getLogger().info("[Hoardi]   " + cat + " (" + totalAmount + " items): " + itemTypes);
        }

        // Step 2: Clear all category assignments
        network.clearCategoryAssignments();

        // Step 3: Determine grouping depth based on fill percentage
        // Calculate how many chests each root category would need
        int chestCapacity = 27 * 64; // Single chest slots * max stack (approximation)
        double splitThreshold = config.getSplitThreshold() / 100.0;

        Map<String, List<String>> categoriesByGrouping = new LinkedHashMap<>();
        List<String> allCategories = new ArrayList<>(itemsByCategory.keySet());

        // Sort all categories alphabetically first
        allCategories.sort((a, b) -> {
            if (a.equals("misc") || a.startsWith("misc/")) return 1;
            if (b.equals("misc") || b.startsWith("misc/")) return -1;
            return a.compareTo(b);
        });

        // First pass: group by root and calculate totals
        Map<String, Integer> itemCountByRoot = new HashMap<>();
        Map<String, List<String>> categoriesByRoot = new LinkedHashMap<>();

        for (String category : allCategories) {
            String root = hierarchy.getRootCategory(category);
            if (root == null) root = "misc";
            categoriesByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(category);

            List<ItemStack> items = itemsByCategory.get(category);
            int itemCount = items != null ? items.stream().mapToInt(ItemStack::getAmount).sum() : 0;
            itemCountByRoot.merge(root, itemCount, Integer::sum);
        }

        // Second pass: Each leaf category gets its own group by default
        // We'll merge small categories later if we don't have enough chests
        Map<String, Integer> itemCountByCategory = new HashMap<>();
        for (String category : allCategories) {
            List<ItemStack> items = itemsByCategory.get(category);
            int itemCount = items != null ? items.stream().mapToInt(ItemStack::getAmount).sum() : 0;
            itemCountByCategory.put(category, itemCount);
            // Each category is its own group initially
            categoriesByGrouping.put(category, new ArrayList<>(List.of(category)));
        }

        plugin.getLogger().info("[Hoardi] Initial: " + categoriesByGrouping.size() + " separate categories");

        // Check if we have enough chests for all categories
        int availableChests = network.size();
        int categoriesNeeded = categoriesByGrouping.size();

        if (categoriesNeeded > availableChests) {
            // Not enough chests - need to merge small categories
            plugin.getLogger().info("[Hoardi] Only " + availableChests + " chests for " + categoriesNeeded + " categories, merging small ones...");

            // Clear and rebuild with merging
            categoriesByGrouping.clear();

            // Sort categories by item count (smallest first for merging)
            List<String> sortedBySize = new ArrayList<>(allCategories);
            sortedBySize.sort(Comparator.comparingInt(c -> itemCountByCategory.getOrDefault(c, 0)));

            // Try to merge small categories with their siblings (same root)
            for (String category : sortedBySize) {
                int catItems = itemCountByCategory.getOrDefault(category, 0);
                double catFill = (double) catItems / chestCapacity;

                if (catFill > splitThreshold) {
                    // Large category - gets its own group
                    categoriesByGrouping.put(category, new ArrayList<>(List.of(category)));
                } else {
                    // Small category - group by root (level 1) or second level
                    String groupKey;
                    if (categoriesByGrouping.size() < availableChests - 5) {
                        // Still have room - use more specific grouping
                        groupKey = getGroupingKey(category, 2);
                    } else {
                        // Running low on chests - use root grouping
                        String root = hierarchy.getRootCategory(category);
                        groupKey = root != null ? root : "misc";
                    }
                    categoriesByGrouping.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(category);
                }
            }

            plugin.getLogger().info("[Hoardi] After merging: " + categoriesByGrouping.size() + " category groups");
        }

        // Sort grouping keys
        List<String> sortedGroupKeys = new ArrayList<>(categoriesByGrouping.keySet());
        sortedGroupKeys.sort((a, b) -> {
            if (a.equals("misc") || a.startsWith("misc/")) return 1;
            if (b.equals("misc") || b.startsWith("misc/")) return -1;
            return a.compareTo(b);
        });

        plugin.getLogger().info("[Hoardi] Final grouping into " + categoriesByGrouping.size() + " category groups:");
        for (String groupKey : sortedGroupKeys) {
            plugin.getLogger().info("[Hoardi]   " + groupKey + ": " + categoriesByGrouping.get(groupKey));
        }

        // Step 4: Distribute items to chests by grouping
        List<NetworkChest> chestsInOrder = network.getChestsInOrder();
        int chestIndex = 0;
        List<ItemStack> overflowItems = new ArrayList<>(); // Track items that couldn't be placed

        plugin.getLogger().info("[Hoardi] Distributing items to " + chestsInOrder.size() + " chests...");

        for (String groupKey : sortedGroupKeys) {
            List<String> subCategories = categoriesByGrouping.get(groupKey);

            plugin.getLogger().info("[Hoardi] Processing group '" + groupKey + "' with sub-categories: " + subCategories);

            int startChestIndex = chestIndex;

            // Place all items from all sub-categories of this group
            for (String subCategory : subCategories) {
                List<ItemStack> items = itemsByCategory.get(subCategory);
                if (items == null || items.isEmpty()) continue;

                for (ItemStack item : items) {
                    // Keep trying to place item until it's fully placed or we run out of chests
                    ItemStack remaining = item.clone();

                    while (remaining != null && remaining.getAmount() > 0) {
                        if (chestIndex >= chestsInOrder.size()) {
                            // No more chests - save for cramming later
                            overflowItems.add(remaining.clone());
                            break;
                        }

                        NetworkChest chest = chestsInOrder.get(chestIndex);
                        Inventory inv = chest.getInventory();
                        if (inv == null) {
                            chestIndex++;
                            continue;
                        }

                        // Try to add item
                        HashMap<Integer, ItemStack> overflow = inv.addItem(remaining);

                        if (overflow.isEmpty()) {
                            // All placed successfully
                            remaining = null;
                        } else {
                            // Chest is full, move to next and try with remaining items
                            remaining = overflow.values().iterator().next();
                            chestIndex++;
                        }
                    }
                }
            }

            // Assign category group to all chests used
            for (int i = startChestIndex; i <= chestIndex && i < chestsInOrder.size(); i++) {
                NetworkChest assignedChest = chestsInOrder.get(i);
                network.assignCategory(assignedChest.getLocation(), groupKey);
                plugin.getLogger().info("[Hoardi]   Assigned chest #" + i + " at " + formatLocation(assignedChest.getLocation()) + " to category '" + groupKey + "'");
            }

            // ALWAYS move to next chest for a new category group (unless we're out of space)
            if (chestIndex < chestsInOrder.size() && overflowItems.isEmpty()) {
                NetworkChest currentChest = chestsInOrder.get(chestIndex);
                double fillPct = currentChest.getFillPercentage();
                plugin.getLogger().info("[Hoardi]   Chest #" + chestIndex + " is " + String.format("%.1f%%", fillPct * 100) + " full, moving to next for new category");
                chestIndex++;
            }
        }

        // Step 5: Cram overflow items into any chest with space (ignore category separation)
        if (!overflowItems.isEmpty()) {
            plugin.getLogger().warning("[Hoardi] Not enough space for clean separation! Cramming " + overflowItems.size() + " item stacks into available space...");

            for (ItemStack item : overflowItems) {
                ItemStack remaining = item.clone();

                // Try ALL chests from the beginning
                for (NetworkChest chest : chestsInOrder) {
                    if (remaining == null || remaining.getAmount() <= 0) break;

                    Inventory inv = chest.getInventory();
                    if (inv == null) continue;

                    HashMap<Integer, ItemStack> overflow = inv.addItem(remaining);
                    if (overflow.isEmpty()) {
                        remaining = null;
                    } else {
                        remaining = overflow.values().iterator().next();
                    }
                }

                // If STILL can't place, this is a serious problem - drop at root
                if (remaining != null && remaining.getAmount() > 0) {
                    plugin.getLogger().severe("[Hoardi] CRITICAL: Could not fit " + remaining.getAmount() + "x " + remaining.getType() + " - dropping at root!");
                    network.getRoot().getWorld().dropItemNaturally(network.getRoot(), remaining);
                }
            }
        }

        // Save network state
        plugin.getNetworkManager().save();

        plugin.getLogger().info("[Hoardi] Full reorganize complete! " + categoriesByGrouping.size() + " category groups distributed.");
    }

    /**
     * Get grouping key for a category at a specific depth
     * e.g., "decoration/glass/stained" at depth 2 returns "decoration/glass"
     */
    private String getGroupingKey(String category, int depth) {
        String[] parts = category.split("/");
        if (parts.length <= depth) {
            return category; // Return full path if not deep enough
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < depth && i < parts.length; i++) {
            if (i > 0) key.append("/");
            key.append(parts[i]);
        }
        return key.toString();
    }

    private String formatLocation(org.bukkit.Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Log the network layout showing all chests, their positions, and contents summary
     */
    private void logNetworkLayout(ChestNetwork network) {
        plugin.getLogger().info("[Hoardi] --- Network Layout ---");
        plugin.getLogger().info("[Hoardi] Root: " + formatLocation(network.getRoot()));

        List<NetworkChest> chests = network.getChestsInOrder();

        // Find bounding box
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (NetworkChest chest : chests) {
            org.bukkit.Location loc = chest.getLocation();
            minX = Math.min(minX, loc.getBlockX());
            maxX = Math.max(maxX, loc.getBlockX());
            minY = Math.min(minY, loc.getBlockY());
            maxY = Math.max(maxY, loc.getBlockY());
            minZ = Math.min(minZ, loc.getBlockZ());
            maxZ = Math.max(maxZ, loc.getBlockZ());
        }

        plugin.getLogger().info("[Hoardi] Bounding box: X[" + minX + " to " + maxX + "] Y[" + minY + " to " + maxY + "] Z[" + minZ + " to " + maxZ + "]");

        // Log each chest with its position number and contents
        plugin.getLogger().info("[Hoardi] Chests in order (by spiral position):");
        for (int i = 0; i < chests.size(); i++) {
            NetworkChest chest = chests.get(i);
            org.bukkit.Location loc = chest.getLocation();
            org.bukkit.inventory.Inventory inv = chest.getInventory();

            StringBuilder contents = new StringBuilder();
            if (inv != null) {
                Map<Material, Integer> itemCounts = new HashMap<>();
                for (org.bukkit.inventory.ItemStack item : inv.getContents()) {
                    if (item != null) {
                        itemCounts.merge(item.getType(), item.getAmount(), Integer::sum);
                    }
                }

                if (itemCounts.isEmpty()) {
                    contents.append("(empty)");
                } else {
                    int shown = 0;
                    for (Map.Entry<Material, Integer> entry : itemCounts.entrySet()) {
                        if (shown > 0) contents.append(", ");
                        if (shown >= 5) {
                            contents.append("...(+" + (itemCounts.size() - 5) + " more)");
                            break;
                        }
                        contents.append(entry.getKey().name()).append("x").append(entry.getValue());
                        shown++;
                    }
                }
            } else {
                contents.append("(no inventory)");
            }

            String assignedCat = chest.getAssignedCategory();
            String catInfo = assignedCat != null ? " [" + assignedCat + "]" : "";

            plugin.getLogger().info("[Hoardi]   #" + i + " pos=" + chest.getPosition() +
                " " + formatLocation(loc) + catInfo +
                " -> " + contents);
        }

        // Also log registered shelves
        plugin.getLogger().info("[Hoardi] --- Registered Shelves ---");
        var shelfManager = plugin.getShelfManager();
        for (org.bukkit.Location shelfLoc : shelfManager.getTrackedShelves()) {
            org.bukkit.Location chestLoc = shelfManager.getChestLocation(shelfLoc);
            plugin.getLogger().info("[Hoardi]   Shelf " + formatLocation(shelfLoc) + " -> Chest " + formatLocation(chestLoc));
        }

        plugin.getLogger().info("[Hoardi] --- End Layout ---");
    }

    /**
     * Collect all items from network grouped by category
     */
    private Map<String, List<ItemStack>> collectAllItems(ChestNetwork network) {
        Map<String, List<ItemStack>> itemsByCategory = new HashMap<>();

        for (NetworkChest chest : network.getChestsInOrder()) {
            Inventory inv = chest.getInventory();
            if (inv == null) continue;

            // Collect items
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack item = inv.getItem(i);
                if (item == null) continue;

                String category = hierarchy.getCategory(item.getType());
                itemsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(item.clone());

                // Remove from chest
                inv.setItem(i, null);
            }
        }

        // Merge stacks where possible
        for (Map.Entry<String, List<ItemStack>> entry : itemsByCategory.entrySet()) {
            entry.setValue(mergeStacks(entry.getValue()));
        }

        return itemsByCategory;
    }

    /**
     * Merge similar item stacks and sort by material name
     */
    private List<ItemStack> mergeStacks(List<ItemStack> items) {
        Map<Material, Integer> totals = new HashMap<>();
        Map<Material, ItemStack> samples = new HashMap<>();

        for (ItemStack item : items) {
            totals.merge(item.getType(), item.getAmount(), Integer::sum);
            if (!samples.containsKey(item.getType())) {
                samples.put(item.getType(), item);
            }
        }

        List<ItemStack> merged = new ArrayList<>();

        // Sort materials alphabetically by name
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
     * Manually trigger reorganization for a network
     */
    public static void trigger(HoarderPlugin plugin, ChestNetwork network) {
        FullReorganizeTask task = new FullReorganizeTask(plugin);
        task.reorganizeNetwork(network);
    }
}
