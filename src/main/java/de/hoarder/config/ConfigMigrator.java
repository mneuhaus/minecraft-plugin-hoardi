package de.hoarder.config;

import de.hoarder.HoarderPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Handles migration from old plugin configurations to the current format.
 *
 * Supports migration from:
 * - Hoardi (alternate name) -> Hoarder (current name)
 * - ChestPreview (old name) -> Hoarder (current name)
 * - Old shelves.yml format (without material) -> new format (with material)
 */
public class ConfigMigrator {

    private final HoarderPlugin plugin;
    private final Logger logger;

    // Old plugin names to check for migration
    private static final String[] OLD_PLUGIN_NAMES = {"Hoardi", "ChestPreview"};

    public ConfigMigrator(HoarderPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Run all migrations. Should be called during plugin enable, before loading data.
     *
     * @return true if any migration was performed
     */
    public boolean migrate() {
        boolean migrated = false;

        // Check for old plugin folders and migrate
        for (String oldName : OLD_PLUGIN_NAMES) {
            if (migrateFromOldPlugin(oldName)) {
                migrated = true;
                break; // Only migrate from one source
            }
        }

        return migrated;
    }

    /**
     * Migrate data from an old plugin folder to the current plugin folder.
     *
     * @param oldPluginName The old plugin name to migrate from
     * @return true if migration was performed
     */
    private boolean migrateFromOldPlugin(String oldPluginName) {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File oldFolder = new File(pluginsFolder, oldPluginName);
        File newFolder = plugin.getDataFolder();

        // Skip if old folder doesn't exist or is the same as new folder
        if (!oldFolder.exists() || oldFolder.equals(newFolder)) {
            return false;
        }

        // Skip if new folder already has data (don't overwrite)
        File newShelvesFile = new File(newFolder, "shelves.yml");
        if (newShelvesFile.exists()) {
            logger.info("Found old " + oldPluginName + " folder, but " + plugin.getName() +
                " already has data. Skipping migration.");
            return false;
        }

        logger.info("Found old " + oldPluginName + " data! Starting migration...");

        try {
            // Ensure new folder exists
            newFolder.mkdirs();

            // Migrate shelves.yml
            File oldShelvesFile = new File(oldFolder, "shelves.yml");
            if (oldShelvesFile.exists()) {
                migrateShelvesFile(oldShelvesFile, newShelvesFile);
                logger.info("Migrated shelves.yml");
            }

            // Migrate config.yml
            File oldConfigFile = new File(oldFolder, "config.yml");
            File newConfigFile = new File(newFolder, "config.yml");
            if (oldConfigFile.exists() && !newConfigFile.exists()) {
                migrateConfigFile(oldConfigFile, newConfigFile);
                logger.info("Migrated config.yml");
            }

            // Migrate networks.yml if it exists
            File oldNetworksFile = new File(oldFolder, "networks.yml");
            File newNetworksFile = new File(newFolder, "networks.yml");
            if (oldNetworksFile.exists() && !newNetworksFile.exists()) {
                Files.copy(oldNetworksFile.toPath(), newNetworksFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
                logger.info("Migrated networks.yml");
            }

            // Rename old folder to .migrated to prevent re-migration
            File backupFolder = new File(pluginsFolder, oldPluginName + ".migrated");
            if (oldFolder.renameTo(backupFolder)) {
                logger.info("Renamed old " + oldPluginName + " folder to " +
                    oldPluginName + ".migrated");
            }

            logger.info("Migration from " + oldPluginName + " complete!");
            return true;

        } catch (IOException e) {
            logger.severe("Failed to migrate from " + oldPluginName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Migrate shelves.yml, adding default material if missing.
     */
    @SuppressWarnings("unchecked")
    private void migrateShelvesFile(File oldFile, File newFile) throws IOException {
        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(oldFile);
        YamlConfiguration newConfig = new YamlConfiguration();

        // Check if the old format has shelf_material field
        var shelves = oldConfig.getMapList("shelves");
        boolean needsMaterialMigration = false;

        for (var shelf : shelves) {
            if (!shelf.containsKey("shelf_material")) {
                needsMaterialMigration = true;
                // Add default material - need to cast to work with the wildcard type
                ((java.util.Map<String, Object>) shelf).put("shelf_material", "OAK_SHELF");
            }
        }

        if (needsMaterialMigration) {
            logger.info("Adding default shelf material (OAK_SHELF) to " + shelves.size() + " shelves");
        }

        newConfig.set("shelves", shelves);
        newConfig.save(newFile);
    }

    /**
     * Migrate config.yml, mapping old config keys to new ones.
     */
    private void migrateConfigFile(File oldFile, File newFile) throws IOException {
        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(oldFile);
        YamlConfiguration newConfig = new YamlConfiguration();

        // Map old config keys to new ones

        // Network gap distance (might have been called something else)
        if (oldConfig.contains("network-gap-distance")) {
            newConfig.set("network-gap-distance", oldConfig.getInt("network-gap-distance", 16));
        } else if (oldConfig.contains("cluster-distance")) {
            newConfig.set("network-gap-distance", oldConfig.getInt("cluster-distance", 16));
        } else if (oldConfig.contains("network-radius")) {
            newConfig.set("network-radius", oldConfig.getInt("network-radius", 50));
        }

        // Preserve any sorting-related configs that might exist
        if (oldConfig.contains("quick-sort-on-close")) {
            newConfig.set("quick-sort-on-close", oldConfig.getBoolean("quick-sort-on-close", true));
        }

        if (oldConfig.contains("full-reorganize-interval")) {
            newConfig.set("full-reorganize-interval", oldConfig.getInt("full-reorganize-interval", 0));
        }

        if (oldConfig.contains("split-threshold")) {
            newConfig.set("split-threshold", oldConfig.getInt("split-threshold", 50));
        }

        if (oldConfig.contains("bottom-to-top")) {
            newConfig.set("bottom-to-top", oldConfig.getBoolean("bottom-to-top", true));
        }

        if (oldConfig.contains("debug")) {
            newConfig.set("debug", oldConfig.getBoolean("debug", false));
        }

        // Preserve categories if they exist
        if (oldConfig.contains("categories")) {
            newConfig.set("categories", oldConfig.getConfigurationSection("categories"));
        }

        if (oldConfig.contains("category-names")) {
            newConfig.set("category-names", oldConfig.getConfigurationSection("category-names"));
        }

        newConfig.save(newFile);
    }
}
