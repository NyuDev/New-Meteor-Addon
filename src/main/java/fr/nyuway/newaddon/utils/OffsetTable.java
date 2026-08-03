package fr.nyuway.newaddon.utils;

import java.util.Arrays;

/**
 * Every offset in a cube, ordered nearest-first, computed once and reused.
 *
 * <p>A scan that walks this table and stops at its first hit has by construction found the
 * closest match, without sorting anything per tick.
 *
 * <p>Each offset is packed into the low 24 bits of an int and paired with its squared
 * distance in the high bits of a long, so one primitive {@link Arrays#sort} orders the whole
 * table with no boxing and no comparator.
 */
public final class OffsetTable {

    /** Components are biased into a byte each so a packed offset stays positive. */
    private static final int PACK_BIAS = 64;

    private int[] offsets = new int[0];
    private int builtRadius = -1;

    /** Rebuilds only when the radius actually changed. */
    public void ensureRadius(int radius) {
        if (radius == builtRadius) return;

        int side = radius * 2 + 1;
        long[] keys = new long[side * side * side];
        int i = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long distSq = dx * dx + dy * dy + dz * dz;
                    int packed = ((dx + PACK_BIAS) << 16) | ((dy + PACK_BIAS) << 8) | (dz + PACK_BIAS);
                    keys[i++] = (distSq << 24) | packed;
                }
            }
        }

        Arrays.sort(keys);

        int[] result = new int[keys.length];
        for (int j = 0; j < keys.length; j++) result[j] = (int) (keys[j] & 0xFFFFFF);

        offsets = result;
        builtRadius = radius;
    }

    /** Packed offsets, nearest-first. Read with {@link #dx}, {@link #dy} and {@link #dz}. */
    public int[] packed() {
        return offsets;
    }

    /** Forces the next {@link #ensureRadius} to rebuild, for use when a module restarts. */
    public void invalidate() {
        builtRadius = -1;
    }

    public static int dx(int packed) {
        return ((packed >> 16) & 0xFF) - PACK_BIAS;
    }

    public static int dy(int packed) {
        return ((packed >> 8) & 0xFF) - PACK_BIAS;
    }

    public static int dz(int packed) {
        return (packed & 0xFF) - PACK_BIAS;
    }
}
