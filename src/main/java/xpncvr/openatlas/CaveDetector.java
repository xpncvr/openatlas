package xpncvr.openatlas;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;

public final class CaveDetector {
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private static final int SKY_LIGHT_LIMIT = 8;
    private static final int MIN_COVER = 3;
    private static final int SLICE_ABOVE_HEAD = 3;

    private CaveDetector() {}

    public static int caveStart(ClientLevel level, LocalPlayer player) {
        int x = Mth.floor(player.getX()), z = Mth.floor(player.getZ());
        int head = Mth.floor(player.getY()) + 1;
        if (head > level.getMaxY() || head < level.getMinY()) return LayerKey.SURFACE;
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return UNKNOWN;

        DimensionType type = level.dimensionType();
        if (type.hasCeiling()) {
            return head < level.getMinY() + type.logicalHeight() ? head + SLICE_ABOVE_HEAD : LayerKey.SURFACE;
        }

        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        if (top - head < MIN_COVER) return LayerKey.SURFACE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top - 1, z);
        if (!chunk.getBlockState(pos).getFluidState().isEmpty()) return LayerKey.SURFACE;
        if (level.getBrightness(LightLayer.SKY, pos.setY(head)) >= SKY_LIGHT_LIMIT) return LayerKey.SURFACE;
        return head + SLICE_ABOVE_HEAD;
    }
}
