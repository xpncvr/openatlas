package xpncvr.openatlas;

public final class Waypoint {
    public static final int[] COLORS = {
            0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF00AA00, 0xFF55FFFF,
            0xFF00AAAA, 0xFF5555FF, 0xFF0000AA, 0xFFFF55FF, 0xFFAA00AA, 0xFFFFFFFF,
            0xFFAAAAAA, 0xFF555555
    };

    public enum Type { NORMAL, DEATH }

    public String name;
    public String initials;
    public int x, y, z;
    public int color;
    public String dimension;
    public String set = "default";
    public boolean visible = true;
    public Type type = Type.NORMAL;
    public long created;

    public Waypoint() {}

    public Waypoint(String name, int x, int y, int z, int color, String dimension) {
        this.name = name;
        this.initials = defaultInitials(name);
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.dimension = dimension;
        this.created = System.currentTimeMillis();
    }

    public static String defaultInitials(String name) {
        String t = name == null ? "" : name.trim();
        if (t.isEmpty()) return "?";
        return t.substring(0, Math.min(2, t.length())).toUpperCase();
    }

    public String label() {
        return initials == null || initials.isEmpty() ? defaultInitials(name) : initials;
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
