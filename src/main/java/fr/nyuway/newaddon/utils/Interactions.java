package fr.nyuway.newaddon.utils;

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
