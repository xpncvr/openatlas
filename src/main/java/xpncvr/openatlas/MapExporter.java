package xpncvr.openatlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class MapExporter {
    private static final int MAX_SIDE = 4096;

    private MapExporter() {}

    public static void export(LayerKey layer) {
        MapCache.INSTANCE.flushDirty();
        DiskCache.INSTANCE.submit(() -> {
            String msg;
            try {
                msg = run(layer);
            } catch (Exception e) {
                OpenAtlas.LOGGER.error("Map export failed", e);
                msg = "Map export failed: " + e.getMessage();
            }
            String text = msg;
            Minecraft.getInstance().execute(() -> Toast.show(text));
        });
    }

    private static String run(LayerKey layer) throws Exception {
        List<int[]> regions = DiskCache.listRegions(layer);
        if (regions.isEmpty()) return "Nothing to export for this layer yet";
        int minRx = Integer.MAX_VALUE, minRz = Integer.MAX_VALUE, maxRx = Integer.MIN_VALUE, maxRz = Integer.MIN_VALUE;
        for (int[] r : regions) {
            minRx = Math.min(minRx, r[0]);
            maxRx = Math.max(maxRx, r[0]);
            minRz = Math.min(minRz, r[1]);
            maxRz = Math.max(maxRz, r[1]);
        }
        long fullW = (long) (maxRx - minRx + 1) * DiskCache.REGION_PX;
        long fullH = (long) (maxRz - minRz + 1) * DiskCache.REGION_PX;
        int step = (int) Math.max(1, (Math.max(fullW, fullH) + MAX_SIDE - 1) / MAX_SIDE);
        int outW = (int) (fullW / step), outH = (int) (fullH / step);
        int regionOut = DiskCache.REGION_PX / step;

        Path dir = Settings.EXPORT_DIR;
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String layerName = layer.isSurface() ? "surface" : "cave" + layer.layer();
        Path file = dir.resolve(DiskCache.sanitize(layer.server()) + "_" + DiskCache.sanitize(layer.dimension()) + "_" + layerName + "_" + stamp + ".png");

        try (NativeImage out = new NativeImage(outW, outH, true)) {
            for (int[] r : regions) {
                MapCache.Region region = DiskCache.INSTANCE.loadSync(layer, r[0], r[1]);
                if (region == null) continue;
                int[] px = region.px();
                int ox = (r[0] - minRx) * regionOut, oz = (r[1] - minRz) * regionOut;
                for (int y = 0; y < regionOut; y++) {
                    int row = y * step * DiskCache.REGION_PX;
                    for (int x = 0; x < regionOut; x++) {
                        int c = px[row + x * step];
                        if (c != 0) out.setPixelABGR(ox + x, oz + y, c);
                    }
                }
            }
            out.writeToFile(file);
        }
        return "Exported " + file + (step > 1 ? " (1:" + step + " scale)" : "");
    }
}
