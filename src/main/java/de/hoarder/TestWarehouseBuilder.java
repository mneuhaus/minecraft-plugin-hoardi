package de.hoarder;

import de.hoarder.shelf.ShelfManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;

/**
 * Builds a test warehouse for development and testing purposes.
 *
 * Layout (side view, player looking at the row):
 *
 *     [Shelf][Chest]    [Shelf][Chest]    [Shelf][Chest]  ...
 *     [Shelf][Chest]    [Shelf][Chest]    [Shelf][Chest]  ...
 *     [Shelf][Chest]    [Shelf][Chest]    [Shelf][Chest]  ...
 *     [Shelf][Chest]    [Shelf][Chest]    [Shelf][Chest]  ...
 *
 * Each column is 4 chests high with shelves in front.
 * Two rows facing each other with a 4-block pathway between.
 */
public class TestWarehouseBuilder {

    private final HoarderPlugin plugin;

    // Warehouse dimensions
    private static final int COLUMNS_PER_ROW = 6;     // 6 columns per row
    private static final int CHESTS_HIGH = 4;         // 4 chests stacked vertically
    private static final int PATHWAY_WIDTH = 4;       // 4 blocks between rows

    public TestWarehouseBuilder(HoarderPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Build a test warehouse in front of the player.
     *
     * @param player The player to build in front of
     * @return true if successful, false if failed
     */
    public boolean build(Player player) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();

        if (world == null) {
            player.sendMessage("§c[Hoarder] §7Cannot build: invalid world.");
            return false;
        }

        ShelfManager shelfManager = plugin.getShelfManager();

        // Get the player's facing direction
        BlockFace facing = getCardinalDirection(player);
        BlockFace left = getLeftDirection(facing);
        BlockFace right = getRightDirection(facing);

        // Start position: 2 blocks in front of player, at player's feet level
        Location start = playerLoc.getBlock().getRelative(facing, 2).getLocation();

        player.sendMessage("§a[Hoarder] §7Building test warehouse...");

        int chestsPlaced = 0;
        int shelvesPlaced = 0;

        // === NEAR ROW (closer to player) ===
        // Shelves face toward player, chests behind them
        Location nearRowStart = new Location(world,
            start.getBlockX() + (left.getModX() * (COLUMNS_PER_ROW / 2)),
            start.getBlockY(),
            start.getBlockZ() + (left.getModZ() * (COLUMNS_PER_ROW / 2)));

        for (int col = 0; col < COLUMNS_PER_ROW; col++) {
            for (int height = 0; height < CHESTS_HIGH; height++) {
                // Shelf position (front, facing player)
                Location shelfLoc = nearRowStart.clone().add(
                    right.getModX() * col,
                    height,
                    right.getModZ() * col
                );

                // Chest position (behind shelf)
                Location chestLoc = shelfLoc.clone().add(
                    facing.getModX(),
                    0,
                    facing.getModZ()
                );

                // Place chest first
                if (placeChest(chestLoc, facing.getOppositeFace())) {
                    chestsPlaced++;

                    // Place shelf in front, facing toward player (opposite of facing)
                    if (placeShelf(shelfLoc, facing.getOppositeFace(), Material.OAK_SHELF, shelfManager)) {
                        shelvesPlaced++;
                        // Register the shelf
                        shelfManager.registerShelf(
                            shelfLoc.getBlock(),
                            chestLoc.getBlock()
                        );
                    }
                }
            }
        }

        // === FAR ROW (further from player, across the pathway) ===
        // Shelves face away from player (toward the pathway), chests behind them
        Location farRowStart = new Location(world,
            start.getBlockX() + (facing.getModX() * (PATHWAY_WIDTH + 2)) + (left.getModX() * (COLUMNS_PER_ROW / 2)),
            start.getBlockY(),
            start.getBlockZ() + (facing.getModZ() * (PATHWAY_WIDTH + 2)) + (left.getModZ() * (COLUMNS_PER_ROW / 2)));

        for (int col = 0; col < COLUMNS_PER_ROW; col++) {
            for (int height = 0; height < CHESTS_HIGH; height++) {
                // Shelf position (front, facing the pathway/player)
                Location shelfLoc = farRowStart.clone().add(
                    right.getModX() * col,
                    height,
                    right.getModZ() * col
                );

                // Chest position (behind shelf, further away)
                Location chestLoc = shelfLoc.clone().add(
                    facing.getModX(),
                    0,
                    facing.getModZ()
                );

                // Place chest first
                if (placeChest(chestLoc, facing)) {
                    chestsPlaced++;

                    // Place shelf in front, facing toward player/pathway
                    if (placeShelf(shelfLoc, facing, Material.BIRCH_SHELF, shelfManager)) {
                        shelvesPlaced++;
                        // Register the shelf
                        shelfManager.registerShelf(
                            shelfLoc.getBlock(),
                            chestLoc.getBlock()
                        );
                    }
                }
            }
        }

        player.sendMessage("§a[Hoarder] §7Test warehouse built!");
        player.sendMessage("§7- §f" + chestsPlaced + "§7 chests placed (" + COLUMNS_PER_ROW + " columns x " + CHESTS_HIGH + " high x 2 rows)");
        player.sendMessage("§7- §f" + shelvesPlaced + "§7 shelves placed and registered");
        player.sendMessage("§7- §eOak shelves§7 on near row, §eBirch shelves§7 on far row");
        player.sendMessage("§7- §f" + PATHWAY_WIDTH + "§7 block pathway between rows");

        return true;
    }

    /**
     * Place a single chest block facing a direction.
     */
    private boolean placeChest(Location loc, BlockFace facing) {
        Block block = loc.getBlock();

        // Clear the space
        block.setType(Material.CHEST);

        // Set facing direction
        if (block.getBlockData() instanceof Directional directional) {
            directional.setFacing(facing);
            block.setBlockData(directional);
        }

        return block.getType() == Material.CHEST;
    }

    /**
     * Place a shelf block facing a direction.
     */
    private boolean placeShelf(Location loc, BlockFace facing, Material shelfMaterial, ShelfManager shelfManager) {
        Block block = loc.getBlock();

        // Place the shelf
        block.setType(shelfMaterial);

        // Set facing direction (shelf faces outward, toward the player)
        if (block.getBlockData() instanceof Directional directional) {
            directional.setFacing(facing);
            block.setBlockData(directional);
        }

        return shelfManager.isShelf(block);
    }

    /**
     * Get the cardinal direction the player is facing.
     */
    private BlockFace getCardinalDirection(Player player) {
        float yaw = player.getLocation().getYaw();
        // Normalize yaw to 0-360
        yaw = (yaw % 360 + 360) % 360;

        if (yaw >= 315 || yaw < 45) {
            return BlockFace.SOUTH;
        } else if (yaw >= 45 && yaw < 135) {
            return BlockFace.WEST;
        } else if (yaw >= 135 && yaw < 225) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.EAST;
        }
    }

    /**
     * Get the direction to the left of the given facing.
     */
    private BlockFace getLeftDirection(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> BlockFace.WEST;
        };
    }

    /**
     * Get the direction to the right of the given facing.
     */
    private BlockFace getRightDirection(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case EAST -> BlockFace.SOUTH;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }
}
