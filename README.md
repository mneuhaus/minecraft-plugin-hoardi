# Hoardi

**Intelligent auto-sorting chest network with shelf previews for Minecraft**

Hoardi is a Paper plugin for Minecraft 1.21+ that transforms your storage into a smart, self-organizing system. Connect chests with decorative shelves, and items automatically sort themselves into logical categories.

## Features

- **Auto-Sorting**: Items automatically move to appropriate chests based on their category
- **Shelf Previews**: Shelves display the top items in each chest at a glance
- **Smart Categories**: 300+ items organized into intuitive hierarchical categories
- **Floor Detection**: Multi-level storage systems supported with intelligent pathfinding
- **No Item Loss**: Overflow protection ensures items are never lost during sorting
- **Zero Configuration**: Works out of the box - just place shelves against chests

## Requirements

- Paper 1.21.10+ (uses the new Shelf block type)
- Java 21+

## Installation

1. Download `Hoardi-1.0.0.jar` from releases
2. Place in your server's `plugins/` folder
3. Restart the server
4. Done!

## Quick Start

1. **Place chests** where you want your storage
2. **Sneak + place a shelf** against the front of each chest
3. **That's it!** Items will auto-sort when you close any chest in the network

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/hoardi` | Show help | - |
| `/hoardi info` | Display network information | - |
| `/hoardi setroot` | Set the network root (look at a chest) | `hoardi.admin` |
| `/hoardi sort` | Trigger full reorganization | `hoardi.admin` |
| `/hoardi stats` | Show detailed statistics | - |
| `/hoardi reload` | Reload configuration | `hoardi.admin` |

**Alias**: `/hr`

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `hoardi.use` | Create shelves and use the network | Everyone |
| `hoardi.admin` | Admin commands (setroot, sort, reload) | OP |

## How It Works

### Shelf Registration
When you sneak + place a shelf against a chest, the chest joins your storage network. The shelf will display the top 3 items in the chest, giving you a visual preview of contents.

### Sorting Algorithm
1. **Collect**: All items from all chests are gathered
2. **Categorize**: Each item is assigned to a category (e.g., `wood/oak`, `ores/iron`, `food/meat`)
3. **Distribute**: Items are placed into chests, one category per chest when possible
4. **Overflow Protection**: If space is tight, categories are merged; items are never lost

### Position Calculation
Hoardi uses nearest-neighbor pathfinding to determine chest order:
- Chests are grouped into "floors" (Y-levels within 1 block of each other)
- Within each floor, chests are visited in logical order starting from the root
- Vertical columns are processed bottom-to-top (configurable)

## Configuration

The `config.yml` file is generated on first run. Key settings:

```yaml
# When to sort
quick-sort-on-close: true          # Sort when closing a chest
full-reorganize-interval: 0        # Auto-reorganize interval (0 = disabled)

# Sorting behavior
split-threshold: 50                # Split category when chest is X% full
bottom-to-top: true               # Process vertical columns bottom-to-top

# Categories
categories:
  wood/oak: [OAK_LOG, OAK_PLANKS, ...]
  ores/iron: [IRON_ORE, IRON_INGOT, IRON_BLOCK, ...]
  # ... 300+ items organized into categories
```

### Category Hierarchy

Categories use a hierarchical structure:
- `wood/oak` - Oak wood items
- `wood/birch` - Birch wood items
- `ores/iron` - Iron ore, ingots, blocks
- `food/meat` - All meat items
- `mob_drops/bones` - Bones and bone blocks

When there aren't enough chests, related categories are merged (e.g., all `wood/*` into one chest).

## Building from Source

```bash
# Clone the repository
git clone https://github.com/mneuhaus/minecraft-plugin-hoardi.git
cd minecraft-plugin-hoardi

# Build with Maven (Docker)
make build

# Or build and deploy to test server
make deploy
make restart
```

### Development Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the plugin JAR |
| `make deploy` | Build and copy to test server |
| `make start` | Start the test server |
| `make stop` | Stop the test server |
| `make restart` | Deploy and restart server |
| `make logs` | View server logs |

## License

MIT License - feel free to use, modify, and distribute.

## Credits

Created by Marc Neuhaus

---

**Happy Hoarding!**
