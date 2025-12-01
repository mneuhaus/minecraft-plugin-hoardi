package de.hoarder.shelf;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages shelf blocks that display chest contents.
 * Tracks shelf material type for material-based network separation.
 */
public class ShelfManager {

    private final JavaPlugin plugin;
    private final File dataFile;

    // Maps shelf location to shelf data (chest location + material)
    private final Map<Location, ShelfData> shelfData = new ConcurrentHashMap<>();

    /**
     * Data class holding shelf information
     */
    public static class ShelfData {
        private final Location chestLocation;
        private final Material shelfMaterial;

        public ShelfData(Location chestLocation, Material shelfMaterial) {
            this.chestLocation = chestLocation;
            this.shelfMaterial = shelfMaterial;
        }

        public Location getChestLocation() {
            return chestLocation;
        }

        public Material getShelfMaterial() {
            return shelfMaterial;
        }
    }

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
        shelfData.clear();

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

                // Load shelf material (default to OAK_SHELF for old configs)
                String materialStr = (String) entry.get("shelf_material");
                Material shelfMaterial = Material.OAK_SHELF;
                if (materialStr != null) {
                    try {
                        shelfMaterial = Material.valueOf(materialStr);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Unknown shelf material: " + materialStr + ", defaulting to OAK_SHELF");
                    }
                }

                World sWorld = Bukkit.getWorld(shelfWorld);
                World cWorld = Bukkit.getWorld(chestWorld);

                if (sWorld != null && cWorld != null) {
                    Location shelfLoc = new Location(sWorld, shelfX, shelfY, shelfZ);
                    Location chestLoc = new Location(cWorld, chestX, chestY, chestZ);
                    shelfData.put(shelfLoc, new ShelfData(chestLoc, shelfMaterial));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load shelf entry: " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + shelfData.size() + " shelf-chest connections");
    }

    /**
     * Save shelf-chest mappings to file
     */
    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> shelves = new ArrayList<>();

        for (Map.Entry<Location, ShelfData> entry : shelfData.entrySet()) {
            Location shelfLoc = entry.getKey();
            ShelfData data = entry.getValue();
            Location chestLoc = data.getChestLocation();

            if (shelfLoc.getWorld() == null || chestLoc.getWorld() == null) {
                continue;
            }

            Map<String, Object> shelfEntry = new HashMap<>();
            shelfEntry.put("shelf_world", shelfLoc.getWorld().getName());
            shelfEntry.put("shelf_x", shelfLoc.getBlockX());
            shelfEntry.put("shelf_y", shelfLoc.getBlockY());
            shelfEntry.put("shelf_z", shelfLoc.getBlockZ());
            shelfEntry.put("chest_world", chestLoc.getWorld().getName());
            shelfEntry.put("chest_x", chestLoc.getBlockX());
            shelfEntry.put("chest_y", chestLoc.getBlockY());
            shelfEntry.put("chest_z", chestLoc.getBlockZ());
            shelfEntry.put("shelf_material", data.getShelfMaterial().name());
            shelves.add(shelfEntry);
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
     * Check if a material is a chest/barrel/container
     */
    public boolean isChest(Material material) {
        return material == Material.CHEST
            || material == Material.TRAPPED_CHEST
            || material == Material.BARREL
            || material == Material.COPPER_CHEST;
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
        Material shelfMaterial = shelf.getType();
        shelfData.put(shelf.getLocation(), new ShelfData(chest.getLocation(), shelfMaterial));
        save();
        plugin.getLogger().info("Registered " + shelfMaterial.name() + " shelf at " + formatLocation(shelf.getLocation()) +
            " for chest at " + formatLocation(chest.getLocation()));
    }

    /**
     * Unregister a shelf
     */
    public void unregisterShelf(Location shelfLoc) {
        if (shelfData.remove(shelfLoc) != null) {
            save();
        }
    }

    /**
     * Check if a shelf is being tracked
     */
    public boolean isTracked(Location shelfLoc) {
        return shelfData.containsKey(shelfLoc);
    }

    /**
     * Get the chest location for a tracked shelf
     */
    public Location getChestLocation(Location shelfLoc) {
        ShelfData data = shelfData.get(shelfLoc);
        return data != null ? data.getChestLocation() : null;
    }

    /**
     * Get the shelf material for a tracked shelf
     */
    public Material getShelfMaterial(Location shelfLoc) {
        ShelfData data = shelfData.get(shelfLoc);
        return data != null ? data.getShelfMaterial() : null;
    }

    /**
     * Get all tracked shelf locations
     */
    public Set<Location> getTrackedShelves() {
        return new HashSet<>(shelfData.keySet());
    }

    /**
     * Get all tracked shelf locations of a specific material
     */
    public Set<Location> getTrackedShelvesByMaterial(Material material) {
        Set<Location> result = new HashSet<>();
        for (Map.Entry<Location, ShelfData> entry : shelfData.entrySet()) {
            if (entry.getValue().getShelfMaterial() == material) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Get all tracked chest locations (unique)
     */
    public Set<Location> getTrackedChests() {
        Set<Location> chests = new HashSet<>();
        for (ShelfData data : shelfData.values()) {
            chests.add(data.getChestLocation());
        }
        return chests;
    }

    /**
     * Get the number of tracked shelves
     */
    public int getTrackedCount() {
        return shelfData.size();
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
     * Get the inventory from a container block (handles chests, barrels, copper chests)
     */
    public Inventory getChestInventory(Block block) {
        BlockState state = block.getState();
        if (state instanceof Container container) {
            return container.getInventory();
        }
        return null;
    }

    /**
     * Check if a chest location has a shelf tracking it
     */
    public boolean hasShelf(Location chestLoc) {
        for (ShelfData data : shelfData.values()) {
            if (data.getChestLocation().equals(chestLoc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all shelves tracking a specific chest
     */
    public List<Location> getShelvesForChest(Location chestLoc) {
        List<Location> shelves = new ArrayList<>();
        for (Map.Entry<Location, ShelfData> entry : shelfData.entrySet()) {
            if (entry.getValue().getChestLocation().equals(chestLoc)) {
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
        Iterator<Map.Entry<Location, ShelfData>> iterator = shelfData.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Location, ShelfData> entry = iterator.next();
            Location shelfLoc = entry.getKey();
            Location chestLoc = entry.getValue().getChestLocation();

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
