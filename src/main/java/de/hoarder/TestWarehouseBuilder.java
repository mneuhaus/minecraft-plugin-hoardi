package de.hoarder;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.hoarder.shelf.ShelfManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Builds a test warehouse from an exported JSON template.
 * Templates are loaded from plugins/Hoardi/templates/{name}.json or bundled default.
 */
public class TestWarehouseBuilder {

    private final HoarderPlugin plugin;
    private static final String DEFAULT_TEMPLATE = "default";

    public TestWarehouseBuilder(HoarderPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Build the test warehouse at the player's position using the default template.
     */
    public boolean build(Player player) {
        return build(player, DEFAULT_TEMPLATE);
    }

    /**
     * Build the test warehouse at the player's position using a named template.
     */
    public boolean build(Player player, String templateName) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();

        if (world == null) {
            player.sendMessage("§c[Hoardi] §7Cannot build: invalid world.");
            return false;
        }

        // Load template from JSON
        List<ExportedBlock> blocks = loadTemplate(templateName);
        if (blocks == null || blocks.isEmpty()) {
            player.sendMessage("§c[Hoardi] §7No template '§f" + templateName + "§7' found.");
            player.sendMessage("§7Export one first with: §f/hoardi export " + templateName);
            player.sendMessage("§7Available templates: §f" + String.join(", ", listTemplates()));
            return false;
        }

        ShelfManager shelfManager = plugin.getShelfManager();
        int playerX = playerLoc.getBlockX();
        int playerY = playerLoc.getBlockY();
        int playerZ = playerLoc.getBlockZ();

        // TABULA RASA: Clear the area first
        player.sendMessage("§a[Hoardi] §7Clearing area (tabula rasa)...");
        int clearedBlocks = clearArea(world, blocks, playerX, playerY, playerZ, 8);
        player.sendMessage("§7- §f" + clearedBlocks + "§7 blocks cleared");

        // Clear old network/shelf data for this area
        clearNetworkData(world, blocks, playerX, playerY, playerZ, 8);

        player.sendMessage("§a[Hoardi] §7Building test warehouse...");

        int blocksPlaced = 0;
        int shelvesRegistered = 0;
        List<Location> chestLocations = new ArrayList<>();
        List<Block> shelfBlocks = new ArrayList<>();
        Location standaloneChestLoc = null; // Track the standalone chest (no shelf)

        // PASS 1: Place all blocks first
        for (ExportedBlock blockData : blocks) {
            Location loc = new Location(world, playerX + blockData.x, playerY + blockData.y, playerZ + blockData.z);
            Block block = loc.getBlock();

            Material material = Material.getMaterial(blockData.material);
            if (material == null) {
                plugin.getLogger().warning("Unknown material: " + blockData.material);
                continue;
            }

            block.setType(material);

            // Apply facing direction if provided
            if (blockData.facing != null) {
                try {
                    BlockFace facing = BlockFace.valueOf(blockData.facing);
                    if (block.getBlockData() instanceof Directional directional) {
                        directional.setFacing(facing);
                        block.setBlockData(directional);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown facing: " + blockData.facing);
                }
            }

            // Apply stairs half if provided
            if (blockData.stairHalf != null) {
                try {
                    Stairs.Half half = Stairs.Half.valueOf(blockData.stairHalf);
                    if (block.getBlockData() instanceof Stairs stairs) {
                        stairs.setHalf(half);
                        block.setBlockData(stairs);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown stair half: " + blockData.stairHalf);
                }
            }

            // Apply chest type (LEFT/RIGHT/SINGLE) for double chests
            if (blockData.chestType != null) {
                try {
                    Chest.Type type = Chest.Type.valueOf(blockData.chestType);
                    if (block.getBlockData() instanceof Chest chest) {
                        chest.setType(type);
                        block.setBlockData(chest);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown chest type: " + blockData.chestType);
                }
            }

            blocksPlaced++;

            // Track chest locations for filling
            if (shelfManager.isChest(block)) {
                chestLocations.add(loc);
            }

            // Track ender chest location - will be replaced with regular chest for shulker boxes
            if (material == Material.ENDER_CHEST) {
                // Replace ender chest with regular chest
                block.setType(Material.CHEST);
                standaloneChestLoc = loc;
            }

            // Track shelf blocks for registration in pass 2
            if (shelfManager.isShelf(block)) {
                shelfBlocks.add(block);
            }
        }

        // PASS 2: Register all shelves (now that all chests exist)
        for (Block shelfBlock : shelfBlocks) {
            Block chestBlock = findAdjacentChest(shelfBlock);
            if (chestBlock != null) {
                shelfManager.registerShelf(shelfBlock, chestBlock);
                // Also register with network manager to create/update networks
                plugin.getNetworkManager().onShelfRegistered(shelfBlock.getLocation(), chestBlock.getLocation());
                shelvesRegistered++;
            }
        }

        // Exclude standalone chest from normal filling
        List<Location> networkChests = new ArrayList<>(chestLocations);
        if (standaloneChestLoc != null) {
            networkChests.remove(standaloneChestLoc);
        }

        // Fill network chests with random items (~30% capacity, 2x shelf count item types)
        int itemsAdded = fillChestsRandom(networkChests, shelvesRegistered);

        // Fill the shulker chest (ender chest replaced with regular chest) with shulker boxes
        int shulkersAdded = 0;
        if (standaloneChestLoc != null) {
            shulkersAdded = fillChestWithShulkers(standaloneChestLoc, 27);
        }

        // Get network count for this world
        int networkCount = plugin.getNetworkManager().getNetworks(world).size();

        player.sendMessage("§a[Hoardi] §7Test warehouse built!");
        player.sendMessage("§7- §f" + blocksPlaced + "§7 blocks placed");
        player.sendMessage("§7- §f" + shelvesRegistered + "§7 shelves registered");
        player.sendMessage("§7- §f" + networkCount + "§7 network(s) created");
        player.sendMessage("§7- §f" + networkChests.size() + "§7 chests with §f" + formatNumber(itemsAdded) + "§7 random items");
        if (shulkersAdded > 0) {
            player.sendMessage("§7- §f" + shulkersAdded + "§7 filled shulker boxes in shulker chest");
        }

        return true;
    }

    /**
     * Clear the area before building - tabula rasa.
     * Sets everything to air except the floor (grass_block at player Y-1).
     *
     * @param padding Extra blocks around the template bounds to clear
     * @return Number of blocks cleared
     */
    private int clearArea(World world, List<ExportedBlock> blocks, int playerX, int playerY, int playerZ, int padding) {
        // Calculate bounds from template
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (ExportedBlock block : blocks) {
            minX = Math.min(minX, block.x);
            maxX = Math.max(maxX, block.x);
            minY = Math.min(minY, block.y);
            maxY = Math.max(maxY, block.y);
            minZ = Math.min(minZ, block.z);
            maxZ = Math.max(maxZ, block.z);
        }

        // Add padding
        minX -= padding;
        maxX += padding;
        minY -= 1; // Include one below for floor
        maxY += padding;
        minZ -= padding;
        maxZ += padding;

        int cleared = 0;

        // Clear all blocks in the area
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int worldX = playerX + x;
                    int worldY = playerY + y;
                    int worldZ = playerZ + z;

                    Block block = world.getBlockAt(worldX, worldY, worldZ);

                    // Floor level (y == -1 relative to player): set to grass
                    if (y == -1) {
                        if (block.getType() != Material.GRASS_BLOCK) {
                            block.setType(Material.GRASS_BLOCK);
                            cleared++;
                        }
                    } else {
                        // Everything else: set to air
                        if (block.getType() != Material.AIR) {
                            block.setType(Material.AIR);
                            cleared++;
                        }
                    }
                }
            }
        }

        return cleared;
    }

    /**
     * Clear old network and shelf data for the area.
     */
    private void clearNetworkData(World world, List<ExportedBlock> blocks, int playerX, int playerY, int playerZ, int padding) {
        ShelfManager shelfManager = plugin.getShelfManager();

        // Calculate bounds
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (ExportedBlock block : blocks) {
            minX = Math.min(minX, block.x);
            maxX = Math.max(maxX, block.x);
            minY = Math.min(minY, block.y);
            maxY = Math.max(maxY, block.y);
            minZ = Math.min(minZ, block.z);
            maxZ = Math.max(maxZ, block.z);
        }

        // Add padding
        minX -= padding;
        maxX += padding;
        minY -= 1;
        maxY += padding;
        minZ -= padding;
        maxZ += padding;

        // Unregister all shelves in the area
        List<Location> shelvesToRemove = new ArrayList<>();
        for (Location shelfLoc : shelfManager.getTrackedShelves()) {
            if (shelfLoc.getWorld() != world) continue;

            int relX = shelfLoc.getBlockX() - playerX;
            int relY = shelfLoc.getBlockY() - playerY;
            int relZ = shelfLoc.getBlockZ() - playerZ;

            if (relX >= minX && relX <= maxX &&
                relY >= minY && relY <= maxY &&
                relZ >= minZ && relZ <= maxZ) {
                shelvesToRemove.add(shelfLoc);
            }
        }

        for (Location loc : shelvesToRemove) {
            Location chestLoc = shelfManager.getChestLocation(loc);
            shelfManager.unregisterShelf(loc);
            if (chestLoc != null) {
                plugin.getNetworkManager().onShelfUnregistered(chestLoc);
            }
        }

        plugin.getLogger().info("[Hoardi] Cleared " + shelvesToRemove.size() + " shelf registrations from area");
    }

    /**
     * Load template from templates/{name}.json file.
     */
    private List<ExportedBlock> loadTemplate(String templateName) {
        File templatesDir = new File(plugin.getDataFolder(), "templates");
        File templateFile = new File(templatesDir, templateName + ".json");
        Gson gson = new Gson();
        Type listType = new TypeToken<List<ExportedBlock>>(){}.getType();

        // Try templates folder first
        if (templateFile.exists()) {
            try (FileReader reader = new FileReader(templateFile)) {
                return gson.fromJson(reader, listType);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to read template " + templateName + ": " + e.getMessage());
            }
        }

        // Try legacy export.json location for backwards compatibility (only for "default")
        if (templateName.equals(DEFAULT_TEMPLATE)) {
            File legacyFile = new File(plugin.getDataFolder(), "export.json");
            if (legacyFile.exists()) {
                try (FileReader reader = new FileReader(legacyFile)) {
                    return gson.fromJson(reader, listType);
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to read legacy export.json: " + e.getMessage());
                }
            }
        }

        // Try bundled resource as fallback
        String resourcePath = "templates/" + templateName + ".json";
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is != null) {
                try (InputStreamReader reader = new InputStreamReader(is)) {
                    return gson.fromJson(reader, listType);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read bundled template " + templateName + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * List all available template names.
     */
    public List<String> listTemplates() {
        List<String> templates = new ArrayList<>();

        // Check templates folder
        File templatesDir = new File(plugin.getDataFolder(), "templates");
        if (templatesDir.exists() && templatesDir.isDirectory()) {
            File[] files = templatesDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    templates.add(name.substring(0, name.length() - 5)); // Remove .json
                }
            }
        }

        // Check legacy export.json
        File legacyFile = new File(plugin.getDataFolder(), "export.json");
        if (legacyFile.exists() && !templates.contains(DEFAULT_TEMPLATE)) {
            templates.add(DEFAULT_TEMPLATE);
        }

        if (templates.isEmpty()) {
            templates.add("(none)");
        }

        return templates;
    }

    /**
     * Fill chests with random items (~30% of total capacity).
     */
    private int fillChestsRandom(List<Location> chestLocations, int shelfCount) {
        if (chestLocations.isEmpty()) {
            return 0;
        }

        int chestCount = chestLocations.size();
        int totalSlots = chestCount * 27;
        int targetItems = (int) (totalSlots * 64 * 0.4); // 40% capacity

        // Pick 1.2x as many item types as shelves
        int typeCount = (int) (shelfCount * 1.2);
        List<Material> itemTypes = getRandomItemTypes(typeCount);

        Random random = new Random();
        List<ItemStack> itemsToDistribute = new ArrayList<>();
        int remainingItems = targetItems;

        // Create item stacks
        for (int i = 0; i < itemTypes.size() && remainingItems > 0; i++) {
            Material mat = itemTypes.get(i);
            int maxStack = mat.getMaxStackSize();

            int maxForType = Math.min(remainingItems, maxStack * 10);
            int amount = random.nextInt(maxForType) + maxStack;
            amount = Math.min(amount, remainingItems);

            while (amount > 0) {
                int stackSize = Math.min(amount, maxStack);
                itemsToDistribute.add(new ItemStack(mat, stackSize));
                amount -= stackSize;
                remainingItems -= stackSize;
            }
        }

        Collections.shuffle(itemsToDistribute, random);

        // Distribute to chests
        int itemIndex = 0;
        int totalAdded = 0;

        for (Location loc : chestLocations) {
            Block block = loc.getBlock();
            if (block.getState() instanceof Container container) {
                Inventory inv = container.getInventory();

                int slotsToFill = random.nextInt(inv.getSize());
                for (int i = 0; i < slotsToFill && itemIndex < itemsToDistribute.size(); i++) {
                    ItemStack item = itemsToDistribute.get(itemIndex++);
                    inv.addItem(item);
                    totalAdded += item.getAmount();
                }
            }
        }

        return totalAdded;
    }

    /**
     * Spawn filled shulker boxes as dropped items near a location (for ender chest).
     */
    private int spawnShulkerBoxes(Location location, int count) {
        World world = location.getWorld();
        if (world == null) return 0;

        // Spawn location slightly above the ender chest
        Location spawnLoc = location.clone().add(0.5, 1.5, 0.5);

        // Get random item types for each shulker
        List<Material> itemTypes = getRandomItemTypes(count);

        // Array of shulker box colors
        Material[] shulkerColors = {
            Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX,
            Material.SHULKER_BOX
        };

        Random random = new Random();
        int shulkersAdded = 0;

        for (int i = 0; i < count && i < itemTypes.size(); i++) {
            Material shulkerMat = shulkerColors[random.nextInt(shulkerColors.length)];
            ItemStack shulkerItem = new ItemStack(shulkerMat);

            // Get the shulker's block state meta to add items inside
            if (shulkerItem.getItemMeta() instanceof BlockStateMeta meta) {
                if (meta.getBlockState() instanceof ShulkerBox shulker) {
                    Inventory shulkerInv = shulker.getInventory();
                    Material fillMat = itemTypes.get(i);
                    int maxStack = fillMat.getMaxStackSize();

                    // Fill 50-80% of the shulker
                    int slotsToFill = 14 + random.nextInt(10);
                    for (int slot = 0; slot < slotsToFill; slot++) {
                        shulkerInv.setItem(slot, new ItemStack(fillMat, maxStack));
                    }

                    meta.setBlockState(shulker);
                    shulkerItem.setItemMeta(meta);
                }
            }

            // Drop the shulker as an item entity
            world.dropItemNaturally(spawnLoc, shulkerItem);
            shulkersAdded++;
        }

        return shulkersAdded;
    }

    /**
     * Fill a regular chest with shulker boxes.
     */
    private int fillChestWithShulkers(Location chestLoc, int count) {
        Block block = chestLoc.getBlock();
        if (!(block.getState() instanceof Container container)) {
            return 0;
        }

        Inventory chestInv = container.getInventory();
        int maxShulkers = Math.min(count, chestInv.getSize());

        // Get random item types for each shulker
        List<Material> itemTypes = getRandomItemTypes(maxShulkers);

        // Array of shulker box colors
        Material[] shulkerColors = {
            Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX,
            Material.SHULKER_BOX
        };

        Random random = new Random();
        int shulkersAdded = 0;

        for (int i = 0; i < maxShulkers && i < itemTypes.size(); i++) {
            // Pick a random shulker color
            Material shulkerMat = shulkerColors[random.nextInt(shulkerColors.length)];

            // Create the shulker box item
            ItemStack shulkerItem = new ItemStack(shulkerMat, 1);

            if (shulkerItem.getItemMeta() instanceof BlockStateMeta meta) {
                if (meta.getBlockState() instanceof ShulkerBox shulkerBox) {
                    Inventory shulkerInv = shulkerBox.getInventory();

                    // Fill all 27 slots with stacks of the chosen item type
                    Material fillMaterial = itemTypes.get(i);
                    int maxStackSize = fillMaterial.getMaxStackSize();

                    for (int slot = 0; slot < 27; slot++) {
                        shulkerInv.setItem(slot, new ItemStack(fillMaterial, maxStackSize));
                    }

                    // Apply the modified shulker state back to the item
                    meta.setBlockState(shulkerBox);
                    shulkerItem.setItemMeta(meta);

                    // Add to chest
                    chestInv.addItem(shulkerItem);
                    shulkersAdded++;
                }
            }
        }

        return shulkersAdded;
    }

    /**
     * Get random stackable item types.
     */
    private List<Material> getRandomItemTypes(int count) {
        List<Material> candidates = new ArrayList<>();

        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir() && mat.getMaxStackSize() > 1) {
                String name = mat.name();
                if (name.contains("SPAWN_EGG") || name.contains("POTION") ||
                    name.contains("BANNER_PATTERN") || name.contains("MUSIC_DISC") ||
                    name.contains("COMMAND") || name.contains("STRUCTURE") ||
                    name.contains("BARRIER") || name.contains("LIGHT") ||
                    name.contains("DEBUG") || name.contains("JIGSAW")) {
                    continue;
                }
                candidates.add(mat);
            }
        }

        Collections.shuffle(candidates, new Random());
        return candidates.subList(0, Math.min(count, candidates.size()));
    }

    /**
     * Find an adjacent chest/barrel block for shelf registration.
     */
    private Block findAdjacentChest(Block shelfBlock) {
        ShelfManager shelfManager = plugin.getShelfManager();

        if (shelfBlock.getBlockData() instanceof Directional directional) {
            BlockFace facing = directional.getFacing();
            Block behind = shelfBlock.getRelative(facing.getOppositeFace());
            if (shelfManager.isChest(behind)) {
                return behind;
            }
        }

        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block adjacent = shelfBlock.getRelative(face);
            if (shelfManager.isChest(adjacent)) {
                return adjacent;
            }
        }

        return null;
    }

    /**
     * Format large numbers.
     */
    private String formatNumber(int number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        }
        return String.valueOf(number);
    }

    /**
     * Record for JSON deserialization.
     */
    private static class ExportedBlock {
        int x;
        int y;
        int z;
        String material;
        String facing;
        String stairHalf;
        String chestType;
    }
}
