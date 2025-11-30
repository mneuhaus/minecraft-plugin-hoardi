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

### Shelf Fill Level Display

Shelves visually indicate how full a chest is using a clever item display system:

| Chest Contents | Display Pattern | Meaning |
|----------------|-----------------|---------|
| 1 item type, < 50% full | `[ ] [X] [ ]` | Single item centered |
| 1 item type, 50-80% full | `[X] [X] [ ]` | Two items shown |
| 1 item type, > 80% full | `[X] [X] [X]` | Three items = nearly full! |
| 2 item types | `[A] [B] [ ]` or `[A] [A] [B]` | Top 2 items shown |
| 3+ item types | `[A] [B] [C]` | Top 3 most common items |

This lets you see at a glance:
- **What's inside** - the actual item types
- **How full it is** - more repeated items = fuller chest
- **When to expand** - three identical items means time for more storage!

### Sorting Algorithm
1. **Collect**: All items from all chests are gathered
2. **Categorize**: Each item is assigned to a category (e.g., `wood/oak`, `ores/iron`, `food/meat`)
3. **Distribute**: Items are placed into chests, one category per chest when possible
4. **Overflow Protection**: If space is tight, categories are merged; items are never lost

### Category Splitting System

Hoardi intelligently decides how to distribute categories across your chests:

**Default behavior**: Each leaf category (e.g., `wood/oak`, `wood/birch`) gets its own chest.

**When you have fewer chests than categories**, Hoardi merges related categories:
1. Large categories (> 50% of a chest) always get their own chest
2. Small categories are grouped with siblings (same parent category)
3. If still not enough space, categories are merged by root (e.g., all `wood/*` together)

**Example with 10 chests and 25 categories:**
```
wood/oak (large)     → Own chest
wood/sticks (large)  → Own chest
wood/birch (small)   → Merged into "wood" chest
wood/spruce (small)  → Merged into "wood" chest
ores/iron (medium)   → Own chest
ores/gold (small)    → Merged into "ores" chest
...
```

The `split-threshold` setting (default: 50%) controls when a category is considered "large enough" to deserve its own chest.

### Position Calculation
Hoardi uses nearest-neighbor pathfinding to determine chest order:
- Chests are grouped into "floors" (Y-levels within 1 block of each other)
- Within each floor, chests are visited in logical order starting from the root
- Vertical columns are processed bottom-to-top (configurable)

## Configuration

The `config.yml` file is generated on first run. Here's a detailed breakdown:

### Sorting Triggers

```yaml
# Sort items when a player closes a chest in the network
quick-sort-on-close: true

# Automatic full reorganization interval in ticks (20 ticks = 1 second)
# Set to 0 to disable automatic reorganization
# Example: 72000 = every hour
full-reorganize-interval: 0
```

### Splitting Behavior

```yaml
# When a category fills more than X% of a chest, it gets its own chest
# Lower value = more separation (needs more chests)
# Higher value = more merging (fewer chests needed)
split-threshold: 50
```

**Examples:**
| split-threshold | Effect |
|-----------------|--------|
| `25` | Very aggressive splitting - even quarter-full categories get own chest |
| `50` | Balanced (default) - half-full categories get own chest |
| `75` | Conservative - only very full categories split |
| `100` | Never split - always merge by parent category |

### Chest Ordering

```yaml
# Process vertical chest columns from bottom to top
# Set to false for top-to-bottom ordering
bottom-to-top: true
```

This affects the order items are placed when you have stacked chests:
- `true`: Ground level chests fill first, then upper levels
- `false`: Top chests fill first, then lower levels

### Category Definitions

```yaml
categories:
  # Format: category/subcategory: [ITEM1, ITEM2, ...]

  wood/oak: [OAK_LOG, OAK_PLANKS, OAK_SLAB, ...]
  wood/birch: [BIRCH_LOG, BIRCH_PLANKS, ...]
  wood/sticks: [STICK]

  ores/iron: [IRON_ORE, DEEPSLATE_IRON_ORE, RAW_IRON, IRON_INGOT, IRON_BLOCK]
  ores/gold: [GOLD_ORE, DEEPSLATE_GOLD_ORE, RAW_GOLD, GOLD_INGOT, GOLD_BLOCK]

  food/meat: [BEEF, COOKED_BEEF, PORKCHOP, COOKED_PORKCHOP, ...]

  # ... 300+ items across 80+ categories
```

### Category Hierarchy

Categories use a path-like structure where `/` separates levels:

```
wood/               <- Root category
├── oak             <- Leaf category (wood/oak)
├── birch           <- Leaf category (wood/birch)
├── sticks          <- Leaf category (wood/sticks)
└── ...

ores/
├── iron
├── gold
├── diamond
└── ...
```

**How merging works:**
- With enough chests: Each leaf category (`wood/oak`, `wood/birch`) gets its own chest
- Limited chests: Siblings merge (`wood/oak` + `wood/birch` → `wood` chest)
- Very limited: All wood items share one chest

### Display Names

```yaml
category-names:
  wood: "Wood & Logs"
  ores: "Ores & Minerals"
  food: "Food & Cooking"
  # Used in /hoardi stats output
```

### Adding Custom Categories

You can add your own categories or reorganize existing ones:

```yaml
categories:
  # Create a new category for your base materials
  mybase/building: [STONE, COBBLESTONE, DIRT, GRAVEL]
  mybase/decoration: [FLOWER_POT, PAINTING, ITEM_FRAME]

  # Override an existing category
  wood/oak: [OAK_LOG, OAK_PLANKS]  # Simplified version
```

Items not in any category go to `misc`.

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
