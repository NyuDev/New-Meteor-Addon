package fr.nyuway.newaddon.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Picks somewhere nearby to put a block down.
 *
 * <p>Two things this has to get right, both learned the hard way:
 * <ul>
 *   <li>Search the whole reachable area, not just the four blocks touching the player. With
 *       only those, the first block placed took one and the second had nowhere to go.</li>
 *   <li>A shulker box only opens if the space its lid swings into is clear. Placed against a
 *       wall - or against the ender chest - it faces sideways and vanilla refuses to open it
 *       at all, which looks exactly like the client hanging.</li>
 * </ul>
 *
 * <p>Created per search rather than held, so it always sees current positions.
 */
public final class SpotFinder {

    private final Minecraft mc;
    private final double radius;
    private final int voidClearance;
    private final BlockPos occupiedA;
    private final BlockPos occupiedB;

    /** Reused across the sweep so a search allocates nothing. */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    /**
     * @param radius        how far to look, capped by what can actually be clicked
     * @param voidClearance solid blocks required below, so nothing dropped falls away
     * @param occupiedA     a position already taken, or null
     * @param occupiedB     another position already taken, or null
     */
    public SpotFinder(Minecraft mc, double radius, int voidClearance,
                      BlockPos occupiedA, BlockPos occupiedB) {
        this.mc = mc;
        this.radius = radius;
        this.voidClearance = voidClearance;
        this.occupiedA = occupiedA;
        this.occupiedB = occupiedB;
    }

    /**
     * Nearest usable spot, or null.
     *
     * <p>Prefers ground ringed by solid floor so a dropped item cannot roll over an edge, then
     * falls back to any valid spot: on a narrow ledge a slightly exposed placement still beats
     * refusing to work and stranding the trip.
     */
    public BlockPos find(boolean forShulker) {
        BlockPos ringed = sweep(forShulker, true);
        return ringed != null ? ringed : sweep(forShulker, false);
    }

    private BlockPos sweep(boolean forShulker, boolean requireRinged) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int reach = Mth.ceil(radius);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    scratch.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!isUsable(scratch, forShulker)) continue;
                    if (requireRinged && !isRingedByGround(scratch)) continue;

                    // Close enough to actually click, not merely close enough to see.
                    double dist = mc.player.getEyePosition().distanceToSqr(
                        scratch.getX() + 0.5, scratch.getY() + 0.5, scratch.getZ() + 0.5);
                    if (dist > radius * radius) continue;

                    if (dist < bestDist) {
                        bestDist = dist;
                        best = scratch.immutable();
                    }
                }
            }
        }

        return best;
    }

    /** True when the floor extends under all eight blocks around the spot, edge included. */
    private boolean isRingedByGround(BlockPos pos) {
        BlockPos base = pos.below();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isGround(mc.level.getBlockState(base.offset(dx, 0, dz)))) return false;
            }
        }
        return true;
    }

    private boolean isUsable(BlockPos pos, boolean forShulker) {
        if (pos.equals(occupiedA) || pos.equals(occupiedB)) return false;
        if (pos.equals(mc.player.blockPosition())) return false;
        if (pos.equals(mc.player.blockPosition().above())) return false;

        if (!mc.level.getBlockState(pos).isAir()) return false;
        if (!mc.level.getBlockState(pos.above()).isAir()) return false;

        // Solid floor directly under it, so the placement clicks the ground and a shulker
        // ends up facing up rather than sideways against whatever else was adjacent.
        if (!isGround(mc.level.getBlockState(pos.below()))) return false;

        for (int d = 1; d <= voidClearance; d++) {
            if (mc.level.getBlockState(pos.below(d)).isAir()) return false;
        }

        if (forShulker) {
            // Away from the other block, or the placement clicks its face.
            if (occupiedA != null && pos.distSqr(occupiedA) < 3.0) return false;

            // The lid opens into the block above; vanilla's own check fails if anything
            // collides there, standing on top of it included.
            if (mc.player.getBoundingBox().intersects(new AABB(pos.above()))) return false;
        }

        return true;
    }

    /**
     * Something a block can be placed against.
     *
     * <p>Deliberately avoids {@code isSolidRender}, whose signature is not stable across the
     * supported versions; not-air and not-fluid is enough to tell a floor from a hole.
     */
    private static boolean isGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
