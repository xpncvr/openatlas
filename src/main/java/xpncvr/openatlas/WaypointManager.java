package xpncvr.openatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WaypointManager {
    public static final WaypointManager INSTANCE = new WaypointManager();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final long UNDO_MILLIS = 30_000L;

    private record Deleted(Waypoint waypoint, long expires) {}

    private final List<Waypoint> waypoints = new ArrayList<>();
    private final List<Deleted> recentlyDeleted = new ArrayList<>();
    private Path file;

    private WaypointManager() {}

    public void load(String world) {
        waypoints.clear();
        recentlyDeleted.clear();
        file = fileFor(world);
        waypoints.addAll(read(file));
    }

    public int importFrom(String world) {
        int added = 0;
        for (Waypoint w : read(fileFor(world))) {
            if (w.type == Waypoint.Type.DEATH) continue;
            boolean dup = waypoints.stream().anyMatch(o -> o.name.equals(w.name) && o.dimension.equals(w.dimension)
                    && o.x == w.x && o.y == w.y && o.z == w.z);
            if (dup) continue;
            waypoints.add(w);
            added++;
        }
        if (added > 0) save();
        return added;
    }

    private static Path fileFor(String world) {
        return DiskCache.serverDir(world).resolve("waypoints.json");
    }

    private static List<Waypoint> read(Path f) {
        List<Waypoint> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        try (Reader r = Files.newBufferedReader(f)) {
            List<Waypoint> list = GSON.fromJson(r, new TypeToken<List<Waypoint>>() {}.getType());
            if (list != null) {
                for (Waypoint w : list) {
                    if (w.name == null || w.dimension == null) continue;
                    if (w.type == null) w.type = Waypoint.Type.NORMAL;
                    if (w.set == null) w.set = "default";
                    out.add(w);
                }
            }
        } catch (Exception e) {
            OpenAtlas.LOGGER.warn("Failed to read waypoints {}: {}", f, e.getMessage());
        }
        return out;
    }

    public void unload() {
        save();
        recentlyDeleted.clear();
        waypoints.clear();
        file = null;
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(waypoints, w);
            }
        } catch (Exception e) {
            OpenAtlas.LOGGER.warn("Failed to write waypoints {}: {}", file, e.getMessage());
        }
    }

    public List<Waypoint> getWaypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    public Set<String> getSets() {
        Set<String> sets = new LinkedHashSet<>();
        sets.add("default");
        for (Waypoint w : waypoints) sets.add(w.set);
        return sets;
    }

    public void add(Waypoint w) {
        waypoints.add(w);
        save();
    }

    public void remove(Waypoint w) {
        if (!waypoints.remove(w)) return;
        recentlyDeleted.add(new Deleted(w, System.currentTimeMillis() + UNDO_MILLIS));
        save();
    }

    public boolean canUndoDelete() {
        purgeDeleted();
        return !recentlyDeleted.isEmpty();
    }

    public Waypoint undoDelete() {
        purgeDeleted();
        if (recentlyDeleted.isEmpty()) return null;
        Waypoint w = recentlyDeleted.removeLast().waypoint();
        waypoints.add(w);
        save();
        return w;
    }

    private void purgeDeleted() {
        long now = System.currentTimeMillis();
        recentlyDeleted.removeIf(d -> d.expires() <= now);
    }

    public void addDeath(int x, int y, int z, String dimension) {
        if (!Settings.INSTANCE.deathWaypoints) return;
        waypoints.removeIf(w -> w.type == Waypoint.Type.DEATH);
        Waypoint w = new Waypoint("Last death", x, y, z, 0xFFFF5555, dimension);
        w.initials = "D";
        w.type = Waypoint.Type.DEATH;
        waypoints.add(w);
        save();
    }
}
