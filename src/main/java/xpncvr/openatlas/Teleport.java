package xpncvr.openatlas;

import net.minecraft.client.Minecraft;

public final class Teleport {
    private Teleport() {}

    public static void to(String dimension, int x, String y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        String args = "@s " + x + " " + y + " " + z;
        if (dimension.equals(MapSession.dimension())) {
            mc.getConnection().sendCommand((Settings.INSTANCE.forceNamespacedTp ? "minecraft:tp " : "tp ") + args);
        } else {
            mc.getConnection().sendCommand("execute in " + dimension + " run tp " + args);
        }
    }
}
