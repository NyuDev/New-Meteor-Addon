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
     * <p>Three passes, from safest to any port in a storm. First a spot with floor two blocks
     * out in every direction - properly inland, where a shulker broken open cannot throw an item
     * over an edge. Then one block out, the old test. Then anything at all, because on a narrow
     * ledge a slightly exposed placement still beats refusing to work and stranding the trip.
     *
     * <p>The wide pass exists because the middle one is not far enough in the End. A drop lands
     * where it likes within a block or so of the broken block, and an island edge one block away
     * is close enough for the difference between keeping an ender chest and watching it fall.
     */
    public BlockPos find(boolean forShulker) {
        for (int ring = 2; ring >= 1; ring--) {
            BlockPos safe = sweep(forShulker, ring);
            if (safe != null) return safe;
        }
        return sweep(forShulker, 0);
    }

    /**
     * @param ring how far out the floor has to reach, or zero to not ask
     */
    private BlockPos sweep(boolean forShulker, int ring) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int reach = Mth.ceil(radius);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    scratch.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!isUsable(scratch, forShulker)) continue;
                    if (ring > 0 && !isRingedByGround(scratch, ring)) continue;

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

    /** True when the floor extends under every block within {@code ring} of the spot. */
    private boolean isRingedByGround(BlockPos pos, int ring) {
        BlockPos base = pos.below();
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (!isGround(mc.level.getBlockState(base.offset(dx, 0, dz)))) return false;
            }
        }
        return true;
    }

    private boolean isUsable(BlockPos pos, boolean forShulker) {
        if (pos.equals(occupiedA) || pos.equals(occupiedB)) return false;

        // Anywhere the player is standing in, not merely the block their feet are counted in.
        // A hitbox is six tenths of a block wide and hardly ever centred, so standing near an
        // edge puts part of you in the next column along - and Minecraft will not place a block
        // inside an entity. It refuses without saying anything, so the routine asked once a tick
        // for ten seconds and then reported a timeout, never having been told no.
        //
        // This was checked only for shulkers, and only for the space above them. It went
        // unnoticed while the run set up mid-slide: by the time the block was placed the player
        // had drifted off it. Standing still, which is the whole point of settling first, is
        // what turned it from a rarity into every time.
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;

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
