package de.hoarder;

import de.hoarder.commands.HoarderCommand;
import de.hoarder.config.HoarderConfig;
import de.hoarder.network.NetworkManager;
import de.hoarder.shelf.ShelfDisplayTask;
import de.hoarder.shelf.ShelfListener;
import de.hoarder.shelf.ShelfManager;
import de.hoarder.sorting.FullReorganizeTask;
import de.hoarder.sorting.ItemHierarchy;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hoarder - Intelligent auto-sorting chest network with shelf previews
 */
public class HoarderPlugin extends JavaPlugin {

    private HoarderConfig hoarderConfig;
    private ShelfManager shelfManager;
    private NetworkManager networkManager;
    private ItemHierarchy itemHierarchy;

    private ShelfDisplayTask displayTask;
    private FullReorganizeTask reorganizeTask;

    @Override
    public void onEnable() {
        // Load configuration
        hoarderConfig = new HoarderConfig(this);
        hoarderConfig.load();

        // Initialize item hierarchy
        itemHierarchy = new ItemHierarchy(hoarderConfig);

        // Initialize shelf manager (handles shelf-chest connections)
        shelfManager = new ShelfManager(this);
        shelfManager.load();

        // Initialize network manager
        networkManager = new NetworkManager(this, shelfManager, hoarderConfig);
        networkManager.load();

        // Register event listeners
        getServer().getPluginManager().registerEvents(
            new ShelfListener(this, shelfManager, networkManager),
            this
        );

        // Register commands
        HoarderCommand commandExecutor = new HoarderCommand(this);
        getCommand("hoarder").setExecutor(commandExecutor);
        getCommand("hoarder").setTabCompleter(commandExecutor);

        // Start shelf display update task (every second)
        displayTask = new ShelfDisplayTask(shelfManager);
        displayTask.runTaskTimer(this, 20L, 20L);

        // Start full reorganize task if enabled
        if (hoarderConfig.getFullReorganizeInterval() > 0) {
            reorganizeTask = new FullReorganizeTask(this);
            reorganizeTask.runTaskTimer(this,
                hoarderConfig.getFullReorganizeInterval(),
                hoarderConfig.getFullReorganizeInterval()
            );
        }

        getLogger().info("Hoarder enabled!");
        getLogger().info("Sneak + place a shelf against a chest to add it to the network!");
    }

    @Override
    public void onDisable() {
        // Cancel tasks
        if (displayTask != null) {
            displayTask.cancel();
        }
        if (reorganizeTask != null) {
            reorganizeTask.cancel();
        }

        // Save data
        if (shelfManager != null) {
            shelfManager.save();
        }
        if (networkManager != null) {
            networkManager.save();
        }

        getLogger().info("Hoarder disabled!");
    }

    /**
     * Reload the plugin configuration
     */
    public void reload() {
        hoarderConfig.load();
        shelfManager.load();
        networkManager.load();
        getLogger().info("Hoarder reloaded!");
    }

    // Getters

    public HoarderConfig getHoarderConfig() {
        return hoarderConfig;
    }

    public ShelfManager getShelfManager() {
        return shelfManager;
    }

    public NetworkManager getNetworkManager() {
        return networkManager;
    }

    public ItemHierarchy getItemHierarchy() {
        return itemHierarchy;
    }
}
