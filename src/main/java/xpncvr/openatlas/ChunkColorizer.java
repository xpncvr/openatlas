package xpncvr.openatlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkColorizer {

    public record Result(int[] colors, short[] heights) {}

    private record BlockColor(int base, Tint tint, int tintColor) {}

    private enum Tint { NONE, GRASS, FOLIAGE, WATER, FIXED }

    private static final Set<Block> GRASS_TINTED = Set.of(Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.FERN,
            Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.POTTED_FERN, Blocks.BUSH, Blocks.SUGAR_CANE,
            Blocks.PINK_PETALS, Blocks.WILDFLOWERS);
    private static final Set<Block> FOLIAGE_TINTED = Set.of(Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES,
            Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.VINE, Blocks.MANGROVE_LEAVES);
    private static final Set<Block> WATER_TINTED = Set.of(Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.WATER_CAULDRON);

    private static final int DEFAULT_GRASS = 0x91BD59;
    private static final int DEFAULT_FOLIAGE = 0x77AB2F;
    private static final int DEFAULT_WATER = 0x3F76E4;
    private static final int VOID_COLOR = 0xFF060606;
    private static final int UNKNOWN_COLOR = 0xFF888888;
    private static final float SHADE_UP = 1.15f;
    private static final float SHADE_DOWN = 0.78f;
    private static final Direction[] FACE_ORDER = {Direction.UP, null, Direction.NORTH, Direction.SOUTH};

    private static final ConcurrentHashMap<BlockState, BlockColor> BLOCK_COLORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Identifier, Integer> SPRITE_COLORS = new ConcurrentHashMap<>();
    private static final ThreadLocal<RandomSource> RANDOM = ThreadLocal.withInitial(() -> RandomSource.create(0L));
    private static final ThreadLocal<List<BlockStateModelPart>> PARTS = ThreadLocal.withInitial(ArrayList::new);

    private ChunkColorizer() {}

    public static Result colorize(ChunkSample s, boolean slope, short[] northEdge, short[] westEdge) {
        int[] argb = new int[256];
        short[] heights = new short[256];
        int baseX = s.cx << 4, baseZ = s.cz << 4;

        for (int i = 0; i < 256; i++) {
            BlockState state = s.states[i];
            if (state == null) {
                argb[i] = s.cave ? VOID_COLOR : UNKNOWN_COLOR;
                heights[i] = MapCache.NO_HEIGHT;
                continue;
            }
            heights[i] = (short) s.heights[i];
            argb[i] = color(state, s.biomes[i], baseX + (i & 15), baseZ + (i >> 4));
        }

        if (slope) shade(argb, heights, northEdge, westEdge);

        int[] abgr = new int[256];
        for (int i = 0; i < 256; i++) abgr[i] = toAbgr(argb[i]);
        return new Result(abgr, heights);
    }

    private static void shade(int[] argb, short[] heights, short[] northEdge, short[] westEdge) {
        for (int i = 0; i < 256; i++) {
            if (heights[i] == MapCache.NO_HEIGHT) continue;
            int x = i & 15, z = i >> 4;
            int y = heights[i];
            int north = neighbour(z > 0 ? heights[i - 16] : edge(northEdge, x), y);
            int west = neighbour(x > 0 ? heights[i - 1] : edge(westEdge, z), y);
            int diff = (y - north) + (y - west);
            if (diff > 0) argb[i] = scale(argb[i], SHADE_UP);
            else if (diff < 0) argb[i] = scale(argb[i], SHADE_DOWN);
        }
    }

    private static short edge(short[] edge, int i) {
        return edge == null ? MapCache.NO_HEIGHT : edge[i];
    }

    private static int neighbour(short h, int fallback) {
        return h == MapCache.NO_HEIGHT ? fallback : h;
    }

    private static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    private static int scale(int col, float f) {
        int r = Math.min(255, (int) (((col >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((col >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((col & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int multiply(int base, int tint) {
        int r = ((base >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((base >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (base & 0xFF) * (tint & 0xFF) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int color(BlockState state, Biome biome, int worldX, int worldZ) {
        BlockColor bc = blockColor(state);
        if (bc == null) return UNKNOWN_COLOR;
        return switch (bc.tint()) {
            case NONE -> 0xFF000000 | bc.base();
            case FIXED -> multiply(bc.base(), bc.tintColor());
            case GRASS -> multiply(bc.base(), biome == null ? DEFAULT_GRASS : biome.getGrassColor(worldX, worldZ));
            case FOLIAGE -> multiply(bc.base(), biome == null ? DEFAULT_FOLIAGE : biome.getFoliageColor());
            case WATER -> multiply(bc.base(), biome == null ? DEFAULT_WATER : biome.getWaterColor());
        };
    }

    private static BlockColor blockColor(BlockState state) {
        BlockColor cached = BLOCK_COLORS.get(state);
        if (cached != null) return cached;
        int base = textureColor(state);
        if (base == 0) return null;
        BlockColor bc = withTint(state, base);
        BLOCK_COLORS.put(state, bc);
        return bc;
    }

    private static BlockColor withTint(BlockState state, int base) {
        Block block = state.getBlock();
        if (GRASS_TINTED.contains(block)) return new BlockColor(base, Tint.GRASS, 0);
        if (FOLIAGE_TINTED.contains(block)) return new BlockColor(base, Tint.FOLIAGE, 0);
        if (WATER_TINTED.contains(block)) return new BlockColor(base, Tint.WATER, 0);
        List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
        if (sources.isEmpty()) return new BlockColor(base, Tint.NONE, 0);
        int color = sources.get(0).color(state);
        return color == -1 ? new BlockColor(base, Tint.NONE, 0) : new BlockColor(base, Tint.FIXED, color & 0xFFFFFF);
    }

    private static int textureColor(BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        try {
            BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = PARTS.get();
            parts.clear();
            model.collectParts(RANDOM.get(), parts);
            TextureAtlasSprite sprite = findSprite(parts);
            if (sprite == null) sprite = model.particleMaterial().sprite();
            if (sprite == null) return mapColor(state);

            Identifier name = sprite.contents().name();
            Integer cached = SPRITE_COLORS.get(name);
            if (cached != null) return cached;

            Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
            Optional<Resource> resource = mc.getResourceManager().getResource(file);
            if (resource.isEmpty()) return mapColor(state);
            try (InputStream is = resource.get().open();
                 NativeImage img = NativeImage.read(NativeImage.Format.RGBA, is)) {
                int avg = averageFirstFrame(img);
                if (avg == 0) return mapColor(state);
                SPRITE_COLORS.put(name, avg);
                return avg;
            }
        } catch (Exception e) {
            return mapColor(state);
        }
    }

    private static TextureAtlasSprite findSprite(List<BlockStateModelPart> parts) {
        for (Direction dir : FACE_ORDER) {
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(dir);
                if (!quads.isEmpty()) return quads.get(0).materialInfo().sprite();
            }
        }
        return null;
    }

    private static int averageFirstFrame(NativeImage img) {
        int size = Math.min(img.getWidth(), img.getHeight());
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int px = img.getPixel(x, y);
                if ((px >>> 24) == 0) continue;
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
                n++;
            }
        }
        return n == 0 ? 0 : (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    private static int mapColor(BlockState state) {
        try {
            MapColor mc = state.getMapColor(null, BlockPos.ZERO);
            return mc != null && mc != MapColor.NONE ? mc.col : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
