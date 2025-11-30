package de.hoarder.commands;

import de.hoarder.HoarderPlugin;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkChest;
import de.hoarder.sorting.FullReorganizeTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command handler for /hoarder
 */
public class HoarderCommand implements CommandExecutor, TabCompleter {

    private final HoarderPlugin plugin;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "info", "setroot", "sort", "reload", "stats"
    );

    public HoarderCommand(HoarderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return showHelp(sender);
        }

        String subcommand = args[0].toLowerCase();

        return switch (subcommand) {
            case "info" -> showInfo(sender);
            case "setroot" -> setRoot(sender);
            case "sort" -> triggerSort(sender);
            case "reload" -> reload(sender);
            case "stats" -> showStats(sender);
            default -> showHelp(sender);
        };
    }

    /**
     * Show plugin help
     */
    private boolean showHelp(CommandSender sender) {
        sender.sendMessage("§6=== Hoarder - Intelligent Storage Network ===");
        sender.sendMessage("");
        sender.sendMessage("§eHow to use:");
        sender.sendMessage("§71. Place chests in your storage area");
        sender.sendMessage("§72. Sneak + place a shelf against each chest");
        sender.sendMessage("§73. Items are automatically sorted when you close a chest!");
        sender.sendMessage("");
        sender.sendMessage("§eCommands:");
        sender.sendMessage("§f/hoarder info §7- Show network information");
        sender.sendMessage("§f/hoarder setroot §7- Set network root (look at chest)");
        sender.sendMessage("§f/hoarder sort §7- Trigger full reorganization");
        sender.sendMessage("§f/hoarder stats §7- Show detailed statistics");
        sender.sendMessage("§f/hoarder reload §7- Reload configuration");
        return true;
    }

    /**
     * Show network info
     */
    private boolean showInfo(CommandSender sender) {
        int totalShelves = plugin.getShelfManager().getTrackedCount();
        int totalChests = plugin.getNetworkManager().getTotalChestCount();

        sender.sendMessage("§6=== Hoarder Network Info ===");
        sender.sendMessage("§7Tracked shelves: §f" + totalShelves);
        sender.sendMessage("§7Network chests: §f" + totalChests);

        if (sender instanceof Player player) {
            World world = player.getWorld();
            List<ChestNetwork> worldNetworks = plugin.getNetworkManager().getNetworks(world);

            if (!worldNetworks.isEmpty()) {
                sender.sendMessage("");
                sender.sendMessage("§eNetworks in §f" + world.getName() + "§e: §f" + worldNetworks.size());

                for (int i = 0; i < worldNetworks.size(); i++) {
                    ChestNetwork network = worldNetworks.get(i);
                    sender.sendMessage("§7  Network #" + (i + 1) + ":");
                    sender.sendMessage("§7    Root: §f" + formatLocation(network.getRoot()));
                    sender.sendMessage("§7    Chests: §f" + network.size());

                    var stats = network.getStats();
                    sender.sendMessage("§7    Total items: §f" + stats.totalItems());
                    sender.sendMessage("§7    Avg fill: §f" + String.format("%.1f%%", stats.averageFill() * 100));
                }
            } else {
                sender.sendMessage("");
                sender.sendMessage("§7No network in current world. Place shelves to create one!");
            }
        }

        return true;
    }

    /**
     * Set network root
     */
    private boolean setRoot(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("hoarder.admin")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        // Get block player is looking at
        Block target = player.getTargetBlockExact(5);

        if (target == null) {
            player.sendMessage("§cLook at a chest to set it as the network root.");
            return true;
        }

        if (!plugin.getShelfManager().isChest(target)) {
            player.sendMessage("§cYou must look at a chest to set it as root.");
            return true;
        }

        Location rootLoc = target.getLocation();
        plugin.getNetworkManager().setRoot(player.getWorld(), rootLoc);

        player.sendMessage("§a[Hoarder] §7Network root set to " + formatLocation(rootLoc));
        player.sendMessage("§7All positions will be calculated relative to this chest.");

        return true;
    }

    /**
     * Trigger full sort
     */
    private boolean triggerSort(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("hoarder.admin")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        // Find network near player
        ChestNetwork network = plugin.getNetworkManager().findNearbyNetwork(player.getLocation());

        if (network == null || network.isEmpty()) {
            // Try any network in this world
            List<ChestNetwork> worldNetworks = plugin.getNetworkManager().getNetworks(player.getWorld());
            if (!worldNetworks.isEmpty()) {
                network = worldNetworks.get(0);
            }
        }

        if (network == null || network.isEmpty()) {
            player.sendMessage("§cNo network found in this world.");
            return true;
        }

        final ChestNetwork targetNetwork = network;
        player.sendMessage("§e[Hoarder] §7Starting full reorganization of network at " + formatLocation(network.getRoot()) + "...");

        // Run on main thread for inventory access
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            FullReorganizeTask.trigger(plugin, targetNetwork);
            player.sendMessage("§a[Hoarder] §7Reorganization complete!");
        });

        return true;
    }

    /**
     * Reload configuration
     */
    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("hoarder.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        plugin.reload();
        sender.sendMessage("§a[Hoarder] §7Configuration reloaded!");

        return true;
    }

    /**
     * Show detailed stats
     */
    private boolean showStats(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        // Find network near player
        ChestNetwork network = plugin.getNetworkManager().findNearbyNetwork(player.getLocation());

        if (network == null || network.isEmpty()) {
            // Try any network in this world
            List<ChestNetwork> worldNetworks = plugin.getNetworkManager().getNetworks(player.getWorld());
            if (!worldNetworks.isEmpty()) {
                network = worldNetworks.get(0);
            }
        }

        if (network == null || network.isEmpty()) {
            player.sendMessage("§cNo network found in this world.");
            return true;
        }

        var stats = network.getStats();

        sender.sendMessage("§6=== Network Statistics ===");
        sender.sendMessage("§7Total chests: §f" + stats.totalChests());
        sender.sendMessage("§7Empty chests: §f" + stats.emptyChests());
        sender.sendMessage("§7Total items: §f" + formatNumber(stats.totalItems()));
        sender.sendMessage("§7Average fill: §f" + String.format("%.1f%%", stats.averageFill() * 100));
        sender.sendMessage("§7Categories: §f" + stats.categoriesUsed());
        sender.sendMessage("");

        // Show category breakdown
        sender.sendMessage("§eChest assignments:");
        int shown = 0;
        for (NetworkChest chest : network.getChestsInOrder()) {
            if (shown >= 10) {
                sender.sendMessage("§7  ... and " + (network.size() - shown) + " more");
                break;
            }

            String category = chest.getAssignedCategory();
            String categoryDisplay = category != null ?
                plugin.getItemHierarchy().getDisplayName(category) : "§8unassigned";

            sender.sendMessage("§7  #" + chest.getPosition() + ": §f" + categoryDisplay +
                " §7(" + String.format("%.0f%%", chest.getFillPercentage() * 100) + " full)");
            shown++;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(prefix))
                .toList();
        }
        return new ArrayList<>();
    }

    /**
     * Format location for display
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Format large numbers
     */
    private String formatNumber(int number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        }
        return String.valueOf(number);
    }
}
