package de.hoarder.shelf;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages shelf blocks that display chest contents
 */
public class ShelfManager {

    private final JavaPlugin plugin;
    private final File dataFile;

    // Maps shelf location to the chest location it's connected to
    private final Map<Location, Location> shelfToChest = new ConcurrentHashMap<>();

    // All shelf material types
    private static final Set<Material> SHELF_MATERIALS = Set.of(
        Material.OAK_SHELF,
        Material.SPRUCE_SHELF,
        Material.BIRCH_SHELF,
        Material.JUNGLE_SHELF,
        Material.ACACIA_SHELF,
        Material.DARK_OAK_SHELF,
        Material.MANGROVE_SHELF,
        Material.CHERRY_SHELF,
        Material.PALE_OAK_SHELF,
        Material.BAMBOO_SHELF,
        Material.CRIMSON_SHELF,
        Material.WARPED_SHELF
    );

    public ShelfManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "shelves.yml");
    }

    /**
     * Load shelf-chest mappings from file
     */
    public void load() {
        shelfToChest.clear();

        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        List<Map<?, ?>> shelves = config.getMapList("shelves");

        for (Map<?, ?> entry : shelves) {
            try {
                String shelfWorld = (String) entry.get("shelf_world");
                int shelfX = ((Number) entry.get("shelf_x")).intValue();
                int shelfY = ((Number) entry.get("shelf_y")).intValue();
                int shelfZ = ((Number) entry.get("shelf_z")).intValue();

                String chestWorld = (String) entry.get("chest_world");
                int chestX = ((Number) entry.get("chest_x")).intValue();
                int chestY = ((Number) entry.get("chest_y")).intValue();
                int chestZ = ((Number) entry.get("chest_z")).intValue();

                World sWorld = Bukkit.getWorld(shelfWorld);
                World cWorld = Bukkit.getWorld(chestWorld);

                if (sWorld != null && cWorld != null) {
                    Location shelfLoc = new Location(sWorld, shelfX, shelfY, shelfZ);
                    Location chestLoc = new Location(cWorld, chestX, chestY, chestZ);
                    shelfToChest.put(shelfLoc, chestLoc);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load shelf entry: " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + shelfToChest.size() + " shelf-chest connections");
    }

    /**
     * Save shelf-chest mappings to file
     */
    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> shelves = new ArrayList<>();

        for (Map.Entry<Location, Location> entry : shelfToChest.entrySet()) {
            Location shelfLoc = entry.getKey();
            Location chestLoc = entry.getValue();

            if (shelfLoc.getWorld() == null || chestLoc.getWorld() == null) {
                continue;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("shelf_world", shelfLoc.getWorld().getName());
            data.put("shelf_x", shelfLoc.getBlockX());
            data.put("shelf_y", shelfLoc.getBlockY());
            data.put("shelf_z", shelfLoc.getBlockZ());
            data.put("chest_world", chestLoc.getWorld().getName());
            data.put("chest_x", chestLoc.getBlockX());
            data.put("chest_y", chestLoc.getBlockY());
            data.put("chest_z", chestLoc.getBlockZ());
            shelves.add(data);
        }

        config.set("shelves", shelves);

        try {
            plugin.getDataFolder().mkdirs();
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save shelf data: " + e.getMessage());
        }
    }

    /**
     * Check if a material is a shelf
     */
    public boolean isShelf(Material material) {
        return SHELF_MATERIALS.contains(material);
    }

    /**
     * Check if a block is a shelf
     */
    public boolean isShelf(Block block) {
        return isShelf(block.getType());
    }

    /**
     * Check if a material is a chest
     */
    public boolean isChest(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }

    /**
     * Check if a block is a chest
     */
    public boolean isChest(Block block) {
        return isChest(block.getType());
    }

    /**
     * Register a shelf as tracking a chest
     */
    public void registerShelf(Block shelf, Block chest) {
        shelfToChest.put(shelf.getLocation(), chest.getLocation());
        save();
        plugin.getLogger().info("Registered shelf at " + formatLocation(shelf.getLocation()) +
            " for chest at " + formatLocation(chest.getLocation()));
    }

    /**
     * Unregister a shelf
     */
    public void unregisterShelf(Location shelfLoc) {
        if (shelfToChest.remove(shelfLoc) != null) {
            save();
        }
    }

    /**
     * Check if a shelf is being tracked
     */
    public boolean isTracked(Location shelfLoc) {
        return shelfToChest.containsKey(shelfLoc);
    }

    /**
     * Get the chest location for a tracked shelf
     */
    public Location getChestLocation(Location shelfLoc) {
        return shelfToChest.get(shelfLoc);
    }

    /**
     * Get all tracked shelf locations
     */
    public Set<Location> getTrackedShelves() {
        return new HashSet<>(shelfToChest.keySet());
    }

    /**
     * Get all tracked chest locations (unique)
     */
    public Set<Location> getTrackedChests() {
        return new HashSet<>(shelfToChest.values());
    }

    /**
     * Get the number of tracked shelves
     */
    public int getTrackedCount() {
        return shelfToChest.size();
    }

    /**
     * Find an adjacent chest to a shelf (the chest behind the shelf based on facing)
     */
    public Block findChestBehindShelf(Block shelf) {
        if (shelf.getBlockData() instanceof Directional directional) {
            BlockFace facing = directional.getFacing();
            BlockFace behind = facing.getOppositeFace();
            Block behindBlock = shelf.getRelative(behind);

            if (isChest(behindBlock)) {
                return behindBlock;
            }
        }
        return null;
    }

    /**
     * Find any adjacent chest to a block
     */
    public Block findAdjacentChest(Block block) {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
        for (BlockFace face : faces) {
            Block adjacent = block.getRelative(face);
            if (isChest(adjacent)) {
                return adjacent;
            }
        }
        return null;
    }

    /**
     * Get the inventory from a chest block (handles double chests)
     */
    public Inventory getChestInventory(Block block) {
        BlockState state = block.getState();
        if (state instanceof Chest chest) {
            return chest.getInventory();
        }
        return null;
    }

    /**
     * Check if a chest location has a shelf tracking it
     */
    public boolean hasShelf(Location chestLoc) {
        return shelfToChest.containsValue(chestLoc);
    }

    /**
     * Get all shelves tracking a specific chest
     */
    public List<Location> getShelvesForChest(Location chestLoc) {
        List<Location> shelves = new ArrayList<>();
        for (Map.Entry<Location, Location> entry : shelfToChest.entrySet()) {
            if (entry.getValue().equals(chestLoc)) {
                shelves.add(entry.getKey());
            }
        }
        return shelves;
    }

    /**
     * Clean up shelves that no longer exist or whose chests are gone
     */
    public void cleanup() {
        boolean changed = false;
        Iterator<Map.Entry<Location, Location>> iterator = shelfToChest.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Location, Location> entry = iterator.next();
            Location shelfLoc = entry.getKey();
            Location chestLoc = entry.getValue();

            // Check if the shelf still exists
            if (!isShelf(shelfLoc.getBlock())) {
                iterator.remove();
                changed = true;
                continue;
            }

            // Check if the chest still exists
            if (!isChest(chestLoc.getBlock())) {
                iterator.remove();
                changed = true;
            }
        }

        if (changed) {
            save();
        }
    }

    /**
     * Format a location for logging
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
