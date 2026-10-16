package xpncvr.openatlas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MapCache {
    public static final MapCache INSTANCE = new MapCache();

    public static final int REGION_CHUNKS = 32;
    public static final int REGION_PX = 512;
    public static final short NO_HEIGHT = Short.MIN_VALUE;

    static final int MAX_REGIONS = 64;
    public static final long REGION_BYTES = (long) REGION_PX * REGION_PX * (Integer.BYTES + Short.BYTES);

    public record RegionKey(LayerKey layer, int rx, int rz) {}

    public record Region(int[] px, short[] heights) {
        static Region empty() {
            short[] h = new short[REGION_PX * REGION_PX];
            Arrays.fill(h, NO_HEIGHT);
            return new Region(new int[REGION_PX * REGION_PX], h);
        }
    }

    private record Area(LayerKey layer, int minRX, int minRZ, int maxRX, int maxRZ) {
        boolean contains(RegionKey k) {
            return k.layer().equals(layer) && k.rx() >= minRX && k.rx() <= maxRX && k.rz() >= minRZ && k.rz() <= maxRZ;
        }
    }

    private record ChunkWrite(int cx, int cz, ChunkColorizer.Result data) {}

    private final LinkedHashMap<RegionKey, Region> regions = new LinkedHashMap<>(MAX_REGIONS + 1, 0.75f, true);
    private final Map<RegionKey, List<ChunkWrite>> pending = new HashMap<>();
    private final Set<RegionKey> dirty = new HashSet<>();
    private final Set<RegionKey> loading = new HashSet<>();
    private final Set<RegionKey> missing = new HashSet<>();
    private Area pinned;
    private int generation;
    private volatile int version;

    private MapCache() {}

    public int version() {
        return version;
    }

    public void put(LayerKey layer, int cx, int cz, ChunkColorizer.Result data) {
        RegionKey rk = new RegionKey(layer, cx >> 5, cz >> 5);
        synchronized (this) {
            Region region = regions.get(rk);
            if (region == null) {
                if (missing.remove(rk)) {
                    region = Region.empty();
                    regions.put(rk, region);
                } else {
                    pending.computeIfAbsent(rk, k -> new ArrayList<>()).add(new ChunkWrite(cx, cz, data));
                    triggerLoad(rk);
                    return;
                }
            }
            if (!blit(region, cx, cz, data)) return;
            dirty.add(rk);
            version++;
        }
        MipCache.onChunkAll(rk, cx, cz, data.colors());
        evict();
    }

    public synchronized void fetchVisible(LayerKey layer, int minRX, int minRZ, int w, int h, int[][] out) {
        pinned = new Area(layer, minRX, minRZ, minRX + w - 1, minRZ + h - 1);
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                RegionKey rk = new RegionKey(layer, minRX + x, minRZ + z);
                Region region = regions.get(rk);
                if (region == null && !missing.contains(rk)) triggerLoad(rk);
                out[z * w + x] = region == null ? null : region.px();
            }
        }
    }

    public synchronized int getHeight(LayerKey layer, int x, int z) {
        Region region = regions.get(new RegionKey(layer, x >> 9, z >> 9));
        if (region == null) return NO_HEIGHT;
        return region.heights()[((z & 511) << 9) | (x & 511)];
    }

    public synchronized int regionCount() {
        return regions.size();
    }

    public synchronized int[] copyPixels(RegionKey rk) {
        Region region = regions.get(rk);
        return region == null ? null : region.px().clone();
    }

    public synchronized short[] edgeHeights(LayerKey layer, int x0, int z0, boolean alongX) {
        Region region = regions.get(new RegionKey(layer, x0 >> 9, z0 >> 9));
        if (region == null) return null;
        short[] out = new short[16];
        for (int i = 0; i < 16; i++) {
            int x = alongX ? x0 + i : x0, z = alongX ? z0 : z0 + i;
            out[i] = region.heights()[((z & 511) << 9) | (x & 511)];
        }
        return out;
    }

    public synchronized void unpin() {
        pinned = null;
    }

    public void flushDirty() {
        List<Map.Entry<RegionKey, Region>> toSave = new ArrayList<>();
        synchronized (this) {
            for (RegionKey key : dirty) {
                Region region = regions.get(key);
                if (region != null) toSave.add(Map.entry(key, region));
            }
            dirty.clear();
        }
        save(toSave);
    }

    public void clear() {
        flushDirty();
        synchronized (this) {
            regions.clear();
            pending.clear();
            loading.clear();
            missing.clear();
            pinned = null;
            generation++;
            version++;
        }
        MipCache.clearAll();
    }

    private static void save(List<Map.Entry<RegionKey, Region>> list) {
        for (Map.Entry<RegionKey, Region> e : list) {
            RegionKey k = e.getKey();
            DiskCache.INSTANCE.saveAsync(k.layer(), k.rx(), k.rz(), e.getValue().px(), e.getValue().heights());
        }
    }

    private void triggerLoad(RegionKey rk) {
        if (!loading.add(rk)) return;
        int gen = generation;
        DiskCache.INSTANCE.loadAsync(rk.layer(), rk.rx(), rk.rz(), loaded -> {
            synchronized (this) {
                if (gen != generation) return;
                loading.remove(rk);
                List<ChunkWrite> writes = pending.remove(rk);
                if (loaded == null && writes == null) {
                    missing.add(rk);
                    return;
                }
                Region region = loaded != null ? loaded : Region.empty();
                if (writes != null) {
                    for (ChunkWrite w : writes) {
                        blit(region, w.cx(), w.cz(), w.data());
                        MipCache.onChunkAll(rk, w.cx(), w.cz(), w.data().colors());
                    }
                    dirty.add(rk);
                }
                regions.put(rk, region);
                version++;
            }
            evict();
        });
    }

    private void evict() {
        List<Map.Entry<RegionKey, Region>> toSave = new ArrayList<>();
        synchronized (this) {
            if (regions.size() <= MAX_REGIONS) return;
            Iterator<Map.Entry<RegionKey, Region>> it = regions.entrySet().iterator();
            while (regions.size() > MAX_REGIONS && it.hasNext()) {
                Map.Entry<RegionKey, Region> e = it.next();
                if (pinned != null && pinned.contains(e.getKey())) continue;
                if (dirty.remove(e.getKey())) toSave.add(Map.entry(e.getKey(), e.getValue()));
                it.remove();
            }
        }
        save(toSave);
    }

    private static boolean blit(Region region, int cx, int cz, ChunkColorizer.Result data) {
        int base = ((cz & (REGION_CHUNKS - 1)) * 16) * REGION_PX + (cx & (REGION_CHUNKS - 1)) * 16;
        boolean changed = false;
        for (int bz = 0; bz < 16 && !changed; bz++) {
            int off = base + bz * REGION_PX;
            changed = Arrays.mismatch(data.colors(), bz * 16, bz * 16 + 16, region.px(), off, off + 16) >= 0
                    || Arrays.mismatch(data.heights(), bz * 16, bz * 16 + 16, region.heights(), off, off + 16) >= 0;
        }
        if (!changed) return false;
        for (int bz = 0; bz < 16; bz++) {
            System.arraycopy(data.colors(), bz * 16, region.px(), base + bz * REGION_PX, 16);
            System.arraycopy(data.heights(), bz * 16, region.heights(), base + bz * REGION_PX, 16);
        }
        return true;
    }
}
