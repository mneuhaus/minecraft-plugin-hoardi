# Hoarder - Claude Code Instructions

## Project Overview

Hoarder (published as Hoardi) is a Minecraft plugin for Paper and Spigot 1.21.9 – 26.3 for intelligent auto-sorting chest networks with shelf previews.

## Build Commands

```bash
make build      # Maven in Docker with JDK 25 (the 26.x API needs it), jar targets Java 21
make compat     # compile against the oldest supported API (Paper + Spigot 1.21.9)
make deploy     # build and copy to the test server
tools/compat/smoke.sh 1.21.10   # smoke test on a real Paper server of that version
```

## Project Structure

```
src/main/java/de/hoarder/
├── HoarderPlugin.java          # Main plugin class
├── TestWarehouseBuilder.java   # /hoardi test command (hidden, for dev)
├── commands/
│   └── HoarderCommand.java     # All /hoardi commands
├── config/
│   ├── ConfigMigrator.java     # Migrates old plugin configs
│   └── HoarderConfig.java      # Configuration handling
├── network/
│   ├── ChestNetwork.java       # Network with material type
│   ├── NetworkChest.java       # Single chest in network
│   ├── NetworkManager.java     # Manages all networks (material + proximity based)
│   └── *PositionCalculator.java # Chest ordering algorithms
├── shelf/
│   ├── ShelfManager.java       # Tracks shelf-chest connections + material
│   ├── ShelfListener.java      # Block place/break events
│   └── ShelfDisplayTask.java   # Updates shelf item displays
└── sorting/
    ├── ItemHierarchy.java      # Category definitions
    ├── CategoryResolver.java   # Resolves item -> category
    ├── FullReorganizeTask.java # Full network sort
    ├── QuickSortTask.java      # Quick sort on chest close
    └── SplitChecker.java       # Category splitting logic
```

## Key Concepts

### Material-Based Networks
- Networks are separated by **shelf material type** (Oak, Birch, etc.)
- Shelves within `network-radius` (default 50 blocks) with same material = one network
- Different materials create separate networks even if adjacent

### ShelfData Class
```java
// ShelfManager stores material with each shelf
public static class ShelfData {
    private final Location chestLocation;
    private final Material shelfMaterial;  // OAK_SHELF, BIRCH_SHELF, etc.
}
```

### Network Discovery
Networks are found using proximity clustering + material type:
1. Group all shelves by material
2. Within each material group, cluster by proximity (Union-Find algorithm)
3. Each cluster = one network

## Important Files

- `plugin.yml` - Plugin metadata, commands, permissions
- `config.yml` - Default configuration with categories
- `shelves.yml` - Persisted shelf-chest mappings (auto-generated)
- `networks.yml` - Persisted network data (auto-generated)

## Permissions

- `hoarder.use` - Create shelves, use network (default: everyone)
- `hoarder.admin` - Admin commands (default: op)

## Testing

Hidden command for development:
```
/hoardi test
```
Builds a test warehouse with Oak shelves on near row, Birch shelves on far row (2 separate networks).

## Common Tasks

### Adding a new shelf material
Nothing to do: `SHELF_MATERIALS` in `ShelfManager.java` collects every `*_SHELF` the running server knows (Poplar in 26.3 worked without a change).

### Changing network grouping behavior
- Edit `NetworkManager.findNearbyNetwork()` and `getOrCreateNetwork()`
- Networks are keyed by material + proximity

### Modifying sort behavior
- `FullReorganizeTask.java` - Main sorting logic
- `ItemHierarchy.java` - Category definitions
- `config.yml` - Category mappings
