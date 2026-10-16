package xpncvr.openatlas;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MipCache {
    public static final MipCache HALF = new MipCache(1, 192);
    public static final MipCache QUARTER = new MipCache(2, 640);
    public static final MipCache EIGHTH = new MipCache(3, 2048);
    public static final MipCache[] LEVELS = {HALF, QUARTER, EIGHTH};

    public final int shift;
    public final int size;
    public final int maxMips;
    private final int cell;

    private final LinkedHashMap<MapCache.RegionKey, int[]> mips;
    private final Set<MapCache.RegionKey> loading = new HashSet<>();
    private final Set<MapCache.RegionKey> missing = new HashSet<>();
    private int generation;
    private volatile int version;

    private MipCache(int shift, int maxMips) {
        this.shift = shift;
        this.size = MapCache.REGION_PX >> shift;
        this.maxMips = maxMips;
        this.cell = 1 << shift;
        this.mips = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<MapCache.RegionKey, int[]> eldest) {
                return size() > MipCache.this.maxMips;
            }
        };
    }

    public static MipCache forShift(int shift) {
        for (MipCache m : LEVELS) {
            if (m.shift == shift) return m;
        }
        return null;
    }

    public static int versionAll() {
        int v = 0;
        for (MipCache m : LEVELS) v = v * 31 + m.version;
        return v;
    }

    public static long bytesAll() {
        long b = 0;
        for (MipCache m : LEVELS) b += m.count() * m.mipBytes();
        return b;
    }

    public static void onChunkAll(MapCache.RegionKey rk, int cx, int cz, int[] abgr) {
        for (MipCache m : LEVELS) m.onChunk(rk, cx, cz, abgr);
    }

    public static void clearAll() {
        for (MipCache m : LEVELS) m.clear();
    }

    public long mipBytes() {
        return (long) size * size * Integer.BYTES;
    }

    public synchronized void fetchVisible(LayerKey layer, int minRX, int minRZ, int w, int h, int[][] out) {
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                MapCache.RegionKey rk = new MapCache.RegionKey(layer, minRX + x, minRZ + z);
                int[] mip = mips.get(rk);
                if (mip == null && !missing.contains(rk)) load(rk);
                out[z * w + x] = mip;
            }
        }
    }

    public synchronized void onChunk(MapCache.RegionKey rk, int cx, int cz, int[] abgr) {
        int[] mip = mips.get(rk);
        if (mip == null) {
            if (!missing.remove(rk)) return;
            mip = new int[size * size];
            mips.put(rk, mip);
        }
        int per = 16 / cell;
        int ox = (cx & (MapCache.REGION_CHUNKS - 1)) * per;
        int oz = (cz & (MapCache.REGION_CHUNKS - 1)) * per;
        for (int mz = 0; mz < per; mz++) {
            for (int mx = 0; mx < per; mx++) {
                mip[(oz + mz) * size + ox + mx] = average(abgr, 16, mx * cell, mz * cell, cell);
            }
        }
        version++;
    }

    public synchronized int count() {
        return mips.size();
    }

    public synchronized void clear() {
        mips.clear();
        loading.clear();
        missing.clear();
        generation++;
        version++;
    }

    private void load(MapCache.RegionKey rk) {
        if (!loading.add(rk)) return;
        int gen = generation;
        int[] inMemory = MapCache.INSTANCE.copyPixels(rk);
        DiskCache.INSTANCE.submit(() -> {
            int[] mip = inMemory != null ? downsample(inMemory) : DiskCache.INSTANCE.loadMip(this, rk.layer(), rk.rx(), rk.rz());
            synchronized (this) {
                if (gen != generation) return;
                loading.remove(rk);
                if (mip == null) missing.add(rk);
                else mips.put(rk, mip);
                version++;
            }
        });
    }

    public int[] downsample(int[] regionAbgr) {
        int[] mip = new int[size * size];
        for (int mz = 0; mz < size; mz++) {
            for (int mx = 0; mx < size; mx++) {
                mip[mz * size + mx] = average(regionAbgr, MapCache.REGION_PX, mx * cell, mz * cell, cell);
            }
        }
        return mip;
    }

    private static int average(int[] px, int stride, int x0, int z0, int cell) {
        int r = 0, g = 0, b = 0, n = 0;
        for (int z = z0; z < z0 + cell; z++) {
            int row = z * stride;
            for (int x = x0; x < x0 + cell; x++) {
                int c = px[row + x];
                if (c == 0) continue;
                r += c & 0xFF;
                g += (c >> 8) & 0xFF;
                b += (c >> 16) & 0xFF;
                n++;
            }
        }
        return n == 0 ? 0 : 0xFF000000 | (b / n) << 16 | (g / n) << 8 | (r / n);
    }
}
