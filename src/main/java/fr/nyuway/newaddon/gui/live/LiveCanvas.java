package fr.nyuway.newaddon.gui.live;

import net.minecraft.client.Minecraft;

//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}

/**
 * The one place that knows how this Minecraft version draws.
 *
 * <h2>What actually differs</h2>
 * Less than it first appears. 26.x stopped drawing inside {@code render} and moved to
 * {@code Renderable.extractRenderState}, which looks like an architectural break - but the
 * object handed to it, {@code GuiGraphicsExtractor}, carries the same immediate-mode calls the
 * old {@code GuiGraphics} did. {@code fill} and the scissor pair are identical down to the
 * signature; only the text call was renamed from {@code drawString} to {@code text}.
 *
 * <p>So the whole divergence between nine versions and three is a type and a method name, and
 * it lives here rather than being sprinkled through fifteen hundred lines of window drawing.
 *
 * <h2>Colours</h2>
 * Everything takes ARGB. A colour with a zero alpha byte is invisible, which on this codebase
 * has already cost one round of "why is nothing drawing" - so {@link #opaque} exists to say
 * what was meant.
 */
public final class LiveCanvas {

    //? if <26.1 {
    private final GuiGraphics g;
    //?} else {
    /*private final GuiGraphicsExtractor g;
    *///?}

    private final Minecraft mc = Minecraft.getInstance();

    //? if <26.1 {
    public LiveCanvas(GuiGraphics g) {
        this.g = g;
    }
    //?} else {
    /*public LiveCanvas(GuiGraphicsExtractor g) {
        this.g = g;
    }
    *///?}

    /** Filled rectangle, in ARGB. */
    public void rect(int left, int top, int right, int bottom, int argb) {
        g.fill(left, top, right, bottom, argb);
    }

    /** Rectangle given as position and size, which is how the window code thinks. */
    public void box(int x, int y, int width, int height, int argb) {
        g.fill(x, y, x + width, y + height, argb);
    }

    /** One-pixel outline. */
    public void outline(int x, int y, int width, int height, int argb) {
        box(x, y, width, 1, argb);
        box(x, y + height - 1, width, 1, argb);
        box(x, y, 1, height, argb);
        box(x + width - 1, y, 1, height, argb);
    }

    public void text(String s, int x, int y, int argb) {
        text(s, x, y, argb, true);
    }

    public void text(String s, int x, int y, int argb, boolean shadow) {
        //? if <26.1 {
        g.drawString(mc.font, s, x, y, argb, shadow);
        //?} else {
        /*g.text(mc.font, s, x, y, argb, shadow);
        *///?}
    }

    public int width(String s) {
        return mc.font.width(s);
    }

    public int lineHeight() {
        return mc.font.lineHeight;
    }

    /** Clips everything drawn until {@link #unclip()} to this rectangle. */
    public void clip(int x, int y, int width, int height) {
        g.enableScissor(x, y, x + width, y + height);
    }

    public void unclip() {
        g.disableScissor();
    }

    /**
     * Alpha-forces a colour.
     *
     * <p>Livemessage's palette is stored as plain RGB, so drawing it straight would ask for
     * fully transparent everything - the failure that looks exactly like a GUI that is not
     * running at all.
     */
    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /** ARGB from a colour and an alpha in 0..1, for the fades the windows animate with. */
    public static int withAlpha(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}
