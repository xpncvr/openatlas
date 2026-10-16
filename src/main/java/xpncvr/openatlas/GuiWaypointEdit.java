package xpncvr.openatlas;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class GuiWaypointEdit extends Screen {
    private static final int LABEL = 0xFFA0A0A0;
    private static final int ROW = 34;

    private final Screen parent;
    private final Waypoint existing;
    private final int initX, initY, initZ;

    private EditBox nameBox, initialsBox, xBox, yBox, zBox, groupBox, rBox, gBox, bBox;
    private Button dimButton;
    private String dimension;
    private int initialColor;
    private boolean visible;
    private String error;
    private int top;

    public GuiWaypointEdit(Screen parent, Waypoint existing, int x, int y, int z, String dimension) {
        super(Component.literal(existing == null ? "New Waypoint" : "Edit Waypoint"));
        this.parent = parent;
        this.existing = existing;
        this.dimension = existing == null ? dimension : existing.dimension;
        this.initX = existing == null ? x : existing.x;
        this.initY = existing == null ? y : existing.y;
        this.initZ = existing == null ? z : existing.z;
        this.initialColor = existing == null ? Waypoint.COLORS[(int) (System.nanoTime() % Waypoint.COLORS.length)] : existing.color;
        this.visible = existing == null || existing.visible;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 100;
        top = Math.max(28, (height - (ROW * 6 + 24)) / 2);
        int y = top + 10;
        nameBox = box(left, y, 150, existing == null ? "Waypoint" : existing.name, 32);
        initialsBox = box(left + 156, y, 44, existing == null ? "" : existing.label(), 3);
        y += ROW;
        xBox = box(left, y, 64, Integer.toString(initX), 9);
        yBox = box(left + 68, y, 64, Integer.toString(initY), 5);
        zBox = box(left + 136, y, 64, Integer.toString(initZ), 9);
        y += ROW;
        groupBox = box(left, y, 200, existing == null ? "default" : existing.set, 24);
        y += ROW;
        rBox = box(left, y, 48, Integer.toString((initialColor >> 16) & 0xFF), 3);
        gBox = box(left + 52, y, 48, Integer.toString((initialColor >> 8) & 0xFF), 3);
        bBox = box(left + 104, y, 48, Integer.toString(initialColor & 0xFF), 3);
        y += ROW + 6;
        dimButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleDimension())
                .bounds(left, y, 132, 20).build());
        addRenderableWidget(Button.builder(visibleLabel(), b -> {
            visible = !visible;
            b.setMessage(visibleLabel());
        }).bounds(left + 136, y, 64, 20).build());
        y += 28;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(left, y, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + 68, y, 64, 20).build());
        Button del = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
            WaypointManager.INSTANCE.remove(existing);
            Toast.show("Deleted " + existing.name + " (right-click map to undo, 30s)");
            onClose();
        }).bounds(left + 136, y, 64, 20).build());
        del.active = existing != null;
        updateDimButton();
        setInitialFocus(nameBox);
    }

    private void cycleDimension() {
        List<String> dims = MapSession.knownDimensions();
        for (String d : Dimensions.VANILLA) {
            if (!dims.contains(d)) dims.add(d);
        }
        if (!dims.contains(dimension)) dims.add(dimension);
        dimension = dims.get((dims.indexOf(dimension) + 1) % dims.size());
        updateDimButton();
    }

    private void updateDimButton() {
        dimButton.setMessage(Component.literal("Dim: " + Dimensions.shortName(dimension)));
    }

    private Component visibleLabel() {
        return Component.literal(visible ? "Visible" : "Hidden");
    }

    private EditBox box(int x, int y, int w, String value, int max) {
        EditBox b = new EditBox(font, x, y, w, 20, Component.empty());
        b.setMaxLength(max);
        b.setValue(value);
        addRenderableWidget(b);
        return b;
    }

    private static int channel(EditBox box) {
        try {
            return Math.clamp(Integer.parseInt(box.getValue().trim()), 0, 255);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int currentColor() {
        int r = channel(rBox), g = channel(gBox), b = channel(bBox);
        if (r < 0 || g < 0 || b < 0) return -1;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void save() {
        int x, y, z;
        try {
            x = Integer.parseInt(xBox.getValue().trim());
            y = Integer.parseInt(yBox.getValue().trim());
            z = Integer.parseInt(zBox.getValue().trim());
        } catch (NumberFormatException e) {
            error = "Coordinates must be whole numbers";
            return;
        }
        int color = currentColor();
        if (color == -1) {
            error = "R, G and B must be numbers from 0 to 255";
            return;
        }
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            error = "Name is required";
            return;
        }
        Waypoint w = existing != null ? existing : new Waypoint(name, x, y, z, color, dimension);
        w.name = name;
        w.initials = initialsBox.getValue().trim().isEmpty() ? Waypoint.defaultInitials(name) : initialsBox.getValue().trim();
        w.x = x;
        w.y = y;
        w.z = z;
        w.color = color;
        w.visible = visible;
        w.dimension = dimension;
        String group = groupBox.getValue().trim();
        w.set = group.isEmpty() ? "default" : group;
        if (existing == null) WaypointManager.INSTANCE.add(w);
        else WaypointManager.INSTANCE.save();
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = width / 2;
        int left = cx - 100;
        g.centeredText(font, title, cx, top - 16, 0xFFFFFFFF);
        int y = top;
        g.text(font, "Name", left, y, LABEL);
        g.text(font, "Initials", left + 156, y, LABEL);
        y += ROW;
        g.text(font, "X", left, y, LABEL);
        g.text(font, "Y", left + 68, y, LABEL);
        g.text(font, "Z", left + 136, y, LABEL);
        y += ROW;
        g.text(font, "Group (filter in the waypoint list)", left, y, LABEL);
        y += ROW;
        g.text(font, "R", left, y, LABEL);
        g.text(font, "G", left + 52, y, LABEL);
        g.text(font, "B", left + 104, y, LABEL);
        int color = currentColor();
        int py = y + 10;
        g.text(font, "Preview", left + 156, y, LABEL);
        if (color != -1) g.fill(left + 156, py, left + 200, py + 20, color);
        g.outline(left + 156, py, 44, 20, 0xFF808080);
        if (error != null) g.centeredText(font, error, cx, top + ROW * 4 + 66, 0xFFFF5555);
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
