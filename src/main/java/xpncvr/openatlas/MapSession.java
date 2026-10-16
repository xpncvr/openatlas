package xpncvr.openatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import xpncvr.openatlas.mixin.BiomeManagerAccessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class MapSession {
    static final String NO_SERVER = "unknown";

    public record CarryOver(String from, String to, String dimension) {}

    private static String server = NO_SERVER;
    private static String world = NO_SERVER;
    private static String importCandidate;
    private static CarryOver carryOver;
    private static String dimension = Dimensions.OVERWORLD;
    private static boolean hasCeiling;
    private static final TreeSet<String> knownDimensions = new TreeSet<>();

    private MapSession() {}

    public static void join(ClientPacketListener connection, Minecraft mc) {
        server = resolveServer(connection, mc);
        if (mc.level != null) enterLevel(mc.level);
    }

    public static void leave() {
        WaypointManager.INSTANCE.unload();
        server = NO_SERVER;
        world = NO_SERVER;
        importCandidate = null;
        carryOver = null;
        knownDimensions.clear();
    }

    public static boolean joined() {
        return !NO_SERVER.equals(server);
    }

    public static void enterLevel(ClientLevel level) {
        if (joined()) selectWorld(level);
        dimension = level.dimension().identifier().toString();
        hasCeiling = level.dimensionType().hasCeiling();
        if (joined() && knownDimensions.add(dimension)) saveKnownDimensions();
    }

    public static String server() {
        return server;
    }

    public static boolean seedDetection() {
        return !WorldIndex.ignoresSeed(server);
    }

    public static void setSeedDetection(boolean enabled) {
        if (!joined()) return;
        WorldIndex.setIgnoreSeed(server, !enabled);
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) enterLevel(level);
    }

    public static String takeImportCandidate() {
        String c = importCandidate;
        importCandidate = null;
        return c;
    }

    public static CarryOver takeCarryOver() {
        CarryOver c = carryOver;
        carryOver = null;
        return c;
    }

    public static void answerImport(String source, boolean accept) {
        WorldIndex.decide(server, accept);
        if (!accept) return;
        String target = world;
        linkInto(target, () -> DiskCache.importRegions(source, target), () -> {
            WaypointManager.INSTANCE.importFrom(source);
            knownDimensions.addAll(readDimensions(dimensionsFile(source)));
            saveKnownDimensions();
            Toast.show(Component.translatable("openatlas.import.done", WorldIndex.serverOf(source)).getString());
        });
    }

    public static void answerCarryOver(CarryOver c, boolean accept) {
        if (!accept) return;
        linkInto(c.to(), () -> DiskCache.importDimension(c.from(), c.to(), c.dimension()),
                () -> Toast.show(Component.translatable("openatlas.seed.copied").getString()));
    }

    private static void linkInto(String target, Runnable copy, Runnable after) {
        if (!target.equals(world)) return;
        MapCache.INSTANCE.clear();
        DiskCache.INSTANCE.submit(() -> {
            copy.run();
            Minecraft.getInstance().execute(() -> {
                if (!target.equals(world)) return;
                after.run();
                MapCache.INSTANCE.clear();
            });
        });
    }

    public static String dimension() {
        return dimension;
    }

    public static boolean hasCeiling() {
        return hasCeiling;
    }

    public static LayerKey surface(String dim) {
        return new LayerKey(world, dim, LayerKey.SURFACE);
    }

    public static List<String> knownDimensions() {
        return new ArrayList<>(knownDimensions);
    }

    private static void selectWorld(ClientLevel level) {
        long s = ((BiomeManagerAccessor) level.getBiomeManager()).openatlas$biomeZoomSeed();
        String dim = level.dimension().identifier().toString();
        String key = WorldIndex.resolve(server, world, dim, s);
        if (key.equals(world)) return;
        String previous = world;
        boolean fresh = !Files.isDirectory(DiskCache.serverDir(key));
        if (!NO_SERVER.equals(previous)) {
            ChunkPipeline.reset();
            MapCache.INSTANCE.clear();
            WaypointManager.INSTANCE.unload();
        }
        world = key;
        WaypointManager.INSTANCE.load(world);
        knownDimensions.clear();
        knownDimensions.addAll(readDimensions(dimensionsFile(world)));
        carryOver = fresh && !NO_SERVER.equals(previous) && dim.equals(dimension)
                && Files.isDirectory(DiskCache.serverDir(previous).resolve(DiskCache.sanitize(dim)))
                ? new CarryOver(previous, key, dim) : null;
        importCandidate = WorldIndex.similar(server, s);
    }

    private static Path dimensionsFile(String w) {
        return DiskCache.serverDir(w).resolve("dimensions.txt");
    }

    private static List<String> readDimensions(Path f) {
        List<String> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        try {
            for (String line : Files.readAllLines(f)) {
                if (!line.isBlank()) out.add(line.trim());
            }
        } catch (IOException e) {
            OpenAtlas.LOGGER.warn("Failed to read {}: {}", f, e.getMessage());
        }
        return out;
    }

    private static void saveKnownDimensions() {
        Path f = dimensionsFile(world);
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, knownDimensions);
        } catch (IOException e) {
            OpenAtlas.LOGGER.warn("Failed to write {}: {}", f, e.getMessage());
        }
    }

    private static String resolveServer(ClientPacketListener connection, Minecraft mc) {
        if (connection.getServerData() != null) return connection.getServerData().ip;
        IntegratedServer sp = mc.getSingleplayerServer();
        return sp != null ? "sp_" + sp.getWorldData().getLevelName() : "singleplayer";
    }
}
