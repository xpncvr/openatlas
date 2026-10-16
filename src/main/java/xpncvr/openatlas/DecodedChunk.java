package xpncvr.openatlas;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

public final class DecodedChunk {
    public final int cx, cz, minY, maxY;
    private final int minSection;
    private final LevelChunkSection[] sections;
    private final int[] surface = new int[256];

    private DecodedChunk(int cx, int cz, int minY, int maxY, int sectionCount) {
        this.cx = cx;
        this.cz = cz;
        this.minY = minY;
        this.maxY = maxY;
        this.minSection = minY >> 4;
        this.sections = new LevelChunkSection[sectionCount];
    }

    public static DecodedChunk fromPacket(Level level, int cx, int cz, ClientboundLevelChunkPacketData data) {
        DecodedChunk d = new DecodedChunk(cx, cz, level.getMinY(), level.getMaxY(), level.getSectionsCount());
        FriendlyByteBuf buf = data.getReadBuffer();
        for (int i = 0; i < d.sections.length; i++) {
            LevelChunkSection section = new LevelChunkSection(level.palettedContainerFactory());
            section.read(buf);
            d.sections[i] = section;
        }
        long[] raw = data.getHeightmaps().get(Heightmap.Types.WORLD_SURFACE);
        if (raw != null) {
            SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(level.getHeight() + 1), 256, raw);
            for (int i = 0; i < 256; i++) d.surface[i] = storage.get(i) + d.minY;
        } else {
            d.computeSurface();
        }
        return d;
    }

    public static DecodedChunk fromLevel(LevelChunk chunk, int fromY, int toY) {
        LevelChunkSection[] src = chunk.getSections();
        DecodedChunk d = new DecodedChunk(chunk.getPos().x(), chunk.getPos().z(), chunk.getMinY(), chunk.getMaxY(), src.length);
        int lo = fromY, hi = toY;
        for (int i = 0; i < 256; i++) {
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, i & 15, i >> 4);
            d.surface[i] = y;
            lo = Math.min(lo, y - ColumnScanner.SURFACE_PROBE - 1);
            hi = Math.max(hi, y);
        }
        int a = Math.max(0, (Math.max(lo, d.minY) >> 4) - d.minSection);
        int b = Math.min(src.length - 1, (Math.min(hi, d.maxY) >> 4) - d.minSection);
        for (int i = a; i <= b; i++) d.sections[i] = src[i].copy();
        return d;
    }

    private void computeSurface() {
        for (int i = 0; i < 256; i++) {
            surface[i] = minY;
            for (int y = maxY; y >= minY; y--) {
                if (!block(i & 15, y, i >> 4).isAir()) {
                    surface[i] = y + 1;
                    break;
                }
            }
        }
    }

    public int surface(int x, int z) {
        return surface[z << 4 | x];
    }

    public BlockState block(int x, int y, int z) {
        LevelChunkSection s = section(y);
        return s == null || s.hasOnlyAir() ? null : s.getBlockState(x, y & 15, z);
    }

    public Biome biome(int x, int y, int z) {
        LevelChunkSection s = section(y);
        return s == null ? null : s.getNoiseBiome(x >> 2, (y >> 2) & 3, z >> 2).value();
    }

    private LevelChunkSection section(int y) {
        int i = (y >> 4) - minSection;
        return i < 0 || i >= sections.length ? null : sections[i];
    }
}
