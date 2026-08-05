package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;

/**
 * Answers whether KillAura is fighting right now.
 *
 * <p>{@code Modules.get().isActive(KillAura.class)} answers a different question: whether the
 * module is switched on. On an anarchy server it stays on from login to logout, so anything that
 * yielded to that answer yielded forever. What callers actually mean is "a fight is happening",
 * and the sign of that is KillAura holding a target.
 */
public final class Combat {
    /**
     * How long a fight keeps its hold after the last target is gone. A swarm drops targets for a
     * few ticks between kills, and resuming into that gap would fight KillAura for the rotation.
     */
    private static final long LINGER_MS = 1000L;

    private static long lastTarget;

    private Combat() {}

    public static boolean killAuraFighting() {
        KillAura aura = Modules.get().get(KillAura.class);
        if (aura == null || !aura.isActive()) return false;

        if (aura.getTarget() != null) {
            lastTarget = System.currentTimeMillis();
            return true;
        }

        return System.currentTimeMillis() - lastTarget < LINGER_MS;
    }
}
