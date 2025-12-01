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
 * Template is loaded from plugins/Hoardi/export.json or bundled default.
 */
public class TestWarehouseBuilder {

    private final HoarderPlugin plugin;

    public TestWarehouseBuilder(HoarderPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Build the test warehouse at the player's position.
     */
    public boolean build(Player player) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();

        if (world == null) {
            player.sendMessage("§c[Hoardi] §7Cannot build: invalid world.");
            return false;
        }

        // Load template from JSON
        List<ExportedBlock> blocks = loadTemplate();
        if (blocks == null || blocks.isEmpty()) {
            player.sendMessage("§c[Hoardi] §7No template found. Export a warehouse first with /hoardi export");
            return false;
        }

        ShelfManager shelfManager = plugin.getShelfManager();
        int playerX = playerLoc.getBlockX();
        int playerY = playerLoc.getBlockY();
        int playerZ = playerLoc.getBlockZ();

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
                // Identify standalone chest at relative position (5, 6, -13) - upper floor
                if (blockData.x == 5 && blockData.y == 6 && blockData.z == -13) {
                    standaloneChestLoc = loc;
                }
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

        // Fill standalone chest with shulker boxes
        int shulkersAdded = 0;
        if (standaloneChestLoc != null) {
            shulkersAdded = fillChestWithShulkers(standaloneChestLoc, 10);
        }

        // Get network count for this world
        int networkCount = plugin.getNetworkManager().getNetworks(world).size();

        player.sendMessage("§a[Hoardi] §7Test warehouse built!");
        player.sendMessage("§7- §f" + blocksPlaced + "§7 blocks placed");
        player.sendMessage("§7- §f" + shelvesRegistered + "§7 shelves registered");
        player.sendMessage("§7- §f" + networkCount + "§7 network(s) created");
        player.sendMessage("§7- §f" + networkChests.size() + "§7 chests with §f" + formatNumber(itemsAdded) + "§7 random items");
        if (shulkersAdded > 0) {
            player.sendMessage("§7- §f" + shulkersAdded + "§7 filled shulker boxes in standalone chest");
        }

        return true;
    }

    /**
     * Load template from export.json file.
     */
    private List<ExportedBlock> loadTemplate() {
        File exportFile = new File(plugin.getDataFolder(), "export.json");
        Gson gson = new Gson();
        Type listType = new TypeToken<List<ExportedBlock>>(){}.getType();

        // Try plugin data folder first
        if (exportFile.exists()) {
            try (FileReader reader = new FileReader(exportFile)) {
                return gson.fromJson(reader, listType);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to read export.json: " + e.getMessage());
            }
        }

        // Try bundled resource as fallback
        try (InputStream is = plugin.getResource("export.json")) {
            if (is != null) {
                try (InputStreamReader reader = new InputStreamReader(is)) {
                    return gson.fromJson(reader, listType);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read bundled export.json: " + e.getMessage());
        }

        return null;
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
        int targetItems = (int) (totalSlots * 64 * 0.3); // 30% capacity

        // Pick 2x as many item types as shelves
        int typeCount = shelfCount * 2;
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
     * Fill a chest with shulker boxes, each fully filled with a single item type.
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
