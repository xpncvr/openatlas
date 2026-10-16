package xpncvr.openatlas;

import java.util.List;

public final class Dimensions {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final List<String> VANILLA = List.of(OVERWORLD, NETHER, "minecraft:the_end");

    private static final String VANILLA_PREFIX = "minecraft:";

    private Dimensions() {}

    public static double coordScale(String dimension) {
        return NETHER.equals(dimension) ? 8.0 : 1.0;
    }

    public static double scaleBetween(String from, String to) {
        return coordScale(from) / coordScale(to);
    }

    public static String shortName(String dimension) {
        return dimension.startsWith(VANILLA_PREFIX) ? dimension.substring(VANILLA_PREFIX.length()) : dimension;
    }
}
