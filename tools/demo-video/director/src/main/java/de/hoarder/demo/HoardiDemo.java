package de.hoarder.demo;

import de.hoarder.HoarderPlugin;
import de.hoarder.network.ChestNetwork;
import de.hoarder.network.NetworkManager;
import de.hoarder.shelf.ShelfManager;
import de.hoarder.sorting.FullReorganizeTask;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Stages the Hoardi demo video on the test server: builds a storage hall, fills it with a
 * lived-in hoard and flies a spectator camera through scripted shots. Shot boundaries are
 * appended to marks.log (epoch millis) so the recording can be cut afterwards.
 */
public final class HoardiDemo extends JavaPlugin {

    static final String CAM_PLAYER = "HoardiCam";
    static final int OX = 1000, OY = -60, OZ = 1000;
    static final int COLS = 14, START_COLS = 11, ROWS = 3;
    static final Material SHELF = Material.BIRCH_SHELF;

    private HoarderPlugin hoardi;
    private World world;
    private ItemDisplay cam;
    private BukkitTask running;
    private File marks;
    private SetupScene setup;
    private final Map<String, Supplier<Shot>> shots = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        hoardi = (HoarderPlugin) getServer().getPluginManager().getPlugin("Hoardi");
        world = getServer().getWorlds().get(0);
        getDataFolder().mkdirs();
        marks = new File(getDataFolder(), "marks.log");
        setup = new SetupScene(this, hoardi, world);
        defineShots();
    }

    @Override
    public void onDisable() {
        stopCamera();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/demo build | reset | cam <shot> <sec> | shot <name...> | all | free | shots");
            return true;
        }
        switch (args[0]) {
            case "build" -> {
                buildSet();
                resetContents();
                sender.sendMessage("set built at " + OX + " " + OY + " " + OZ);
            }
            case "reset" -> {
                resetContents();
                sender.sendMessage("contents reset");
            }
            case "cam" -> {
                Shot shot = shots.get(args[1]).get();
                double sec = args.length > 2 ? Double.parseDouble(args[2]) : 0;
                poseStill(shot, sec);
                sender.sendMessage("camera at " + args[1] + " t=" + sec);
            }
            case "shot" -> runShots(List.of(args).subList(1, args.length));
            case "all" -> runShots(List.of("hall", "dump", "sorted", "find", "grow", "end"));
            case "setupbuild" -> {
                setup.build();
                setup.reset();
                sender.sendMessage("setup set built at " + SetupScene.SX + " " + SetupScene.SY + " " + SetupScene.SZ);
            }
            case "setupreset" -> {
                setup.reset();
                sender.sendMessage("setup reset");
            }
            case "free" -> stopCamera();
            case "shots" -> sender.sendMessage(String.join(", ", shots.keySet()));
            case "shelves" -> {
                // which shelf blocks this server + Hoardi build treat as network shelves
                List<String> names = new ArrayList<>();
                for (Material m : Material.values()) {
                    if (hoardi.getShelfManager().isShelf(m)) names.add(m.name());
                }
                sender.sendMessage(names.size() + " shelf types: " + String.join(", ", names));
            }
            case "stats" -> {
                // per wall and row: used slots of each chest along x
                for (int side : new int[]{-1, 1}) {
                    for (int y = ROWS - 1; y >= 0; y--) {
                        StringBuilder line = new StringBuilder(side < 0 ? "N" : "S").append(y).append(':');
                        for (int x = 0; x < COLS; x++) {
                            int[] c = {x, y, side};
                            line.append(' ').append(block(x, y, 4 * side).getType() == Material.CHEST
                                ? String.format("%2d", used(chestInv(c))) : " -");
                        }
                        sender.sendMessage(line.toString());
                    }
                }
            }
            default -> sender.sendMessage("unknown: " + args[0]);
        }
        return true;
    }

    // ------------------------------------------------------------------ set

    Block block(int x, int y, int z) {
        return world.getBlockAt(OX + x, OY + y, OZ + z);
    }

    Location at(double x, double y, double z) {
        return new Location(world, OX + x, OY + y, OZ + z);
    }

    private void buildSet() {
        for (int cx = (OX - 8) >> 4; cx <= (OX + 24) >> 4; cx++) {
            for (int cz = (OZ - 12) >> 4; cz <= (OZ + 12) >> 4; cz++) {
                world.getChunkAt(cx, cz).addPluginChunkTicket(this);
            }
        }
        unregisterSet();

        for (int x = -3; x <= 16; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -1; y <= 6; y++) {
                    Block b = block(x, y, z);
                    if (b.getState() instanceof TileStateInventoryHolder h) h.getInventory().clear();
                    Material m;
                    if (y == -1) m = Material.SPRUCE_PLANKS;
                    else if (y >= 5) m = Material.DARK_OAK_PLANKS;
                    else if (x == -3 || x == 16 || Math.abs(z) >= 3) m = Material.SPRUCE_PLANKS;
                    else m = Material.AIR;
                    b.setType(m, false);
                }
            }
        }
        // trim beams above the shelves and along the floor edge
        for (int x = -2; x <= 15; x++) {
            for (int side : new int[]{-1, 1}) {
                log(block(x, 3, 3 * side), Axis.X);
            }
        }
        for (int z = -2; z <= 2; z++) {
            for (int x : new int[]{-2, 15}) log(block(x, 4, z), Axis.Z);
        }
        for (int x = 0; x < START_COLS; x++) placeColumn(x, false);

        // light: hanging lanterns plus invisible light blocks so the shelves read well on video
        for (int x = 1; x <= 14; x += 4) {
            Block lantern = block(x, 4, 0);
            lantern.setType(Material.LANTERN, false);
            Lantern data = (Lantern) lantern.getBlockData();
            data.setHanging(true);
            lantern.setBlockData(data, false);
        }
        for (int x = -2; x <= 15; x++) {
            for (int z : new int[]{-2, 2}) {
                for (int y = 0; y <= 3; y++) light(block(x, y, z));
            }
            if (x % 2 == 0) {
                light(block(x, 3, 0));
                light(block(x, 0, 0));
            }
        }
        for (Entity e : world.getNearbyEntities(at(7, 2, 0), 14, 6, 8)) {
            if (e instanceof Item) e.remove();
        }
    }

    private void log(Block b, Axis axis) {
        b.setType(Material.STRIPPED_SPRUCE_LOG, false);
        Orientable data = (Orientable) b.getBlockData();
        data.setAxis(axis);
        b.setBlockData(data, false);
    }

    private void light(Block b) {
        b.setType(Material.LIGHT, false);
        Levelled data = (Levelled) b.getBlockData();
        data.setLevel(15);
        b.setBlockData(data, false);
    }

    /** Places chests + shelves of one column on both walls; with register=true they join the network. */
    private void placeColumn(int x, boolean particles) {
        for (int side : new int[]{-1, 1}) {
            BlockFace facing = side < 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            for (int y = 0; y < ROWS; y++) {
                Block chest = block(x, y, 4 * side);
                chest.setType(Material.CHEST, false);
                Chest chestData = (Chest) chest.getBlockData();
                chestData.setFacing(facing);
                chestData.setType(Chest.Type.SINGLE);
                chest.setBlockData(chestData, false);

                Block shelf = block(x, y, 3 * side);
                shelf.setType(SHELF, false);
                Directional shelfData = (Directional) shelf.getBlockData();
                shelfData.setFacing(facing);
                shelf.setBlockData(shelfData, false);
                if (particles) {
                    world.spawnParticle(Particle.BLOCK, shelf.getLocation().add(0.5, 0.5, 0.5 - side * 0.5),
                        24, 0.3, 0.3, 0.1, SHELF.createBlockData());
                }
            }
        }
    }

    private void registerColumn(int x) {
        ShelfManager shelves = hoardi.getShelfManager();
        NetworkManager networks = hoardi.getNetworkManager();
        for (int side : new int[]{-1, 1}) {
            for (int y = 0; y < ROWS; y++) {
                Block chest = block(x, y, 4 * side);
                Block shelf = block(x, y, 3 * side);
                shelves.registerShelf(shelf, chest);
                networks.onShelfRegistered(shelf.getLocation(), chest.getLocation());
            }
        }
    }

    private void unregisterSet() {
        ShelfManager shelves = hoardi.getShelfManager();
        NetworkManager networks = hoardi.getNetworkManager();
        for (Location shelf : new ArrayList<>(shelves.getTrackedShelves())) {
            if (shelf.getWorld() != world) continue;
            int x = shelf.getBlockX() - OX, z = shelf.getBlockZ() - OZ;
            if (x < -3 || x > 16 || z < -6 || z > 6) continue;
            Location chest = shelves.getChestLocation(shelf);
            shelves.unregisterShelf(shelf);
            if (chest != null) networks.onShelfUnregistered(chest);
        }
    }

    /** Back to the opening state: START_COLS columns, the hoard sorted, no loot. */
    private void resetContents() {
        unregisterSet();
        for (int x = 0; x < COLS; x++) {
            for (int side : new int[]{-1, 1}) {
                for (int y = 0; y < ROWS; y++) {
                    for (int z : new int[]{3 * side, 4 * side}) {
                        Block b = block(x, y, z);
                        if (b.getState() instanceof Container c) c.getInventory().clear();
                        if (b.getState() instanceof org.bukkit.block.Shelf s) s.getInventory().clear();
                        if (x >= START_COLS) b.setType(Material.SPRUCE_PLANKS, false);
                    }
                }
            }
        }
        for (int x = 0; x < START_COLS; x++) {
            placeColumn(x, false);
            registerColumn(x);
        }
        hoardi.getNetworkManager().setRoot(world, block(0, 0, -4).getLocation());

        List<Inventory> chests = new ArrayList<>();
        for (int x = 0; x < START_COLS; x++) {
            for (int side : new int[]{-1, 1}) {
                for (int y = 0; y < ROWS; y++) {
                    chests.add(((Container) block(x, y, 4 * side).getState()).getInventory());
                }
            }
        }
        List<ItemStack> hoard = stacks(Hoard.HOARD);
        Collections.shuffle(hoard, new Random(7));
        int i = 0;
        for (ItemStack stack : hoard) {
            for (int tries = 0; tries < chests.size(); tries++) {
                if (chests.get(i++ % chests.size()).addItem(stack).isEmpty()) break;
            }
        }
        sortNetwork();
        prepareDumpChest();
        getLogger().info("hoard: " + hoard.size() + " stacks in " + chests.size() + " chests");
    }

    /**
     * Hoardi spreads the hoard over every chest, so none is empty. Empty the emptiest chest at eye
     * level on the north wall (moving its few stacks next to their kind) to have somewhere to dump.
     */
    private void prepareDumpChest() {
        List<int[]> all = chestSlots();
        int[] target = all.stream()
            .filter(c -> c[2] < 0 && c[1] == 1 && c[0] >= 3 && c[0] <= 8)
            .min(Comparator.comparingInt(c -> used(chestInv(c))))
            .orElseThrow();
        Inventory source = chestInv(target);
        for (ItemStack stack : source.getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            List<int[]> others = new ArrayList<>(all);
            others.remove(target);
            others.sort(Comparator.comparingInt(c -> -count(chestInv(c), stack.getType())));
            for (int[] other : others) {
                if (chestInv(other).addItem(stack.clone()).isEmpty()) break;
            }
        }
        source.clear();
        dumpChest = target;
    }

    private void sortNetwork() {
        ChestNetwork network = hoardi.getNetworkManager().getNetworkForChest(block(0, 0, -4).getLocation());
        if (network == null) {
            getLogger().warning("no network at the set");
            return;
        }
        network.markDirty();
        FullReorganizeTask.trigger(hoardi, network);
    }

    static List<ItemStack> stacks(String[] table) {
        List<ItemStack> out = new ArrayList<>();
        for (String row : table) {
            String[] parts = row.split(":");
            Material m = Material.matchMaterial(parts[0]);
            if (m == null) {
                Bukkit.getLogger().warning("[HoardiDemo] unknown material " + parts[0]);
                continue;
            }
            int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            while (count > 0) {
                int n = Math.min(count, m.getMaxStackSize());
                out.add(new ItemStack(m, n));
                count -= n;
            }
        }
        return out;
    }

    /** Network chests of the north wall (side -1) in the given row, as inventories with their column. */
    private List<int[]> chestSlots() {
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < COLS; x++) {
            for (int side : new int[]{-1, 1}) {
                for (int y = 0; y < ROWS; y++) {
                    if (block(x, y, 4 * side).getType() == Material.CHEST) out.add(new int[]{x, y, side});
                }
            }
        }
        return out;
    }

    private Inventory chestInv(int[] c) {
        return ((Container) block(c[0], c[1], 4 * c[2]).getState()).getInventory();
    }

    private static int count(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack s : inv.getContents()) if (s != null && s.getType() == m) n += s.getAmount();
        return n;
    }

    private static int used(Inventory inv) {
        int n = 0;
        for (ItemStack s : inv.getContents()) if (s != null && !s.getType().isAir()) n++;
        return n;
    }

    // ------------------------------------------------------------------ shots

    record Key(double t, double x, double y, double z, double yaw, double pitch) {}

    static final class Shot {
        final String name;
        final double seconds;
        final List<Key> keys = new ArrayList<>();
        final Map<Integer, List<Runnable>> actions = new TreeMap<>();

        Shot(String name, double seconds) {
            this.name = name;
            this.seconds = seconds;
        }

        Shot key(double t, double x, double y, double z, double yaw, double pitch) {
            if (!keys.isEmpty()) {
                double prev = keys.get(keys.size() - 1).yaw();
                while (yaw - prev > 180) yaw -= 360;
                while (yaw - prev < -180) yaw += 360;
            }
            keys.add(new Key(t, x, y, z, yaw, pitch));
            return this;
        }

        Shot at(double sec, Runnable action) {
            actions.computeIfAbsent((int) Math.round(sec * 20), k -> new ArrayList<>()).add(action);
            return this;
        }

        /**
         * Camera pose at time t: Catmull-Rom through the keys. A simple two-key move is smoothstep-eased;
         * longer paths keep their key times so captions can be timed against them.
         */
        double[] pose(double t) {
            Key first = keys.get(0), last = keys.get(keys.size() - 1);
            if (keys.size() == 1 || t <= first.t()) return vec(first);
            if (t >= last.t()) return vec(last);
            double tt = t;
            if (keys.size() == 2) {
                double p = (t - first.t()) / (last.t() - first.t());
                tt = first.t() + p * p * (3 - 2 * p) * (last.t() - first.t());
            }
            int i = 0;
            while (i < keys.size() - 2 && keys.get(i + 1).t() <= tt) i++;
            Key k1 = keys.get(i), k2 = keys.get(i + 1);
            Key k0 = keys.get(Math.max(i - 1, 0)), k3 = keys.get(Math.min(i + 2, keys.size() - 1));
            double u = (tt - k1.t()) / (k2.t() - k1.t());
            double[] a = vec(k0), b = vec(k1), c = vec(k2), d = vec(k3), out = new double[5];
            for (int j = 0; j < 5; j++) {
                out[j] = 0.5 * (2 * b[j] + (-a[j] + c[j]) * u + (2 * a[j] - 5 * b[j] + 4 * c[j] - d[j]) * u * u
                    + (-a[j] + 3 * b[j] - 3 * c[j] + d[j]) * u * u * u);
            }
            return out;
        }

        private static double[] vec(Key k) {
            return new double[]{k.x(), k.y(), k.z(), k.yaw(), k.pitch()};
        }
    }

    /** Camera in the aisle looking straight at the shelf in front of a chest. */
    static double[] facing(int[] c, double distance) {
        int side = c[2];
        double shelfFace = side < 0 ? -2.0 : 3.0;
        double z = shelfFace - side * distance;
        double yaw = side < 0 ? 180 : 0;
        return new double[]{c[0] + 0.5, c[1] + 0.5, z, yaw};
    }

    private int[] dumpChest;

    static double clampX(double x) {
        return Math.max(-1.4, Math.min(14.4, x));
    }

    private void defineShots() {
        shots.put("hall", () -> new Shot("hall", 7)
            .key(0, -1.6, 2.3, 0.5, -90, 9)
            .key(7, 5.0, 1.75, 0.5, -90, 4));

        shots.put("dump", () -> {
            int[] target = dumpChest != null ? dumpChest : new int[]{5, 1, -1};
            double[] f = facing(target, 1.7);
            Inventory inv = chestInv(target);
            List<ItemStack> loot = stacks(Hoard.LOOT);
            Shot s = new Shot("dump", 9.5)
                .key(0, clampX(f[0] + 1.6), f[1] + 0.45, f[2] - target[2] * 1.3, f[3] + 35 * target[2], 10)
                .key(2.2, f[0], f[1] + 0.12, f[2], f[3], 4);
            s.at(2.6, () -> camPlayer().openInventory(inv));
            for (int i = 0; i < loot.size() && i < 27; i++) {
                ItemStack stack = loot.get(i);
                int slot = i;
                s.at(3.2 + i * 0.1, () -> inv.setItem(slot, stack));
            }
            s.at(8.6, () -> camPlayer().closeInventory());
            return s;
        });

        shots.put("sorted", () -> {
            int[] target = dumpChest != null ? dumpChest : new int[]{8, 1, -1};
            double[] f = facing(target, 1.7);
            return new Shot("sorted", 7)
                .key(0, f[0], f[1] + 0.12, f[2], f[3], 4)
                .key(7, f[0] + 2.2, f[1] + 0.6, 1.9, f[3] - 38, 12);
        });

        shots.put("find", () -> {
            int[] best = chestSlots().stream()
                .filter(c -> c[1] >= 1)
                .max(Comparator.comparingInt(c -> count(chestInv(c), Material.RAW_IRON)))
                .orElse(new int[]{3, 1, -1});
            double[] f = facing(best, 1.7);
            Inventory inv = chestInv(best);
            return new Shot("find", 8)
                .key(0, clampX(f[0] - 3.5), f[1] + 0.5, 0.5 - best[2] * 0.7, f[3] - 40 * best[2], 8)
                .key(2.8, f[0], f[1] + 0.12, f[2], f[3], 4)
                .at(3.3, () -> camPlayer().openInventory(inv))
                .at(7.6, () -> camPlayer().closeInventory());
        });

        shots.put("grow", () -> new Shot("grow", 11)
            .key(0, 12.3, 1.7, 1.9, 180, 4)
            .key(11, 12.0, 1.65, 1.3, 180, 3)
            .at(1.2, () -> placeColumn(START_COLS, true))
            .at(1.8, () -> placeColumn(START_COLS + 1, true))
            .at(2.4, () -> placeColumn(START_COLS + 2, true))
            .at(3.4, () -> {
                for (int x = START_COLS; x < COLS; x++) registerColumn(x);
                sortNetwork();
            }));

        shots.put("setup", () -> setup.shot());

        shots.put("end", () -> new Shot("end", 5)
            .key(0, -1.4, 2.5, 0.5, -90, 11)
            .key(5, -1.9, 2.7, 0.5, -90, 12));
    }

    // ------------------------------------------------------------------ camera

    private Player camPlayer() {
        Player p = Bukkit.getPlayerExact(CAM_PLAYER);
        if (p == null) throw new IllegalStateException(CAM_PLAYER + " is not online");
        return p;
    }

    private ItemDisplay spawnCam(double[] pose) {
        Location l = at(pose[0], pose[1], pose[2]);
        l.setYaw((float) pose[3]);
        l.setPitch((float) pose[4]);
        return world.spawn(l, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(2);
        });
    }

    private void moveCam(double[] pose) {
        Location l = at(pose[0], pose[1], pose[2]);
        l.setYaw((float) pose[3]);
        l.setPitch((float) pose[4]);
        cam.teleport(l);
    }

    private void poseStill(Shot shot, double sec) {
        stopCamera();
        Player p = camPlayer();
        p.setGameMode(GameMode.SPECTATOR);
        double[] pose = shot.pose(sec);
        p.teleport(at(pose[0], pose[1], pose[2]));
        cam = spawnCam(pose);
        Bukkit.getScheduler().runTaskLater(this, () -> p.setSpectatorTarget(cam), 5);
    }

    private void stopCamera() {
        if (running != null) running.cancel();
        running = null;
        Player p = Bukkit.getPlayerExact(CAM_PLAYER);
        if (p != null && p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
        if (cam != null) cam.remove();
        cam = null;
    }

    private void mark(String what) {
        try (FileWriter w = new FileWriter(marks, true)) {
            w.write(System.currentTimeMillis() + " " + what + "\n");
        } catch (IOException e) {
            getLogger().warning("marks.log: " + e.getMessage());
        }
    }

    private void runShots(List<String> names) {
        stopCamera();
        Player p = camPlayer();
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(names.get(0).equals("setup") ? setup.at(0.5, 1, 3) : at(4, 1, 0.5));
        mark("run " + String.join(",", names));
        running = new BukkitRunnable() {
            int warmup = 60, index = -1, tick;
            Shot shot;
            ItemDisplay next;

            @Override
            public void run() {
                // a focus change may have left the pause menu open: an opened+closed container clears any screen
                if (warmup == 40) p.openInventory(Bukkit.createInventory(null, 9));
                if (warmup == 36) p.closeInventory();
                if (warmup-- > 0) return;
                if (shot == null || tick > shot.seconds * 20) {
                    if (shot != null) mark("end " + shot.name);
                    if (++index >= names.size()) {
                        mark("done");
                        stopCamera();
                        return;
                    }
                    shot = shots.get(names.get(index)).get();
                    tick = -2;
                    next = spawnCam(shot.pose(0));
                }
                if (tick == 0) {
                    // the new camera entity exists on the client by now: cut to it
                    p.setSpectatorTarget(next);
                    if (cam != null) cam.remove();
                    cam = next;
                    mark("start " + shot.name);
                }
                if (tick >= 0) {
                    for (Runnable action : shot.actions.getOrDefault(tick, List.of())) action.run();
                    moveCam(shot.pose(tick / 20.0));
                    if (p.getSpectatorTarget() != cam) p.setSpectatorTarget(cam);
                }
                tick++;
            }
        }.runTaskTimer(this, 1, 1);
    }
}
