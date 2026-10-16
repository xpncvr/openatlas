package xpncvr.openatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class WorldIndex {
    public static final long NO_SEED = 0L;

    private static final Path FILE = Settings.BASE_DIR.resolve("worlds.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Entry {
        String server;
        Map<String, Long> seeds = new HashMap<>();

        Entry(String server) {
            this.server = server;
        }
    }

    private static final class Data {
        Map<String, Entry> worlds = new LinkedHashMap<>();
        Map<String, Boolean> imports = new HashMap<>();
        Set<String> ignoreSeed = new HashSet<>();
    }

    private static Data data;

    private WorldIndex() {}

    public static String resolve(String server, String current, String dimension, long seed) {
        if (seed == NO_SEED || MapSession.NO_SERVER.equals(server) || ignoresSeed(server)) return server;
        Data d = data();
        for (Map.Entry<String, Entry> e : d.worlds.entrySet()) {
            Entry w = e.getValue();
            if (w.server.equals(server) && Long.valueOf(seed).equals(w.seeds.get(dimension))) return e.getKey();
        }
        String key = attachTarget(d, server, current, dimension);
        if (key == null) key = server + "#" + Long.toHexString(seed);
        d.worlds.computeIfAbsent(key, k -> new Entry(server)).seeds.putIfAbsent(dimension, seed);
        save();
        return key;
    }

    private static String attachTarget(Data d, String server, String current, String dimension) {
        Entry cur = d.worlds.get(current);
        if (cur != null && cur.server.equals(server) && !cur.seeds.containsKey(dimension)) return current;
        Entry primary = d.worlds.get(server);
        if (primary == null || !primary.seeds.containsKey(dimension)) return server;
        return null;
    }

    public static String similar(String server, long seed) {
        if (seed == NO_SEED || ignoresSeed(server)) return null;
        Data d = data();
        if (d.imports.containsKey(server)) return null;
        for (Map.Entry<String, Entry> e : d.worlds.entrySet()) {
            Entry w = e.getValue();
            if (w.server.equals(server) || !w.seeds.containsValue(seed)) continue;
            if (Files.isDirectory(DiskCache.serverDir(e.getKey()))) return e.getKey();
        }
        return null;
    }

    public static String serverOf(String world) {
        Entry e = data().worlds.get(world);
        return e == null ? world : e.server;
    }

    public static void decide(String server, boolean imported) {
        data().imports.put(server, imported);
        save();
    }

    public static boolean ignoresSeed(String server) {
        return data().ignoreSeed.contains(server);
    }

    public static void setIgnoreSeed(String server, boolean ignore) {
        if (ignore) data().ignoreSeed.add(server);
        else data().ignoreSeed.remove(server);
        save();
    }

    private static Data data() {
        if (data != null) return data;
        data = new Data();
        if (!Files.exists(FILE)) return data;
        try (Reader r = Files.newBufferedReader(FILE)) {
            Data loaded = GSON.fromJson(r, Data.class);
            if (loaded != null) {
                if (loaded.worlds != null) loaded.worlds.forEach((k, v) -> {
                    if (v == null || v.server == null || MapSession.NO_SERVER.equals(v.server)) return;
                    if (v.seeds == null) v.seeds = new HashMap<>();
                    data.worlds.put(k, v);
                });
                if (loaded.imports != null) data.imports.putAll(loaded.imports);
                if (loaded.ignoreSeed != null) data.ignoreSeed.addAll(loaded.ignoreSeed);
            }
        } catch (Exception e) {
            OpenAtlas.LOGGER.warn("Failed to read {}: {}", FILE, e.getMessage());
        }
        return data;
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(data, w);
            }
        } catch (IOException e) {
            OpenAtlas.LOGGER.warn("Failed to write {}: {}", FILE, e.getMessage());
        }
    }
}
