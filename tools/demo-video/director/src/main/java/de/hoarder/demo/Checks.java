package de.hoarder.demo;

import de.hoarder.HoarderPlugin;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkManager;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Behaviour checks for the smoke test and local runs, printed as "PASS ..." / "FAIL ..." lines.
 * {@code demo checkowners} needs no player (network owners through the API, persistence, owners off);
 * {@code demo checkplayer} needs the HoardiCam client online and the setup room built (barrel close,
 * a protection plugin refusing a shelf click, a stranger's shelf click).
 */
final class Checks implements Listener {

    private static final UUID A = UUID.nameUUIDFromBytes("hoardi-check-A".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("hoardi-check-B".getBytes());
    private static final int CX = 1000, CY = -60, CZ = 1300;

    private final Plugin plugin;
    private final HoarderPlugin hoardi;
    private final World world;
    private boolean denyShelfClicks;

    Checks(Plugin plugin, HoarderPlugin hoardi, World world) {
        this.plugin = plugin;
        this.hoardi = hoardi;
        this.world = world;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Stands in for a claim plugin: refuses shelf clicks while a check asks for it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (denyShelfClicks && event.getClickedBlock() != null && hoardi.getShelfManager().isShelf(event.getClickedBlock())) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    private static void report(CommandSender sender, boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        sender.sendMessage(line);
        Bukkit.getLogger().info("[HoardiDemo] " + line);
    }

    /** Chest i of the check row with an Oak shelf in front (south side), not registered yet. */
    private Block[] pair(int i) {
        Block chest = world.getBlockAt(CX + 2 * i, CY, CZ);
        Block shelf = chest.getRelative(BlockFace.SOUTH);
        chest.setType(Material.CHEST, false);
        shelf.setType(Material.OAK_SHELF, false);
        Directional data = (Directional) shelf.getBlockData();
        data.setFacing(BlockFace.SOUTH);
        shelf.setBlockData(data, false);
        return new Block[]{chest, shelf};
    }

    private ChestNetwork register(Block[] pair, UUID player) {
        hoardi.getShelfManager().registerShelf(pair[1], pair[0]);
        return hoardi.getNetworkManager().onShelfRegistered(pair[1].getLocation(), pair[0].getLocation(), player);
    }

    private void clear() {
        NetworkManager nm = hoardi.getNetworkManager();
        for (int i = 0; i < 5; i++) {
            Block chest = world.getBlockAt(CX + 2 * i, CY, CZ);
            Block shelf = chest.getRelative(BlockFace.SOUTH);
            if (hoardi.getShelfManager().isTracked(shelf.getLocation())) {
                hoardi.getShelfManager().unregisterShelf(shelf.getLocation());
                nm.onShelfUnregistered(chest.getLocation());
            }
            for (Block b : new Block[]{chest, shelf}) {
                if (b.getState() instanceof TileStateInventoryHolder h) h.getInventory().clear();
                b.setType(Material.AIR, false);
            }
        }
    }

    private void setOwners(boolean on) {
        hoardi.getConfig().set("settings.network_owners", on);
        hoardi.saveConfig();
        hoardi.getHoarderConfig().load();
    }

    void checkOwners(CommandSender sender) {
        world.getChunkAt(CX >> 4, CZ >> 4).load();
        clear();
        setOwners(true);
        NetworkManager nm = hoardi.getNetworkManager();
        try {
            ChestNetwork n1 = register(pair(0), A);
            report(sender, n1 != null && A.equals(n1.getOwner()), "first shelf makes its player the network owner");

            ChestNetwork n2 = register(pair(1), B);
            report(sender, n2 != null && n2 != n1 && B.equals(n2.getOwner()),
                "a stranger's shelf next to it starts the stranger's own network");

            n1.trust(B);
            ChestNetwork n3 = register(pair(2), B);
            report(sender, n3 == n1, "a trusted player's shelf joins the owner's network");

            // a second shelf for a chest that already has a network: still one network
            ChestNetwork again = hoardi.getNetworkManager().onShelfRegistered(
                world.getBlockAt(CX, CY, CZ).getRelative(BlockFace.SOUTH).getLocation(), world.getBlockAt(CX, CY, CZ).getLocation(), B);
            long holders = nm.getAllNetworks().stream().filter(n -> n.containsChest(world.getBlockAt(CX, CY, CZ).getLocation())).count();
            report(sender, again == n1 && holders == 1, "a chest stays in exactly one network");

            nm.save();
            nm.load();
            ChestNetwork r1 = nm.getNetworkForChest(world.getBlockAt(CX, CY, CZ).getLocation());
            ChestNetwork r2 = nm.getNetworkForChest(world.getBlockAt(CX + 2, CY, CZ).getLocation());
            report(sender, r1 != null && A.equals(r1.getOwner()) && r1.getTrusted().contains(B) && r1.size() == 2
                && r2 != null && B.equals(r2.getOwner()), "owner and trusted players survive save + load");

            ChestNetwork hall = nm.getNetworkForChest(world.getBlockAt(HoardiDemo.OX, HoardiDemo.OY, HoardiDemo.OZ - 4).getLocation());
            report(sender, hall == null || hall.getOwner() == null, "networks registered without a player stay shared");

            setOwners(false);
            ChestNetwork n4 = register(pair(3), B);
            report(sender, n4 == r1 || n4 == r2, "with network_owners: false a shelf joins the nearby network");
        } catch (RuntimeException e) {
            report(sender, false, "owner checks threw " + e);
        } finally {
            setOwners(true);
            clear();
        }
    }

    void checkPlayer(CommandSender sender, Player cam, SetupScene setup) {
        NetworkManager nm = hoardi.getNetworkManager();
        Block barrel = setup.block(5, 0, -1);
        Block barrelShelf = setup.block(5, 1, -1);
        ChestNetwork barrels = nm.getNetworkForChest(barrel.getLocation());
        if (barrels == null || !(barrel.getState() instanceof TileStateInventoryHolder holder)) {
            report(sender, false, "setup room missing (run demo setupbuild first)");
            return;
        }
        cam.closeInventory();

        // 1. closing a barrel triggers the sort like a chest does
        barrels.clearDirty();
        cam.openInventory(holder.getInventory());
        cam.closeInventory();
        boolean markedOnClose = barrels.isDirty();
        later(30, () -> report(sender, markedOnClose && !barrels.isDirty(),
            "closing a barrel marks its network and sorts it (dirty after close: " + markedOnClose + ")"));

        // 2. a claim plugin refusing the shelf click keeps the chest closed
        later(40, () -> {
            denyShelfClicks = true;
            click(cam, barrelShelf);
            boolean opened = cam.getOpenInventory().getTopInventory().getType() == InventoryType.BARREL;
            denyShelfClicks = false;
            cam.closeInventory();
            report(sender, !opened, "a refused shelf click does not open the chest behind it");

            click(cam, barrelShelf);
            boolean openedAllowed = cam.getOpenInventory().getTopInventory().getType() == InventoryType.BARREL;
            cam.closeInventory();
            report(sender, openedAllowed, "an allowed shelf click opens the chest behind it");

            // 3. a stranger (not owner, not trusted, no admin) can't open an owned network through its shelf
            UUID before = barrels.getOwner();
            barrels.setOwner(A);
            boolean admin = cam.hasPermission("hoarder.admin");
            click(cam, barrelShelf);
            boolean strangerOpened = cam.getOpenInventory().getTopInventory().getType() == InventoryType.BARREL;
            cam.closeInventory();
            barrels.setOwner(before);
            report(sender, admin ? strangerOpened : !strangerOpened, admin
                ? "an admin may still open an owned network through its shelf"
                : "a stranger can't open an owned network through its shelf");
        });
    }

    private void click(Player player, Block shelf) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Bukkit.getPluginManager().callEvent(
            new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, hand, shelf, BlockFace.WEST, EquipmentSlot.HAND));
    }

    private void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
    }
}
