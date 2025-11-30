package de.hoarder.shelf;

import de.hoarder.HoarderPlugin;
import de.hoarder.network.NetworkManager;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Handles events for shelf-chest connections and triggers sorting
 */
public class ShelfListener implements Listener {

    private final HoarderPlugin plugin;
    private final ShelfManager shelfManager;
    private final NetworkManager networkManager;

    public ShelfListener(HoarderPlugin plugin, ShelfManager shelfManager, NetworkManager networkManager) {
        this.plugin = plugin;
        this.shelfManager = shelfManager;
        this.networkManager = networkManager;
    }

    /**
     * Handle shelf placement - auto-register if placed adjacent to a chest
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();

        if (!shelfManager.isShelf(placed)) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("hoarder.use")) {
            return;
        }

        // Check if the shelf is placed in front of a chest
        Block chest = shelfManager.findChestBehindShelf(placed);

        if (chest != null) {
            if (player.isSneaking()) {
                // Sneak + place = create preview shelf and add to network
                shelfManager.registerShelf(placed, chest);

                // Check if this chest should be added to a network
                networkManager.onShelfRegistered(chest.getLocation());

                player.sendMessage("§a[Hoarder] §7Shelf registered! It will display the top 3 items from the chest.");
                player.sendMessage("§7Click the shelf to open the chest. Items will be auto-sorted!");
            } else {
                player.sendMessage("§e[Hoarder] §7Tip: Sneak + place a shelf to add this chest to your storage network!");
            }
        }
    }

    /**
     * Handle shelf/chest breaking - unregister connections
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        Location brokenLoc = broken.getLocation();

        // If a tracked shelf is broken, unregister it
        if (shelfManager.isShelf(broken) && shelfManager.isTracked(brokenLoc)) {
            Location chestLoc = shelfManager.getChestLocation(brokenLoc);
            shelfManager.unregisterShelf(brokenLoc);

            // Notify network manager
            if (chestLoc != null) {
                networkManager.onShelfUnregistered(chestLoc);
            }

            event.getPlayer().sendMessage("§e[Hoarder] §7Shelf removed from network.");
            return;
        }

        // If a chest is broken, unregister all shelves connected to it
        if (shelfManager.isChest(broken)) {
            for (Location shelfLoc : shelfManager.getShelvesForChest(brokenLoc)) {
                shelfManager.unregisterShelf(shelfLoc);
            }
            networkManager.onChestRemoved(brokenLoc);
        }
    }

    /**
     * Handle shelf interaction - pass through to chest
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // Only handle main hand to prevent double-firing
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        if (!shelfManager.isShelf(clicked)) {
            return;
        }

        Location shelfLoc = clicked.getLocation();
        if (!shelfManager.isTracked(shelfLoc)) {
            return;
        }

        // This is a tracked preview shelf - cancel normal interaction and open chest
        event.setCancelled(true);

        Player player = event.getPlayer();
        Location chestLoc = shelfManager.getChestLocation(shelfLoc);

        if (chestLoc == null) {
            return;
        }

        Block chestBlock = chestLoc.getBlock();
        if (chestBlock.getState() instanceof Chest chest) {
            player.openInventory(chest.getInventory());
        }
    }

    /**
     * Handle inventory close - trigger sorting if it's a network chest
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();

        Location chestLoc = null;

        // Handle both single chest and double chest
        if (holder instanceof Chest chest) {
            chestLoc = chest.getLocation();
        } else if (holder instanceof DoubleChest doubleChest) {
            // For double chests, get the left side location
            InventoryHolder leftSide = doubleChest.getLeftSide();
            InventoryHolder rightSide = doubleChest.getRightSide();

            // Try left side first, then right side
            if (leftSide instanceof Chest leftChest) {
                chestLoc = leftChest.getLocation();

                // Check if left has shelf, if not try right
                if (!shelfManager.hasShelf(chestLoc) && rightSide instanceof Chest rightChest) {
                    Location rightLoc = rightChest.getLocation();
                    if (shelfManager.hasShelf(rightLoc)) {
                        chestLoc = rightLoc;
                    }
                }
            } else if (rightSide instanceof Chest rightChest) {
                chestLoc = rightChest.getLocation();
            }
        } else {
            return;
        }

        if (chestLoc == null) {
            return;
        }

        // Check if this chest is part of the network (has a shelf)
        if (!shelfManager.hasShelf(chestLoc)) {
            return;
        }

        // Trigger sort if enabled
        if (plugin.getHoarderConfig().isQuickSortOnClose()) {
            networkManager.scheduleQuickSort(chestLoc, player);
        }
    }

    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
