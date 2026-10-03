package de.hoarder.demo;

import de.hoarder.HoarderPlugin;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The "how to set it up" clip. North wall: the builder stacks double chests side-on (Marc's own
 * layout) and sneak-places Birch shelves on their ends. West wall: single chests with Oak shelves.
 * East wall: barrels with Spruce shelves on top. Finally every network lights up in its own colour.
 * Lives 100 blocks south of the hall so the Birch networks never merge.
 */
final class SetupScene {

    static final int SX = HoardiDemo.OX, SY = HoardiDemo.OY, SZ = HoardiDemo.OZ + 100;
    static final int[] NORTH_COLUMNS = {-2, -1, 0, 1, 2};
    static final int ROWS = 3;

    /** One category per double chest on the north wall, in placing order (bottom-left first). */
    private static final String[][] NORTH = {
        {"oak_log:1728", "spruce_log:1152"}, {"cobblestone:2304", "stone:960"}, {"iron_ingot:1536", "raw_iron:640"},
        {"wheat:1024", "wheat_seeds:1280", "carrot:384"}, {"redstone:1856", "repeater:96", "comparator:48"},
        {"oak_planks:2048", "spruce_planks:1024"}, {"cobbled_deepslate:2240", "tuff:640"}, {"gold_ingot:640", "copper_ingot:1280"},
        {"bread:640", "baked_potato:512", "apple:320"}, {"diamond:192", "emerald:320", "lapis_lazuli:896"},
        {"glass:1280", "sand:1024"}, {"andesite:1024", "diorite:896", "granite:896"}, {"coal:1792", "charcoal:448"},
        {"bone:960", "string:640", "rotten_flesh:768"}, {"netherrack:1536", "quartz:640", "glowstone_dust:320"},
    };

    /** West wall: decoration blocks in single chests. */
    private static final String[][] WEST = {
        {"white_wool:448", "red_wool:320"}, {"yellow_wool:384", "blue_wool:256"}, {"terracotta:512", "orange_terracotta:256"},
        {"poppy:192", "dandelion:256", "cornflower:128"}, {"bricks:640"}, {"bookshelf:192", "book:320"},
        {"lantern:192", "torch:512"}, {"white_concrete:704"},
    };

    /** East wall: odds and ends in barrels. */
    private static final String[][] EAST = {
        {"rail:768", "powered_rail:192"}, {"bucket:64", "water_bucket:4", "shears:2"},
        {"ender_pearl:96", "blaze_rod:64"}, {"arrow:640", "bow:2"},
    };

    private final Plugin plugin;
    private final HoarderPlugin hoardi;
    private final World world;
    private Mannequin builder;
    private final List<Entity> glows = new ArrayList<>();

    SetupScene(Plugin plugin, HoarderPlugin hoardi, World world) {
        this.plugin = plugin;
        this.hoardi = hoardi;
        this.world = world;
    }

    Block block(int x, int y, int z) {
        return world.getBlockAt(SX + x, SY + y, SZ + z);
    }

    Location at(double x, double y, double z) {
        return new Location(world, SX + x, SY + y, SZ + z);
    }

    /** Empties chests, barrels and shelves before they are removed, so nothing pops out as items. */
    private static void remove(Block b, Material replacement) {
        if (b.getState() instanceof TileStateInventoryHolder holder) holder.getInventory().clear();
        b.setType(replacement, false);
    }

    private void clearDroppedItems() {
        for (Entity e : world.getNearbyEntities(at(0.5, 2, 0.5), 8, 6, 8)) {
            if (e instanceof Item) e.remove();
        }
    }

    void build() {
        for (int cx = (SX - 8) >> 4; cx <= (SX + 8) >> 4; cx++) {
            for (int cz = (SZ - 8) >> 4; cz <= (SZ + 8) >> 4; cz++) {
                world.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
            }
        }
        unregister();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -1; y <= 6; y++) {
                    Material m;
                    if (y == -1) m = Material.SPRUCE_PLANKS;
                    else if (y >= 5) m = Material.DARK_OAK_PLANKS;
                    else if (Math.abs(x) == 6 || Math.abs(z) == 6) m = Material.SPRUCE_PLANKS;
                    else m = Material.AIR;
                    remove(block(x, y, z), m);
                }
            }
        }
        for (int x : new int[]{-2, 2}) {
            for (int z : new int[]{-1, 3}) {
                Block lantern = block(x, 4, z);
                lantern.setType(Material.LANTERN, false);
                Lantern data = (Lantern) lantern.getBlockData();
                data.setHanging(true);
                lantern.setBlockData(data, false);
            }
        }
        for (int x = -4; x <= 4; x += 2) {
            for (int z = -2; z <= 4; z += 2) light(block(x, 3, z));
        }
        for (int i = -3; i <= 3; i += 2) {
            light(block(i, 0, -2));
            light(block(-3, 0, i));
            light(block(3, 0, i));
        }
        clearDroppedItems();
    }

    private void light(Block b) {
        b.setType(Material.LIGHT, false);
        Levelled data = (Levelled) b.getBlockData();
        data.setLevel(15);
        b.setBlockData(data, false);
    }

    private void unregister() {
        for (Location shelf : new ArrayList<>(hoardi.getShelfManager().getTrackedShelves())) {
            if (shelf.getWorld() != world) continue;
            int x = shelf.getBlockX() - SX, z = shelf.getBlockZ() - SZ;
            if (Math.abs(x) > 6 || Math.abs(z) > 6) continue;
            Location chest = hoardi.getShelfManager().getChestLocation(shelf);
            hoardi.getShelfManager().unregisterShelf(shelf);
            if (chest != null) hoardi.getNetworkManager().onShelfUnregistered(chest);
        }
    }

    private void register(Block shelf, Block container) {
        hoardi.getShelfManager().registerShelf(shelf, container);
        hoardi.getNetworkManager().onShelfRegistered(shelf.getLocation(), container.getLocation());
    }

    private static void fill(Inventory inv, String[] contents) {
        for (ItemStack stack : HoardiDemo.stacks(contents)) inv.addItem(stack);
    }

    private static void face(Block b, Material type, BlockFace facing) {
        b.setType(type, false);
        Directional data = (Directional) b.getBlockData();
        data.setFacing(facing);
        b.setBlockData(data, false);
    }

    /** Opening state: empty north wall, ready-made west and east walls, builder with a chest in hand. */
    void reset() {
        unregister();
        glows.forEach(Entity::remove);
        glows.clear();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 0; y <= 3; y++) {
                    Block b = block(x, y, z);
                    if (b.getType() != Material.AIR && b.getType() != Material.LIGHT && b.getType() != Material.LANTERN) {
                        remove(b, Material.AIR);
                    }
                }
            }
        }
        // west: single chests (2 rows) with Oak shelves in front
        int i = 0;
        for (int y = 0; y < 2; y++) {
            for (int z = -1; z <= 2; z++) {
                Block chest = block(-5, y, z);
                face(chest, Material.CHEST, BlockFace.EAST);
                fill(((TileStateInventoryHolder) chest.getState()).getInventory(), WEST[i++]);
                Block shelf = block(-4, y, z);
                face(shelf, Material.OAK_SHELF, BlockFace.EAST);
                register(shelf, chest);
            }
        }
        // east: barrels with Spruce shelves standing on top
        i = 0;
        for (int z = -1; z <= 2; z++) {
            Block barrel = block(5, 0, z);
            face(barrel, Material.BARREL, BlockFace.UP);
            fill(((TileStateInventoryHolder) barrel.getState()).getInventory(), EAST[i++]);
            Block shelf = block(5, 1, z);
            face(shelf, Material.SPRUCE_SHELF, BlockFace.WEST);
            register(shelf, barrel);
        }
        clearDroppedItems();

        if (builder != null) builder.remove();
        Location start = at(0.5, 0, 0.3);
        start.setYaw(180);
        builder = world.spawn(start, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setImmovable(true);
            m.setDescription(null);
            m.getEquipment().setItemInMainHand(new ItemStack(Material.CHEST));
        });
    }

    private void lookAt(Block target) {
        Location eye = builder.getLocation().add(0, 1.62, 0);
        Location goal = target.getLocation().add(0.5, 0.5, 0.5);
        builder.teleport(builder.getLocation().setDirection(goal.toVector().subtract(eye.toVector())));
    }

    /** North wall double chest: runs into the wall (z -4 front half, z -5 back half), end side facing the room. */
    private void placeDoubleChest(int index) {
        int x = NORTH_COLUMNS[index % NORTH_COLUMNS.length], y = index / NORTH_COLUMNS.length;
        Block front = block(x, y, -4), back = block(x, y, -5);
        lookAt(front);
        builder.swingMainHand();
        // facing EAST: the chest's left hand points north, so the back (north) half is LEFT
        for (Block half : new Block[]{back, front}) {
            half.setType(Material.CHEST, false);
            Chest data = (Chest) half.getBlockData();
            data.setFacing(BlockFace.EAST);
            data.setType(half == back ? Chest.Type.LEFT : Chest.Type.RIGHT);
            half.setBlockData(data, false);
        }
        fill(((TileStateInventoryHolder) front.getState()).getInventory(), NORTH[index]);
        world.spawnParticle(Particle.BLOCK, front.getLocation().add(0.5, 0.5, 1.0), 14, 0.3, 0.3, 0.05,
            Material.CHEST.createBlockData());
    }

    private void placeNorthShelf(int index) {
        int x = NORTH_COLUMNS[index % NORTH_COLUMNS.length], y = index / NORTH_COLUMNS.length;
        Block shelf = block(x, y, -3);
        lookAt(shelf);
        builder.swingMainHand();
        face(shelf, Material.BIRCH_SHELF, BlockFace.SOUTH);
        world.spawnParticle(Particle.BLOCK, shelf.getLocation().add(0.5, 0.5, 0.9), 14, 0.3, 0.3, 0.05,
            Material.BIRCH_SHELF.createBlockData());
        // what Hoardi does when a sneaking player places a shelf against a chest
        register(shelf, block(x, y, -4));
    }

    /** Outlines every shelf of the set in its network's colour. */
    private void showNetworks() {
        for (Location loc : hoardi.getShelfManager().getTrackedShelves()) {
            if (loc.getWorld() != world || Math.abs(loc.getBlockX() - SX) > 6 || Math.abs(loc.getBlockZ() - SZ) > 6) continue;
            Block shelf = loc.getBlock();
            Color color = switch (shelf.getType()) {
                case BIRCH_SHELF -> Color.fromRGB(255, 214, 64);
                case OAK_SHELF -> Color.fromRGB(255, 110, 40);
                default -> Color.fromRGB(70, 190, 255);
            };
            BlockDisplay glow = world.spawn(loc, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(shelf.getBlockData());
                d.setTransformation(new Transformation(new Vector3f(-0.003f), new AxisAngle4f(),
                    new Vector3f(1.006f), new AxisAngle4f()));
                d.setGlowColorOverride(color);
                d.setGlowing(true);
            });
            glows.add(glow);
        }
    }

    /**
     * Short prep: the north wall's double chests already stand (filled, no shelves yet) and the
     * builder waits off-centre, sneaking, with a shelf in hand.
     */
    void placeAllChests() {
        int count = NORTH_COLUMNS.length * ROWS;
        for (int i = 0; i < count; i++) placeDoubleChest(i);
        clearDroppedItems();
        Location spot = at(-1.3, 0, -0.3);
        spot.setYaw(160);
        builder.teleport(spot);
        builder.getEquipment().setItemInMainHand(new ItemStack(Material.BIRCH_SHELF));
        builder.setPose(Pose.SNEAKING, true);
    }

    /**
     * Short: one sneak-place per chest, each shelf fills up right away. The first shelf lands at
     * 1.9 s (edit.py anchors it to "Sneak"), the last at 3.58 s. Needs placeAllChests first.
     */
    HoardiDemo.Shot shortSetupShot() {
        HoardiDemo.Shot s = new HoardiDemo.Shot("vsetup", 6.2);
        key(s, 0, look(2.6, 2.05, 2.0, 0.7, 1.1, -3.5));
        key(s, 6.2, look(1.95, 1.8, 0.7, 0.6, 1.05, -3.5));
        int count = NORTH_COLUMNS.length * ROWS;
        for (int i = 0; i < count; i++) {
            int n = i;
            s.at(1.9 + i * 0.12, () -> {
                placeNorthShelf(n);
                new de.hoarder.shelf.ShelfDisplayTask(hoardi.getShelfManager()).run();
            });
        }
        return s;
    }

    /** Short: the three networks light up, the camera whips west, north, east across them. */
    HoardiDemo.Shot shortNetworkShot() {
        double z = SZ - HoardiDemo.OZ;
        HoardiDemo.Shot s = new HoardiDemo.Shot("vnet", 3.8);
        s.key(0, 0.5, 1.9, 1.5 + z, 90, 10);
        s.key(0.3, 0.5, 1.9, 1.5 + z, 90, 10);
        s.key(1.4, 0.5, 1.9, 1.3 + z, 180, 9);
        s.key(2.5, 0.5, 1.9, 1.5 + z, 270, 11);
        s.key(3.8, 0.5, 1.9, 1.5 + z, 270, 11);
        // the builder would stand in the middle of the pan: park him behind the camera
        s.at(0, () -> builder.teleport(at(-3.6, 0, 4.4)));
        s.at(0, this::showNetworks);
        return s;
    }

    /** Camera pose looking from (x,y,z) at a target, in the director's hall-local frame (z shifted by 100). */
    private static double[] look(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new double[]{x, y, z + (SZ - HoardiDemo.OZ), yaw, pitch};
    }

    private static void key(HoardiDemo.Shot s, double t, double[] p) {
        s.key(t, p[0], p[1], p[2], p[3], p[4]);
    }

    HoardiDemo.Shot shot() {
        HoardiDemo.Shot s = new HoardiDemo.Shot("setup", 17.5);
        key(s, 0, look(3.0, 2.25, 3.6, 0.5, 1.3, -3.5));
        key(s, 8.3, look(2.5, 2.1, 3.0, 0.5, 1.3, -3.5));
        key(s, 9.6, look(1.6, 1.9, 1.2, -4.0, 0.9, 0.5));
        key(s, 10.9, look(1.4, 1.85, 0.8, -4.0, 0.9, 0.5));
        key(s, 12.3, look(-0.4, 1.9, 1.0, 5.0, 1.0, 0.5));
        key(s, 13.4, look(-0.7, 1.85, 0.6, 5.0, 1.0, 0.5));
        key(s, 14.8, look(0.5, 3.4, 4.7, 0.5, 0.6, -2.0));
        key(s, 17.5, look(0.5, 3.5, 5.0, 0.5, 0.6, -2.0));

        int count = NORTH_COLUMNS.length * ROWS;
        for (int i = 0; i < count; i++) {
            int n = i;
            s.at(0.8 + i * 0.22, () -> placeDoubleChest(n));
        }
        s.at(4.3, () -> builder.getEquipment().setItemInMainHand(new ItemStack(Material.BIRCH_SHELF)));
        s.at(4.6, () -> builder.setPose(Pose.SNEAKING, true));
        for (int i = 0; i < count; i++) {
            int n = i;
            s.at(5.0 + i * 0.22, () -> placeNorthShelf(n));
        }
        s.at(8.4, () -> builder.setPose(Pose.STANDING, false));
        // walk to the south-west corner: away from the camera, outside the final wide shot
        for (int k = 0; k <= 24; k++) {
            double u = k / 24.0;
            u = u * u * (3 - 2 * u);
            Location l = at(0.5 - 4.7 * u, 0, 0.3 + 4.0 * u);
            l.setYaw(k == 24 ? -143 : 50);
            s.at(8.6 + k * 0.05, () -> builder.teleport(l));
        }
        s.at(14.6, this::showNetworks);
        return s;
    }
}
