package xpncvr.openatlas;

public record LayerKey(String server, String dimension, int layer) {
    public static final int SURFACE = Integer.MAX_VALUE;
    public static final int BAND_HEIGHT = 16;

    public boolean isSurface() {
        return layer == SURFACE;
    }

    public LayerKey withLayer(int newLayer) {
        return new LayerKey(server, dimension, newLayer);
    }

    public int bandBottom() {
        return layer * BAND_HEIGHT;
    }

    public int bandTop() {
        return bandBottom() + BAND_HEIGHT - 1;
    }

    public static int layerFor(int caveStart) {
        return caveStart == SURFACE ? SURFACE : Math.floorDiv(caveStart, BAND_HEIGHT);
    }
}
