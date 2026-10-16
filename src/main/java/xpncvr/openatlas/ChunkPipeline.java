package xpncvr.openatlas;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPipeline {
    public enum Reason { PACKET, BLOCK_CHANGE, LAYER_SWITCH, SLICE_SHIFT, VIEWED_LAYER }

    private static final int WORKERS = 2;
    private static final int MAX_LIVE_IN_FLIGHT = WORKERS * 4;
    private static final long LIVE_COPY_ESTIMATE = 128 * 1024;
    private static final long FRAME_BUDGET_NANOS = 1_500_000L;
    private static final int NEARBY_RADIUS = 5;
    private static final int STATS_TICKS = 100;
    private static final int VIEW_SETTLE_TICKS = 10;

    private static final ExecutorService workers = Executors.newFixedThreadPool(WORKERS, r -> {
        Thread t = new Thread(r, "openatlas-colorizer");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger liveInFlight = new AtomicInteger();
    private static final AtomicLong queuedPacketBytes = new AtomicLong();
    private static final LongLinkedOpenHashSet redraw = new LongLinkedOpenHashSet();
    private static final Long2LongLinkedOpenHashMap dirty = new Long2LongLinkedOpenHashMap();
    private static final List<Runnable> heldPackets = new ArrayList<>();
    private static final int[] reasonCounts = new int[Reason.values().length];

    private static int viewedLayer = LayerKey.SURFACE;
    private static int requestedLayer = LayerKey.SURFACE;
    private static long requestedAt;
    private static long tick;

    private record Job(LayerKey layer, int caveStart) {}

    private record Plan(List<Job> jobs, int depth, boolean slope) {
        int lowestY() {
            int low = Integer.MAX_VALUE;
            for (Job j : jobs) if (!j.layer().isSurface()) low = Math.min(low, j.caveStart() - depth);
            return low;
        }

        int highestY() {
            int high = Integer.MIN_VALUE;
            for (Job j : jobs) if (!j.layer().isSurface()) high = Math.max(high, j.caveStart());
            return high;
        }
    }

    private ChunkPipeline() {}

    public static long queuedBytes() {
        return queuedPacketBytes.get() + (long) liveInFlight.get() * LIVE_COPY_ESTIMATE;
    }

    public static void reset() {
        redraw.clear();
        dirty.clear();
        heldPackets.clear();
        queuedPacketBytes.set(0);
        viewedLayer = LayerKey.SURFACE;
        requestedLayer = LayerKey.SURFACE;
    }

    public static void onChunkPacket(ClientLevel level, int cx, int cz, ClientboundLevelChunkPacketData data) {
        long key = ChunkPos.pack(cx, cz);
        redraw.remove(key);
        dirty.remove(key);
        count(Reason.PACKET);
        long bytes = data.getReadBuffer().readableBytes();
        queuedPacketBytes.addAndGet(bytes);
        Runnable submit = () -> {
            Plan plan = plan(level);
            workers.execute(() -> {
                try {
                    process(plan, DecodedChunk.fromPacket(level, cx, cz, data));
                } finally {
                    queuedPacketBytes.addAndGet(-bytes);
                }
            });
        };
        if (CaveTracker.known()) submit.run();
        else heldPackets.add(submit);
    }

    public static void markDirty(BlockPos pos) {
        dirty.putIfAbsent(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), tick);
    }

    public static void setViewedLayer(int layer) {
        requestedLayer = layer;
        requestedAt = tick;
        if (layer == LayerKey.SURFACE) viewedLayer = layer;
    }

    private static void settleViewedLayer() {
        if (requestedLayer == viewedLayer || tick - requestedAt < VIEW_SETTLE_TICKS) return;
        viewedLayer = requestedLayer;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            redrawAround(mc.level, mc.player.chunkPosition(), mc.options.getEffectiveRenderDistance(), Reason.VIEWED_LAYER);
        }
    }

    public static void onCaveChange(ClientLevel level, ChunkPos center, int change) {
        if (change == CaveTracker.REDRAW_ALL) {
            redrawAround(level, center, Minecraft.getInstance().options.getEffectiveRenderDistance(), Reason.LAYER_SWITCH);
        } else if (change == CaveTracker.REDRAW_NEARBY) {
            redrawAround(level, center, NEARBY_RADIUS, Reason.SLICE_SHIFT);
        }
    }

    public static void tick() {
        tick++;
        settleViewedLayer();
        if (CaveTracker.known() && !heldPackets.isEmpty()) {
            heldPackets.forEach(Runnable::run);
            heldPackets.clear();
        }
        long delay = Settings.INSTANCE.blockUpdateDelayTicks;
        while (!dirty.isEmpty() && tick - dirty.get(dirty.firstLongKey()) >= delay) {
            if (redraw.add(dirty.firstLongKey())) count(Reason.BLOCK_CHANGE);
            dirty.removeFirstLong();
        }
        if (tick % STATS_TICKS == 0) logStats();
    }

    public static void frame(ClientLevel level) {
        if (redraw.isEmpty() || !CaveTracker.known()) return;
        Plan plan = plan(level);
        int fromY = plan.lowestY(), toY = plan.highestY();
        long deadline = System.nanoTime() + FRAME_BUDGET_NANOS;
        while (!redraw.isEmpty() && liveInFlight.get() < MAX_LIVE_IN_FLIGHT && System.nanoTime() < deadline) {
            long key = redraw.removeFirstLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            DecodedChunk copy = DecodedChunk.fromLevel(chunk, fromY, toY);
            liveInFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    process(plan, copy);
                } finally {
                    liveInFlight.decrementAndGet();
                }
            });
        }
    }

    private static void process(Plan plan, DecodedChunk chunk) {
        try {
            int bx = chunk.cx << 4, bz = chunk.cz << 4;
            for (Job job : plan.jobs()) {
                ChunkSample sample = job.layer().isSurface()
                        ? ColumnScanner.surface(chunk)
                        : ColumnScanner.cave(chunk, job.caveStart(), plan.depth());
                short[] north = MapCache.INSTANCE.edgeHeights(job.layer(), bx, bz - 1, true);
                short[] west = MapCache.INSTANCE.edgeHeights(job.layer(), bx - 1, bz, false);
                MapCache.INSTANCE.put(job.layer(), chunk.cx, chunk.cz, ChunkColorizer.colorize(sample, plan.slope(), north, west));
            }
        } catch (RuntimeException e) {
            OpenAtlas.LOGGER.warn("Map update failed for chunk {},{}: {}", chunk.cx, chunk.cz, e.toString());
        }
    }

    private static Plan plan(ClientLevel level) {
        List<Job> jobs = new ArrayList<>(3);
        LayerKey surface = MapSession.surface(MapSession.dimension());
        int caveStart = CaveTracker.caveStart();
        boolean underground = caveStart != LayerKey.SURFACE;
        int playerLayer = LayerKey.layerFor(caveStart);
        if (!underground || !MapSession.hasCeiling()) jobs.add(new Job(surface, LayerKey.SURFACE));
        if (underground) jobs.add(new Job(surface.withLayer(playerLayer), caveStart));
        if (viewedLayer != LayerKey.SURFACE && viewedLayer != playerLayer) {
            LayerKey viewed = surface.withLayer(viewedLayer);
            jobs.add(new Job(viewed, viewed.bandTop()));
        }
        int depth = MapSession.hasCeiling() ? level.getHeight() : Settings.INSTANCE.caveDepth;
        return new Plan(jobs, depth, Settings.INSTANCE.slopeShading);
    }

    private static void redrawAround(ClientLevel level, ChunkPos center, int radius, Reason reason) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int cx = center.x() + dx, cz = center.z() + dz;
                if (level.getChunkSource().getChunkNow(cx, cz) != null && redraw.add(ChunkPos.pack(cx, cz))) count(reason);
            }
        }
        LongArrayList sorted = new LongArrayList(redraw);
        sorted.unstableSort((a, b) -> Long.compare(distSq(a, center), distSq(b, center)));
        redraw.clear();
        redraw.addAll(sorted);
    }

    private static long distSq(long key, ChunkPos center) {
        long dx = ChunkPos.getX(key) - center.x(), dz = ChunkPos.getZ(key) - center.z();
        return dx * dx + dz * dz;
    }

    private static void count(Reason reason) {
        reasonCounts[reason.ordinal()]++;
    }

    private static void logStats() {
        StringBuilder sb = new StringBuilder();
        for (Reason r : Reason.values()) {
            int n = reasonCounts[r.ordinal()];
            if (n == 0) continue;
            sb.append(' ').append(r.name().toLowerCase()).append('=').append(n);
            reasonCounts[r.ordinal()] = 0;
        }
        if (!sb.isEmpty()) OpenAtlas.LOGGER.info("Map chunk updates (last {}s):{}", STATS_TICKS / 20, sb);
    }
}
