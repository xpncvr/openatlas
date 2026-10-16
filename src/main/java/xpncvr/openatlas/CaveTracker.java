package xpncvr.openatlas;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public final class CaveTracker {
    public static final int NO_REDRAW = 0;
    public static final int REDRAW_NEARBY = 1;
    public static final int REDRAW_ALL = 2;

    private static final int MIN_SLICE_SHIFT = 4;

    private static int caveStart = LayerKey.SURFACE;
    private static boolean known;
    private static int candidate = CaveDetector.UNKNOWN;
    private static int redrawnStart = LayerKey.SURFACE;

    private CaveTracker() {}

    public static void reset() {
        caveStart = LayerKey.SURFACE;
        known = false;
        candidate = CaveDetector.UNKNOWN;
        redrawnStart = LayerKey.SURFACE;
    }

    public static boolean known() {
        return known;
    }

    public static int caveStart() {
        return caveStart;
    }

    public static int update(ClientLevel level, LocalPlayer player) {
        boolean detect = Settings.INSTANCE.autoCaveMode || MapSession.hasCeiling();
        int next = detect ? CaveDetector.caveStart(level, player) : LayerKey.SURFACE;
        if (next == CaveDetector.UNKNOWN) return NO_REDRAW;

        boolean first = !known;
        known = true;
        int nextLayer = LayerKey.layerFor(next);
        boolean layerChange = nextLayer != LayerKey.layerFor(caveStart);
        if (!first && layerChange && (candidate == CaveDetector.UNKNOWN || LayerKey.layerFor(candidate) != nextLayer)) {
            candidate = next;
            return NO_REDRAW;
        }
        candidate = CaveDetector.UNKNOWN;
        caveStart = next;
        if (first) {
            redrawnStart = next;
            return NO_REDRAW;
        }
        if (layerChange) {
            redrawnStart = next;
            return REDRAW_ALL;
        }
        if (next != LayerKey.SURFACE && Math.abs(next - redrawnStart) >= MIN_SLICE_SHIFT) {
            redrawnStart = next;
            return REDRAW_NEARBY;
        }
        return NO_REDRAW;
    }
}
