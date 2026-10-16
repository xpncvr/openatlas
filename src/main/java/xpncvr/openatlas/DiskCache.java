package xpncvr.openatlas;

import com.mojang.blaze3d.platform.NativeImage;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class DiskCache {
    public static final DiskCache INSTANCE = new DiskCache();

    public static final int REGION_PX = 512;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "openatlas-io");
        t.setDaemon(true);
        return t;
    });

    private DiskCache() {}

    public static Path serverDir(String server) {
        return Settings.BASE_DIR.resolve(sanitize(server));
    }

    public static Path layerDir(LayerKey key) {
        Path dir = serverDir(key.server()).resolve(sanitize(key.dimension()));
        return key.isSurface() ? dir : dir.resolve("caves").resolve(Integer.toString(key.layer()));
    }

    public void saveAsync(LayerKey key, int rx, int rz, int[] abgrPixels, short[] heights) {
        NativeImage img = image(abgrPixels, REGION_PX);
        NativeImage[] mips = new NativeImage[MipCache.LEVELS.length];
        for (int i = 0; i < mips.length; i++) {
            MipCache level = MipCache.LEVELS[i];
            mips[i] = image(level.downsample(abgrPixels), level.size);
        }
        byte[] hb = new byte[heights.length * 2];
        ByteBuffer.wrap(hb).asShortBuffer().put(heights);
        io.execute(() -> {
            try (img) {
                Path file = regionFile(key, rx, rz);
                Files.createDirectories(file.getParent());
                unlink(file);
                img.writeToFile(file);
                for (int i = 0; i < mips.length; i++) {
                    Path mf = mipFile(MipCache.LEVELS[i], key, rx, rz);
                    unlink(mf);
                    mips[i].writeToFile(mf);
                }
                unlink(heightFile(key, rx, rz));
                try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(heightFile(key, rx, rz)))) {
                    os.write(hb);
                }
            } catch (IOException e) {
                OpenAtlas.LOGGER.error("Failed to save region {} {},{}: {}", key, rx, rz, e.getMessage());
            } finally {
                for (NativeImage m : mips) m.close();
            }
        });
    }

    public void loadAsync(LayerKey key, int rx, int rz, Consumer<MapCache.Region> callback) {
        io.execute(() -> callback.accept(loadSync(key, rx, rz)));
    }

    public void submit(Runnable task) {
        io.execute(task);
    }

    private static Path regionFile(LayerKey key, int rx, int rz) {
        return layerDir(key).resolve("r." + rx + "." + rz + ".png");
    }

    private static NativeImage image(int[] abgr, int size) {
        NativeImage img = new NativeImage(size, size, false);
        MemoryUtil.memIntBuffer(img.getPointer(), size * size).put(abgr);
        return img;
    }

    private static Path mipFile(MipCache level, LayerKey key, int rx, int rz) {
        String suffix = level == MipCache.EIGHTH ? ".mip.png" : ".mip" + level.shift + ".png";
        return layerDir(key).resolve("r." + rx + "." + rz + suffix);
    }

    public int[] loadMip(MipCache level, LayerKey key, int rx, int rz) {
        Path file = mipFile(level, key, rx, rz);
        if (Files.exists(file)) {
            try (InputStream is = Files.newInputStream(file);
                 NativeImage img = NativeImage.read(NativeImage.Format.RGBA, is)) {
                if (img.getWidth() == level.size && img.getHeight() == level.size) return img.getPixelsABGR();
            } catch (IOException e) {
                OpenAtlas.LOGGER.warn("Failed to load mip {} {},{}: {}", key, rx, rz, e.getMessage());
            }
        }
        MapCache.Region region = loadSync(key, rx, rz);
        if (region == null) return null;
        int[] mip = level.downsample(region.px());
        try (NativeImage img = image(mip, level.size)) {
            unlink(file);
            img.writeToFile(file);
        } catch (IOException e) {
            OpenAtlas.LOGGER.warn("Failed to save mip {} {},{}: {}", key, rx, rz, e.getMessage());
        }
        return mip;
    }

    private static Path heightFile(LayerKey key, int rx, int rz) {
        return layerDir(key).resolve("r." + rx + "." + rz + ".hgt");
    }

    public MapCache.Region loadSync(LayerKey key, int rx, int rz) {
        Path file = regionFile(key, rx, rz);
        if (!Files.exists(file)) return null;
        int[] px;
        try (InputStream is = Files.newInputStream(file);
             NativeImage img = NativeImage.read(NativeImage.Format.RGBA, is)) {
            if (img.getWidth() != REGION_PX || img.getHeight() != REGION_PX) return null;
            px = img.getPixelsABGR();
        } catch (IOException e) {
            OpenAtlas.LOGGER.error("Failed to load region {} {},{}: {}", key, rx, rz, e.getMessage());
            return null;
        }
        short[] h = new short[REGION_PX * REGION_PX];
        Path hf = heightFile(key, rx, rz);
        boolean loaded = false;
        if (Files.exists(hf)) {
            try (InputStream is = new GZIPInputStream(Files.newInputStream(hf))) {
                byte[] hb = is.readAllBytes();
                if (hb.length == h.length * 2) {
                    ByteBuffer.wrap(hb).asShortBuffer().get(h);
                    loaded = true;
                }
            } catch (IOException e) {
                OpenAtlas.LOGGER.warn("Failed to load heights {} {},{}: {}", key, rx, rz, e.getMessage());
            }
        }
        if (!loaded) Arrays.fill(h, MapCache.NO_HEIGHT);
        return new MapCache.Region(px, h);
    }

    public static List<int[]> listRegions(LayerKey key) {
        List<int[]> out = new ArrayList<>();
        Path dir = layerDir(key);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith("r.") || !n.endsWith(".png")) return;
                String[] parts = n.substring(2, n.length() - 4).split("\\.");
                if (parts.length != 2) return;
                try {
                    out.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
                } catch (NumberFormatException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    public static List<Integer> listCaveLayers(LayerKey surface) {
        List<Integer> out = new ArrayList<>();
        Path caves = layerDir(surface).resolve("caves");
        if (!Files.isDirectory(caves)) return out;
        try (Stream<Path> dirs = Files.list(caves)) {
            dirs.forEach(p -> {
                try {
                    out.add(Integer.parseInt(p.getFileName().toString()));
                } catch (NumberFormatException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    public static void importRegions(String fromWorld, String toWorld) {
        copyRegions(serverDir(fromWorld), serverDir(toWorld));
    }

    public static void importDimension(String fromWorld, String toWorld, String dimension) {
        copyRegions(serverDir(fromWorld).resolve(sanitize(dimension)), serverDir(toWorld).resolve(sanitize(dimension)));
    }

    private static void copyRegions(Path from, Path to) {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> files = Files.walk(from)) {
            files.filter(Files::isRegularFile).forEach(src -> {
                String n = src.getFileName().toString();
                if (!n.startsWith("r.")) return;
                String[] parts = n.split("\\.");
                if (parts.length < 4) return;
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.exists(dst.resolveSibling(parts[0] + "." + parts[1] + "." + parts[2] + ".png"))) return;
                try {
                    Files.createDirectories(dst.getParent());
                    link(src, dst);
                } catch (IOException e) {
                    OpenAtlas.LOGGER.warn("Failed to import {}: {}", src, e.getMessage());
                }
            });
        } catch (IOException e) {
            OpenAtlas.LOGGER.warn("Failed to import from {}: {}", from, e.getMessage());
        }
    }

    private static void link(Path src, Path dst) throws IOException {
        try {
            Files.createLink(dst, src);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(src, dst);
        }
    }

    private static void unlink(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    public static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
