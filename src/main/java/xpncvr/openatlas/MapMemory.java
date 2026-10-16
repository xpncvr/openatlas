package xpncvr.openatlas;

public final class MapMemory {
    private static final long MB = 1024 * 1024;

    private MapMemory() {}

    public static long estimateBytes() {
        return MapCache.INSTANCE.regionCount() * MapCache.REGION_BYTES
                + MipCache.bytesAll()
                + ChunkPipeline.queuedBytes();
    }

    public static String label(long extraBytes) {
        return "Map RAM ~" + (estimateBytes() + extraBytes) / MB + " MB";
    }
}
