package de.hoarder.sorting;

import de.hoarder.HoarderPlugin;
import de.hoarder.config.HoarderConfig;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkChest;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick sort task - sorts items from a single chest after it's closed
 * Runs async over multiple ticks to prevent lag
 */
public class QuickSortTask extends BukkitRunnable {

    private final HoarderPlugin plugin;
    private final ChestNetwork network;
    private final Location sourceChestLoc;
    private final HoarderConfig config;
    private final CategoryResolver categoryResolver;

    private final List<ItemToMove> itemsToMove = new ArrayList<>();
    private int currentIndex = 0;
    private boolean initialized = false;

    public QuickSortTask(HoarderPlugin plugin, ChestNetwork network, Location sourceChestLoc) {
        this.plugin = plugin;
        this.network = network;
        this.sourceChestLoc = sourceChestLoc;
        this.config = plugin.getHoarderConfig();
        this.categoryResolver = new CategoryResolver(config, plugin.getItemHierarchy());
    }

    @Override
    public void run() {
        plugin.getLogger().info("[DEBUG] QuickSortTask.run() - initialized=" + initialized);

        // Initialize on first run
        if (!initialized) {
            initialize();
            initialized = true;
            plugin.getLogger().info("[DEBUG] Initialization complete, items to move: " + itemsToMove.size());
            return;
        }

        // Process items per tick
        int itemsPerTick = config.getItemsPerTick();
        int processed = 0;

        plugin.getLogger().info("[DEBUG] Processing items, currentIndex=" + currentIndex + ", total=" + itemsToMove.size());

        while (currentIndex < itemsToMove.size() && processed < itemsPerTick) {
            ItemToMove itemToMove = itemsToMove.get(currentIndex);
            plugin.getLogger().info("[DEBUG] Moving item: " + itemToMove.item().getType() + " x" + itemToMove.item().getAmount() +
                " from slot " + itemToMove.sourceSlot() + " to " + formatLocation(itemToMove.targetLoc()));
            moveItem(itemToMove);
            currentIndex++;
            processed++;
        }

        // Done?
        if (currentIndex >= itemsToMove.size()) {
            cancel();
            plugin.getLogger().info("[DEBUG] Quick sort complete. Moved " + itemsToMove.size() + " item stacks.");

            // Check if split is needed
            checkForSplit();
        }
    }

    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Initialize - scan source chest and plan moves
     */
    private void initialize() {
        plugin.getLogger().info("[DEBUG] QuickSortTask.initialize() for " + formatLocation(sourceChestLoc));

        NetworkChest sourceChest = network.getChest(sourceChestLoc);
        plugin.getLogger().info("[DEBUG] Source chest found: " + (sourceChest != null));

        if (sourceChest == null) {
            plugin.getLogger().info("[DEBUG] Source chest not in network, cancelling");
            cancel();
            return;
        }

        Inventory sourceInv = sourceChest.getInventory();
        plugin.getLogger().info("[DEBUG] Source inventory: " + (sourceInv != null ? "size=" + sourceInv.getSize() : "null"));

        if (sourceInv == null) {
            plugin.getLogger().info("[DEBUG] No inventory, cancelling");
            cancel();
            return;
        }

        // Count items
        int totalItems = 0;
        for (ItemStack item : sourceInv.getContents()) {
            if (item != null) totalItems++;
        }
        plugin.getLogger().info("[DEBUG] Source chest has " + totalItems + " item stacks");

        // Scan all items in source chest
        for (int slot = 0; slot < sourceInv.getSize(); slot++) {
            ItemStack item = sourceInv.getItem(slot);
            if (item == null) continue;

            String category = plugin.getItemHierarchy().getCategory(item.getType());
            plugin.getLogger().info("[DEBUG] Item in slot " + slot + ": " + item.getType() + " x" + item.getAmount() + " -> category: " + category);

            // Find target chest (passing source location to exclude it from results)
            NetworkChest targetChest = categoryResolver.findTargetChest(network, item, sourceChestLoc);
            plugin.getLogger().info("[DEBUG] Target chest: " + (targetChest != null ? formatLocation(targetChest.getLocation()) : "null"));

            // Move if we found a different target
            if (targetChest != null) {
                itemsToMove.add(new ItemToMove(slot, item.clone(), targetChest.getLocation()));
                plugin.getLogger().info("[DEBUG] Queued move: " + item.getType() + " -> " + formatLocation(targetChest.getLocation()));
            } else {
                plugin.getLogger().info("[DEBUG] No suitable target found for " + item.getType() + " (keeping in source)");
            }
        }

        plugin.getLogger().info("[DEBUG] Quick sort initialized. " + itemsToMove.size() + " items to move.");
    }

    /**
     * Move a single item
     */
    private void moveItem(ItemToMove itemToMove) {
        // Get source chest
        NetworkChest sourceChest = network.getChest(sourceChestLoc);
        if (sourceChest == null) return;

        Inventory sourceInv = sourceChest.getInventory();
        if (sourceInv == null) return;

        // Verify item is still in source slot
        ItemStack currentItem = sourceInv.getItem(itemToMove.sourceSlot);
        if (currentItem == null || currentItem.getType() != itemToMove.item.getType()) {
            return; // Item was changed/removed, skip
        }

        // Get target chest
        NetworkChest targetChest = network.getChest(itemToMove.targetLoc);
        if (targetChest == null) return;

        Inventory targetInv = targetChest.getInventory();
        if (targetInv == null) return;

        // Try to add item to target
        int remainingAmount = currentItem.getAmount();

        // First try to stack with existing items
        for (int i = 0; i < targetInv.getSize() && remainingAmount > 0; i++) {
            ItemStack targetSlot = targetInv.getItem(i);
            if (targetSlot != null && targetSlot.isSimilar(currentItem)) {
                int canAdd = targetSlot.getMaxStackSize() - targetSlot.getAmount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, remainingAmount);
                    targetSlot.setAmount(targetSlot.getAmount() + toAdd);
                    remainingAmount -= toAdd;
                }
            }
        }

        // Then try empty slots
        for (int i = 0; i < targetInv.getSize() && remainingAmount > 0; i++) {
            if (targetInv.getItem(i) == null) {
                ItemStack newStack = currentItem.clone();
                int toAdd = Math.min(currentItem.getMaxStackSize(), remainingAmount);
                newStack.setAmount(toAdd);
                targetInv.setItem(i, newStack);
                remainingAmount -= toAdd;
            }
        }

        // Update source slot
        if (remainingAmount <= 0) {
            sourceInv.setItem(itemToMove.sourceSlot, null);
        } else {
            currentItem.setAmount(remainingAmount);
        }
    }

    /**
     * Check if any chest needs splitting after sort
     */
    private void checkForSplit() {
        SplitChecker splitChecker = new SplitChecker(plugin, network);

        for (NetworkChest chest : network.getChestsInOrder()) {
            if (splitChecker.shouldSplit(chest)) {
                splitChecker.performSplit(chest);
            }
        }
    }

    /**
     * Data class for planned item moves
     */
    private record ItemToMove(int sourceSlot, ItemStack item, Location targetLoc) {}
}
