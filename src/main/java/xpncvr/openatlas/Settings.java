package xpncvr.openatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Settings {
    public static final Path BASE_DIR = FabricLoader.getInstance().getGameDir().resolve("openatlas");
    public static final Path EXPORT_DIR = BASE_DIR.resolve("exports");
    private static final Path FILE = BASE_DIR.resolve("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Settings INSTANCE = new Settings();

    public boolean showGrid = true;
    public boolean slopeShading = true;
    public boolean autoCaveMode = true;
    public int caveDepth = 32;
    public boolean showMapWaypoints = true;
    public boolean showWorldWaypoints = true;
    public int worldWaypointRange = 0;
    public boolean deathWaypoints = true;
    public boolean locatorPlayers = true;
    public boolean forceNamespacedTp = false;
    public boolean showMapMemory = false;
    public int blockUpdateDelayTicks = 100;

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE)) {
                Settings s = GSON.fromJson(r, Settings.class);
                if (s != null) INSTANCE = s;
            } catch (Exception e) {
                OpenAtlas.LOGGER.warn("Failed to read settings: {}", e.getMessage());
            }
        }
    }

    public static void save() {
        try {
            Files.createDirectories(BASE_DIR);
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(INSTANCE, w);
            }
        } catch (IOException e) {
            OpenAtlas.LOGGER.warn("Failed to write settings: {}", e.getMessage());
        }
    }
}
