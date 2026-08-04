package fr.nyuway.newaddon.utils;

import net.minecraft.world.level.Level;

/**
 * Where the world ends, and where falling past it starts hurting.
 *
 * <p>Another spot needing per-version source: the world floor is {@code getMinBuildHeight()}
 * up to 1.21.1 and {@code getMinY()} from 1.21.3 on. Keeping the split here leaves the
 * modules asking a plain question.
 */
public final class WorldBounds {

    /** How far below the world floor vanilla starts applying void damage. */
    public static final int VOID_DAMAGE_DROP = 64;

    private WorldBounds() {
    }

    /** Lowest buildable Y in this dimension. */
    public static int minY(Level level) {
        //? if >=1.21.3 {
        return level.getMinY();
        //?} else {
        /*return level.getMinBuildHeight();
        *///?}
    }

    /**
     * Y below which the game deals void damage.
     *
     * <p>Vanilla checks {@code getY() < minY - 64}, so this is dimension-dependent rather
     * than one fixed number: about -128 in the Overworld, whose floor is -64, but -64 in the
     * Nether and the End, whose floor is 0.
     */
    public static double voidDamageY(Level level) {
        return minY(level) - VOID_DAMAGE_DROP;
    }
}
