package fr.nyuway.newaddon.gui.live;

import java.util.ArrayList;
import java.util.List;

/**
 * A draggable, resizable window, ported from Livemessage's {@code LiveWindow}.
 *
 * <p>The measurements are his and are kept exactly: a 17-pixel title bar, a background of
 * {@code rgb(32,32,32)}, a border in the conversation's own colour that greys to 128 when the
 * window is not the active one, an 11-pixel close box inset 2 from the right, and a resize
 * triangle in the bottom-right corner. Those numbers are the look; rounding them off would
 * have produced something that merely resembles it.
 *
 * <p>What could not come across is the opening animation. Upstream renders the window into a
 * second framebuffer and draws that scaled and faded - framebuffer juggling that does not
 * survive the move to 1.20+, let alone 26.x. The same motion is reproduced by offsetting and
 * fading the window's own drawing instead, which costs nothing and looks the same from the
 * outside.
 */
public class LiveWindow {

    public static final int TITLEBAR = 17;

    /** Upstream's palette, by name so the numbers are not scattered. */
    protected static final int BACKGROUND = LiveColors.rgb(32, 32, 32);
    protected static final int INACTIVE = LiveColors.rgb(128, 128, 128);
    protected static final int TOOLTIP_BG = LiveColors.rgb(36, 36, 36);

    /** How long the open animation runs. */
    private static final int OPEN_MILLIS = 400;

    public int x;
    public int y;
    public int w = 400;
    public int h = 250;
    public int minW = 100;
    public int minH = 100;

    public String title = "";
    public boolean active = true;
    public boolean closeButton = true;
    public int primaryColor;

    public int lastMouseX;
    public int lastMouseY;

    private int dragX;
    private int dragY;
    private boolean dragging;
    private boolean resizing;

    private final long opened = System.currentTimeMillis();

    /**
     * Where this window was last left - position and size both, and across restarts.
     *
     * <p>Keyed by a string the subclass chooses, so every conversation keeps its own spot rather
     * than sharing one with the rest. {@link LivePlaces} holds the file; this is only the pair
     * of calls that ask it.
     */
    protected void restorePlace(String key, int screenWidth, int screenHeight) {
        int[] p = LivePlaces.get(key);
        if (p == null) return;

        x = p[0];
        y = p[1];
        w = Math.max(minW, p[2]);
        h = Math.max(minH, p[3]);
        keepOnScreen(screenWidth, screenHeight);
    }

    protected void rememberPlace(String key) {
        LivePlaces.put(key, x, y, w, h);
    }

    protected final List<LiveButton> buttons = new ArrayList<>();

    /** A tiny pixel glyph drawn on a button, tinted by the button's live state. */
    public interface Icon {
        void draw(LiveCanvas c, int x, int y, int width, int height, int argb);
    }

    /**
     * A header icon button - a friend heart, an ignore sign - that colours itself from a live
     * bit of state: its {@code activeColor} while the thing it toggles is on, white while off,
     * with a faint plate under the cursor. The tooltip is a supplier so it can read "Add friend"
     * or "Remove friend" off the same state the icon is.
     */
    public final class LiveButton {
        public final int bx;
        public final int by;
        public final int bw;
        public final int bh;
        /** Measured from the right edge, which is how the window's own controls are placed. */
        public final boolean fromRight;
        private final Icon icon;
        private final java.util.function.BooleanSupplier active;
        /**
         * Asked each frame rather than kept, so a colour that lives in Meteor's config tab - the
         * friend and enemy ones do - shows a change on the next frame instead of the next time
         * the window is opened.
         */
        private final java.util.function.IntSupplier activeColor;
        private final java.util.function.Supplier<String> tooltip;
        public final Runnable action;

        /** For a button whose colour is fixed. */
        public LiveButton(int bx, int by, int bw, int bh, boolean fromRight, Icon icon,
                          java.util.function.BooleanSupplier active, int activeColor,
                          java.util.function.Supplier<String> tooltip, Runnable action) {
            this(bx, by, bw, bh, fromRight, icon, active, () -> activeColor, tooltip, action);
        }

        public LiveButton(int bx, int by, int bw, int bh, boolean fromRight, Icon icon,
                          java.util.function.BooleanSupplier active,
                          java.util.function.IntSupplier activeColor,
                          java.util.function.Supplier<String> tooltip, Runnable action) {
            this.bx = bx;
            this.by = by;
            this.bw = bw;
            this.bh = bh;
            this.fromRight = fromRight;
            this.icon = icon;
            this.active = active;
            this.activeColor = activeColor;
            this.tooltip = tooltip;
            this.action = action;
        }

        public int gx() {
            return fromRight ? (w - bx) : bx;
        }

        public boolean hovered() {
            return inRect(gx(), by, bw, bh, lastMouseX, lastMouseY);
        }

        String tooltip() {
            return tooltip == null ? "" : tooltip.get();
        }

        public void draw(LiveCanvas c) {
            if (hovered()) {
                c.box(x + gx(), y + by, bw, bh, LiveCanvas.opaque(LiveColors.rgb(72, 72, 72)));
            }

            boolean on = active != null && active.getAsBoolean();
            icon.draw(c, x + gx(), y + by, bw, bh,
                LiveCanvas.opaque(on ? activeColor.getAsInt() : 0xFFFFFF));
        }
    }

    protected LiveWindow(int screenWidth, int screenHeight, int color) {
        this.primaryColor = color;
        this.x = Math.max(0, (screenWidth - w) / 2);
        this.y = Math.max(0, (screenHeight - h) / 2);
    }

    /** 0 while opening, 1 once open. Drives the slide and fade. */
    protected float openProgress() {
        long elapsed = System.currentTimeMillis() - opened;
        if (elapsed >= OPEN_MILLIS) return 1f;
        return (float) LiveColors.easeOutQuint((double) elapsed / OPEN_MILLIS);
    }

    public boolean inRect(int rx, int ry, int rw, int rh, int mouseX, int mouseY) {
        return mouseX > x + rx && mouseX < x + rx + rw && mouseY > y + ry && mouseY < y + ry + rh;
    }

    public boolean inWindow(int mouseX, int mouseY) {
        return mouseX > x && mouseX < x + w && mouseY > y && mouseY < y + h;
    }

    /** @return true when this click asked for the window to close */
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (closeButton && mouseX > x + w - 13 && mouseX < x + w - 2
            && mouseY > y + 3 && mouseY < y + 14) {
            return true;
        }

        for (LiveButton b : buttons) {
            if (b.hovered()) {
                b.action.run();
                return false;
            }
        }

        if (mouseY < y + 20) {
            dragging = true;
            resizing = false;
            dragX = mouseX - x;
            dragY = mouseY - y;
        } else if (mouseX > x + w - 7 && mouseY > y + h - 7) {
            dragging = false;
            resizing = true;
            dragX = mouseX - x - w;
            dragY = mouseY - y - h;
        }

        return false;
    }

    public void mouseReleased() {
        dragging = false;
        resizing = false;
    }

    public void mouseMoved(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (dragging) {
            x = Math.max(0, mouseX - dragX);
            y = Math.max(0, mouseY - dragY);
        } else if (resizing) {
            w = Math.max(minW, mouseX - dragX - x);
            h = Math.max(minH, mouseY - dragY - y);
        }

        keepOnScreen(screenWidth, screenHeight);
    }

    protected void keepOnScreen(int screenWidth, int screenHeight) {
        if (x + w > screenWidth) x = screenWidth - w;
        if (y + h > screenHeight) y = screenHeight - h;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
    }

    /** Frame and title bar. Subclasses draw their contents on top. */
    public void draw(LiveCanvas c) {
        int foreground = active ? primaryColor : INACTIVE;
        float alpha = openProgress();

        c.box(x, y, w, h, LiveCanvas.withAlpha(BACKGROUND, alpha));
        c.outline(x, y, w, h, LiveCanvas.withAlpha(foreground, alpha));

        c.box(x, y, w, TITLEBAR, LiveCanvas.withAlpha(foreground, alpha));
        c.text(title, x + 5, y + 5, LiveCanvas.withAlpha(0xFFFFFF, alpha));

        if (closeButton) {
            c.box(x + w - 13, y + 3, 11, 11, LiveCanvas.withAlpha(BACKGROUND, alpha));

            // A cross, drawn rather than blitted. Upstream's icons.png holds the friend,
            // block and colour glyphs but not this one, and two diagonals of pixels need no
            // texture, no atlas and no per-version blit signature.
            int cross = LiveCanvas.withAlpha(0xFFFFFF, alpha);
            for (int i = 0; i < 5; i++) {
                c.box(x + w - 11 + i, y + 5 + i, 1, 1, cross);
                c.box(x + w - 7 - i, y + 5 + i, 1, 1, cross);
            }
        }

        // Resize grip: upstream draws a half-square triangle; stepped rows read the same at
        // this size and need no geometry the canvas does not already have.
        for (int i = 0; i < 6; i++) {
            c.box(x + w - 1 - i, y + h - 6 + i, i + 1, 1, LiveCanvas.withAlpha(foreground, alpha));
        }

        for (LiveButton b : buttons) b.draw(c);
    }

    /** Drawn after every window, so it is never covered by one in front. */
    public void drawTooltips(LiveCanvas c) {
        if (!active) return;

        for (LiveButton b : buttons) {
            String tip = b.tooltip();
            if (!tip.isEmpty() && b.hovered()) drawTooltip(c, tip);
        }
    }

    protected void drawTooltip(LiveCanvas c, String text) {
        int padding = 3;
        int tx = lastMouseX + 1;
        int ty = lastMouseY - 12 - padding;

        c.box(tx, ty, c.width(text) + padding * 2, 12 + padding - 1, LiveCanvas.opaque(TOOLTIP_BG));
        c.text(text, tx + padding, ty + padding, LiveCanvas.opaque(0xFFFFFF));
    }
}
