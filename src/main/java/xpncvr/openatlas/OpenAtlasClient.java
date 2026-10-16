package xpncvr.openatlas;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class OpenAtlasClient implements ClientModInitializer {
    private static final int FLUSH_TICKS = 600;
    private static final int CAVE_CHECK_TICKS = 10;

    public static KeyMapping mapKey;
    private static KeyMapping waypointKey;
    private static KeyMapping waypointListKey;
    private static long tick;

    @Override
    public void onInitializeClient() {
        Settings.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(OpenAtlas.MOD_ID, "controls"));
        mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.openatlas.open", GLFW.GLFW_KEY_M, category));
        waypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.openatlas.waypoint", GLFW.GLFW_KEY_B, category));
        waypointListKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.openatlas.waypoints", GLFW.GLFW_KEY_U, category));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!MapSession.joined()) MapSession.join(handler, client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ChunkPipeline.reset();
            MapCache.INSTANCE.clear();
            MapSession.leave();
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            ChunkPipeline.reset();
            if (level == null) return;
            if (!MapSession.joined() && client.getConnection() != null) MapSession.join(client.getConnection(), client);
            MapSession.enterLevel(level);
            CaveTracker.reset();
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(OpenAtlas.MOD_ID, "waypoints"), WaypointHud::extract);
        ClientTickEvents.END_CLIENT_TICK.register(OpenAtlasClient::onTick);
    }

    private static void onTick(Minecraft client) {
        tick++;
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        if (level != null && player != null) {
            if (!CaveTracker.known() || tick % CAVE_CHECK_TICKS == 0) {
                ChunkPipeline.onCaveChange(level, player.chunkPosition(), CaveTracker.update(level, player));
            }
            ChunkPipeline.tick();
            if (client.screen == null) prompt(client);
        }
        if (tick % FLUSH_TICKS == 0) DiskCache.INSTANCE.submit(MapCache.INSTANCE::flushDirty);
        handleKeys(client, player);
    }

    private static void prompt(Minecraft client) {
        MapSession.CarryOver carry = MapSession.takeCarryOver();
        if (carry != null) {
            client.setScreen(new ConfirmScreen(accept -> {
                MapSession.answerCarryOver(carry, accept);
                client.setScreen(null);
            }, Component.translatable("openatlas.seed.title"), Component.translatable("openatlas.seed.message")));
            return;
        }
        String source = MapSession.takeImportCandidate();
        if (source == null) return;
        client.setScreen(new ConfirmScreen(accept -> {
            MapSession.answerImport(source, accept);
            client.setScreen(null);
        }, Component.translatable("openatlas.import.title"),
                Component.translatable("openatlas.import.message", WorldIndex.serverOf(source))));
    }

    private static void handleKeys(Minecraft client, LocalPlayer player) {
        while (mapKey.consumeClick()) {
            if (client.level != null) client.setScreen(new GuiWorldMap());
        }
        while (waypointKey.consumeClick()) {
            if (player != null) {
                BlockPos p = player.blockPosition();
                client.setScreen(new GuiWaypointEdit(null, null, p.getX(), p.getY(), p.getZ(), MapSession.dimension()));
            }
        }
        while (waypointListKey.consumeClick()) {
            if (client.level != null) client.setScreen(new GuiWaypointList(null));
        }
    }

    public static LayerKey currentLayer() {
        return MapSession.surface(MapSession.dimension()).withLayer(LayerKey.layerFor(CaveTracker.caveStart()));
    }

    public static void onPlayerDeath(LocalPlayer player) {
        BlockPos p = player.blockPosition();
        WaypointManager.INSTANCE.addDeath(p.getX(), p.getY(), p.getZ(), MapSession.dimension());
    }
}
