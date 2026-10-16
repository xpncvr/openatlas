package xpncvr.openatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class Toast {
    private static final long DURATION_MILLIS = 2000L;

    private static String text;
    private static long until;

    private Toast() {}

    public static void show(String message) {
        text = message;
        until = System.currentTimeMillis() + DURATION_MILLIS;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof GuiWorldMap) && mc.player != null) mc.player.sendOverlayMessage(Component.literal(message));
    }

    public static void render(GuiGraphicsExtractor g, Font font, int centerX, int y) {
        if (text == null || System.currentTimeMillis() >= until) return;
        int w = font.width(text);
        g.fill(centerX - w / 2 - 4, y, centerX + w - w / 2 + 4, y + 14, 0xC0000000);
        g.centeredText(font, text, centerX, y + 3, 0xFFFFFFFF);
    }
}
