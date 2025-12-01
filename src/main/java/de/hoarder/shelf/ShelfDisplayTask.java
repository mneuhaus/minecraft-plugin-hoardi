package de.hoarder.shelf;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Periodically updates shelf contents to match chest contents
 */
public class ShelfDisplayTask extends BukkitRunnable {

    private final ShelfManager shelfManager;

    public ShelfDisplayTask(ShelfManager shelfManager) {
        this.shelfManager = shelfManager;
    }

    @Override
    public void run() {
        Set<Location> toRemove = new HashSet<>();
        Set<Location> tracked = shelfManager.getTrackedShelves();
        Set<Location> processedPairs = new HashSet<>();

        for (Location shelfLoc : tracked) {
            // Skip if already processed as part of a pair
            if (processedPairs.contains(shelfLoc)) {
                continue;
            }

            Location chestLoc = shelfManager.getChestLocation(shelfLoc);

            if (chestLoc == null) {
                toRemove.add(shelfLoc);
                continue;
            }

            World world = shelfLoc.getWorld();
            if (world == null) {
                toRemove.add(shelfLoc);
                continue;
            }

            // Check if the chunk is loaded
            if (!world.isChunkLoaded(shelfLoc.getBlockX() >> 4, shelfLoc.getBlockZ() >> 4)) {
                continue;
            }

            Block shelfBlock = shelfLoc.getBlock();
            Block chestBlock = chestLoc.getBlock();

            // Verify blocks still exist
            if (!shelfManager.isShelf(shelfBlock)) {
                toRemove.add(shelfLoc);
                continue;
            }

            if (!shelfManager.isChest(chestBlock)) {
                toRemove.add(shelfLoc);
                continue;
            }

            // Get chest inventory
            Inventory chestInv = shelfManager.getChestInventory(chestBlock);
            if (chestInv == null) {
                continue;
            }

            // Check for paired shelf (side-by-side on same chest/double chest)
            Block pairedShelf = findPairedShelf(shelfBlock, chestBlock, tracked);

            if (pairedShelf != null) {
                // Mark both as processed
                processedPairs.add(shelfLoc);
                processedPairs.add(pairedShelf.getLocation());

                // Determine which shelf is "left" and which is "right" based on position
                Block leftShelf, rightShelf;
                if (isLeftOf(shelfBlock, pairedShelf)) {
                    leftShelf = shelfBlock;
                    rightShelf = pairedShelf;
                } else {
                    leftShelf = pairedShelf;
                    rightShelf = shelfBlock;
                }

                // Calculate 6-slot display for paired shelves
                DisplayInfo displayInfo = calculateDisplayPaired(chestInv);

                // Update left shelf (slots 0-2)
                updateShelf(leftShelf, new DisplayInfo(new ItemStack[]{
                    displayInfo.slots[0], displayInfo.slots[1], displayInfo.slots[2]
                }));

                // Update right shelf (slots 3-5)
                updateShelf(rightShelf, new DisplayInfo(new ItemStack[]{
                    displayInfo.slots[3], displayInfo.slots[4], displayInfo.slots[5]
                }));
            } else {
                // Single shelf - use standard 3-slot display
                DisplayInfo displayInfo = calculateDisplay(chestInv);
                updateShelf(shelfBlock, displayInfo);
            }
        }

        // Clean up invalid shelves
        for (Location loc : toRemove) {
            shelfManager.unregisterShelf(loc);
        }
    }

    /**
     * Find a paired shelf that is side-by-side with this shelf on the same chest
     */
    private Block findPairedShelf(Block shelfBlock, Block chestBlock, Set<Location> trackedShelves) {
        // Get the facing direction of the shelf
        if (!(shelfBlock.getBlockData() instanceof Directional directional)) {
            return null;
        }

        BlockFace facing = directional.getFacing();

        // Determine the horizontal directions perpendicular to facing
        BlockFace[] sideFaces;
        if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
            sideFaces = new BlockFace[]{BlockFace.EAST, BlockFace.WEST};
        } else {
            sideFaces = new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH};
        }

        // Check adjacent blocks for another shelf pointing same direction at same chest
        for (BlockFace side : sideFaces) {
            Block adjacent = shelfBlock.getRelative(side);
            Location adjacentLoc = adjacent.getLocation();

            if (!trackedShelves.contains(adjacentLoc)) {
                continue;
            }

            if (!shelfManager.isShelf(adjacent)) {
                continue;
            }

            // Check if adjacent shelf points to same chest (or the other half of a double chest)
            Location adjacentChestLoc = shelfManager.getChestLocation(adjacentLoc);
            if (adjacentChestLoc == null) {
                continue;
            }

            // Check if it's the same chest or part of same double chest
            if (isSameOrDoubleChest(chestBlock.getLocation(), adjacentChestLoc)) {
                // Verify same facing direction
                if (adjacent.getBlockData() instanceof Directional adjDir) {
                    if (adjDir.getFacing() == facing) {
                        return adjacent;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Check if two chest locations are the same chest or part of a CONNECTED double chest
     * (not just adjacent chests, but actually sharing the same inventory)
     */
    private boolean isSameOrDoubleChest(Location loc1, Location loc2) {
        if (loc1.equals(loc2)) {
            return true;
        }

        // Check if they're adjacent horizontally
        int dx = Math.abs(loc1.getBlockX() - loc2.getBlockX());
        int dy = Math.abs(loc1.getBlockY() - loc2.getBlockY());
        int dz = Math.abs(loc1.getBlockZ() - loc2.getBlockZ());

        if (dy != 0 || !((dx == 1 && dz == 0) || (dx == 0 && dz == 1))) {
            return false; // Not adjacent horizontally
        }

        // Check if they actually form a double chest (same inventory)
        Block block1 = loc1.getBlock();
        Block block2 = loc2.getBlock();

        // Both must be chests (not barrels, etc.)
        if (!(block1.getBlockData() instanceof org.bukkit.block.data.type.Chest chest1Data) ||
            !(block2.getBlockData() instanceof org.bukkit.block.data.type.Chest chest2Data)) {
            return false;
        }

        // Single chests can't be paired
        if (chest1Data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE ||
            chest2Data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) {
            return false;
        }

        // They must face the same direction to be a double chest
        if (chest1Data.getFacing() != chest2Data.getFacing()) {
            return false;
        }

        // One must be LEFT and one must be RIGHT
        boolean isDoubleChest = (chest1Data.getType() == org.bukkit.block.data.type.Chest.Type.LEFT &&
                                  chest2Data.getType() == org.bukkit.block.data.type.Chest.Type.RIGHT) ||
                                 (chest1Data.getType() == org.bukkit.block.data.type.Chest.Type.RIGHT &&
                                  chest2Data.getType() == org.bukkit.block.data.type.Chest.Type.LEFT);

        return isDoubleChest;
    }

    /**
     * Determine if block A is to the "left" of block B based on coordinates
     */
    private boolean isLeftOf(Block a, Block b) {
        // Use a consistent ordering: lower X first, then lower Z
        if (a.getX() != b.getX()) {
            return a.getX() < b.getX();
        }
        return a.getZ() < b.getZ();
    }

    /**
     * Calculate 6-slot display for paired shelves (left shelf = 0,1,2 / right shelf = 3,4,5)
     * Groups similar material variants (e.g., different wood shelves) to show diversity of block types.
     *
     * Display positions based on item count:
     * - 1 item:  slot 1 (left middle)
     * - 2 items: slots 1, 4 (both middles)
     * - 3 items: slots 0, 1, 2 (left shelf full)
     * - 4 items: slots 0, 1, 3, 4 (outer slots of both)
     * - 5 items: slots 0, 1, 2, 3, 4
     * - 6 items: all slots
     */
    private DisplayInfo calculateDisplayPaired(Inventory inventory) {
        Map<Material, Integer> itemCounts = new HashMap<>();
        Map<Material, ItemStack> itemSamples = new HashMap<>();
        int totalItems = 0;

        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                Material type = item.getType();
                int amount = item.getAmount();
                itemCounts.merge(type, amount, Integer::sum);
                totalItems += amount;
                if (!itemSamples.containsKey(type)) {
                    itemSamples.put(type, item.clone());
                }
            }
        }

        ItemStack[] slots = new ItemStack[6];

        if (itemCounts.isEmpty() || totalItems == 0) {
            return new DisplayInfo(slots);
        }

        // Group by base type to show diversity (SHELF, CHEST, BARREL instead of OAK_SHELF, BIRCH_SHELF, JUNGLE_SHELF)
        Map<String, GroupedItem> grouped = groupByBaseType(itemCounts, itemSamples);

        // Sort grouped items by count (descending)
        List<Map.Entry<String, GroupedItem>> sortedGroups = grouped.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().totalCount, a.getValue().totalCount))
            .toList();

        int uniqueGroups = sortedGroups.size();
        int maxItems = inventory.getSize() * 64;

        // Slot positions for each item count (indices into the 6-slot array)
        // Left shelf = 0,1,2   Right shelf = 3,4,5
        int[][] slotPositions = {
            {1},              // 1 item:  left middle
            {1, 4},           // 2 items: both middles
            {0, 1, 2},        // 3 items: left shelf full
            {0, 1, 3, 4},     // 4 items: left full minus right-edge + right outer two
            {0, 1, 2, 3, 4},  // 5 items: all except right-right
            {0, 1, 2, 3, 4, 5} // 6 items: all slots
        };

        if (uniqueGroups == 1) {
            // Only 1 base type - show fill level
            GroupedItem group = sortedGroups.get(0).getValue();
            double fillPercentage = (double) group.totalCount / maxItems;

            ItemStack sample = group.representative.clone();
            sample.setAmount(1);

            // Determine how many slots to fill based on fill percentage
            int slotsToFill;
            if (fillPercentage < 0.17) {
                slotsToFill = 1;
            } else if (fillPercentage < 0.33) {
                slotsToFill = 2;
            } else if (fillPercentage < 0.50) {
                slotsToFill = 3;
            } else if (fillPercentage < 0.67) {
                slotsToFill = 4;
            } else if (fillPercentage < 0.83) {
                slotsToFill = 5;
            } else {
                slotsToFill = 6;
            }

            int[] positions = slotPositions[slotsToFill - 1];
            for (int pos : positions) {
                slots[pos] = sample.clone();
            }
        } else {
            // Multiple base types - show top types for diversity
            int itemsToShow = Math.min(uniqueGroups, 6);
            int[] positions = slotPositions[itemsToShow - 1];

            for (int i = 0; i < itemsToShow; i++) {
                GroupedItem group = sortedGroups.get(i).getValue();
                ItemStack sample = group.representative.clone();
                sample.setAmount(1);
                slots[positions[i]] = sample;
            }
        }

        return new DisplayInfo(slots);
    }

    /**
     * Calculate what to display based on chest contents (3 slots)
     * Groups similar material variants (e.g., different wood shelves) to show diversity of block types.
     */
    private DisplayInfo calculateDisplay(Inventory inventory) {
        Map<Material, Integer> itemCounts = new HashMap<>();
        Map<Material, ItemStack> itemSamples = new HashMap<>();
        int totalItems = 0;

        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                Material type = item.getType();
                int amount = item.getAmount();
                itemCounts.merge(type, amount, Integer::sum);
                totalItems += amount;
                if (!itemSamples.containsKey(type)) {
                    itemSamples.put(type, item.clone());
                }
            }
        }

        if (itemCounts.isEmpty() || totalItems == 0) {
            return new DisplayInfo(new ItemStack[3]);
        }

        // Group by base type to show diversity (SHELF, CHEST, BARREL instead of OAK_SHELF, BIRCH_SHELF, JUNGLE_SHELF)
        Map<String, GroupedItem> grouped = groupByBaseType(itemCounts, itemSamples);

        // Sort grouped items by count (descending)
        List<Map.Entry<String, GroupedItem>> sortedGroups = grouped.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().totalCount, a.getValue().totalCount))
            .toList();

        ItemStack[] slots = new ItemStack[3];
        int uniqueGroups = sortedGroups.size();

        GroupedItem mostCommonGroup = sortedGroups.get(0).getValue();
        int maxItems = inventory.getSize() * 64;
        double fillPercentage = (double) mostCommonGroup.totalCount / maxItems;

        ItemStack mostCommonSample = mostCommonGroup.representative.clone();
        mostCommonSample.setAmount(1);

        if (uniqueGroups == 1) {
            // Only 1 base type - show fill level
            if (fillPercentage < 0.50) {
                slots[1] = mostCommonSample.clone(); // Centered
            } else if (fillPercentage < 0.80) {
                slots[0] = mostCommonSample.clone();
                slots[1] = mostCommonSample.clone();
            } else {
                slots[0] = mostCommonSample.clone();
                slots[1] = mostCommonSample.clone();
                slots[2] = mostCommonSample.clone();
            }
        } else if (uniqueGroups == 2) {
            // 2 base types
            GroupedItem secondGroup = sortedGroups.get(1).getValue();
            ItemStack secondSample = secondGroup.representative.clone();
            secondSample.setAmount(1);

            slots[0] = mostCommonSample.clone();
            if (fillPercentage >= 0.50) {
                slots[1] = mostCommonSample.clone();
                slots[2] = secondSample;
            } else {
                slots[1] = secondSample;
            }
        } else {
            // 3+ base types - show top 3 different types
            for (int i = 0; i < 3; i++) {
                GroupedItem group = sortedGroups.get(i).getValue();
                ItemStack sample = group.representative.clone();
                sample.setAmount(1);
                slots[i] = sample;
            }
        }

        return new DisplayInfo(slots);
    }

    /**
     * Update a shelf block with items
     */
    private void updateShelf(Block shelfBlock, DisplayInfo displayInfo) {
        BlockState state = shelfBlock.getState();

        if (state instanceof org.bukkit.block.Shelf shelf) {
            Inventory shelfInv = shelf.getSnapshotInventory();

            // Check if update is needed
            boolean needsUpdate = false;
            for (int i = 0; i < 3; i++) {
                ItemStack current = shelfInv.getItem(i);
                ItemStack target = displayInfo.slots[i];

                if (!itemsMatch(current, target)) {
                    needsUpdate = true;
                    break;
                }
            }

            if (needsUpdate) {
                shelfInv.clear();
                for (int i = 0; i < 3; i++) {
                    if (displayInfo.slots[i] != null) {
                        shelfInv.setItem(i, displayInfo.slots[i]);
                    }
                }
                state.update(true, true);
            }
        }
    }

    /**
     * Check if two ItemStacks match (type only)
     */
    private boolean itemsMatch(ItemStack a, ItemStack b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.getType() == b.getType();
    }

    /**
     * Helper class to hold display information
     */
    private static class DisplayInfo {
        final ItemStack[] slots;

        DisplayInfo(ItemStack[] slots) {
            this.slots = slots;
        }
    }

    /**
     * Get the "base type" of a material for grouping similar variants together.
     * E.g., OAK_SHELF, BIRCH_SHELF, JUNGLE_SHELF all become "SHELF"
     * This allows showing different block types rather than color/wood variants.
     */
    private static String getBaseType(Material material) {
        String name = material.name();

        // Wood variant prefixes to strip
        String[] woodPrefixes = {
            "OAK_", "SPRUCE_", "BIRCH_", "JUNGLE_", "ACACIA_", "DARK_OAK_",
            "MANGROVE_", "CHERRY_", "PALE_OAK_", "BAMBOO_", "CRIMSON_", "WARPED_"
        };

        // Color variant prefixes to strip
        String[] colorPrefixes = {
            "WHITE_", "ORANGE_", "MAGENTA_", "LIGHT_BLUE_", "YELLOW_", "LIME_",
            "PINK_", "GRAY_", "LIGHT_GRAY_", "CYAN_", "PURPLE_", "BLUE_",
            "BROWN_", "GREEN_", "RED_", "BLACK_"
        };

        // Copper oxidation prefixes to strip
        String[] copperPrefixes = {
            "WAXED_OXIDIZED_", "WAXED_WEATHERED_", "WAXED_EXPOSED_", "WAXED_",
            "OXIDIZED_", "WEATHERED_", "EXPOSED_"
        };

        // Try copper prefixes first (they're longer and more specific)
        for (String prefix : copperPrefixes) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }

        // Try wood prefixes
        for (String prefix : woodPrefixes) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }

        // Try color prefixes
        for (String prefix : colorPrefixes) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }

        return name;
    }

    /**
     * Group items by their base type for display diversity.
     * Returns a map of base type -> (total count, representative ItemStack)
     */
    private static class GroupedItem {
        int totalCount;
        ItemStack representative;

        GroupedItem(int count, ItemStack item) {
            this.totalCount = count;
            this.representative = item;
        }
    }

    private Map<String, GroupedItem> groupByBaseType(Map<Material, Integer> itemCounts, Map<Material, ItemStack> itemSamples) {
        Map<String, GroupedItem> grouped = new HashMap<>();

        for (Map.Entry<Material, Integer> entry : itemCounts.entrySet()) {
            Material mat = entry.getKey();
            int count = entry.getValue();
            String baseType = getBaseType(mat);

            GroupedItem existing = grouped.get(baseType);
            if (existing == null) {
                grouped.put(baseType, new GroupedItem(count, itemSamples.get(mat).clone()));
            } else {
                existing.totalCount += count;
                // Keep the one with higher individual count as representative
                if (count > itemCounts.getOrDefault(existing.representative.getType(), 0)) {
                    existing.representative = itemSamples.get(mat).clone();
                }
            }
        }

        return grouped;
    }
}
