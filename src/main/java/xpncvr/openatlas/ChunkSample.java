package xpncvr.openatlas;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

public final class ChunkSample {
    public final int cx, cz;
    public final boolean cave;
    public final BlockState[] states = new BlockState[256];
    public final Biome[] biomes = new Biome[256];
    public final int[] heights = new int[256];

    ChunkSample(int cx, int cz, boolean cave) {
        this.cx = cx;
        this.cz = cz;
        this.cave = cave;
    }
}
