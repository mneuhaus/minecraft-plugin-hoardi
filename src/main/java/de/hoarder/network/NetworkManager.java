package de.hoarder.network;

import de.hoarder.HoarderPlugin;
import de.hoarder.config.HoarderConfig;
import de.hoarder.shelf.ShelfManager;
import de.hoarder.sorting.FullReorganizeTask;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Manages all chest networks across worlds.
 * Networks are separated by shelf material type and proximity clustering.
 */
public class NetworkManager {

    private final HoarderPlugin plugin;
    private final ShelfManager shelfManager;
    private final HoarderConfig config;
    private final File dataFile;

    // Multiple networks per world (world name -> list of networks)
    private final Map<String, List<ChestNetwork>> networks = new HashMap<>();

    // Pending sort tasks (chest location -> scheduled task)
    private final Map<Location, Integer> pendingSorts = new HashMap<>();

    public NetworkManager(HoarderPlugin plugin, ShelfManager shelfManager, HoarderConfig config) {
        this.plugin = plugin;
        this.shelfManager = shelfManager;
        this.config = config;
        this.dataFile = new File(plugin.getDataFolder(), "networks.yml");
    }

    /**
     * Load networks from file
     */
    public void load() {
        networks.clear();

        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection networksSection = yaml.getConfigurationSection("networks");

        if (networksSection == null) {
            return;
        }

        for (String worldName : networksSection.getKeys(false)) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Unknown world in networks.yml: " + worldName);
                continue;
            }

            ConfigurationSection worldSection = networksSection.getConfigurationSection(worldName);
            if (worldSection == null) continue;

            // Load all networks for this world
            List<Map<?, ?>> networksList = worldSection.getMapList("network_list");

            if (networksList.isEmpty()) {
                // Legacy format: single network
                ConfigurationSection rootSection = worldSection.getConfigurationSection("root");
                if (rootSection == null) continue;

                Location root = new Location(
                    world,
                    rootSection.getInt("x"),
                    rootSection.getInt("y"),
                    rootSection.getInt("z")
                );

                ChestNetwork network = new ChestNetwork(world, root, config);

                // Load chests
                List<Map<?, ?>> chestsList = worldSection.getMapList("chests");
                for (Map<?, ?> chestData : chestsList) {
                    try {
                        int x = ((Number) chestData.get("x")).intValue();
                        int y = ((Number) chestData.get("y")).intValue();
                        int z = ((Number) chestData.get("z")).intValue();
                        String category = (String) chestData.get("category");

                        Location chestLoc = new Location(world, x, y, z);
                        network.addChest(chestLoc);

                        if (category != null && !category.isEmpty()) {
                            network.assignCategory(chestLoc, category);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to load chest: " + e.getMessage());
                    }
                }

                networks.computeIfAbsent(worldName, k -> new ArrayList<>()).add(network);
                plugin.getLogger().info("Loaded legacy network in " + worldName + " with " + network.size() + " chests");
            } else {
                // New format: multiple networks
                for (Map<?, ?> networkData : networksList) {
                    try {
                        int rootX = ((Number) networkData.get("root_x")).intValue();
                        int rootY = ((Number) networkData.get("root_y")).intValue();
                        int rootZ = ((Number) networkData.get("root_z")).intValue();

                        // Load shelf material (default to OAK_SHELF for old configs)
                        String materialStr = (String) networkData.get("shelf_material");
                        Material shelfMaterial = Material.OAK_SHELF;
                        if (materialStr != null) {
                            try {
                                shelfMaterial = Material.valueOf(materialStr);
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Unknown shelf material: " + materialStr + ", defaulting to OAK_SHELF");
                            }
                        }

                        Location root = new Location(world, rootX, rootY, rootZ);
                        ChestNetwork network = new ChestNetwork(world, root, shelfMaterial, config);
                        if (networkData.get("owner") instanceof String owner) {
                            network.setOwner(UUID.fromString(owner));
                        }
                        if (networkData.get("trusted") instanceof List<?> trusted) {
                            for (Object uuid : trusted) {
                                network.trust(UUID.fromString(uuid.toString()));
                            }
                        }

                        @SuppressWarnings("unchecked")
                        List<Map<?, ?>> chestsList = (List<Map<?, ?>>) networkData.get("chests");
                        if (chestsList != null) {
                            for (Map<?, ?> chestData : chestsList) {
                                try {
                                    int x = ((Number) chestData.get("x")).intValue();
                                    int y = ((Number) chestData.get("y")).intValue();
                                    int z = ((Number) chestData.get("z")).intValue();
                                    String category = (String) chestData.get("category");

                                    Location chestLoc = new Location(world, x, y, z);
                                    network.addChest(chestLoc);

                                    if (category != null && !category.isEmpty()) {
                                        network.assignCategory(chestLoc, category);
                                    }
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed to load chest: " + e.getMessage());
                                }
                            }
                        }

                        networks.computeIfAbsent(worldName, k -> new ArrayList<>()).add(network);
                        String materialName = formatMaterialName(shelfMaterial);
                        plugin.getLogger().info("Loaded " + materialName + " network in " + worldName + " at " + formatLocation(root) + " with " + network.size() + " chests");
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to load network: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Save networks to file
     */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();

        for (Map.Entry<String, List<ChestNetwork>> entry : networks.entrySet()) {
            String worldName = entry.getKey();
            List<ChestNetwork> worldNetworks = entry.getValue();

            String basePath = "networks." + worldName;

            // Save all networks for this world
            List<Map<String, Object>> networksList = new ArrayList<>();
            for (ChestNetwork network : worldNetworks) {
                Map<String, Object> networkData = new HashMap<>();

                // Save root
                Location root = network.getRoot();
                networkData.put("root_x", root.getBlockX());
                networkData.put("root_y", root.getBlockY());
                networkData.put("root_z", root.getBlockZ());

                // Save shelf material
                networkData.put("shelf_material", network.getShelfMaterial().name());
                if (network.getOwner() != null) {
                    networkData.put("owner", network.getOwner().toString());
                }
                if (!network.getTrusted().isEmpty()) {
                    networkData.put("trusted", network.getTrusted().stream().map(UUID::toString).toList());
                }

                // Save chests
                List<Map<String, Object>> chestsList = new ArrayList<>();
                for (NetworkChest chest : network.getChestsInOrder()) {
                    Map<String, Object> chestData = new HashMap<>();
                    chestData.put("x", chest.getLocation().getBlockX());
                    chestData.put("y", chest.getLocation().getBlockY());
                    chestData.put("z", chest.getLocation().getBlockZ());
                    if (chest.hasAssignedCategory()) {
                        chestData.put("category", chest.getAssignedCategory());
                    }
                    chestsList.add(chestData);
                }
                networkData.put("chests", chestsList);
                networksList.add(networkData);
            }
            yaml.set(basePath + ".network_list", networksList);
        }

        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save networks: " + e.getMessage());
        }
    }

    /**
     * Check if two locations are within a certain radius (XZ distance)
     */
    private boolean isWithinRadius(Location a, Location b, int radius) {
        if (a.getWorld() != b.getWorld()) return false;
        double distXZ = Math.sqrt(
            Math.pow(a.getBlockX() - b.getBlockX(), 2) +
            Math.pow(a.getBlockZ() - b.getBlockZ(), 2)
        );
        return distXZ <= radius;
    }

    /**
     * Get all networks for a world
     */
    public List<ChestNetwork> getNetworks(World world) {
        return networks.getOrDefault(world.getName(), new ArrayList<>());
    }

    /**
     * Get network containing a chest location
     */
    public ChestNetwork getNetworkForChest(Location chestLoc) {
        if (chestLoc.getWorld() == null) return null;
        List<ChestNetwork> worldNetworks = networks.get(chestLoc.getWorld().getName());
        if (worldNetworks == null) return null;

        for (ChestNetwork network : worldNetworks) {
            if (network.containsChest(chestLoc)) {
                return network;
            }
        }
        return null;
    }

    /**
     * Get network containing a chest location with specific shelf material
     */
    public ChestNetwork getNetworkForChest(Location chestLoc, Material shelfMaterial) {
        if (chestLoc.getWorld() == null) return null;
        List<ChestNetwork> worldNetworks = networks.get(chestLoc.getWorld().getName());
        if (worldNetworks == null) return null;

        for (ChestNetwork network : worldNetworks) {
            if (network.getShelfMaterial() == shelfMaterial && network.containsChest(chestLoc)) {
                return network;
            }
        }

        // Fallback: try finding by material only within radius
        return findNearbyNetwork(chestLoc, shelfMaterial);
    }

    /** Any network near a location, whatever its wood (commands that act on "the network here"). */
    public ChestNetwork findNearbyNetwork(Location loc) {
        return findNearbyNetwork(loc, null, null);
    }

    /**
     * Find nearby network for a location with specific material type (within network_radius)
     * If material is null, returns any nearby network (legacy behavior)
     */
    public ChestNetwork findNearbyNetwork(Location loc, Material shelfMaterial) {
        return findNearbyNetwork(loc, shelfMaterial, null);
    }

    /**
     * Like {@link #findNearbyNetwork(Location, Material)}, but with network owners on only networks
     * the player may use count, so a stranger's shelf next to your storage starts its own network.
     */
    public ChestNetwork findNearbyNetwork(Location loc, Material shelfMaterial, UUID player) {
        if (loc.getWorld() == null) return null;
        List<ChestNetwork> worldNetworks = networks.get(loc.getWorld().getName());
        if (worldNetworks == null) return null;

        int maxRadius = config.getNetworkRadius();
        for (ChestNetwork network : worldNetworks) {
            // Skip networks with different material (unless material is null for legacy compatibility)
            if (shelfMaterial != null && network.getShelfMaterial() != shelfMaterial) {
                continue;
            }
            if (player != null && config.isNetworkOwners() && !network.mayUse(player)) {
                continue;
            }

            if (isWithinRadius(loc, network.getRoot(), maxRadius)) {
                return network;
            }
            // Also check if near any chest in the network
            for (Location chestLoc : network.getChestLocations()) {
                if (isWithinRadius(loc, chestLoc, maxRadius)) {
                    return network;
                }
            }
        }
        return null;
    }

    /** Registers a chest for a shelf placed without a player (API, test builders): no owner. */
    public ChestNetwork onShelfRegistered(Location shelfLoc, Location chestLoc) {
        return onShelfRegistered(shelfLoc, chestLoc, null);
    }

    /**
     * Called when a shelf is registered for a chest: the chest joins a nearby network of the same
     * wood that the player may use, or starts a new one (owned by the player with network owners on).
     * A chest belongs to one network only: a further shelf on it (other side, on top, other wood)
     * just shows its contents. Callers check {@link ChestNetwork#mayUse} for that case first.
     */
    public ChestNetwork onShelfRegistered(Location shelfLoc, Location chestLoc, UUID player) {
        World world = chestLoc.getWorld();
        if (world == null) return null;

        ChestNetwork existing = getNetworkForChest(chestLoc);
        if (existing != null) {
            return existing;
        }

        Material shelfMaterial = shelfLoc != null ? shelfManager.getShelfMaterial(shelfLoc) : null;
        if (shelfMaterial == null) {
            shelfMaterial = Material.OAK_SHELF;
        }

        ChestNetwork network = findNearbyNetwork(chestLoc, shelfMaterial, player);
        if (network == null) {
            network = new ChestNetwork(world, chestLoc, shelfMaterial, config);
            if (player != null && config.isNetworkOwners()) {
                network.setOwner(player);
            }
            networks.computeIfAbsent(world.getName(), k -> new ArrayList<>()).add(network);
            plugin.getLogger().info("Created new " + formatMaterialName(shelfMaterial) + " network in "
                + world.getName() + " at " + formatLocation(chestLoc));
        }

        network.addChest(chestLoc);
        network.markDirty();
        save();
        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Added chest to " + formatMaterialName(shelfMaterial) + " network: " + formatLocation(chestLoc));
        }
        return network;
    }

    /**
     * Called when a shelf is unregistered
     */
    public void onShelfUnregistered(Location chestLoc) {
        // a double chest can carry two shelves: the chest stays in while one is left
        if (shelfManager.hasShelf(chestLoc)) {
            return;
        }
        ChestNetwork network = getNetworkForChest(chestLoc);
        if (network != null) {
            network.removeChest(chestLoc);
            network.markDirty();

            // Remove empty networks
            if (network.isEmpty()) {
                removeNetwork(network);
                plugin.getLogger().info("Removed empty network");
            }

            save();

            if (config.isDebug()) {
                plugin.getLogger().info("Removed chest from network: " + formatLocation(chestLoc));
            }
        }
    }

    /**
     * Remove a network from tracking
     */
    private void removeNetwork(ChestNetwork network) {
        if (network.getWorld() == null) return;
        String worldName = network.getWorld().getName();
        List<ChestNetwork> worldNetworks = networks.get(worldName);
        if (worldNetworks != null) {
            worldNetworks.remove(network);
        }
    }

    /**
     * Called when a chest is broken
     */
    public void onChestRemoved(Location chestLoc) {
        onShelfUnregistered(chestLoc);
    }

    /**
     * Set the root chest for a network (finds nearby network first)
     */
    public void setRoot(World world, Location root) {
        ChestNetwork network = getNetworkForChest(root);
        if (network == null) {
            network = findNearbyNetwork(root);
        }
        if (network == null) {
            return;
        }
        network.setRoot(root);
        save();
        plugin.getLogger().info("Set network root to " + formatLocation(root));
    }

    /**
     * Schedule a full sort after delay
     */
    public void scheduleQuickSort(Location chestLoc, Player player) {
        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] scheduleQuickSort called for " + formatLocation(chestLoc));
        }

        // Cancel existing pending sort for this world/network
        Integer existingTask = pendingSorts.remove(chestLoc);
        if (existingTask != null) {
            if (config.isDebug()) {
                plugin.getLogger().info("[DEBUG] Cancelled existing sort task " + existingTask);
            }
            Bukkit.getScheduler().cancelTask(existingTask);
        }

        // Schedule new sort
        int delay = config.getSortDelayTicks();
        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Scheduling full sort with delay of " + delay + " ticks");
        }

        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (config.isDebug()) {
                plugin.getLogger().info("[DEBUG] Sort task executing for " + formatLocation(chestLoc));
            }
            pendingSorts.remove(chestLoc);
            executeFullSort(chestLoc, player);
        }, delay).getTaskId();

        pendingSorts.put(chestLoc, taskId);
        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Scheduled sort task " + taskId);
        }
    }

    /**
     * Execute full sort for a network
     */
    private void executeFullSort(Location chestLoc, Player player) {
        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] executeFullSort for " + formatLocation(chestLoc));
        }

        ChestNetwork network = getNetworkForChest(chestLoc);
        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Network found: " + (network != null));
        }

        if (network == null) {
            if (config.isDebug()) {
                plugin.getLogger().info("[DEBUG] No network found, aborting sort");
            }
            return;
        }

        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Network has " + network.size() + " chests");
        }

        // Use FullReorganizeTask for proper category-based sorting
        FullReorganizeTask.trigger(plugin, network);

        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] FullReorganizeTask completed");
        }

        if (player.isOnline()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText("§a[Hoardi] §7Items sorted"));
        }
    }

    /**
     * Get total number of tracked chests across all networks
     */
    public int getTotalChestCount() {
        int total = 0;
        for (List<ChestNetwork> worldNetworks : networks.values()) {
            for (ChestNetwork network : worldNetworks) {
                total += network.size();
            }
        }
        return total;
    }

    /**
     * Get all networks across all worlds
     */
    public Collection<ChestNetwork> getAllNetworks() {
        List<ChestNetwork> all = new ArrayList<>();
        for (List<ChestNetwork> worldNetworks : networks.values()) {
            all.addAll(worldNetworks);
        }
        return all;
    }

    /** Display name of a network's owner ("everyone" for shared networks). */
    public static String ownerName(ChestNetwork network) {
        if (network.getOwner() == null) {
            return "everyone";
        }
        String name = Bukkit.getOfflinePlayer(network.getOwner()).getName();
        return name != null ? name : network.getOwner().toString().substring(0, 8);
    }

    /**
     * Get a summary of all networks for display purposes
     */
    public String getNetworksSummary() {
        Collection<ChestNetwork> allNetworks = getAllNetworks();

        if (allNetworks.isEmpty()) {
            return "No storage networks found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(allNetworks.size()).append(" network(s):\n");

        int i = 1;
        for (ChestNetwork network : allNetworks) {
            String materialName = formatMaterialName(network.getShelfMaterial());
            sb.append(String.format("  %d. %s network: %d chest(s)\n",
                i++, materialName, network.size()));
        }

        return sb.toString();
    }

    /**
     * Format material name for display (e.g., OAK_SHELF -> Oak)
     */
    private String formatMaterialName(Material material) {
        String name = material.name();
        // Remove _SHELF suffix and capitalize
        if (name.endsWith("_SHELF")) {
            name = name.substring(0, name.length() - 6);
        }
        // Convert DARK_OAK to Dark Oak
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

    /**
     * Format location for logging
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
