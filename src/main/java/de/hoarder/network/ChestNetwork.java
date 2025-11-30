package de.hoarder.network;

import de.hoarder.config.HoarderConfig;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

/**
 * Represents a network of connected chests with a root
 */
public class ChestNetwork {

    private final World world;
    private Location root;
    private final Map<Location, NetworkChest> chests = new LinkedHashMap<>();
    private final SpiralPositionCalculator positionCalculator;
    private final HoarderConfig config;

    // Category assignments (category -> list of chest locations)
    private final Map<String, List<Location>> categoryAssignments = new HashMap<>();

    public ChestNetwork(World world, Location root, HoarderConfig config) {
        this.world = world;
        this.root = root;
        this.config = config;
        this.positionCalculator = new SpiralPositionCalculator(config);
    }

    /**
     * Get the world this network is in
     */
    public World getWorld() {
        return world;
    }

    /**
     * Get the root chest location
     */
    public Location getRoot() {
        return root;
    }

    /**
     * Set a new root location
     */
    public void setRoot(Location root) {
        this.root = root;
        recalculatePositions();
    }

    /**
     * Add a chest to the network
     */
    public void addChest(Location location) {
        if (chests.containsKey(location)) {
            return;
        }

        // Calculate position
        Set<Location> existingLocations = new HashSet<>(chests.keySet());
        existingLocations.add(location);
        Map<Location, Integer> positions = positionCalculator.calculatePositions(root, existingLocations);

        int position = positions.getOrDefault(location, chests.size() + 1);
        NetworkChest networkChest = new NetworkChest(location, position);
        chests.put(location, networkChest);

        // Recalculate all positions to keep them consistent
        recalculatePositions();
    }

    /**
     * Remove a chest from the network
     */
    public void removeChest(Location location) {
        NetworkChest removed = chests.remove(location);
        if (removed != null) {
            // Remove from category assignments
            String category = removed.getAssignedCategory();
            if (category != null && categoryAssignments.containsKey(category)) {
                categoryAssignments.get(category).remove(location);
            }
            recalculatePositions();
        }
    }

    /**
     * Check if a chest is in the network
     */
    public boolean containsChest(Location location) {
        return chests.containsKey(location);
    }

    /**
     * Get a chest by location
     */
    public NetworkChest getChest(Location location) {
        return chests.get(location);
    }

    /**
     * Get all chests in position order
     */
    public List<NetworkChest> getChestsInOrder() {
        List<NetworkChest> ordered = new ArrayList<>(chests.values());
        ordered.sort(Comparator.comparingInt(NetworkChest::getPosition));
        return ordered;
    }

    /**
     * Get all chest locations
     */
    public Set<Location> getChestLocations() {
        return new HashSet<>(chests.keySet());
    }

    /**
     * Get the number of chests in the network
     */
    public int size() {
        return chests.size();
    }

    /**
     * Check if the network is empty
     */
    public boolean isEmpty() {
        return chests.isEmpty();
    }

    /**
     * Recalculate all positions based on current chests
     */
    public void recalculatePositions() {
        if (chests.isEmpty()) return;

        Map<Location, Integer> newPositions = positionCalculator.calculatePositions(root, chests.keySet());

        // Update positions
        for (Map.Entry<Location, Integer> entry : newPositions.entrySet()) {
            NetworkChest chest = chests.get(entry.getKey());
            if (chest != null) {
                // Create new chest with updated position (position is final)
                NetworkChest updated = new NetworkChest(entry.getKey(), entry.getValue());
                updated.setAssignedCategory(chest.getAssignedCategory());
                chests.put(entry.getKey(), updated);
            }
        }
    }

    /**
     * Assign a category to a chest
     */
    public void assignCategory(Location chestLoc, String category) {
        NetworkChest chest = chests.get(chestLoc);
        if (chest == null) return;

        // Remove old assignment
        String oldCategory = chest.getAssignedCategory();
        if (oldCategory != null && categoryAssignments.containsKey(oldCategory)) {
            categoryAssignments.get(oldCategory).remove(chestLoc);
        }

        // Add new assignment
        chest.setAssignedCategory(category);
        if (category != null) {
            categoryAssignments.computeIfAbsent(category, k -> new ArrayList<>()).add(chestLoc);
        }
    }

    /**
     * Get chests assigned to a category
     */
    public List<NetworkChest> getChestsForCategory(String category) {
        List<NetworkChest> result = new ArrayList<>();

        List<Location> locations = categoryAssignments.get(category);
        if (locations != null) {
            for (Location loc : locations) {
                NetworkChest chest = chests.get(loc);
                if (chest != null) {
                    result.add(chest);
                }
            }
        }

        return result;
    }

    /**
     * Find a chest with free space for a category
     */
    public NetworkChest findChestWithSpaceForCategory(String category) {
        // First try chests assigned to this category
        for (NetworkChest chest : getChestsForCategory(category)) {
            if (chest.hasFreeSpace()) {
                return chest;
            }
        }

        // Then try chests assigned to parent categories
        String parentCategory = getParentCategory(category);
        while (parentCategory != null) {
            for (NetworkChest chest : getChestsForCategory(parentCategory)) {
                if (chest.hasFreeSpace()) {
                    return chest;
                }
            }
            parentCategory = getParentCategory(parentCategory);
        }

        // Finally try unassigned chests
        for (NetworkChest chest : getChestsInOrder()) {
            if (!chest.hasAssignedCategory() && chest.hasFreeSpace()) {
                return chest;
            }
        }

        return null;
    }

    /**
     * Get parent category
     */
    private String getParentCategory(String category) {
        if (category == null) return null;
        int lastSlash = category.lastIndexOf('/');
        if (lastSlash > 0) {
            return category.substring(0, lastSlash);
        }
        return null;
    }

    /**
     * Clear all category assignments
     */
    public void clearCategoryAssignments() {
        for (NetworkChest chest : chests.values()) {
            chest.setAssignedCategory(null);
        }
        categoryAssignments.clear();
    }

    /**
     * Get statistics about the network
     */
    public NetworkStats getStats() {
        int totalChests = chests.size();
        int totalItems = 0;
        int emptyChests = 0;
        double totalFill = 0;

        for (NetworkChest chest : chests.values()) {
            totalItems += chest.getTotalItems();
            double fill = chest.getFillPercentage();
            totalFill += fill;
            if (fill == 0) emptyChests++;
        }

        double avgFill = totalChests > 0 ? totalFill / totalChests : 0;
        int categoriesUsed = categoryAssignments.size();

        return new NetworkStats(totalChests, totalItems, emptyChests, avgFill, categoriesUsed);
    }

    /**
     * Statistics about the network
     */
    public record NetworkStats(
        int totalChests,
        int totalItems,
        int emptyChests,
        double averageFill,
        int categoriesUsed
    ) {}
}
