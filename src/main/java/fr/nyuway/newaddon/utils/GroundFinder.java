package fr.nyuway.newaddon.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds somewhere to stand, for a flight that is running out of world beneath it.
 *
 * <h2>Why not ask Baritone</h2>
 * Its elytra process picks where to land for its own reasons - it will glide out from under
 * the islands and only put down once the fireworks run low, which over the End means putting
 * down in nothing. What is needed here is narrower: the nearest block that is solid, has room
 * to stand on, and sits comfortably above the height where the world starts killing you.
 *
 * <h2>Cost</h2>
 * Run once when the guard trips, never per tick. The sweep is bounded and steps in twos: a
 * landing spot two blocks off is as good as an exact one, and halving the step would quadruple
 * a scan that already reads tens of thousands of blocks.
 */
public final class GroundFinder {

    /** Horizontal reach of the sweep, in blocks. */
    private static final int RADIUS = 48;

    /** Sampling step. Two is close enough to land on and four times cheaper than one. */
    private static final int STEP = 2;

    /** How far above the player to keep looking; higher ground is still worth reaching. */
    private static final int UP = 48;

    private GroundFinder() {
    }

    /**
     * Nearest standable block at or above {@code minY}, or null when there is nothing.
     *
     * @param minY lowest height that counts as safe - the search will not offer anything lower
     */
    public static BlockPos find(Minecraft mc, double minY) {
        if (mc.player == null || mc.level == null) return null;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int px = mc.player.getBlockX();
        int pz = mc.player.getBlockZ();
        int floor = Mth.ceil(minY);
        int top = Math.max(floor + 1, mc.player.getBlockY() + UP);

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -RADIUS; dx <= RADIUS; dx += STEP) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += STEP) {
                int x = px + dx;
                int z = pz + dz;

                // Top down: the first solid block with headroom is the one we would land on,
                // and it is the one furthest from the drop below it.
                for (int y = top; y >= floor; y--) {
                    cursor.set(x, y, z);
                    BlockState state = mc.level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (state.getCollisionShape(mc.level, cursor).isEmpty()) continue;

                    if (!headroom(mc, cursor, x, y, z)) break;

                    double dist = distSq(mc, x, y + 1, z);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new BlockPos(x, y + 1, z);
                    }
                    break;
                }
            }
        }

        return best;
    }

    /** Two blocks of air above, or it is not somewhere a player fits. */
    private static boolean headroom(Minecraft mc, BlockPos.MutableBlockPos cursor,
                                    int x, int y, int z) {
        for (int i = 1; i <= 2; i++) {
            cursor.set(x, y + i, z);
            if (!mc.level.getBlockState(cursor).isAir()) return false;
        }
        return true;
    }

    private static double distSq(Minecraft mc, int x, int y, int z) {
        double dx = mc.player.getX() - (x + 0.5);
        double dy = mc.player.getY() - y;
        double dz = mc.player.getZ() - (z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
