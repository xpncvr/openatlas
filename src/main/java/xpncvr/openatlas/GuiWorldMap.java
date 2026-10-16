package xpncvr.openatlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

public class GuiWorldMap extends Screen {
    private static final int GRID_COLOR = 0x40000000;
    private static final int REGION_GRID_COLOR = 0x80000000;
    private static final int BG_COLOR = 0xFF111111;
    private static final int VIEW_HALF_REGIONS = 4;
    private static final int MENU_ITEM_H = 12;
    private static final int HOVER_RADIUS = 6;
    private static final int EDGE_MARGIN = 6;
    private static final double MIN_SCALE = 0.05, MAX_SCALE = 16.0, ZOOM_STEP = 1.25;
    private static final int MIP_HALF_REGIONS = 22;
    private static final int FADE_START = 16, FADE_END = 64;
    private static final float FADE_MIN = 0.25f;
    private static final int NETHER_DEFAULT_LAYER = 4;
    private static final Identifier MAP_TEXTURE_ID = Identifier.fromNamespaceAndPath(OpenAtlas.MOD_ID, "map_view");
    private static final int REGION_PX = MapCache.REGION_PX;

    private static double scale = 1.0;
    private static Integer fixedLayer;

    private double cameraX, cameraZ;
    private double drawX, drawZ;
    private String viewDim;
    private LayerKey layer;
    private LayerKey labelledLayer;
    private String otherDimLayerFor;
    private int otherDimLayer;
    private String zoomLabel;

    private DynamicTexture mapTexture;
    private NativeImage mapPixels;
    private int texWidth, texHeight, texScale;
    private int[] colRx = new int[0], colLx = new int[0];
    private int[][] visible = new int[0][];
    private long lastSig;
    private int lastVersion = -1;

    private Button dimButton, layerButton;
    private Waypoint hovered;

    private record MenuItem(String label, Runnable action) {}

    private List<MenuItem> menu;
    private int menuX, menuY, menuW;

    public GuiWorldMap() {
        super(Component.literal("OpenAtlas"));
    }

    @Override
    protected void init() {
        if (viewDim == null) {
            viewDim = MapSession.dimension();
            Player player = minecraft.player;
            if (player != null) {
                cameraX = player.getX();
                cameraZ = player.getZ();
            }
        }
        allocateTexture(width, height);
        zoomLabel = String.format("Zoom %.2fx", scale);

        int right = width - 4;
        dimButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleDimension())
                .bounds(right - 120, 4, 120, 16).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> stepLayer(-1)).bounds(right - 120, 22, 16, 16).build());
        layerButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleAutoLayer())
                .bounds(right - 102, 22, 84, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> stepLayer(1)).bounds(right - 16, 22, 16, 16).build());

        int bottom = height - 20;
        addRenderableWidget(Button.builder(Component.literal("Waypoints"), b -> minecraft.setScreen(new GuiWaypointList(this)))
                .bounds(right - 196, bottom, 64, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Settings"), b -> minecraft.setScreen(new GuiSettings(this)))
                .bounds(right - 130, bottom, 64, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Export"), b -> {
            Toast.show("Exporting...");
            MapExporter.export(layer);
        }).bounds(right - 64, bottom, 64, 16).build());

        layer = resolveLayer();
        labelledLayer = null;
        dimButton.setMessage(Component.literal(Dimensions.shortName(viewDim)));
    }

    private boolean viewingCurrent() {
        return viewDim.equals(MapSession.dimension());
    }

    private LayerKey resolveLayer() {
        LayerKey surface = MapSession.surface(viewDim);
        if (fixedLayer != null) return surface.withLayer(fixedLayer);
        if (viewingCurrent()) return OpenAtlasClient.currentLayer();
        if (Dimensions.coordScale(viewDim) == 1.0) return surface;
        if (!viewDim.equals(otherDimLayerFor)) {
            otherDimLayerFor = viewDim;
            otherDimLayer = LayerKey.SURFACE;
            for (int l : DiskCache.listCaveLayers(surface)) {
                if (otherDimLayer == LayerKey.SURFACE || Math.abs(l - NETHER_DEFAULT_LAYER) < Math.abs(otherDimLayer - NETHER_DEFAULT_LAYER)) {
                    otherDimLayer = l;
                }
            }
        }
        return surface.withLayer(otherDimLayer);
    }

    private void refreshLayerLabel() {
        if (layer.equals(labelledLayer)) return;
        labelledLayer = layer;
        String text = layer.isSurface() ? "Surface" : "Y " + layer.bandBottom() + ".." + layer.bandTop();
        layerButton.setMessage(Component.literal(fixedLayer == null ? "Auto: " + text : text));
    }

    private void viewChanged() {
        layer = resolveLayer();
        ChunkPipeline.setViewedLayer(fixedLayer != null && viewingCurrent() ? fixedLayer : LayerKey.SURFACE);
        dimButton.setMessage(Component.literal(Dimensions.shortName(viewDim)));
    }

    private void cycleDimension() {
        List<String> dims = MapSession.knownDimensions();
        if (!dims.contains(MapSession.dimension())) dims.add(MapSession.dimension());
        String next = dims.get((dims.indexOf(viewDim) + 1) % dims.size());
        double f = Dimensions.scaleBetween(viewDim, next);
        cameraX *= f;
        cameraZ *= f;
        viewDim = next;
        fixedLayer = null;
        viewChanged();
    }

    private void toggleAutoLayer() {
        fixedLayer = fixedLayer == null ? LayerKey.SURFACE : null;
        viewChanged();
    }

    private void stepLayer(int dir) {
        int cur = layer.layer();
        if (cur == LayerKey.SURFACE) {
            int y = minecraft.player != null ? Mth.floor(minecraft.player.getY()) : NETHER_DEFAULT_LAYER * LayerKey.BAND_HEIGHT;
            cur = LayerKey.layerFor(y) - (dir > 0 ? 1 : 0);
        }
        ClientLevel level = minecraft.level;
        int min = level != null ? LayerKey.layerFor(level.getMinY()) : -4;
        int max = level != null ? LayerKey.layerFor(level.getMaxY()) : 19;
        fixedLayer = Math.clamp(cur + dir, min, max);
        viewChanged();
    }

    private void allocateTexture(int w, int h) {
        releaseTexture();
        texScale = Math.max(1, minecraft.getWindow().getGuiScale());
        texWidth = w * texScale;
        texHeight = h * texScale;
        mapTexture = new DynamicTexture(MAP_TEXTURE_ID.toString(), texWidth, texHeight, true);
        mapPixels = mapTexture.getPixels();
        minecraft.getTextureManager().register(MAP_TEXTURE_ID, mapTexture);
        colRx = new int[texWidth];
        colLx = new int[texWidth];
        lastVersion = -1;
    }

    private void releaseTexture() {
        if (mapTexture == null) return;
        minecraft.getTextureManager().release(MAP_TEXTURE_ID);
        mapTexture = null;
        mapPixels = null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, BG_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int w = width, h = height, cx = w / 2, cy = h / 2;
        if (mapPixels == null || texWidth != w * texScale || texHeight != h * texScale
                || texScale != Math.max(1, minecraft.getWindow().getGuiScale())) allocateTexture(w, h);
        if (fixedLayer == null && viewingCurrent()) layer = OpenAtlasClient.currentLayer();
        refreshLayerLabel();

        double pixel = scale * texScale;
        drawX = Math.round(cameraX * pixel) / pixel;
        drawZ = Math.round(cameraZ * pixel) / pixel;
        renderMap(g, w, h, cx, cy);

        if (Settings.INSTANCE.showGrid) drawGrid(g, cx, cy, w, h);
        hovered = null;
        if (Settings.INSTANCE.showMapWaypoints) renderWaypoints(g, cx, cy, w, h, mouseX, mouseY);
        if (Settings.INSTANCE.locatorPlayers && viewingCurrent()) renderLocatorPlayers(g, cx, cy, mouseX, mouseY);
        renderPlayer(g, cx, cy);
        renderHud(g, mouseX, mouseY, cx, cy, h);

        super.extractRenderState(g, mouseX, mouseY, delta);
        Toast.render(g, font, cx, h - 44);
        if (menu != null) renderMenu(g, mouseX, mouseY);
    }

    private void renderMap(GuiGraphicsExtractor g, int w, int h, int cx, int cy) {
        MipCache mip = MipCache.forShift(pickShift(w, h));
        int half = mip != null ? MIP_HALF_REGIONS : VIEW_HALF_REGIONS;
        int midRX = Mth.floor(drawX) >> 9, midRZ = Mth.floor(drawZ) >> 9;
        int minRX = Math.max(Mth.floor((drawX - cx / scale) / REGION_PX), midRX - half);
        int maxRX = Math.min(Mth.floor((drawX + cx / scale) / REGION_PX), midRX + half);
        int minRZ = Math.max(Mth.floor((drawZ - cy / scale) / REGION_PX), midRZ - half);
        int maxRZ = Math.min(Mth.floor((drawZ + cy / scale) / REGION_PX), midRZ + half);
        int visW = maxRX - minRX + 1, visH = maxRZ - minRZ + 1;
        if (visible.length < visW * visH) visible = new int[visW * visH][];
        if (mip != null) {
            MapCache.INSTANCE.unpin();
            mip.fetchVisible(layer, minRX, minRZ, visW, visH, visible);
        } else {
            MapCache.INSTANCE.fetchVisible(layer, minRX, minRZ, visW, visH, visible);
        }

        long sig = Double.doubleToLongBits(drawX) * 31 + Double.doubleToLongBits(drawZ) * 17
                + Double.doubleToLongBits(scale) * 13 + layer.hashCode() * 7919L + w * 131L + h;
        int version = MapCache.INSTANCE.version() * 31 + MipCache.versionAll();
        if (sig != lastSig || version != lastVersion) {
            paint(minRX, minRZ, visW, visH, cx, cy, mip != null ? mip.shift : 0);
            mapTexture.upload();
            lastSig = sig;
            lastVersion = version;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE_ID, 0, 0, 0.0f, 0.0f, w, h, texWidth, texHeight, texWidth, texHeight, -1);
    }

    private int pickShift(int w, int h) {
        double density = scale * texScale;
        int maxShift = MipCache.EIGHTH.shift;
        int shift = 0;
        while (shift < maxShift && density <= 1.0 / (2 << shift)) shift++;
        int spanX = (int) Math.ceil(w / scale / REGION_PX) + 1;
        int spanZ = (int) Math.ceil(h / scale / REGION_PX) + 1;
        while (shift < maxShift && !fits(shift, spanX, spanZ)) shift++;
        return shift;
    }

    private static boolean fits(int shift, int spanX, int spanZ) {
        if (shift == 0) {
            int limit = VIEW_HALF_REGIONS * 2 + 1;
            return spanX <= limit && spanZ <= limit && spanX * spanZ <= MapCache.MAX_REGIONS;
        }
        return spanX * spanZ <= MipCache.forShift(shift).maxMips;
    }

    private void paint(int minRX, int minRZ, int visW, int visH, int cx, int cy, int shift) {
        long base = mapPixels.getPointer();
        double inv = 1.0 / (scale * texScale);
        int pcx = cx * texScale, pcy = cy * texScale;
        int rowBits = 9 - shift;
        for (int px = 0; px < texWidth; px++) {
            int bx = Mth.floor(drawX + (px - pcx + 0.5) * inv);
            colRx[px] = (bx >> 9) - minRX;
            colLx[px] = (bx & 511) >> shift;
        }
        for (int py = 0; py < texHeight; py++) {
            int bz = Mth.floor(drawZ + (py - pcy + 0.5) * inv);
            int rzi = (bz >> 9) - minRZ;
            int rowOff = ((bz & 511) >> shift) << rowBits;
            long row = base + ((long) py * texWidth << 2);
            boolean rowValid = rzi >= 0 && rzi < visH;
            int last = Integer.MIN_VALUE;
            int[] reg = null;
            for (int px = 0; px < texWidth; px++) {
                int rxi = colRx[px];
                if (rxi != last) {
                    last = rxi;
                    reg = rowValid && rxi >= 0 && rxi < visW ? visible[rzi * visW + rxi] : null;
                }
                MemoryUtil.memPutInt(row + ((long) px << 2), reg == null ? 0 : reg[rowOff + colLx[px]]);
            }
        }
    }

    private void drawGrid(GuiGraphicsExtractor g, int cx, int cy, int w, int h) {
        if (scale * 16.0 < 6.0) return;
        int c0 = Mth.floor((drawX - cx / scale) / 16), c1 = Mth.floor((drawX + cx / scale) / 16) + 1;
        for (int c = c0; c <= c1; c++) {
            int sx = wx(c * 16.0, cx);
            g.fill(sx, 0, sx + 1, h, (c & 31) == 0 ? REGION_GRID_COLOR : GRID_COLOR);
        }
        int r0 = Mth.floor((drawZ - cy / scale) / 16), r1 = Mth.floor((drawZ + cy / scale) / 16) + 1;
        for (int r = r0; r <= r1; r++) {
            int sz = wz(r * 16.0, cy);
            g.fill(0, sz, w, sz + 1, (r & 31) == 0 ? REGION_GRID_COLOR : GRID_COLOR);
        }
    }

    private Integer referenceY() {
        if (!layer.isSurface()) return layer.bandBottom() + LayerKey.BAND_HEIGHT / 2;
        if (viewingCurrent() && minecraft.player != null && fixedLayer == null) return Mth.floor(minecraft.player.getY());
        return null;
    }

    private static float heightFade(Integer refY, int y) {
        if (refY == null) return 1.0f;
        int dy = Math.abs(y - refY);
        if (dy <= FADE_START) return 1.0f;
        if (dy >= FADE_END) return FADE_MIN;
        return 1.0f - (1.0f - FADE_MIN) * (dy - FADE_START) / (float) (FADE_END - FADE_START);
    }

    private static int faded(int color, float alpha) {
        return ARGB.color(Math.round(ARGB.alpha(color) * alpha), color);
    }

    private void renderWaypoints(GuiGraphicsExtractor g, int cx, int cy, int w, int h, int mouseX, int mouseY) {
        Integer refY = referenceY();
        int bestDist = Integer.MAX_VALUE;
        for (Waypoint wp : WaypointManager.INSTANCE.getWaypoints()) {
            if (!wp.visible || !viewDim.equals(wp.dimension)) continue;
            int sx = wx(wp.x + 0.5, cx), sz = wz(wp.z + 0.5, cy);
            float alpha = heightFade(refY, wp.y);
            if (sx < EDGE_MARGIN || sx > w - EDGE_MARGIN || sz < EDGE_MARGIN || sz > h - EDGE_MARGIN) {
                int ex = Math.clamp(sx, EDGE_MARGIN, w - EDGE_MARGIN), ez = Math.clamp(sz, EDGE_MARGIN, h - EDGE_MARGIN);
                g.fill(ex - 3, ez - 3, ex + 3, ez + 3, faded(0xFF000000, alpha));
                g.fill(ex - 2, ez - 2, ex + 2, ez + 2, faded(wp.color, alpha));
                continue;
            }
            String label = wp.label();
            int lw = font.width(label);
            g.fill(sx - lw / 2 - 2, sz - 5, sx + lw - lw / 2 + 2, sz + 5, faded(0xC0000000, alpha));
            g.outline(sx - lw / 2 - 2, sz - 5, lw + 4, 10, faded(wp.color, alpha));
            g.text(font, label, sx - lw / 2, sz - 4, faded(wp.color, alpha), false);
            if (scale >= 0.5) g.text(font, wp.name, sx - font.width(wp.name) / 2, sz + 7, faded(0xFFFFFFFF, alpha), false);

            int d = Math.max(Math.abs(mouseX - sx), Math.abs(mouseY - sz));
            if (menu == null && d <= HOVER_RADIUS && d < bestDist) {
                bestDist = d;
                hovered = wp;
            }
        }
        if (hovered != null) {
            String dist = "";
            if (minecraft.player != null && viewingCurrent()) {
                dist = " (" + (int) hovered.distanceTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()) + "m)";
            }
            g.setTooltipForNextFrame(font, Component.literal(hovered.name + " " + hovered.x + " " + hovered.y + " " + hovered.z + dist), mouseX, mouseY);
        }
    }

    private void renderLocatorPlayers(GuiGraphicsExtractor g, int cx, int cy, int mouseX, int mouseY) {
        LocatorPlayers.Marker hover = null;
        for (LocatorPlayers.Marker m : LocatorPlayers.collect()) {
            int sx = wx(m.x(), cx), sz = wz(m.z(), cy);
            if (m.exact()) {
                g.fill(sx - 3, sz - 3, sx + 3, sz + 3, 0xFF000000);
                g.fill(sx - 2, sz - 2, sx + 2, sz + 2, m.color());
            } else {
                int r = Math.max(3, (int) Math.round(8 * scale));
                g.outline(sx - r - 1, sz - r - 1, 2 * r + 2, 2 * r + 2, 0xFF000000);
                g.outline(sx - r, sz - r, 2 * r, 2 * r, m.color());
            }
            if (menu == null && Math.abs(mouseX - sx) <= 5 && Math.abs(mouseY - sz) <= 5) hover = m;
        }
        if (hover != null) {
            g.setTooltipForNextFrame(font, Component.literal(hover.name() + (hover.exact() ? "" : " (within this chunk)")), mouseX, mouseY);
        }
    }

    private void renderPlayer(GuiGraphicsExtractor g, int cx, int cy) {
        Player player = minecraft.player;
        if (player == null) return;
        double f = Dimensions.scaleBetween(MapSession.dimension(), viewDim);
        g.pose().pushMatrix();
        g.pose().translate(wx(player.getX() * f, cx), wz(player.getZ() * f, cy));
        g.pose().rotate((float) Math.toRadians(player.getYRot() + 180.0f));
        arrow(g, -1, 0, 0xFF000000);
        arrow(g, 1, 0, 0xFF000000);
        arrow(g, 0, -1, 0xFF000000);
        arrow(g, 0, 1, 0xFF000000);
        arrow(g, 0, 0, viewingCurrent() ? 0xFFFF4444 : 0xFFAAAAAA);
        g.pose().popMatrix();
    }

    private static void arrow(GuiGraphicsExtractor g, int ox, int oy, int color) {
        for (int i = 0; i < 6; i++) {
            int half = i / 2;
            g.fill(ox - half, oy - 3 + i, ox + half + 1, oy - 2 + i, color);
        }
    }

    private void renderHud(GuiGraphicsExtractor g, int mouseX, int mouseY, int cx, int cy, int h) {
        int bx = toWorldX(mouseX, cx), bz = toWorldZ(mouseY, cy);
        Integer by = blockY(bx, bz);
        String coords = "X: " + bx + "  Y: " + (by == null ? "?" : by) + "  Z: " + bz;
        String biome = biomeAt(bx, by, bz);
        if (biome != null) coords += "  " + biome;
        g.text(font, coords, 4, 4, 0xFFFFFFFF);
        g.text(font, zoomLabel, 4, 14, 0xFFAAAAAA);
        if (Settings.INSTANCE.showMapMemory) {
            g.text(font, MapMemory.label((long) texWidth * texHeight * Integer.BYTES), 4, 24, 0xFFAAAAAA);
        }
        g.text(font, "Right-click menu | G grid | Space center", 4, h - 12, 0xFFAAAAAA);
    }

    private Integer blockY(int x, int z) {
        int stored = MapCache.INSTANCE.getHeight(layer, x, z);
        if (stored != MapCache.NO_HEIGHT) return stored;
        ClientLevel level = minecraft.level;
        if (level == null || !viewingCurrent() || !layer.isSurface()) return null;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    }

    private String biomeAt(int x, Integer y, int z) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || !viewingCurrent()) return null;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
        int by = y != null ? y : Mth.floor(minecraft.player.getY());
        return level.getBiome(new BlockPos(x, by, z)).unwrapKey()
                .map(k -> Dimensions.shortName(k.identifier().toString())).orElse(null);
    }

    private void renderMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int hgt = menu.size() * MENU_ITEM_H + 4;
        g.fill(menuX, menuY, menuX + menuW, menuY + hgt, 0xF0202020);
        g.outline(menuX, menuY, menuW, hgt, 0xFF808080);
        for (int i = 0; i < menu.size(); i++) {
            int iy = menuY + 2 + i * MENU_ITEM_H;
            boolean hover = mouseX >= menuX && mouseX < menuX + menuW && mouseY >= iy && mouseY < iy + MENU_ITEM_H;
            if (hover) g.fill(menuX + 1, iy, menuX + menuW - 1, iy + MENU_ITEM_H, 0xFF404060);
            g.text(font, menu.get(i).label(), menuX + 5, iy + 2, 0xFFFFFFFF);
        }
    }

    private void openMenu(int mouseX, int mouseY) {
        int cx = width / 2, cy = height / 2;
        int bx = toWorldX(mouseX, cx), bz = toWorldZ(mouseY, cy);
        Integer by = blockY(bx, bz);
        List<MenuItem> items = new ArrayList<>();
        if (WaypointManager.INSTANCE.canUndoDelete()) {
            items.add(new MenuItem("Undo deletion", () -> {
                Waypoint restored = WaypointManager.INSTANCE.undoDelete();
                if (restored != null) Toast.show("Restored " + restored.name);
            }));
        }
        Waypoint wp = hovered;
        if (wp != null) {
            items.add(new MenuItem("Edit " + wp.name, () -> minecraft.setScreen(new GuiWaypointEdit(this, wp, 0, 0, 0, wp.dimension))));
            items.add(new MenuItem("Teleport", () -> teleport(wp.dimension, wp.x, Integer.toString(wp.y), wp.z)));
            items.add(new MenuItem("Copy coordinates", () -> copyCoords(wp.x + " " + wp.y + " " + wp.z)));
            items.add(new MenuItem("Hide", () -> {
                wp.visible = false;
                WaypointManager.INSTANCE.save();
            }));
            items.add(new MenuItem("Delete", () -> {
                WaypointManager.INSTANCE.remove(wp);
                Toast.show("Deleted " + wp.name + " (right-click to undo, 30s)");
            }));
        } else {
            String standY = by != null ? Integer.toString(by + 1) : "~";
            int newY = by != null ? by + 1 : minecraft.player != null ? Mth.floor(minecraft.player.getY()) : 64;
            items.add(new MenuItem("Create waypoint here", () -> minecraft.setScreen(new GuiWaypointEdit(this, null, bx, newY, bz, viewDim))));
            items.add(new MenuItem("Teleport here", () -> teleport(viewDim, bx, standY, bz)));
            items.add(new MenuItem("Copy coordinates", () -> copyCoords(bx + " " + standY + " " + bz)));
        }
        int mw = 0;
        for (MenuItem m : items) mw = Math.max(mw, font.width(m.label()));
        menuW = mw + 10;
        menuX = Math.min(mouseX, width - menuW - 2);
        menuY = Math.min(mouseY, height - (items.size() * MENU_ITEM_H + 4) - 2);
        menu = items;
    }

    private void teleport(String dim, int x, String y, int z) {
        Teleport.to(dim, x, y, z);
        onClose();
    }

    private void copyCoords(String coords) {
        minecraft.keyboardHandler.setClipboard(coords);
        Toast.show("Copied " + coords);
    }

    private int toWorldX(double screenX, int cx) {
        return Mth.floor(drawX + (screenX - cx) / scale);
    }

    private int toWorldZ(double screenY, int cy) {
        return Mth.floor(drawZ + (screenY - cy) / scale);
    }

    private int wx(double worldX, int cx) {
        return Mth.floor(cx + (worldX - drawX) * scale);
    }

    private int wz(double worldZ, int cy) {
        return Mth.floor(cy + (worldZ - drawZ) * scale);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        if (menu != null) {
            List<MenuItem> items = menu;
            menu = null;
            int i = (my - menuY - 2) / MENU_ITEM_H;
            if (mx >= menuX && mx < menuX + menuW && my >= menuY + 2 && i < items.size()) items.get(i).action().run();
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            openMenu(mx, my);
            return true;
        }
        return event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && menu == null) {
            cameraX -= dx / scale;
            cameraZ -= dy / scale;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizAmount, double vertAmount) {
        menu = null;
        double newScale = Math.clamp(vertAmount > 0 ? scale * ZOOM_STEP : scale / ZOOM_STEP, MIN_SCALE, MAX_SCALE);
        double ox = mouseX - width / 2.0, oz = mouseY - height / 2.0;
        cameraX += ox / scale - ox / newScale;
        cameraZ += oz / scale - oz / newScale;
        scale = newScale;
        zoomLabel = String.format("Zoom %.2fx", scale);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (menu != null && event.isEscape()) {
            menu = null;
            return true;
        }
        if (event.isEscape() || OpenAtlasClient.mapKey.matches(event)) {
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_G) {
            Settings.INSTANCE.showGrid = !Settings.INSTANCE.showGrid;
            Settings.save();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_SPACE && minecraft.player != null) {
            viewDim = MapSession.dimension();
            fixedLayer = null;
            cameraX = minecraft.player.getX();
            cameraZ = minecraft.player.getZ();
            viewChanged();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        ChunkPipeline.setViewedLayer(LayerKey.SURFACE);
        super.onClose();
    }

    @Override
    public void removed() {
        releaseTexture();
        MapCache.INSTANCE.unpin();
    }
}
