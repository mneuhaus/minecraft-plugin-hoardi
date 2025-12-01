# The Zen of Hoardi

*Guiding principles for intelligent chest organization*

---

**Proximity beats perfection.**
Items flow to the nearest fitting chest, not the "best" one far away.

**Categories emerge, they are not imposed.**
A chest becomes "for wood" when wood fills it, not when you label it.

**Full chests split their burden.**
When a chest overflows, its secondary items seek new homes nearby.

**The first item claims the chest.**
An empty chest adopts the category of whoever arrives first.

**Neighbors share the load.**
Adjacent chests of the same category form a natural group.

**Variety consolidates, abundance spreads.**
Few item types cluster together; many item types distribute evenly.

**Shelves reflect, they do not dictate.**
The shelf shows what is inside, not what should be.

**Two shelves see more than one.**
Side-by-side shelves on a double chest share six slots of wisdom.

**Networks stay in their lane.**
Oak shelves know not what Birch shelves hold.

**The root anchors, but does not rule.**
Position flows from the root; decisions flow from the items.

**Empty space is opportunity.**
An unassigned chest awaits its purpose with patience.

**Sorting is a conversation, not a command.**
Close a chest to whisper; reorganize to shout.

---

## The Algorithm in Practice

### Quick Sort (on chest close)
```
When a chest closes:
  For each item that doesn't belong:
    Find the nearest chest where it does belong
    If none exists, find the nearest chest with space
    Move the item there
```

### Full Reorganize (on command)
```
Gather all items from all chests
Group by category
For each category:
  Find or create home chests (nearest empty or matching)
  Fill from root outward, row by row
  Split overflow to adjacent chests
```

### Category Assignment
```
A chest's category is:
  The category of its most abundant item type
  Or "unassigned" if empty

Categories split when:
  A chest is >80% full AND
  Contains items of multiple categories AND
  An empty chest exists nearby
```

### Position Calculation
```
Chests are numbered by:
  1. Floor level (Y-coordinate groups)
  2. Row within floor (distance from root)
  3. Column within row (left to right)

Lower numbers fill first.
```

---

*"In chaos, there is opportunity. In organization, there is peace."*
