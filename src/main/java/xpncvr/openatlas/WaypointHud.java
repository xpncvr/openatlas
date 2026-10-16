package xpncvr.openatlas;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

public final class WaypointHud {
    private static final float TEXT_SCALE = 0.5f;
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Vector4f CLIP = new Vector4f();
    private static Vec3 camPos;

    private WaypointHud() {}

    public static void capture(Matrix4fc projection, Matrix4fc viewRotation, Vec3 pos) {
        VIEW_PROJ.set(projection).mul(viewRotation);
        camPos = pos;
    }

    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Settings cfg = Settings.INSTANCE;
        if (!cfg.showWorldWaypoints || camPos == null || mc.level == null || mc.player == null || mc.gui.hud.isHidden()) return;

        int w = g.guiWidth(), h = g.guiHeight();
        String dim = MapSession.dimension();

        for (Waypoint wp : WaypointManager.INSTANCE.getWaypoints()) {
            if (!wp.visible || !dim.equals(wp.dimension)) continue;
            double dx = wp.x + 0.5 - camPos.x, dy = wp.y + 0.5 - camPos.y, dz = wp.z + 0.5 - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (cfg.worldWaypointRange > 0 && dist > cfg.worldWaypointRange) continue;
            VIEW_PROJ.transform(CLIP.set((float) dx, (float) dy, (float) dz, 1.0f));
            if (CLIP.w <= 0.05f) continue;
            float sx = (CLIP.x / CLIP.w * 0.5f + 0.5f) * w;
            float sy = (0.5f - CLIP.y / CLIP.w * 0.5f) * h;
            if (sx < -50 || sx > w + 50 || sy < -20 || sy > h + 20) continue;

            boolean near = Math.abs(sx - w / 2f) < 30 && Math.abs(sy - h / 2f) < 20;
            String label = wp.label();
            String info = near ? wp.name + " (" + (int) dist + "m)" : (int) dist + "m";
            int lw = mc.font.width(label), iw = mc.font.width(info);

            g.pose().pushMatrix();
            g.pose().translate(sx, sy);
            g.pose().scale(TEXT_SCALE, TEXT_SCALE);
            g.fill(-lw / 2 - 2, -6, lw - lw / 2 + 2, 5, 0xA0000000);
            g.outline(-lw / 2 - 2, -6, lw + 4, 11, wp.color);
            g.text(mc.font, label, -lw / 2, -4, wp.color, false);
            g.text(mc.font, info, -iw / 2, 8, 0xFFFFFFFF, false);
            g.pose().popMatrix();
        }
    }
}
