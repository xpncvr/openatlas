package xpncvr.openatlas;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.Blaze3D;

import java.nio.file.Files;

import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class GuiSettings extends Screen {
    private static final int[] CAVE_DEPTHS = {16, 32, 48, 64, 96};
    private static final int[] RANGES = {0, 250, 500, 1000, 2500, 5000};
    private static final int[] DELAYS = {20, 40, 100, 200};

    private final Screen parent;

    public GuiSettings(Screen parent) {
        super(Component.literal("Map Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Settings s = Settings.INSTANCE;
        int x = width / 2 - 155;
        int y = 40;
        toggle(x, y, "Chunk grid", () -> s.showGrid, v -> s.showGrid = v);
        toggle(x + 160, y, "Slope shading", () -> s.slopeShading, v -> s.slopeShading = v);
        y += 24;
        toggle(x, y, "Auto cave mode", () -> s.autoCaveMode, v -> s.autoCaveMode = v);
        cycle(x + 160, y, "Cave depth", CAVE_DEPTHS, () -> s.caveDepth, v -> s.caveDepth = v, v -> v + " blocks");
        y += 24;
        toggle(x, y, "Waypoints on map", () -> s.showMapWaypoints, v -> s.showMapWaypoints = v);
        toggle(x + 160, y, "Waypoints in world", () -> s.showWorldWaypoints, v -> s.showWorldWaypoints = v);
        y += 24;
        cycle(x, y, "Waypoint range", RANGES, () -> s.worldWaypointRange, v -> s.worldWaypointRange = v, v -> v == 0 ? "Unlimited" : v + "m");
        toggle(x + 160, y, "Death waypoints", () -> s.deathWaypoints, v -> s.deathWaypoints = v);
        y += 24;
        cycle(x, y, "Block update delay", DELAYS, () -> s.blockUpdateDelayTicks, v -> s.blockUpdateDelayTicks = v, v -> (v / 20) + "s");
        toggle(x + 160, y, "Players on map", () -> s.locatorPlayers, v -> s.locatorPlayers = v);
        y += 24;
        toggle(x, y, "Force minecraft:tp", () -> s.forceNamespacedTp, v -> s.forceNamespacedTp = v);
        toggle(x + 160, y, "Show map RAM", () -> s.showMapMemory, v -> s.showMapMemory = v);
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Open map folder"), b -> openFolder())
                .bounds(x, y, 150, 20).build());
        if (MapSession.joined()) {
            toggle(x + 160, y, "Detect worlds by seed", MapSession::seedDetection, MapSession::setSeedDetection);
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 100, height - 30, 200, 20).build());
    }

    private static void openFolder() {
        try {
            Files.createDirectories(Settings.BASE_DIR);
        } catch (Exception e) {
            OpenAtlas.LOGGER.warn("Failed to create {}: {}", Settings.BASE_DIR, e.getMessage());
        }
        Blaze3D.openPath(Settings.BASE_DIR);
    }

    private void toggle(int x, int y, String name, Supplier<Boolean> get, Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(Component.literal(name + ": " + (get.get() ? "ON" : "OFF")), b -> {
            set.accept(!get.get());
            b.setMessage(Component.literal(name + ": " + (get.get() ? "ON" : "OFF")));
        }).bounds(x, y, 150, 20).build());
    }

    private void cycle(int x, int y, String name, int[] values, Supplier<Integer> get, Consumer<Integer> set,
                       IntFunction<String> fmt) {
        addRenderableWidget(Button.builder(Component.literal(name + ": " + fmt.apply(get.get())), b -> {
            int cur = get.get();
            int next = values[0];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == cur) {
                    next = values[(i + 1) % values.length];
                    break;
                }
            }
            set.accept(next);
            b.setMessage(Component.literal(name + ": " + fmt.apply(next)));
        }).bounds(x, y, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Settings.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
