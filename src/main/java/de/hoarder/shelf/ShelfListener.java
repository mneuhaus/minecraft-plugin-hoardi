package de.hoarder.shelf;

import de.hoarder.HoarderPlugin;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkManager;
import de.hoarder.network.NetworkChest;
import de.hoarder.sorting.FullReorganizeTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.block.Shelf;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;

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
                // A chest of somebody else's storage can't be pulled into your network by a shelf
                ChestNetwork current = networkManager.getNetworkForChest(chest.getLocation());
                if (current != null && !mayUse(player, current)) {
                    player.sendMessage("§c[Hoardi] §7This chest belongs to §e" + NetworkManager.ownerName(current)
                        + "§7's storage.");
                    return;
                }

                // Sneak + place = create preview shelf and add to network
                shelfManager.registerShelf(placed, chest);
                ChestNetwork network = networkManager.onShelfRegistered(placed.getLocation(), chest.getLocation(),
                    player.getUniqueId());
                if (network == null) {
                    return;
                }

                String materialName = formatMaterialName(network.getShelfMaterial());
                player.sendMessage("§a[Hoardi] §7" + formatMaterialName(placed.getType()) + " shelf registered!");
                if (network.size() == 1) {
                    player.sendMessage("§7This is the start of a new §e" + materialName + "§7 network.");
                } else {
                    player.sendMessage("§7Added to the §e" + materialName + "§7 network (" + network.size() + " chests).");
                }

                player.sendMessage("§7Click the shelf to open the chest. Use different shelf types for separate networks!");
            } else {
                player.sendMessage("§e[Hoardi] §7Tip: Sneak + place a shelf to add this chest to your storage network!");
            }
        }
    }

    /** Creepers and TNT destroy blocks without a BlockBreakEvent: clear the displays first, or they drop as real items. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        clearShelfDisplays(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        clearShelfDisplays(event.blockList());
    }

    private void clearShelfDisplays(java.util.List<Block> blocks) {
        for (Block block : blocks) {
            if (shelfManager.isShelf(block) && shelfManager.isTracked(block.getLocation())
                && block.getState() instanceof Shelf shelf) {
                shelf.getSnapshotInventory().clear();
                shelf.update(true, false);
            }
        }
    }

    /**
     * Handle shelf breaking - clear display items BEFORE the block breaks
     * This prevents the displayed items from dropping
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShelfBreakEarly(BlockBreakEvent event) {
        Block broken = event.getBlock();
        Location brokenLoc = broken.getLocation();

        // If a tracked shelf is being broken, clear its inventory first
        if (shelfManager.isShelf(broken) && shelfManager.isTracked(brokenLoc)) {
            if (broken.getState() instanceof Shelf shelf) {
                // Clear the shelf inventory so items don't drop
                shelf.getSnapshotInventory().clear();
                shelf.update(true, false);
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
            Material material = shelfManager.getShelfMaterial(brokenLoc);
            Location chestLoc = shelfManager.getChestLocation(brokenLoc);
            shelfManager.unregisterShelf(brokenLoc);

            // Notify network manager
            if (chestLoc != null) {
                networkManager.onShelfUnregistered(chestLoc);
            }

            String materialName = material != null ? formatMaterialName(material) : "Preview";
            event.getPlayer().sendMessage("§e[Hoardi] §7" + materialName + " shelf removed from network.");
            return;
        }

        // If a chest is broken, unregister all shelves connected to it
        if (shelfManager.isChest(broken)) {
            for (Location shelfLoc : shelfManager.getShelvesForChest(brokenLoc)) {
                // Clear shelf inventory before unregistering
                Block shelfBlock = shelfLoc.getBlock();
                if (shelfBlock.getState() instanceof Shelf shelf) {
                    shelf.getSnapshotInventory().clear();
                    shelf.update(true, false);
                }
                shelfManager.unregisterShelf(shelfLoc);
            }
            networkManager.onChestRemoved(brokenLoc);
        }
    }

    /**
     * Handle shelf interaction - pass through to chest or unload shulker
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

        // A claim or protection plugin refused the click: no way into the chest through the shelf either
        if (event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }

        // The shelf's items are only a preview: never let vanilla hand them out
        event.setCancelled(true);

        Player player = event.getPlayer();
        Location chestLoc = shelfManager.getChestLocation(shelfLoc);
        if (chestLoc == null) {
            return;
        }
        ChestNetwork network = networkManager.getNetworkForChest(chestLoc);
        if (network != null && !mayUse(player, network)) {
            player.sendMessage("§c[Hoardi] §7This storage belongs to §e" + NetworkManager.ownerName(network) + "§7.");
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (isShulkerBox(itemInHand.getType())) {
            unloadShulkerIntoNetwork(player, itemInHand, shelfLoc);
            return;
        }

        Block chestBlock = chestLoc.getBlock();
        if (chestBlock.getState() instanceof org.bukkit.block.Container container) {
            player.openInventory(container.getInventory());
        }
    }

    /**
     * Unload a shulker box into the network
     */
    private void unloadShulkerIntoNetwork(Player player, ItemStack shulkerItem, Location shelfLoc) {
        // Get the shulker's contents
        if (!(shulkerItem.getItemMeta() instanceof BlockStateMeta meta)) {
            player.sendMessage("§c[Hoardi] §7Could not read shulker contents.");
            return;
        }

        if (!(meta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            player.sendMessage("§c[Hoardi] §7Not a valid shulker box.");
            return;
        }

        Inventory shulkerInv = shulkerBox.getInventory();
        List<ItemStack> itemsToDistribute = new ArrayList<>();

        // Collect all items from shulker
        for (ItemStack item : shulkerInv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                itemsToDistribute.add(item.clone());
            }
        }

        if (itemsToDistribute.isEmpty()) {
            // Empty shulker - just open the chest behind the shelf
            Location chestLoc = shelfManager.getChestLocation(shelfLoc);
            if (chestLoc != null) {
                Block chestBlock = chestLoc.getBlock();
                if (chestBlock.getState() instanceof org.bukkit.block.Container container) {
                    player.openInventory(container.getInventory());
                }
            }
            return;
        }

        // Find the network for this shelf
        Material shelfMaterial = shelfManager.getShelfMaterial(shelfLoc);
        Location chestLoc = shelfManager.getChestLocation(shelfLoc);

        if (chestLoc == null || shelfMaterial == null) {
            player.sendMessage("§c[Hoardi] §7Shelf is not properly registered.");
            return;
        }

        ChestNetwork network = networkManager.getNetworkForChest(chestLoc, shelfMaterial);
        if (network == null) {
            player.sendMessage("§c[Hoardi] §7No network found for this shelf.");
            return;
        }

        // Distribute items into the network
        int totalItems = 0;
        int itemsPlaced = 0;
        List<ItemStack> overflow = new ArrayList<>();

        for (ItemStack item : itemsToDistribute) {
            totalItems += item.getAmount();
            ItemStack remaining = item.clone();

            // Try to place in network chests
            for (NetworkChest networkChest : network.getChestsInOrder()) {
                if (remaining == null || remaining.getAmount() <= 0) break;

                Inventory inv = networkChest.getInventory();
                if (inv == null) continue;

                var leftover = inv.addItem(remaining);
                if (leftover.isEmpty()) {
                    itemsPlaced += remaining.getAmount();
                    remaining = null;
                } else {
                    int placed = remaining.getAmount() - leftover.values().iterator().next().getAmount();
                    itemsPlaced += placed;
                    remaining = leftover.values().iterator().next();
                }
            }

            // Track overflow
            if (remaining != null && remaining.getAmount() > 0) {
                overflow.add(remaining);
            }
        }

        // Clear the shulker's contents and put the emptied box back into the hand:
        // the held item may be a copy, and a box that keeps its contents after
        // unloading would be an item duplicator.
        shulkerInv.clear();
        meta.setBlockState(shulkerBox);
        shulkerItem.setItemMeta(meta);
        player.getInventory().setItemInMainHand(shulkerItem);

        // Report results
        if (overflow.isEmpty()) {
            player.sendMessage("§a[Hoardi] §7Unloaded §f" + totalItems + "§7 items into the network!");
        } else {
            int overflowCount = overflow.stream().mapToInt(ItemStack::getAmount).sum();
            player.sendMessage("§e[Hoardi] §7Unloaded §f" + itemsPlaced + "§7 items. §c" + overflowCount + "§7 items didn't fit!");

            // Give overflow back to player or drop
            for (ItemStack item : overflow) {
                var notAdded = player.getInventory().addItem(item);
                for (ItemStack dropped : notAdded.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                }
            }
        }

        // Trigger a sort to organize the new items
        player.sendMessage("§7Sorting network...");
        FullReorganizeTask.trigger(plugin, network);
    }

    /**
     * Check if a material is a shulker box
     */
    private boolean isShulkerBox(Material material) {
        return material == Material.SHULKER_BOX
            || material == Material.WHITE_SHULKER_BOX
            || material == Material.ORANGE_SHULKER_BOX
            || material == Material.MAGENTA_SHULKER_BOX
            || material == Material.LIGHT_BLUE_SHULKER_BOX
            || material == Material.YELLOW_SHULKER_BOX
            || material == Material.LIME_SHULKER_BOX
            || material == Material.PINK_SHULKER_BOX
            || material == Material.GRAY_SHULKER_BOX
            || material == Material.LIGHT_GRAY_SHULKER_BOX
            || material == Material.CYAN_SHULKER_BOX
            || material == Material.PURPLE_SHULKER_BOX
            || material == Material.BLUE_SHULKER_BOX
            || material == Material.BROWN_SHULKER_BOX
            || material == Material.GREEN_SHULKER_BOX
            || material == Material.RED_SHULKER_BOX
            || material == Material.BLACK_SHULKER_BOX;
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

        // Double chests, then any other container block (chests, copper chests, barrels)
        if (holder instanceof DoubleChest doubleChest) {
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
        } else if (holder instanceof Container container) {
            chestLoc = container.getLocation();
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

        // A player looked inside: contents may have changed -> next full
        // reorganize cycle must pick this network up again.
        markNetworkDirty(chestLoc);

        // Trigger sort if enabled
        if (plugin.getHoarderConfig().isQuickSortOnClose()) {
            networkManager.scheduleQuickSort(chestLoc, player);
        }
    }

    /**
     * Hoppers (and droppers etc.) feed network chests without any player
     * interaction - mark the network dirty so those changes get sorted too.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(org.bukkit.event.inventory.InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getDestination().getHolder();
        if (holder instanceof Container container) {
            Location loc = container.getLocation();
            if (shelfManager.hasShelf(loc)) {
                markNetworkDirty(loc);
            }
        } else if (holder instanceof DoubleChest doubleChest) {
            // The shelf can hang on either half.
            for (InventoryHolder side : new InventoryHolder[]{doubleChest.getLeftSide(), doubleChest.getRightSide()}) {
                if (side instanceof Chest sideChest) {
                    Location loc = sideChest.getLocation();
                    if (shelfManager.hasShelf(loc)) {
                        markNetworkDirty(loc);
                        return;
                    }
                }
            }
        }
    }

    /** With network owners off everyone may; otherwise owner, trusted players and admins. */
    private boolean mayUse(Player player, ChestNetwork network) {
        return !plugin.getHoarderConfig().isNetworkOwners() || network.mayUse(player.getUniqueId())
            || player.hasPermission("hoarder.admin");
    }

    private void markNetworkDirty(Location chestLoc) {
        de.hoarder.network.ChestNetwork network = networkManager.getNetworkForChest(chestLoc);
        if (network != null) {
            network.markDirty();
        }
    }

    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Format material name for display (e.g., OAK_SHELF -> Oak)
     */
    private String formatMaterialName(Material material) {
        String name = material.name();
        if (name.endsWith("_SHELF")) {
            name = name.substring(0, name.length() - 6);
        }
        String[] parts = name.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)))
                      .append(part.substring(1))
                      .append(" ");
            }
        }
        return result.toString().trim();
    }
}
