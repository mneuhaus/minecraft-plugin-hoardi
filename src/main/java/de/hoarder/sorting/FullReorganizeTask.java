package de.hoarder.sorting;

import de.hoarder.HoarderPlugin;
import de.hoarder.config.HoarderConfig;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkChest;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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

    // Default capacity for single chest (27 slots * 64 items)
    private static final int SINGLE_CHEST_CAPACITY = 27 * 64;

    // Will be calculated per-network based on actual chest sizes
    private int averageChestCapacity = SINGLE_CHEST_CAPACITY;

    // Every Nth cycle all networks are reorganized even if not marked dirty,
    // as a safety net for changes we cannot observe (default: 6 -> hourly at
    // the default 10 minute interval).
    private static final int FORCE_EVERY_N_CYCLES = 6;
    private static final long OVERFLOW_WARN_COOLDOWN_MS = 30L * 60L * 1000L;
    private int cycleCounter = 0;

    // Set by buildDistributionPlan when categories had to be merged (per run)
    private boolean lastPlanNeededMerge = false;

    public FullReorganizeTask(HoarderPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getHoarderConfig();
        this.hierarchy = plugin.getItemHierarchy();
    }

    /** Verbose logging only with settings.debug: true */
    private void debug(String message) {
        if (config.isDebug()) {
            plugin.getLogger().info(message);
        }
    }

    /**
     * Calculate the average chest capacity for this network.
     * This accounts for mixed single (27 slot) and double (54 slot) chests.
     */
    private void calculateAverageCapacity(ChestNetwork network) {
        int totalSlots = 0;
        int chestCount = 0;

        for (NetworkChest chest : network.getChestsInOrder()) {
            Inventory inv = chest.getInventory();
            if (inv != null) {
                totalSlots += inv.getSize();
                chestCount++;
            }
        }

        if (chestCount > 0) {
            // Average slots per chest * 64 items per slot
            averageChestCapacity = (totalSlots / chestCount) * 64;
            debug("Average chest capacity: " + (totalSlots / chestCount) + " slots (" + averageChestCapacity + " items)");
        } else {
            averageChestCapacity = SINGLE_CHEST_CAPACITY;
        }
    }

    @Override
    public void run() {
        cycleCounter++;
        boolean forceAll = (cycleCounter % FORCE_EVERY_N_CYCLES) == 0;

        for (ChestNetwork network : plugin.getNetworkManager().getAllNetworks()) {
            if (network.isEmpty()) continue;
            if (!forceAll && !network.isDirty()) {
                continue; // nothing changed since the last run
            }
            if (!network.isLoaded()) {
                continue; // nobody around: sorting would load its chunks; it stays dirty for later
            }
            reorganizeNetwork(network);
        }
    }

    /**
     * Reorganize a single network
     */
    public void reorganizeNetwork(ChestNetwork network) {
        long startedAt = System.currentTimeMillis();
        debug("========== FULL REORGANIZE ==========");
        debug("Network has " + network.size() + " chests");

        // Calculate average chest capacity for this network (mix of single/double chests)
        calculateAverageCapacity(network);

        // Step 1: Collect ALL items from all chests (read-only), grouped by leaf category
        Map<String, List<ItemStack>> itemsByLeafCategory = collectAllItems(network);

        if (itemsByLeafCategory.isEmpty()) {
            debug("No items to sort.");
            network.clearDirty();
            return;
        }

        // Step 2: Crash safety - persist everything BEFORE any chest is emptied.
        // If the journal cannot be written we do not touch the chests at all.
        File journal = ReorganizeJournal.write(plugin, network, itemsByLeafCategory);
        if (journal == null) {
            return;
        }

        // Step 3: Now it is safe to empty the chests.
        clearAllChests(network);

        // Debug: Show collected items
        logCollectedItems(itemsByLeafCategory);

        // Step 4: Clear all category assignments
        network.clearCategoryAssignments();

        // Step 5: Build distribution plan using hierarchical splitting
        int availableChests = network.size();
        lastPlanNeededMerge = false;
        List<CategoryGroup> distributionPlan = buildDistributionPlan(itemsByLeafCategory, availableChests);
        if (lastPlanNeededMerge) {
            notifyOverfull(network, "Your storage network is running out of chests - "
                + "categories are being mixed. Add more chests with shelves!");
        }

        // Debug: Show distribution plan
        debug("Distribution plan (" + distributionPlan.size() + " groups for " + availableChests + " chests):");
        for (CategoryGroup group : distributionPlan) {
            debug("  " + group.displayName + ": " + group.itemCount + " items (~" +
                String.format("%.1f", group.chestsNeeded) + " chests)");
        }

        // Step 6: Distribute items to chests
        List<NetworkChest> chestsInOrder = network.getChestsInOrder();
        List<ItemStack> overflowItems = distributeItems(distributionPlan, chestsInOrder, network);

        // Step 7: Handle overflow by cramming
        if (!overflowItems.isEmpty()) {
            handleOverflow(overflowItems, chestsInOrder, network);
        }

        // All items are back in chests - the journal has served its purpose.
        ReorganizeJournal.clear(plugin, journal);
        network.clearDirty();

        // Save network state
        plugin.getNetworkManager().save();

        // Log final chest contents (debug only)
        if (config.isDebug()) {
            logChestContents(chestsInOrder);
            exportNetworkInventory(network, chestsInOrder);
        }

        int totalItems = 0;
        for (List<ItemStack> list : itemsByLeafCategory.values()) {
            for (ItemStack item : list) totalItems += item.getAmount();
        }
        Location root = network.getRoot();
        debug("Reorganized " + network.getWorld().getName()
            + " (" + root.getBlockX() + ", " + root.getBlockY() + ", " + root.getBlockZ() + "): "
            + totalItems + " items, " + network.size() + " chests, "
            + distributionPlan.size() + " categories in "
            + (System.currentTimeMillis() - startedAt) + " ms");
    }

    /**
     * Warn nearby players (throttled) that their network is over capacity.
     * The log alone is invisible to survival players.
     */
    private void notifyOverfull(ChestNetwork network, String message) {
        if (!network.tryArmOverflowWarn(OVERFLOW_WARN_COOLDOWN_MS)) {
            return;
        }
        Location root = network.getRoot();
        for (org.bukkit.entity.Player player : network.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(root) <= 64 * 64) {
                player.sendMessage("§e[Hoardi] §c" + message);
            }
        }
    }

    /**
     * Log contents of each chest after sorting
     */
    private void logChestContents(List<NetworkChest> chests) {
        debug("=== CHEST CONTENTS AFTER SORT ===");

        int chestNum = 0;
        for (NetworkChest chest : chests) {
            Inventory inv = chest.getInventory();
            if (inv == null) {
                chestNum++;
                continue;
            }

            // Count items and types
            Map<Material, Integer> contents = new LinkedHashMap<>();
            int usedSlots = 0;
            int totalItems = 0;

            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    contents.merge(item.getType(), item.getAmount(), Integer::sum);
                    totalItems += item.getAmount();
                    usedSlots++;
                }
            }

            int capacity = inv.getSize() * 64;
            double fillPercent = (double) totalItems / capacity * 100;

            if (contents.isEmpty()) {
                debug("Chest #" + chestNum + ": EMPTY");
            } else {
                // Build contents string
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (Map.Entry<Material, Integer> entry : contents.entrySet()) {
                    if (shown > 0) sb.append(", ");
                    sb.append(entry.getValue()).append("x ").append(entry.getKey().name());
                    shown++;
                    if (shown >= 5 && contents.size() > 5) {
                        sb.append(" ...(+").append(contents.size() - 5).append(" more types)");
                        break;
                    }
                }

                debug("Chest #" + chestNum + ": " +
                    String.format("%.1f%%", fillPercent) + " full (" + usedSlots + "/" + inv.getSize() + " slots, " +
                    totalItems + " items, " + contents.size() + " types) [" + sb + "]");
            }
            chestNum++;
        }
    }

    /**
     * Export network inventory to JSON file
     */
    private void exportNetworkInventory(ChestNetwork network, List<NetworkChest> chests) {
        Map<String, Object> export = new LinkedHashMap<>();

        // Network summary
        export.put("shelfMaterial", network.getShelfMaterial().name());
        export.put("chestCount", chests.size());
        export.put("root", formatLocation(network.getRoot()));

        // Per-chest details
        List<Map<String, Object>> chestList = new ArrayList<>();
        int totalItems = 0;
        int totalTypes = 0;
        Set<Material> allTypes = new HashSet<>();

        int chestNum = 0;
        for (NetworkChest chest : chests) {
            Map<String, Object> chestData = new LinkedHashMap<>();
            chestData.put("index", chestNum);
            chestData.put("location", formatLocation(chest.getLocation()));

            Inventory inv = chest.getInventory();
            if (inv == null) {
                chestData.put("status", "unavailable");
                chestList.add(chestData);
                chestNum++;
                continue;
            }

            // Count contents
            Map<String, Integer> contents = new LinkedHashMap<>();
            int slots = inv.getSize();
            int usedSlots = 0;
            int itemCount = 0;

            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    String name = item.getType().name();
                    contents.merge(name, item.getAmount(), Integer::sum);
                    itemCount += item.getAmount();
                    usedSlots++;
                    allTypes.add(item.getType());
                }
            }

            totalItems += itemCount;

            int capacity = slots * 64;
            double fillPercent = (double) itemCount / capacity * 100;

            chestData.put("slots", slots);
            chestData.put("usedSlots", usedSlots);
            chestData.put("itemCount", itemCount);
            chestData.put("fillPercent", Math.round(fillPercent * 10) / 10.0);
            chestData.put("typeCount", contents.size());
            chestData.put("category", chest.getAssignedCategory());
            chestData.put("contents", contents);

            chestList.add(chestData);
            chestNum++;
        }

        totalTypes = allTypes.size();

        export.put("totalItems", totalItems);
        export.put("totalTypes", totalTypes);
        export.put("chests", chestList);

        // Write to file
        File outputFile = new File(plugin.getDataFolder(), "network-inventory.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try (FileWriter writer = new FileWriter(outputFile)) {
            gson.toJson(export, writer);
            debug("Network inventory exported to " + outputFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to export network inventory: " + e.getMessage());
        }
    }

    private String formatLocation(org.bukkit.Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
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
            plugin.getLogger().warning("Not enough chests: " + groups.size() + " categories for "
                + availableChests + " chests - merging categories. Add more chests to prevent mixing!");
            lastPlanNeededMerge = true;
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

        double chestsNeeded = (double) totalItems / averageChestCapacity;

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
                existing.chestsNeeded = (double) existing.itemCount / averageChestCapacity;
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

        debug("Distributing items to " + chests.size() + " chests...");

        // Separate misc from other groups - misc will be placed at the end
        List<CategoryGroup> regularGroups = new ArrayList<>();
        CategoryGroup miscGroup = null;

        for (CategoryGroup group : groups) {
            if (group.categoryPath.equals("misc") || group.categoryPath.startsWith("misc/")) {
                // Merge all misc groups into one
                if (miscGroup == null) {
                    miscGroup = new CategoryGroup("misc", new ArrayList<>(group.items), group.itemCount, group.chestsNeeded);
                } else {
                    miscGroup.items.addAll(group.items);
                    miscGroup.itemCount += group.itemCount;
                    miscGroup.chestsNeeded += group.chestsNeeded;
                }
            } else {
                regularGroups.add(group);
            }
        }

        // Place all regular categories first (front to back)
        for (CategoryGroup group : regularGroups) {
            if (chestIndex >= chests.size()) {
                // No more chests, all remaining items are overflow
                overflow.addAll(group.items);
                continue;
            }

            debug("Placing '" + group.displayName + "' (" + group.itemCount + " items)...");

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
                debug("  Chest #" + i + " -> " + group.displayName);
            }

            // Move to next chest for next category (unless we're out of space)
            if (chestIndex < chests.size() && overflow.isEmpty()) {
                chestIndex++;
            }
        }

        // Now place misc at the END of used chests (working backwards from last chest)
        if (miscGroup != null && !miscGroup.items.isEmpty()) {
            // Calculate how many chests misc needs
            int miscChestsNeeded = (int) Math.ceil(miscGroup.chestsNeeded);
            if (miscChestsNeeded < 1) miscChestsNeeded = 1;

            // Find the last chest index that was used by regular categories
            int lastUsedIndex = chestIndex - 1;
            if (lastUsedIndex < 0) lastUsedIndex = 0;

            // Start placing misc from the end of the used area
            // If we have empty chests after the used area, use those from the end
            int totalChests = chests.size();
            int miscStartIndex;

            if (chestIndex < totalChests) {
                // There are unused chests - put misc at the END of all chests
                miscStartIndex = totalChests - miscChestsNeeded;
                if (miscStartIndex < chestIndex) miscStartIndex = chestIndex;
            } else {
                // All chests used - misc goes into overflow
                miscStartIndex = totalChests;
            }

            debug("Placing 'misc' (" + miscGroup.itemCount + " items) at end (chests " + miscStartIndex + "-" + (totalChests - 1) + ")...");

            int miscChestIndex = miscStartIndex;
            for (ItemStack item : miscGroup.items) {
                ItemStack remaining = item.clone();

                while (remaining != null && remaining.getAmount() > 0) {
                    if (miscChestIndex >= totalChests) {
                        overflow.add(remaining.clone());
                        break;
                    }

                    NetworkChest chest = chests.get(miscChestIndex);
                    Inventory inv = chest.getInventory();
                    if (inv == null) {
                        miscChestIndex++;
                        continue;
                    }

                    HashMap<Integer, ItemStack> leftover = inv.addItem(remaining);

                    if (leftover.isEmpty()) {
                        remaining = null;
                    } else {
                        remaining = leftover.values().iterator().next();
                        miscChestIndex++;
                    }
                }
            }

            // Assign misc category to all chests used
            for (int i = miscStartIndex; i <= miscChestIndex && i < totalChests; i++) {
                NetworkChest chest = chests.get(i);
                network.assignCategory(chest.getLocation(), "misc");
                debug("  Chest #" + i + " -> misc");
            }
        }

        return overflow;
    }

    /**
     * Handle overflow items by cramming into any available space
     */
    private void handleOverflow(List<ItemStack> overflow, List<NetworkChest> chests, ChestNetwork network) {
        plugin.getLogger().warning("Cramming " + overflow.size() + " overflow stacks into available space...");
        notifyOverfull(network, "Your storage network is overfull ("
            + overflow.size() + " stacks had no proper place). Add more chests with shelves!");

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

        // Read-only pass: chests are cleared separately AFTER the journal has
        // been written (see reorganizeNetwork), so a crash can never eat items.
        // Dedupe by inventory location: both halves of a double chest return
        // the same 54-slot inventory and must only be counted once.
        Set<Location> seenInventories = new HashSet<>();
        for (NetworkChest chest : network.getChestsInOrder()) {
            Inventory inv = chest.getInventory();
            if (inv == null) continue;
            Location invKey = inv.getLocation();
            if (invKey != null && !seenInventories.add(invKey)) continue;

            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack item = inv.getItem(i);
                if (item == null) continue;

                String category = hierarchy.getCategory(item.getType());
                itemsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(item.clone());
            }
        }

        // Merge stacks and sort by material
        for (Map.Entry<String, List<ItemStack>> entry : itemsByCategory.entrySet()) {
            entry.setValue(mergeAndSortStacks(entry.getValue()));
        }

        return itemsByCategory;
    }

    /** Empty every chest in the network. Only called after the journal exists. */
    private void clearAllChests(ChestNetwork network) {
        for (NetworkChest chest : network.getChestsInOrder()) {
            Inventory inv = chest.getInventory();
            if (inv == null) continue;
            inv.clear();
        }
    }

    /**
     * Merge similar item stacks and sort by material name
     */
    /** One-item copy as identity key (Paper's ItemStack#asOne, kept to plain Bukkit API for Spigot). */
    private static ItemStack singleOf(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return one;
    }

    private List<ItemStack> mergeAndSortStacks(List<ItemStack> items) {
        // Merge by full item identity (type + components), NOT just Material.
        // Keying by Material collapsed distinct items onto the first sample seen:
        // two same-colored shulker boxes with different contents became two copies
        // of the first one (duplicating one payload, erasing the other). The same
        // applied to enchanted tools, potions, named items etc.
        // The arithmetic lives in StackMath so it is unit-testable.
        Map<ItemStack, Integer> totals = StackMath.tally(items, FullReorganizeTask::singleOf, ItemStack::getAmount);

        List<Map.Entry<ItemStack, Integer>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().getType().name()));

        List<ItemStack> merged = new ArrayList<>();
        for (Map.Entry<ItemStack, Integer> entry : entries) {
            ItemStack sample = entry.getKey();
            for (int amount : StackMath.splitAmounts(entry.getValue(), sample.getMaxStackSize())) {
                ItemStack stack = sample.clone();
                stack.setAmount(amount);
                merged.add(stack);
            }
        }

        return merged;
    }

    /**
     * Log collected items for debugging
     */
    private void logCollectedItems(Map<String, List<ItemStack>> itemsByCategory) {
        debug("Collected items by category:");

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

            debug("  " + cat + ": " + totalAmount + " items [" + itemTypes + "]");
        }

        // Special: Log all "misc" items in a copy-paste friendly format for adding to config
        logMiscItems(itemsByCategory);
    }

    /**
     * Log all items in "misc" category in a format ready to copy-paste to config.yml
     * This helps identify items that need to be categorized.
     */
    private void logMiscItems(Map<String, List<ItemStack>> itemsByCategory) {
        List<ItemStack> miscItems = itemsByCategory.get("misc");
        if (miscItems == null || miscItems.isEmpty()) {
            return;
        }

        // Collect unique materials
        Set<String> miscMaterials = new TreeSet<>();
        for (ItemStack item : miscItems) {
            miscMaterials.add(item.getType().name());
        }

        if (miscMaterials.isEmpty()) {
            return;
        }

        plugin.getLogger().warning("==========================================");
        plugin.getLogger().warning("UNCATEGORIZED ITEMS (" + miscMaterials.size() + " types)");
        plugin.getLogger().warning("Add these to config.yml in appropriate categories:");
        plugin.getLogger().warning("==========================================");

        // Log in groups of 10 for readability
        List<String> materialList = new ArrayList<>(miscMaterials);
        for (int i = 0; i < materialList.size(); i += 10) {
            int end = Math.min(i + 10, materialList.size());
            String chunk = String.join(", ", materialList.subList(i, end));
            plugin.getLogger().warning("" + chunk);
        }

        plugin.getLogger().warning("==========================================");
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
