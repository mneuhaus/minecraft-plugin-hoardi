package de.hoarder.network;

import de.hoarder.config.HoarderConfig;
import org.bukkit.Location;

import java.util.*;

/**
 * Calculates positions using nearest-neighbor pathfinding per floor
 *
 * Definitions:
 * - FLOOR (Ebene): A group of Y-levels within 3 blocks of each other
 * - ROW (Reihe): Chests along the same axis (e.g., same Z, varying X)
 * - COLUMN (Spalte): 1-3 chests stacked vertically at the same XZ position
 *
 * Algorithm:
 * 1. Detect floors (groups of Y-levels with max 3 blocks gap)
 * 2. For each floor (starting nearest to root):
 *    a. Find all columns on this floor
 *    b. Start at column nearest to root XZ
 *    c. Pathfind through all columns (nearest neighbor)
 *    d. Within each column: bottom to top
 * 3. Move to next floor, repeat from root XZ position
 */
public class RowBasedPositionCalculator {

    private final HoarderConfig config;
    private static final int MAX_FLOOR_GAP = 1; // Max Y gap within same floor (1 = adjacent blocks only)

    public RowBasedPositionCalculator(HoarderConfig config) {
        this.config = config;
    }

    /**
     * Calculate positions for all chests
     */
    public Map<Location, Integer> calculatePositions(Location root, Set<Location> chestLocations) {
        if (chestLocations.isEmpty()) {
            return new HashMap<>();
        }

        // Normalize all locations
        Set<Location> normalized = new HashSet<>();
        for (Location loc : chestLocations) {
            normalized.add(normalizeLocation(loc));
        }

        // Step 1: Detect floors (groups of Y-levels)
        List<Floor> floors = detectFloors(normalized);

        // Sort floors by distance from root Y (nearest first)
        int rootY = root.getBlockY();
        floors.sort((a, b) -> {
            int distA = Math.min(Math.abs(a.minY - rootY), Math.abs(a.maxY - rootY));
            int distB = Math.min(Math.abs(b.minY - rootY), Math.abs(b.maxY - rootY));
            return Integer.compare(distA, distB);
        });

        // Step 2: Process each floor
        Map<Location, Integer> positions = new HashMap<>();
        int position = 1;
        int rootX = root.getBlockX();
        int rootZ = root.getBlockZ();

        for (Floor floor : floors) {
            // Group chests on this floor into columns
            Map<String, List<Location>> columns = groupIntoColumns(floor.chests);

            // Sort each column by Y (bottom to top)
            boolean bottomToTop = config.isBottomToTop();
            for (List<Location> column : columns.values()) {
                if (bottomToTop) {
                    column.sort(Comparator.comparingInt(Location::getBlockY));
                } else {
                    column.sort((a, b) -> Integer.compare(b.getBlockY(), a.getBlockY()));
                }
            }

            // Build column centers for pathfinding (XZ only for this floor)
            Map<String, int[]> columnCenters = new HashMap<>();
            for (Map.Entry<String, List<Location>> entry : columns.entrySet()) {
                int[] coords = parseColumnKey(entry.getKey());
                columnCenters.put(entry.getKey(), coords);
            }

            // Pathfind through columns on this floor, starting from root XZ
            List<String> orderedColumns = pathfindColumns(rootX, rootZ, columnCenters);

            // Assign positions
            for (String columnKey : orderedColumns) {
                List<Location> column = columns.get(columnKey);
                for (Location chest : column) {
                    positions.put(chest, position++);
                }
            }
        }

        return positions;
    }

    /**
     * Detect floors - groups of Y-levels that are close together
     */
    private List<Floor> detectFloors(Set<Location> chests) {
        // Get all unique Y values
        Set<Integer> yValues = new TreeSet<>();
        for (Location chest : chests) {
            yValues.add(chest.getBlockY());
        }

        // Group Y values into floors (consecutive Y values within MAX_FLOOR_GAP)
        List<Floor> floors = new ArrayList<>();
        List<Integer> currentFloorYs = new ArrayList<>();
        int lastY = Integer.MIN_VALUE;

        for (int y : yValues) {
            if (lastY == Integer.MIN_VALUE || y - lastY <= MAX_FLOOR_GAP) {
                // Same floor
                currentFloorYs.add(y);
            } else {
                // New floor - save current and start new
                if (!currentFloorYs.isEmpty()) {
                    floors.add(createFloor(currentFloorYs, chests));
                }
                currentFloorYs = new ArrayList<>();
                currentFloorYs.add(y);
            }
            lastY = y;
        }

        // Don't forget the last floor
        if (!currentFloorYs.isEmpty()) {
            floors.add(createFloor(currentFloorYs, chests));
        }

        return floors;
    }

    /**
     * Create a Floor object from Y values and all chests
     */
    private Floor createFloor(List<Integer> yValues, Set<Location> allChests) {
        int minY = Collections.min(yValues);
        int maxY = Collections.max(yValues);

        Set<Location> floorChests = new HashSet<>();
        for (Location chest : allChests) {
            if (yValues.contains(chest.getBlockY())) {
                floorChests.add(chest);
            }
        }

        return new Floor(minY, maxY, floorChests);
    }

    /**
     * Pathfind through columns using nearest neighbor algorithm
     */
    private List<String> pathfindColumns(int startX, int startZ, Map<String, int[]> columnCenters) {
        List<String> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        // Find starting column (nearest to start position)
        String current = findNearestColumn(startX, startZ, columnCenters, visited);

        while (current != null) {
            ordered.add(current);
            visited.add(current);

            int[] coords = columnCenters.get(current);
            current = findNearestColumn(coords[0], coords[1], columnCenters, visited);
        }

        return ordered;
    }

    /**
     * Find nearest unvisited column
     */
    private String findNearestColumn(int x, int z,
                                      Map<String, int[]> columnCenters,
                                      Set<String> visited) {
        String nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Map.Entry<String, int[]> entry : columnCenters.entrySet()) {
            if (visited.contains(entry.getKey())) continue;

            int[] coords = entry.getValue();
            double dist = Math.sqrt(
                Math.pow(coords[0] - x, 2) +
                Math.pow(coords[1] - z, 2)
            );

            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = entry.getKey();
            }
        }

        return nearest;
    }

    /**
     * Group chests into columns (same XZ position)
     */
    private Map<String, List<Location>> groupIntoColumns(Set<Location> chests) {
        Map<String, List<Location>> columns = new HashMap<>();

        for (Location chest : chests) {
            String key = columnKey(chest.getBlockX(), chest.getBlockZ());
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(chest);
        }

        return columns;
    }

    private String columnKey(int x, int z) {
        return x + "," + z;
    }

    private int[] parseColumnKey(String key) {
        String[] parts = key.split(",");
        return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
    }

    private Location normalizeLocation(Location loc) {
        return new Location(
            loc.getWorld(),
            loc.getBlockX(),
            loc.getBlockY(),
            loc.getBlockZ()
        );
    }

    /**
     * Represents a floor (Ebene) with its Y range and chests
     */
    private static class Floor {
        final int minY;
        final int maxY;
        final Set<Location> chests;

        Floor(int minY, int maxY, Set<Location> chests) {
            this.minY = minY;
            this.maxY = maxY;
            this.chests = chests;
        }
    }
}
