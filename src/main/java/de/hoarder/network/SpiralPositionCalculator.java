package de.hoarder.network;

import de.hoarder.config.HoarderConfig;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

/**
 * Calculates spiral positions from a root chest
 *
 * Spiral pattern (counter-clockwise from East):
 *
 *        [5] [4] [3]
 *        [6] [R] [2]
 *        [7] [8] [1]
 *             ↑
 *        Start, counter-clockwise
 *
 * With stack_height=3:
 * Position 1a (bottom), 1b (middle), 1c (top)
 * Then 2a, 2b, 2c, etc.
 */
public class SpiralPositionCalculator {

    private final HoarderConfig config;

    // Direction vectors for counter-clockwise spiral: E, N, W, W, S, S, E, E, E, N, N, N, ...
    private static final int[] DX_CCW = {1, 0, -1, 0};  // E, N, W, S
    private static final int[] DZ_CCW = {0, -1, 0, 1};

    // Direction vectors for clockwise spiral: E, S, W, N
    private static final int[] DX_CW = {1, 0, -1, 0};
    private static final int[] DZ_CW = {0, 1, 0, -1};

    public SpiralPositionCalculator(HoarderConfig config) {
        this.config = config;
    }

    /**
     * Calculate positions for all chests relative to a root
     * Returns a map of Location -> position number (1-based)
     */
    public Map<Location, Integer> calculatePositions(Location root, Set<Location> chestLocations) {
        Map<Location, Integer> positions = new HashMap<>();
        int radius = config.getNetworkRadius();
        int stackHeight = config.getStackHeight();
        boolean counterClockwise = config.isCounterClockwise();
        boolean bottomToTop = config.isBottomToTop();

        // Generate spiral coordinates
        List<int[]> spiralCoords = generateSpiralCoordinates(radius);

        int position = 1;

        // Process each spiral position
        for (int[] coord : spiralCoords) {
            int dx = coord[0];
            int dz = coord[1];

            // Process stack at this XZ position
            List<Integer> yOffsets = getYOffsets(stackHeight, bottomToTop);

            for (int dy : yOffsets) {
                Location checkLoc = root.clone().add(dx, dy, dz);

                // Normalize location (ensure block coordinates)
                Location normalizedLoc = new Location(
                    checkLoc.getWorld(),
                    checkLoc.getBlockX(),
                    checkLoc.getBlockY(),
                    checkLoc.getBlockZ()
                );

                // Check if there's a chest at this location
                if (chestLocations.contains(normalizedLoc)) {
                    positions.put(normalizedLoc, position);
                    position++;
                }
            }
        }

        return positions;
    }

    /**
     * Generate spiral coordinates up to given radius
     * Returns list of [dx, dz] pairs in spiral order
     */
    private List<int[]> generateSpiralCoordinates(int radius) {
        List<int[]> coords = new ArrayList<>();

        // Start with root position
        coords.add(new int[]{0, 0});

        boolean counterClockwise = config.isCounterClockwise();
        int[] dx = counterClockwise ? DX_CCW : DX_CW;
        int[] dz = counterClockwise ? DZ_CCW : DZ_CW;

        int x = 0, z = 0;
        int direction = 0;  // 0=E, 1=N/S, 2=W, 3=S/N
        int stepsInDirection = 1;
        int stepsTaken = 0;
        int turnsAtCurrentLength = 0;

        int maxPositions = (2 * radius + 1) * (2 * radius + 1);

        while (coords.size() < maxPositions) {
            // Move in current direction
            x += dx[direction];
            z += dz[direction];
            stepsTaken++;

            // Check if within radius
            if (Math.abs(x) <= radius && Math.abs(z) <= radius) {
                coords.add(new int[]{x, z});
            }

            // Check if we need to turn
            if (stepsTaken == stepsInDirection) {
                stepsTaken = 0;
                direction = (direction + 1) % 4;
                turnsAtCurrentLength++;

                // After 2 turns, increase step length
                if (turnsAtCurrentLength == 2) {
                    turnsAtCurrentLength = 0;
                    stepsInDirection++;
                }
            }

            // Safety check
            if (coords.size() > maxPositions * 2) break;
        }

        return coords;
    }

    /**
     * Get Y offsets for stack processing
     */
    private List<Integer> getYOffsets(int stackHeight, boolean bottomToTop) {
        List<Integer> offsets = new ArrayList<>();

        if (bottomToTop) {
            for (int i = 0; i < stackHeight; i++) {
                offsets.add(i);
            }
        } else {
            for (int i = stackHeight - 1; i >= 0; i--) {
                offsets.add(i);
            }
        }

        return offsets;
    }

    /**
     * Find the nearest network position to a given location
     */
    public int findNearestPosition(Location target, Location root, Map<Location, Integer> positions) {
        if (positions.containsKey(target)) {
            return positions.get(target);
        }

        // Find the closest position by distance
        int nearestPos = -1;
        double nearestDist = Double.MAX_VALUE;

        for (Map.Entry<Location, Integer> entry : positions.entrySet()) {
            double dist = entry.getKey().distanceSquared(target);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestPos = entry.getValue();
            }
        }

        return nearestPos;
    }

    /**
     * Calculate which position a new chest should get
     */
    public int calculateNextPosition(Location root, Set<Location> existingChests, Location newChest) {
        Set<Location> allChests = new HashSet<>(existingChests);
        allChests.add(newChest);

        Map<Location, Integer> positions = calculatePositions(root, allChests);
        return positions.getOrDefault(newChest, -1);
    }
}
