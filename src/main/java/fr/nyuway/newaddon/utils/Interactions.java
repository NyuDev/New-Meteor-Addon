package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block interactions that look like a player made them.
 *
 * <p>Servers running a serious anticheat - 2b2t among them - reject or flag a client that
 * opens a chest or mines a block it is not facing, because the rotation the server has on
 * record does not point at the target. Meteor's {@code BlockUtils.interact} and
 * {@code breakBlock} do not rotate on their own, so calling them directly is exactly that
 * mistake.
 *
 * <p>Everything here rotates first and acts in the rotation's callback, so the rotation
 * reaches the server before the interaction does.
 *
 * <h2>Silent rotations</h2>
 * Meteor's rotation system carries the angle in the movement packets it is already sending,
 * and can do so without turning the visible camera. That is what {@code silent} selects: the
 * server sees the player looking at the block either way, but the screen does not whip
 * around. It changes nothing about what the server is told - only what you see.
 */
public final class Interactions {

    /** Priority for these rotations, above idle aiming but below combat modules. */
    private static final int PRIORITY = 50;

    private Interactions() {
    }

    /** Turns to face a block, then runs the action once the rotation is in flight. */
    public static void lookAt(BlockPos pos, boolean silent, Runnable then) {
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), PRIORITY, !silent, then);
    }

    /**
     * Right-clicks a block after facing it.
     *
     * <p>The clicked face is the one turned toward the player rather than always the top: a
     * click on a face the player cannot see is another thing worth not doing.
     */
    public static void interact(Minecraft mc, BlockPos pos, boolean silent, boolean swing) {
        lookAt(pos, silent, () -> clickBlock(mc, pos, swing));
    }

    /**
     * Right-clicks a block without rotating, for callers already inside a rotation callback.
     *
     * <p>Rotating again from within a rotation would be harmless but pointless; what matters
     * is that the hit lands on a face turned toward the player either way.
     */
    public static void clickBlock(Minecraft mc, BlockPos pos, boolean swing) {
        if (mc.player == null) return;
        Direction face = faceToward(mc, pos);
        BlockUtils.interact(
            new BlockHitResult(hitVec(pos, face), face, pos, false),
            InteractionHand.MAIN_HAND, swing);
    }

    /**
     * Places a block, honouring the caller's rotation choice.
     *
     * <p>{@code BlockUtils.place} takes a {@code rotate} flag, but its rotation is always the
     * visible kind - it has no way to ask for a silent one. So when a rotation is wanted this
     * turns the head itself and hands {@code place} a {@code rotate} of {@code false}; when it
     * is not wanted, nothing touches yaw or pitch at all and the block simply goes down at the
     * angle the player is already holding.
     *
     * <p>The aim point is the same one {@code place} clicks: the centre of the face of the
     * supporting neighbour, not the centre of the target. Aiming anywhere else would put the
     * server's idea of where we are looking half a block off the click we then send.
     *
     * @param allowAirPlace place even with no block to click against. Meteor falls back to
     *                      clicking the target position itself, which no vanilla server
     *                      accepts - the packet names a block that is not there. Left off,
     *                      such a target is skipped instead of silently wasting the attempt.
     * @return whether a placement was attempted
     */
    public static boolean place(BlockPos pos, FindItemResult item, boolean rotate, boolean silent,
                                boolean swing, boolean allowAirPlace) {
        Direction side = BlockUtils.getPlaceSide(pos);
        if (side == null && !allowAirPlace) return false;

        if (!rotate) return BlockUtils.place(pos, item, false, PRIORITY, swing, true);

        Vec3 aim = side == null ? Vec3.atCenterOf(pos) : hitVec(pos, side);
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), PRIORITY, !silent,
            () -> BlockUtils.place(pos, item, false, PRIORITY, swing, true));
        return true;
    }

    /**
     * Mines a block after facing it.
     *
     * <p>Safe to call every tick: breaking is progressive, and repeating the rotation simply
     * keeps the player pointed at the block for as long as it takes.
     */
    public static void mine(Minecraft mc, BlockPos pos, boolean silent, boolean swing) {
        lookAt(pos, silent, () -> {
            if (mc.player == null) return;
            BlockUtils.breakBlock(pos, swing);
        });
    }

    /** The face of the block most turned toward the player's eyes. */
    private static Direction faceToward(Minecraft mc, BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);

        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** A point on the chosen face rather than the block centre, as a real cursor would land. */
    private static Vec3 hitVec(BlockPos pos, Direction face) {
        return new Vec3(
            pos.getX() + 0.5 + face.getStepX() * 0.5,
            pos.getY() + 0.5 + face.getStepY() * 0.5,
            pos.getZ() + 0.5 + face.getStepZ() * 0.5);
    }
}
