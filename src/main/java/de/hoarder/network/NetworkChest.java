package de.hoarder.network;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.inventory.Inventory;

/**
 * Represents a chest in the network with its position.
 * For double chests, this represents both halves as a single unit.
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
     * Check if the block at a location is part of a double chest
     */
    public static boolean isDoubleChest(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData)) {
            return false;
        }
        return chestData.getType() != Type.SINGLE;
    }

    /**
     * Get the "canonical" location for a chest - the LEFT half for double chests.
     * This ensures both halves of a double chest map to the same NetworkChest.
     */
    public static Location getCanonicalLocation(Location location) {
        Block block = location.getBlock();
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData)) {
            return location;
        }

        // Single chests are their own canonical location
        if (chestData.getType() == Type.SINGLE) {
            return location;
        }

        // For double chests, always use the LEFT half as canonical
        if (chestData.getType() == Type.LEFT) {
            return location;
        }

        // This is the RIGHT half - find the LEFT half
        Block otherHalf = getOtherHalf(block, chestData);
        if (otherHalf != null) {
            return otherHalf.getLocation();
        }

        // Fallback to current location if we can't find the other half
        return location;
    }

    /**
     * Get the other half of a double chest
     */
    public static Block getOtherHalf(Block chestBlock, org.bukkit.block.data.type.Chest chestData) {
        if (chestData.getType() == Type.SINGLE) {
            return null;
        }

        BlockFace facing = chestData.getFacing();
        BlockFace otherHalfDirection;

        // The other half is 90 degrees from facing direction
        // LEFT chest: other half is to the right when looking at chest
        // RIGHT chest: other half is to the left when looking at chest
        if (chestData.getType() == Type.LEFT) {
            otherHalfDirection = rotateClockwise(facing);
        } else {
            otherHalfDirection = rotateCounterClockwise(facing);
        }

        return chestBlock.getRelative(otherHalfDirection);
    }

    private static BlockFace rotateClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    private static BlockFace rotateCounterClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> face;
        };
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
     * Get the full inventory for this chest.
     * For double chests, returns the combined 54-slot inventory.
     * Single chests and other containers return their normal inventory.
     */
    public Inventory getInventory() {
        Block block = getBlock();
        if (block.getState() instanceof Chest chest) {
            // Use getInventory() to get the combined inventory (54 slots for double chests)
            // This works because we only register ONE NetworkChest per double chest
            // (using the canonical LEFT half location)
            return chest.getInventory();
        }
        if (block.getState() instanceof Container container) {
            return container.getInventory();
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
