package xpncvr.openatlas;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class GuiWaypointList extends Screen {
    private static final int ROW_H = 22;
    private static final String[] ACTIONS = {"Edit", "TP", "Show", "Del"};

    private final Screen parent;
    private EditBox search;
    private Button dimButton, setButton;
    private String dimFilter;
    private String setFilter;
    private double scroll;
    private final List<Waypoint> rows = new ArrayList<>();
    private int listTop, listBottom;

    public GuiWaypointList(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
        this.dimFilter = MapSession.dimension();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        search = new EditBox(font, cx - 160, 24, 140, 20, Component.literal("Search"));
        search.setHint(Component.literal("Search..."));
        search.setResponder(s -> refresh());
        addRenderableWidget(search);
        dimButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            dimFilter = cycle(dimensionOptions(), dimFilter);
            refresh();
        }).bounds(cx - 16, 24, 90, 20).build());
        setButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            List<String> sets = new ArrayList<>();
            sets.add(null);
            sets.addAll(WaypointManager.INSTANCE.getSets());
            setFilter = cycle(sets, setFilter);
            refresh();
        }).bounds(cx + 78, 24, 82, 20).build());
        addRenderableWidget(Button.builder(Component.literal("New"), b -> {
            BlockPos p = minecraft.player != null ? minecraft.player.blockPosition() : BlockPos.ZERO;
            minecraft.setScreen(new GuiWaypointEdit(this, null, p.getX(), p.getY(), p.getZ(), MapSession.dimension()));
        }).bounds(cx - 160, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx + 60, height - 28, 100, 20).build());
        refresh();
    }

    private List<String> dimensionOptions() {
        List<String> dims = new ArrayList<>();
        dims.add(null);
        dims.addAll(MapSession.knownDimensions());
        for (Waypoint w : WaypointManager.INSTANCE.getWaypoints()) if (!dims.contains(w.dimension)) dims.add(w.dimension);
        return dims;
    }

    private static String cycle(List<String> options, String current) {
        int i = options.indexOf(current);
        return options.get((i + 1) % options.size());
    }

    private void refresh() {
        dimButton.setMessage(Component.literal(dimFilter == null ? "All dims" : Dimensions.shortName(dimFilter)));
        setButton.setMessage(Component.literal(setFilter == null ? "All sets" : setFilter));
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        rows.clear();
        for (Waypoint w : WaypointManager.INSTANCE.getWaypoints()) {
            if (dimFilter != null && !dimFilter.equals(w.dimension)) continue;
            if (setFilter != null && !setFilter.equals(w.set)) continue;
            if (!q.isEmpty() && !w.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            rows.add(w);
        }
        rows.sort(Comparator.comparingDouble(this::distance));
    }

    private double distance(Waypoint w) {
        if (minecraft.player == null) return 0;
        String cur = MapSession.dimension();
        double f = Dimensions.scaleBetween(w.dimension, cur);
        boolean sameOrLinked = w.dimension.equals(cur) || f != 1.0;
        if (!sameOrLinked) return Double.MAX_VALUE;
        double dx = w.x * f - minecraft.player.getX(), dz = w.z * f - minecraft.player.getZ();
        double dy = w.dimension.equals(cur) ? w.y - minecraft.player.getY() : 0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int cx = width / 2;
        int left = cx - 160, right = cx + 160;
        g.centeredText(font, title, cx, 8, 0xFFFFFFFF);

        int y = 50;
        listTop = y;
        listBottom = height - 34;
        int maxScroll = Math.max(0, rows.size() * ROW_H - (listBottom - listTop));
        scroll = Math.clamp(scroll, 0, maxScroll);
        g.enableScissor(left, listTop, right, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            int ry = listTop + i * ROW_H - (int) scroll;
            if (ry + ROW_H < listTop || ry > listBottom) continue;
            Waypoint w = rows.get(i);
            boolean hover = mouseX >= left && mouseX < right && mouseY >= ry && mouseY < ry + ROW_H - 2;
            g.fill(left, ry, right, ry + ROW_H - 2, hover ? 0x60FFFFFF : 0x40000000);
            int sw = Math.max(14, font.width(w.label()) + 4);
            g.fill(left + 3, ry + 3, left + 3 + sw, ry + 17, w.color);
            g.text(font, w.label(), left + 3 + (sw - font.width(w.label())) / 2, ry + 6, contrastText(w.color), false);
            int textColor = w.visible ? 0xFFFFFFFF : 0xFF808080;
            int tx = left + 8 + sw;
            g.text(font, w.name, tx, ry + 2, textColor);
            double d = distance(w);
            String dist = d == Double.MAX_VALUE ? "" : "  " + (int) d + "m";
            g.text(font, w.x + " " + w.y + " " + w.z + "  " + Dimensions.shortName(w.dimension) + dist, tx, ry + 11, 0xFF909090);
            int bx = right - 4 - ACTIONS.length * 34;
            for (String a : ACTIONS) {
                button(g, bx, ry + 2, a.equals("Show") ? (w.visible ? "Hide" : "Show") : a, mouseX, mouseY);
                bx += 34;
            }
        }
        g.disableScissor();
        if (rows.isEmpty()) g.centeredText(font, "No waypoints", cx, listTop + 10, 0xFF808080);

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private static int contrastText(int color) {
        int r = (color >> 16) & 0xFF, gr = (color >> 8) & 0xFF, b = color & 0xFF;
        return r * 299 + gr * 587 + b * 114 > 140_000 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private void button(GuiGraphicsExtractor g, int x, int y, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + 32 && my >= y && my < y + 16;
        g.fill(x, y, x + 32, y + 16, hover ? 0xFF606060 : 0xFF303030);
        g.outline(x, y, 32, 16, 0xFF808080);
        g.centeredText(font, label, x + 16, y + 4, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        int mx = (int) event.x(), my = (int) event.y();
        int right = width / 2 + 160;

        if (my < listTop || my >= listBottom) return false;
        int i = (int) ((my - listTop + scroll) / ROW_H);
        if (i < 0 || i >= rows.size()) return false;
        Waypoint w = rows.get(i);
        int ry = listTop + i * ROW_H - (int) scroll;
        if (my < ry + 2 || my >= ry + 18) return false;
        int bx = right - 4 - ACTIONS.length * 34;
        for (String a : ACTIONS) {
            if (mx >= bx && mx < bx + 32) {
                action(a, w);
                return true;
            }
            bx += 34;
        }
        return false;
    }

    private void action(String a, Waypoint w) {
        switch (a) {
            case "Edit" -> minecraft.setScreen(new GuiWaypointEdit(this, w, 0, 0, 0, w.dimension));
            case "TP" -> {
                Teleport.to(w.dimension, w.x, Integer.toString(w.y), w.z);
                onClose();
            }
            case "Show" -> {
                w.visible = !w.visible;
                WaypointManager.INSTANCE.save();
            }
            case "Del" -> {
                WaypointManager.INSTANCE.remove(w);
                Toast.show("Deleted " + w.name + " (right-click map to undo, 30s)");
                refresh();
            }
            default -> {
            }
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        scroll -= sy * ROW_H;
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
