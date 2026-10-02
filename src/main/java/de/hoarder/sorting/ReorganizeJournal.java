package de.hoarder.sorting;

import de.hoarder.HoarderPlugin;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkChest;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Crash safety for the full reorganize: before any chest is emptied, the
 * complete network inventory is written to a journal file. Once distribution
 * has finished, the journal is deleted. If the server dies in between, the
 * journal survives and is restored into the network on the next startup -
 * without it, a crash mid-reorganize would silently erase every item that
 * was held in memory at that moment.
 */
public final class ReorganizeJournal {

    private static final String DIR_NAME = "journal";

    private ReorganizeJournal() {
    }

    private static File journalDir(HoarderPlugin plugin) {
        return new File(plugin.getDataFolder(), DIR_NAME);
    }

    private static File journalFile(HoarderPlugin plugin, ChestNetwork network) {
        Location root = network.getRoot();
        String name = String.format("reorg-%s_%d_%d_%d.yml",
            network.getWorld().getName(), root.getBlockX(), root.getBlockY(), root.getBlockZ());
        return new File(journalDir(plugin), name);
    }

    /**
     * Write all items to the journal. Returns the file on success, null on
     * failure - in which case the caller must NOT clear any chests.
     */
    public static File write(HoarderPlugin plugin, ChestNetwork network,
                             Map<String, List<ItemStack>> itemsByCategory) {
        File dir = journalDir(plugin);
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create journal directory, skipping reorganize for safety.");
            return null;
        }

        List<ItemStack> all = new ArrayList<>();
        for (List<ItemStack> list : itemsByCategory.values()) {
            all.addAll(list);
        }

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("world", network.getWorld().getName());
        Location root = network.getRoot();
        yml.set("root", root.getBlockX() + "," + root.getBlockY() + "," + root.getBlockZ());
        yml.set("items", all);

        File file = journalFile(plugin, network);
        try {
            yml.save(file);
            return file;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not write reorganize journal (" + e.getMessage()
                + "), skipping reorganize for safety.");
            return null;
        }
    }

    /**
     * Delete the journal after a completed reorganize. With settings.debug the
     * file is kept as "<name>.done" instead - useful to inspect the on-disk
     * format and to test the restore path with real data.
     */
    public static void clear(HoarderPlugin plugin, File journal) {
        if (journal == null || !journal.exists()) {
            return;
        }
        if (plugin.getHoarderConfig().isDebug()) {
            File done = new File(journal.getParentFile(), journal.getName() + ".done");
            if (journal.renameTo(done)) {
                return;
            }
        }
        if (!journal.delete()) {
            journal.deleteOnExit();
        }
    }

    /**
     * Restore any journals left behind by a crash. Called once on startup
     * after networks have been loaded. Items are stuffed back into the
     * journal's network chests; anything that does not fit is dropped at the
     * root chest so nothing is ever silently lost.
     */
    public static void restoreAll(HoarderPlugin plugin) {
        File dir = journalDir(plugin);
        File[] files = dir.listFiles((d, n) -> n.startsWith("reorg-") && n.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return;
        }

        // Defer to the first server tick: worlds and schedulers are fully up,
        // so chest block states (and thus inventories) are safely accessible.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (File file : files) {
                try {
                    restoreOne(plugin, file);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to restore reorganize journal " + file.getName()
                        + ": " + e.getMessage() + " - file kept for manual recovery.");
                }
            }
        });
    }

    private static void restoreOne(HoarderPlugin plugin, File file) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String worldName = yml.getString("world", "");
        World world = plugin.getServer().getWorld(worldName);

        if (world == null) {
            plugin.getLogger().severe("Journal " + file.getName() + " references unknown world '"
                + worldName + "' - file kept for manual recovery.");
            return;
        }

        // Only accept entries that deserialized into real, non-empty items.
        // Anything else (nulls, AIR from a broken entry) must not be counted
        // as "restored" - if present, keep the file for manual recovery.
        List<?> raw = yml.getList("items", new ArrayList<>());
        List<ItemStack> items = new ArrayList<>();
        int broken = 0;
        for (Object entry : raw) {
            if (entry instanceof ItemStack stack && !stack.getType().isAir() && stack.getAmount() > 0) {
                items.add(stack);
            } else {
                broken++;
            }
        }
        if (broken > 0) {
            plugin.getLogger().severe("Journal " + file.getName() + ": " + broken + " of " + raw.size()
                + " entries could not be read as items - file kept for manual recovery.");
            return;
        }

        plugin.getLogger().warning("Found reorganize journal " + file.getName()
            + " from an interrupted run - restoring " + items.size() + " stacks.");

        String[] parts = yml.getString("root", "0,0,0").split(",");
        Location root = new Location(world,
            Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));

        ChestNetwork network = plugin.getNetworkManager().getNetworkForChest(root);

        List<ItemStack> leftover = new ArrayList<>(items);
        if (network != null) {
            leftover = stuffIntoNetwork(network, leftover);
            network.markDirty();
        }

        // Whatever did not fit (or no network found): drop at the root so the
        // items are physically in the world instead of gone.
        for (ItemStack item : leftover) {
            world.dropItemNaturally(root.clone().add(0.5, 1, 0.5), item);
        }
        if (!leftover.isEmpty()) {
            plugin.getLogger().warning("  " + leftover.size()
                + " stacks did not fit and were dropped at " + worldName + " ("
                + root.getBlockX() + ", " + root.getBlockY() + ", " + root.getBlockZ() + ").");
        }

        if (!file.delete()) {
            file.deleteOnExit();
        }
        plugin.getLogger().info("Journal " + file.getName() + " restored.");
    }

    private static List<ItemStack> stuffIntoNetwork(ChestNetwork network, List<ItemStack> items) {
        List<ItemStack> leftover = new ArrayList<>();
        List<NetworkChest> chests = network.getChestsInOrder();

        for (ItemStack item : items) {
            ItemStack remaining = item;
            for (NetworkChest chest : chests) {
                Inventory inv = chest.getInventory();
                if (inv == null) continue;
                Map<Integer, ItemStack> rest = inv.addItem(remaining);
                if (rest.isEmpty()) {
                    remaining = null;
                    break;
                }
                remaining = rest.values().iterator().next();
            }
            if (remaining != null) {
                leftover.add(remaining);
            }
        }
        return leftover;
    }
}
