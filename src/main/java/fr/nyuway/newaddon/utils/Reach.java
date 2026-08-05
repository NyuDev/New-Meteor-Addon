package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * How far away a block may be before breaking or placing it is refused.
 *
 * <h2>Why this is separate from the scan range</h2>
 * The scan range decides what the module looks at; this decides what it is willing to touch.
 * They are not the same limit. Bone mealing at 4.5 passes on servers that reject a break or a
 * place at the same distance, because those two go through a stricter server-side check - and
 * anticheats on anarchy servers tighten it further.
 *
 * <h2>Measuring the way a server does</h2>
 * The custom check measures from the eyes to the <i>closest point of the block</i>, not to its
 * centre. Centre-to-eye reports up to {@code sqrt(3)/2} - about 0.87 - more than the distance
 * the server computes, which is enough to sail past a limit you thought you were under.
 */
public final class Reach {

    private Reach() {
    }

    /**
     * @param vanilla defer to the client's own interaction range, whatever this version calls it
     * @param custom  limit in blocks, used only when {@code vanilla} is false
     */
    public static boolean canReach(Minecraft mc, BlockPos pos, boolean vanilla, double custom) {
        if (mc.player == null) return false;
        if (vanilla) return PlayerUtils.isWithinReach(pos);

        Vec3 eye = mc.player.getEyePosition();
        double dx = axisGap(eye.x, pos.getX());
        double dy = axisGap(eye.y, pos.getY());
        double dz = axisGap(eye.z, pos.getZ());

        return dx * dx + dy * dy + dz * dz <= custom * custom;
    }

    /** Distance from a coordinate to the nearest point of the block's span on that axis. */
    private static double axisGap(double from, int blockMin) {
        if (from < blockMin) return blockMin - from;
        if (from > blockMin + 1) return from - (blockMin + 1);
        return 0;
    }
}
