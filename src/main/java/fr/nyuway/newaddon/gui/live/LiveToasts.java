package fr.nyuway.newaddon.gui.live;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The advancement-style toasts that announce a message while the messenger is closed.
 *
 * <p>Each slides in from the right edge, holds, and slides back out, stacking downward from the
 * top-right corner. The motion is entirely positional - no fading - which is deliberate: the
 * player head drawn on a toast cannot be tinted with an alpha the same way across every version,
 * so a toast that never changes opacity needs no version-specific trick to fade it. Everything
 * is drawn through the same {@link LiveCanvas} the windows use, so the single drawing split at
 * 26.1 is the only version knowledge anywhere near this.
 */
public final class LiveToasts {

    private static final int WIDTH = 176;
    private static final int HEIGHT = 32;
    private static final int MARGIN = 6;
    private static final int GAP = 4;
    private static final int MAX_VISIBLE = 4;

    private static final long SLIDE = 300;   // in and out, each
    private static final long HOLD = 4500;   // fully out, between the two slides
    private static final long LIFETIME = SLIDE + HOLD + SLIDE;

    /** One queued toast; {@code born} is when it was pushed, and it drives the whole animation. */
    private record Toast(UUID peer, String name, String preview, int color, long born) { }

    private final List<Toast> toasts = new ArrayList<>();

    /**
     * Queues a toast. The colour is taken now rather than looked up at draw time - a friend
     * toggle recolouring a live toast is not worth the coupling.
     */
    public void push(UUID peer, String name, String preview, int color) {
        toasts.add(new Toast(peer, name, preview, color, System.currentTimeMillis()));

        // A burst of messages should not build a backlog that then takes half a minute to drain.
        while (toasts.size() > MAX_VISIBLE * 3) toasts.remove(0);
    }

    public void render(LiveCanvas c, int screenWidth) {
        long now = System.currentTimeMillis();

        for (Iterator<Toast> it = toasts.iterator(); it.hasNext(); ) {
            if (now - it.next().born() > LIFETIME) it.remove();
        }
        if (toasts.isEmpty()) return;

        int shown = 0;
        int top = MARGIN;

        for (Toast t : toasts) {
            if (shown >= MAX_VISIBLE) break;

            float visible = visibility(now - t.born());
            // Slides in from past the right edge: at 0 it rests fully off-screen, at 1 it sits
            // WIDTH+MARGIN in from the edge.
            int left = screenWidth - Math.round(visible * (WIDTH + MARGIN));

            draw(c, t, left, top);

            top += HEIGHT + GAP;
            shown++;
        }
    }

    /** 0 off-screen, 1 at rest. Rises over the first slide, holds, falls over the last. */
    private static float visibility(long age) {
        if (age < SLIDE) return (float) LiveColors.easeOutQuint((double) age / SLIDE);
        if (age < SLIDE + HOLD) return 1f;
        return (float) LiveColors.easeOutQuint((double) (LIFETIME - age) / SLIDE);
    }

    private void draw(LiveCanvas c, Toast t, int left, int top) {
        c.clip(left, top, WIDTH, HEIGHT);

        c.box(left, top, WIDTH, HEIGHT, LiveCanvas.opaque(LiveColors.rgb(24, 24, 24)));
        c.outline(left, top, WIDTH, HEIGHT, LiveCanvas.opaque(LiveColors.rgb(64, 64, 64)));
        // A stripe of the person's colour down the left edge, the same identity cue the windows
        // carry, so a toast reads as being from them before a word of it is.
        c.box(left, top, 2, HEIGHT, LiveCanvas.opaque(t.color()));

        int headSize = 24;
        int headX = left + 4;
        int headY = top + (HEIGHT - headSize) / 2;
        c.head(t.peer(), headX, headY, headSize);

        int textX = headX + headSize + 4;
        int room = left + WIDTH - 4 - textX;
        c.text(fit(c, t.name(), room), textX, top + 6, LiveCanvas.opaque(0xFFFFFF));
        c.text(fit(c, t.preview(), room), textX, top + 18,
            LiveCanvas.opaque(LiveColors.rgb(190, 190, 190)));

        c.unclip();
    }

    /** Trims a string with an ellipsis so it fits in {@code room} pixels. */
    private static String fit(LiveCanvas c, String s, int room) {
        if (s == null || s.isEmpty()) return "";
        if (c.width(s) <= room) return s;

        int budget = room - c.width("...");
        if (budget <= 0) return "";

        int used = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            int cw = c.width(String.valueOf(s.charAt(i)));
            if (used + cw > budget) break;
            used += cw;
            sb.append(s.charAt(i));
        }
        return sb + "...";
    }
}
