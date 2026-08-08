package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.modules.dm.LiveStore;

import java.util.UUID;

/**
 * Livemessage's palette and easing, carried over from its {@code GuiUtil}.
 *
 * <p>Almost none of this touched Minecraft, so it comes across as it was written: the HSL
 * conversion, the hue nudge away from the unreadable blues, the per-person colour derived from
 * the first two bytes of a UUID, and the handful of colours rebane2001 hard-coded for specific
 * people. That last part is not decoration - it is why a conversation looks like itself, and
 * dropping it would have been quietly redesigning the thing I was asked to port.
 *
 * <p>The one change: timing reads {@code System.currentTimeMillis} rather than 1.12.2's
 * {@code Minecraft.getSystemTime}, which no longer exists.
 */
public final class LiveColors {

    private LiveColors() {
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    public static int stripAlpha(int color) {
        return color & 0x00FFFFFF;
    }

    /** HSL to RGB, as upstream. */
    public static int hsl(float h, float s, float l) {
        float r, g, b;

        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? (l * (1 + s)) : (l + s - l * s);
            float p = 2 * l - q;
            r = hue2rgb(p, q, h + 1.0f / 3);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1.0f / 3);
        }

        return rgb(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    private static float hue2rgb(float p, float q, float h) {
        if (h < 0) h += 1;
        if (h > 1) h -= 1;

        if (6 * h < 1) return p + ((q - p) * 6 * h);
        if (2 * h < 1) return q;
        if (3 * h < 2) return p + ((q - p) * 6 * ((2.0f / 3.0f) - h));
        return p;
    }

    /** Pushes a hue out of the band that reads badly on a dark window. */
    public static float readableHue(float hue) {
        if (hue > 0.62 && hue < 0.72) hue += 0.1f;
        return hue;
    }

    /**
     * A conversation's colour: the one you chose, else a name everyone knows, else one
     * derived from the UUID so the same person is always the same colour.
     */
    public static int windowColor(LiveStore store, UUID uuid) {
        LiveStore.PeerSettings settings = store.settingsOf(uuid);
        if (settings.customColor > 0) return settings.customColor;

        switch (uuid.toString()) {
            case "342fc44b-1fd1-4272-a4c3-a98a2df98abc": return 0x3575DF; // popstonia
            case "cda8edd9-430e-4f6e-a45a-be4566f39c38": return 0xEB8258; // rebane2001
            case "c499a96f-8a69-47c3-8525-0595b6b50f00": return 0x00FF44; // Littlepip
            case "a997ff99-4515-4055-9117-39be3469c9d7": return 0xFFCE3D; // Yqe
            case "4d03444c-2e0b-4b8e-a445-a2965c907676": return 0x48B6ED; // mikroskeem
            default: break;
        }

        String id = uuid.toString();
        float first = Integer.parseInt(id.substring(0, 2), 16) / 255f;
        float second = Integer.parseInt(id.substring(2, 4), 16) / 255f;
        return hsl(first, 0.6f + second * 0.15f, 0.5f);
    }

    public static double easeOutQuint(double t) {
        return 1 - Math.pow(1 - t, 5);
    }

    public static double easeInOutCubic(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /**
     * Upstream's animation: a value that slides to wherever it was last set.
     *
     * <p>Set a target with {@link #animate}, read {@link #state} every frame. The easing is
     * what makes the windows feel like the messenger this is imitating rather than a panel
     * that snaps between positions.
     */
    public static final class Slide {

        private final int length;
        private long startTime;
        private float startValue;
        private float target;

        public Slide(int lengthMillis) {
            this.length = lengthMillis;
        }

        public Slide(int lengthMillis, float initial) {
            this.length = lengthMillis;
            this.startValue = initial;
            this.target = initial;
        }

        public float animate(float value) {
            if (value != target) {
                startValue = state();
                target = value;
                startTime = System.currentTimeMillis();
            }
            return state();
        }

        public float state() {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > length) return target;

            double progress = (double) elapsed / length;
            return (float) (startValue - easeOutQuint(progress) * (startValue - target));
        }
    }
}
