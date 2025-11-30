package de.hoarder.shelf;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
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

        for (Location shelfLoc : tracked) {
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

            // Calculate what to display
            DisplayInfo displayInfo = calculateDisplay(chestInv);

            // Update shelf contents
            updateShelf(shelfBlock, displayInfo);
        }

        // Clean up invalid shelves
        for (Location loc : toRemove) {
            shelfManager.unregisterShelf(loc);
        }
    }

    /**
     * Calculate what to display based on chest contents
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

        // Sort items by count (descending)
        List<Map.Entry<Material, Integer>> sortedItems = itemCounts.entrySet().stream()
            .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
            .toList();

        ItemStack[] slots = new ItemStack[3];
        int uniqueTypes = sortedItems.size();

        Material mostCommon = sortedItems.get(0).getKey();
        int mostCommonCount = sortedItems.get(0).getValue();
        int maxItems = inventory.getSize() * 64;
        double fillPercentage = (double) mostCommonCount / maxItems;

        ItemStack mostCommonSample = itemSamples.get(mostCommon).clone();
        mostCommonSample.setAmount(1);

        if (uniqueTypes == 1) {
            // Only 1 item type - show fill level
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
        } else if (uniqueTypes == 2) {
            // 2 item types
            Material secondMost = sortedItems.get(1).getKey();
            ItemStack secondSample = itemSamples.get(secondMost).clone();
            secondSample.setAmount(1);

            slots[0] = mostCommonSample.clone();
            if (fillPercentage >= 0.50) {
                slots[1] = mostCommonSample.clone();
                slots[2] = secondSample;
            } else {
                slots[1] = secondSample;
            }
        } else {
            // 3+ item types - show top 3
            for (int i = 0; i < 3; i++) {
                Material material = sortedItems.get(i).getKey();
                ItemStack sample = itemSamples.get(material).clone();
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
}
