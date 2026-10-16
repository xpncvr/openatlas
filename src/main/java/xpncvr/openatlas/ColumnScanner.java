package xpncvr.openatlas;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.concurrent.ConcurrentHashMap;

public final class ColumnScanner {
    public static final int SURFACE_PROBE = 4;

    private static final ConcurrentHashMap<BlockState, Boolean> MAPPED = new ConcurrentHashMap<>();

    private ColumnScanner() {}

    public static ChunkSample surface(DecodedChunk c) {
        ChunkSample s = new ChunkSample(c.cx, c.cz, false);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = c.surface(x, z);
                if (!probe(s, c, x, z, top, top - SURFACE_PROBE)) probe(s, c, x, z, c.maxY, c.minY);
            }
        }
        return s;
    }

    private static boolean probe(ChunkSample s, DecodedChunk c, int x, int z, int from, int to) {
        for (int y = from; y >= to; y--) {
            BlockState state = c.block(x, y, z);
            if (state != null && mapped(state)) {
                record(s, c, x, y, z, state);
                return true;
            }
        }
        return false;
    }

    public static ChunkSample cave(DecodedChunk c, int caveStart, int depth) {
        ChunkSample s = new ChunkSample(c.cx, c.cz, true);
        int start = Math.min(caveStart, c.maxY);
        int low = Math.max(caveStart - depth, c.minY);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean underAir = false;
                for (int y = start; y >= low; y--) {
                    BlockState state = c.block(x, y, z);
                    if (state == null || state.isAir()) {
                        underAir = true;
                    } else if ((underAir || !state.getFluidState().isEmpty()) && mapped(state)) {
                        record(s, c, x, y, z, state);
                        break;
                    }
                }
            }
        }
        return s;
    }

    private static void record(ChunkSample s, DecodedChunk c, int x, int y, int z, BlockState state) {
        int i = z << 4 | x;
        s.states[i] = state;
        s.heights[i] = y;
        s.biomes[i] = c.biome(x, y, z);
    }

    private static boolean mapped(BlockState state) {
        return MAPPED.computeIfAbsent(state, ColumnScanner::hasMapColor);
    }

    private static boolean hasMapColor(BlockState state) {
        try {
            MapColor mc = state.getMapColor(null, BlockPos.ZERO);
            return mc != null && mc != MapColor.NONE && mc.col != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
