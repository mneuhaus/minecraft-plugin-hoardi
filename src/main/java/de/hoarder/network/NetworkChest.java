package de.hoarder.network;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;

/**
 * Represents a chest in the network with its position
 */
public class NetworkChest {

    private final Location location;
    private final int position;  // Position in spiral (1-based)
    private String assignedCategory;  // Category assigned to this chest (null = any)

    public NetworkChest(Location location, int position) {
        this.location = location;
        this.position = position;
        this.assignedCategory = null;
    }

    /**
     * Get the chest location
     */
    public Location getLocation() {
        return location;
    }

    /**
     * Get the spiral position (1-based)
     */
    public int getPosition() {
        return position;
    }

    /**
     * Get the assigned category (null = accepts any items)
     */
    public String getAssignedCategory() {
        return assignedCategory;
    }

    /**
     * Set the assigned category
     */
    public void setAssignedCategory(String category) {
        this.assignedCategory = category;
    }

    /**
     * Check if this chest has an assigned category
     */
    public boolean hasAssignedCategory() {
        return assignedCategory != null && !assignedCategory.isEmpty();
    }

    /**
     * Get the block at this location
     */
    public Block getBlock() {
        return location.getBlock();
    }

    /**
     * Get the chest inventory (handles double chests)
     */
    public Inventory getInventory() {
        Block block = getBlock();
        if (block.getState() instanceof Chest chest) {
            return chest.getInventory();
        }
        return null;
    }

    /**
     * Check if the chest still exists at this location
     */
    public boolean isValid() {
        Block block = getBlock();
        return block.getType().name().contains("CHEST");
    }

    /**
     * Get fill percentage (0.0 - 1.0)
     */
    public double getFillPercentage() {
        Inventory inv = getInventory();
        if (inv == null) return 0;

        int usedSlots = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) != null) {
                usedSlots++;
            }
        }
        return (double) usedSlots / inv.getSize();
    }

    /**
     * Get total item count
     */
    public int getTotalItems() {
        Inventory inv = getInventory();
        if (inv == null) return 0;

        int total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            var item = inv.getItem(i);
            if (item != null) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /**
     * Check if chest has free space
     */
    public boolean hasFreeSpace() {
        Inventory inv = getInventory();
        if (inv == null) return false;
        return inv.firstEmpty() != -1;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        NetworkChest other = (NetworkChest) obj;
        return location.equals(other.location);
    }

    @Override
    public int hashCode() {
        return location.hashCode();
    }

    @Override
    public String toString() {
        return String.format("NetworkChest[pos=%d, loc=(%d,%d,%d), cat=%s]",
            position,
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ(),
            assignedCategory != null ? assignedCategory : "any"
        );
    }
}
