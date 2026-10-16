package xpncvr.openatlas;

import com.mojang.datafixers.util.Either;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Vec3i;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.waypoints.TrackedWaypoint;
import xpncvr.openatlas.mixin.ChunkWaypointAccessor;
import xpncvr.openatlas.mixin.ClientWaypointManagerAccessor;
import xpncvr.openatlas.mixin.Vec3iWaypointAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LocatorPlayers {
    public static final double MAX_DISTANCE = 344.0;

    public record Marker(String name, double x, double z, int color, boolean exact) {}

    private LocatorPlayers() {}

    public static List<Marker> collect() {
        List<Marker> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.level == null || mc.player == null) return out;
        for (TrackedWaypoint wp : ((ClientWaypointManagerAccessor) conn.getWaypointManager()).openatlas$waypoints().values()) {
            Either<UUID, String> id = wp.id();
            UUID uuid = id.left().orElse(null);
            double x, z;
            boolean exact;
            Player live = uuid != null ? mc.level.getPlayerByUUID(uuid) : null;
            if (live != null && live != mc.player) {
                x = live.getX();
                z = live.getZ();
                exact = true;
            } else if ((Object) wp instanceof Vec3iWaypointAccessor v) {
                Vec3i p = v.openatlas$vector();
                x = p.getX() + 0.5;
                z = p.getZ() + 0.5;
                exact = true;
            } else if ((Object) wp instanceof ChunkWaypointAccessor c) {
                ChunkPos p = c.openatlas$chunkPos();
                x = (p.x() << 4) + 8;
                z = (p.z() << 4) + 8;
                exact = false;
            } else {
                continue;
            }
            double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
            if (dx * dx + dz * dz > MAX_DISTANCE * MAX_DISTANCE) continue;
            out.add(new Marker(name(conn, id), x, z, color(wp), exact));
        }
        return out;
    }

    private static String name(ClientPacketListener conn, Either<UUID, String> id) {
        return id.map(uuid -> {
            PlayerInfo info = conn.getPlayerInfo(uuid);
            return info != null ? info.getProfile().name() : uuid.toString().substring(0, 8);
        }, s -> s);
    }

    private static int color(TrackedWaypoint wp) {
        return wp.icon().color.orElseGet(() -> wp.id().map(
                uuid -> ARGB.setBrightness(ARGB.color(255, uuid.hashCode()), 0.9F),
                name -> ARGB.setBrightness(ARGB.color(255, name.hashCode()), 0.9F))) | 0xFF000000;
    }
}
