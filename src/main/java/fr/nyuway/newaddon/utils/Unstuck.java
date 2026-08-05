package fr.nyuway.newaddon.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds a block that has closed around the player.
 *
 * <h2>How you end up inside a block</h2>
 * You normally cannot walk into one - the collision pushes you out. But a block that
 * <i>appears</i> where you already are does not push anything: growing an azalea into a tree
 * puts logs and leaves wherever the trunk went, and that can be exactly where you stand. The
 * result is suffocation damage if it caught your head, and a pathfinder that cannot understand
 * why it is not moving if it caught your feet.
 *
 * <h2>What counts</h2>
 * A block traps you when its collision shape overlaps your hitbox. That is a stronger test
 * than asking whether it suffocates: suffocation only covers the head, while a block around
 * the feet damages nothing and still leaves Baritone wedged. Blocks you can walk through -
 * grass, flowers - have no collision and are correctly ignored.
 *
 * <p>The search runs head-down, so the block doing the damage is the one reported first.
 */
public final class Unstuck {

    private Unstuck() {
    }

    /**
     * @param cursor scratch position, so a per-tick check allocates nothing
     * @return the trapping block nearest the head, or null when the player is in the clear
     */
    public static BlockPos find(Minecraft mc, BlockPos.MutableBlockPos cursor) {
        if (mc.player == null || mc.level == null) return null;

        AABB box = mc.player.getBoundingBox();

        // Shrink by a hair: standing on a floor or brushing a wall touches those blocks
        // exactly, and neither of them is trapping anyone.
        int minX = Mth.floor(box.minX + 1.0E-6), maxX = Mth.floor(box.maxX - 1.0E-6);
        int minY = Mth.floor(box.minY + 1.0E-6), maxY = Mth.floor(box.maxY - 1.0E-6);
        int minZ = Mth.floor(box.minZ + 1.0E-6), maxZ = Mth.floor(box.maxZ - 1.0E-6);

        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);

                    BlockState state = mc.level.getBlockState(cursor);
                    if (state.isAir()) continue;

                    VoxelShape shape = state.getCollisionShape(mc.level, cursor);
                    if (shape.isEmpty()) continue;

                    if (shape.bounds().move(x, y, z).intersects(box)) return cursor.immutable();
                }
            }
        }

        return null;
    }
}
