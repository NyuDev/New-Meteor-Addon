package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import fr.nyuway.newaddon.mixin.GameModeAccessor;
import fr.nyuway.newaddon.utils.Interactions;
import fr.nyuway.newaddon.utils.Reach;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockActivateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * AutoBreak - point at a kind of block, and everything of that kind nearby comes down.
 *
 * <h2>Choosing what to break</h2>
 * Switch it on and right-click a block. That block's <em>kind</em> becomes the job - not that
 * one block - and from then on the module walks to the nearest one with Baritone and breaks it,
 * the way AutoMoss walks to the nearest moss. A kind is picked rather than typed because you are
 * standing in front of the thing you want gone, and naming it would mean knowing what it is
 * called.
 *
 * <h2>Two ways of breaking</h2>
 * <b>Vanilla</b> is the honest one: face a block, hold the button, wait for it to break, move on.
 *
 * <p><b>SpeedMine</b> is the two-at-a-time trick. You start breaking one block, and a moment
 * later - a fifth of a second is plenty - you start breaking the next one, without ever telling
 * the server you gave up on the first. Both then come down together. It only works because the
 * client and the server disagree slightly about how many blocks a player can be part-way through,
 * which is also why the timings here are all settings: the exact numbers that work are a property
 * of the server, not of this code.
 *
 * <p>The whole point of it is to be doing the same thing as a second client on the other block,
 * so nothing here rotates by default. A player who turns to face each block in turn is doing
 * something visibly different from one who does not, and the two clients have to look alike.
 *
 * <h2>Why the packets are sent by hand</h2>
 * {@code MultiPlayerGameMode.startDestroyBlock} sends an <em>abort</em> for the block you were
 * on before starting the next one - which is correct for a player and fatal here, since the
 * abort is precisely the message that throws away the progress the trick depends on. So
 * SpeedMine builds the start and stop packets itself and never touches that path. Vanilla mode
 * goes through Meteor's ordinary breaking, because there it is the right thing.
 */
public class AutoBreak extends Module {

    /** How to break. */
    public enum Mode {
        /** Face it, hold, wait. One block at a time. */
        Vanilla,
        /** Start one, start the next, let both finish. */
        SpeedMine
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed mine");
    private final SettingGroup sgWalk = settings.createGroup("Walking");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Vanilla breaks one block at a time and waits for each. SpeedMine starts " +
                     "two and lets both finish, which is what makes it match a second client " +
                     "working the other block.")
        .defaultValue(Mode.SpeedMine)
        .build());

    private final Setting<Keybind> pickKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("pick-key")
        .description("Takes the block you are looking at as the job, for when right-clicking it " +
                     "would do something you did not want - a chest, a door, a crafting table.")
        .defaultValue(Keybind.none())
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turn to face each block. Off, because the point of SpeedMine is to look " +
                     "like the client working beside you, and that one is not turning either.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your arm. Off sends the swing as a packet instead, which everyone " +
                     "else still sees, so this is about your own view.")
        .defaultValue(true)
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far a block can be and still be broken. Past the server's own reach " +
                     "nothing happens, whatever this says.")
        .defaultValue(4.5).min(1).max(6).sliderRange(1, 6)
        .build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Say in chat what was picked, and why it stopped.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Write each phase and each pair to the game log.")
        .defaultValue(false)
        .build());

    // --- speed mine ----------------------------------------------------------

    private final Setting<Integer> holdMs = sgSpeed.add(new IntSetting.Builder()
        .name("hold-ms")
        .description("How long to stay on each of the two blocks before moving to the other. " +
                     "Long enough for the server to have registered the start; a fifth of a " +
                     "second is usually plenty, and longer only makes the pair slower.")
        .defaultValue(200).min(20).max(2000).sliderRange(50, 800)
        .build());

    private final Setting<Integer> waitMs = sgSpeed.add(new IntSetting.Builder()
        .name("wait-ms")
        .description("How long to wait for both blocks to come down before giving up on the pair " +
                     "and starting another. A pair that never breaks is the sign the timings do " +
                     "not suit this server.")
        .defaultValue(3000).min(200).max(20000).sliderRange(500, 8000)
        .build());

    private final Setting<Boolean> finish = sgSpeed.add(new BoolSetting.Builder()
        .name("send-finish")
        .description("Send the stop-breaking message for both blocks once the second has been " +
                     "held. Some servers break a block on that message rather than on their own " +
                     "clock; on the ones that do not, it costs two packets.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> abortOnSwitch = sgSpeed.add(new BoolSetting.Builder()
        .name("abort-on-switch")
        .description("Tell the server you gave up on the first block before starting the second, " +
                     "the way vanilla does. Off, and it has to be: that message is exactly what " +
                     "throws away the progress this whole mode is built on. Here to be turned on " +
                     "if a server needs it, not because it is a good idea.")
        .defaultValue(false)
        .build());

    // --- walking -------------------------------------------------------------

    private final Setting<Boolean> walk = sgWalk.add(new BoolSetting.Builder()
        .name("walk")
        .description("Use Baritone to go to the nearest block when none is in reach. Off keeps " +
                     "the module to what you can already touch.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> walkRadius = sgWalk.add(new IntSetting.Builder()
        .name("walk-radius")
        .description("How close Baritone has to get before this takes over. Two or three: " +
                     "standing on the block is neither necessary nor helpful.")
        .defaultValue(2).min(0).max(6).sliderRange(0, 6)
        .build());

    private final Setting<Integer> searchChunks = sgWalk.add(new IntSetting.Builder()
        .name("search-chunks")
        .description("How far to look for more, in chunks.")
        .defaultValue(8).min(1).max(32).sliderRange(1, 16)
        .build());

    private final Setting<Integer> yRange = sgWalk.add(new IntSetting.Builder()
        .name("y-range")
        .description("How far up or down to consider. Keeps a job on this floor from wandering " +
                     "into the one below it.")
        .defaultValue(32).min(4).max(256).sliderRange(8, 128)
        .build());

    // --- safety --------------------------------------------------------------

    private final Setting<Boolean> noSpleef = sgSafety.add(new BoolSetting.Builder()
        .name("no-spleef")
        .description("Never break the block holding you up. Without it, a job whose blocks are " +
                     "the floor ends with you in the hole, which on 2b2t is usually the end of " +
                     "the trip rather than an inconvenience.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> stopOnTeleport = sgSafety.add(new BoolSetting.Builder()
        .name("stop-on-teleport")
        .description("Switch off when you are moved somewhere you did not walk to - a stasis " +
                     "pull, a teleport, anything sudden. Wherever you have just arrived, " +
                     "carrying on mining is not what you wanted.")
        .defaultValue(true)
        .build());

    private final Setting<Double> teleportDistance = sgSafety.add(new DoubleSetting.Builder()
        .name("teleport-distance")
        .description("How far you have to move in a single tick to count as having been moved " +
                     "rather than having walked.")
        .defaultValue(16).min(4).max(256).sliderRange(8, 64)
        .visible(stopOnTeleport::get)
        .build());

    // --- render --------------------------------------------------------------

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Outline the blocks being worked on.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the outline is drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 140, 60, 40))
        .visible(render::get)
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 140, 60))
        .visible(render::get)
        .build());

    // --- state ---------------------------------------------------------------

    /** The kind of block to break. Null until something has been picked. */
    private Block wanted;

    /** Where the two halves of a pair are, and how far through it we are. */
    private BlockPos first;
    private BlockPos second;
    private Phase phase = Phase.IDLE;
    private long phaseAt;

    private enum Phase { IDLE, HOLD_FIRST, HOLD_SECOND, WAITING }

    /** Where Baritone is taking us, if anywhere. */
    private BlockPos walkTarget;

    /** Last position, to tell walking from being moved. */
    private Vec3 lastPos;

    private boolean pickHeld;

    /** Consecutive pairs that ran out of patience, so the reason gets said rather than repeated. */
    private int timeouts;

    /** When Baritone's scanner was last asked, so it is not asked twenty times a second. */
    private long lastScan;

    public AutoBreak() {
        super(NewAddon.CATEGORY, "auto-break",
            "Right-click a block; every block of that kind nearby comes down.");
    }

    @Override
    public String getInfoString() {
        if (wanted == null) return "pick one";
        return wanted.getName().getString();
    }

    @Override
    public void onActivate() {
        wanted = null;
        clearPair();
        walkTarget = null;
        lastPos = mc.player == null ? null : mc.player.position();
        pickHeld = false;
        timeouts = 0;
        lastScan = 0;

        if (notify.get()) {
            info("Right-click the block you want gone, or bind pick-key and look at it.");
        }
        if (walk.get() && !BaritoneBridge.isPresent()) {
            warning("Baritone is not installed, so nothing will walk anywhere.");
        }
    }

    @Override
    public void onDeactivate() {
        stopBreaking();
        if (walkTarget != null) BaritoneBridge.cancel();
        walkTarget = null;
        clearPair();
    }

    // --- picking -------------------------------------------------------------

    /**
     * Takes the kind of block that was just right-clicked.
     *
     * <p>Meteor fires this for every block activated, which is every right-click on a block
     * whether or not the block does anything about it - so plain stone works as well as a chest.
     * Only the first one counts; once there is a job, right-clicking is yours again.
     */
    @EventHandler
    private void onActivateBlock(BlockActivateEvent event) {
        if (wanted != null || event.blockState == null) return;

        Block block = event.blockState.getBlock();
        if (block == Blocks.AIR) return;

        adopt(block);
    }

    private void adopt(Block block) {
        wanted = block;
        clearPair();
        if (notify.get()) info("Breaking %s.", block.getName().getString());
    }

    // --- the work ------------------------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (movedByForce()) {
            if (notify.get()) warning("Moved somewhere sudden; stopping.");
            toggle();
            return;
        }

        // The keybind is the way in when right-clicking the block would do something else.
        boolean pick = pickKey.get().isSet() && pickKey.get().isPressed();
        if (pick && !pickHeld && mc.screen == null) {
            BlockPos looking = lookingAt();
            if (looking != null) adopt(mc.level.getBlockState(looking).getBlock());
        }
        pickHeld = pick;

        if (wanted == null) return;

        if (mode.get() == Mode.SpeedMine) tickPair();
        else tickOne();
    }

    /** One block, faced and held until it breaks, then the next. */
    private void tickOne() {
        if (first != null && !isWanted(first)) first = null;
        if (first == null) first = nearestInReach(null);

        if (first == null) {
            goFind();
            return;
        }

        if (rotate.get()) Interactions.mine(mc, first, false, swing.get());
        else BlockUtils.breakBlock(first, swing.get());
    }

    /**
     * Two blocks, started a moment apart, then left to finish together.
     *
     * <p>Everything here is time in milliseconds rather than ticks. The window that makes this
     * work is a property of the server's timing, and a tick is twenty of those - too coarse to
     * aim with.
     */
    private void tickPair() {
        long now = System.currentTimeMillis();

        switch (phase) {
            case IDLE -> {
                first = nearestInReach(null);
                if (first == null) {
                    goFind();
                    return;
                }

                // A second block is wanted but not required: the last one of a job has nobody to
                // pair with, and refusing to break it would leave the job unfinished.
                second = nearestInReach(first);

                start(first);
                phase = Phase.HOLD_FIRST;
                phaseAt = now;
                if (debug.get()) log("pair %s + %s", first, second);
            }

            case HOLD_FIRST -> {
                if (now - phaseAt < holdMs.get()) {
                    keepGoing(first);
                    return;
                }

                if (second == null) {
                    // Nothing to pair with. Finish this one on its own rather than sitting on it.
                    if (finish.get()) stop(first);
                    phase = Phase.WAITING;
                    phaseAt = now;
                    return;
                }

                if (abortOnSwitch.get()) send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, first);
                start(second);
                phase = Phase.HOLD_SECOND;
                phaseAt = now;
            }

            case HOLD_SECOND -> {
                if (now - phaseAt < holdMs.get()) {
                    keepGoing(second);
                    return;
                }

                if (finish.get()) {
                    stop(first);
                    stop(second);
                }
                phase = Phase.WAITING;
                phaseAt = now;
            }

            case WAITING -> {
                boolean firstGone = first == null || !isWanted(first);
                boolean secondGone = second == null || !isWanted(second);

                if (firstGone && secondGone) {
                    if (debug.get()) log("pair done in %d ms", now - phaseAt);
                    timeouts = 0;
                    clearPair();
                    return;
                }

                if (now - phaseAt > waitMs.get()) {
                    if (debug.get()) log("pair timed out; starting another");

                    // Said out loud eventually. Starting pair after pair that never breaks looks
                    // identical to working, and the cause is always one of two things a person
                    // can fix - the timings not suiting the server, or the wrong tool in hand.
                    if (++timeouts == 3) {
                        timeouts = 0;
                        if (notify.get()) {
                            warning("Nothing is breaking. Try a longer hold-ms, or check your tool.");
                        }
                    }
                    clearPair();
                }
            }
        }
    }

    private void clearPair() {
        first = null;
        second = null;
        phase = Phase.IDLE;
        phaseAt = 0;
    }

    // --- breaking ------------------------------------------------------------

    /**
     * Starts breaking, by hand.
     *
     * <p>Not {@code MultiPlayerGameMode.startDestroyBlock}: that sends an abort for whatever you
     * were on before, which is right for a player and is the one message this mode must never
     * send. The sequence number still comes from the game's own prediction, so the server's
     * acknowledgements line up as they would for any other block interaction.
     */
    private void start(BlockPos pos) {
        if (pos == null) return;

        if (rotate.get()) {
            Interactions.lookAt(pos, false, () -> {
                send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos);
                swingArm();
            });
            return;
        }

        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos);
        swingArm();
    }

    private void stop(BlockPos pos) {
        if (pos != null) send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos);
    }

    /** Keeps the arm moving while a block is held, which is all a held button looks like. */
    private void keepGoing(BlockPos pos) {
        if (pos != null) swingArm();
    }

    private void swingArm() {
        if (mc.player == null) return;

        if (swing.get()) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        else if (mc.getConnection() != null) {
            mc.getConnection().send(
                new net.minecraft.network.protocol.game.ServerboundSwingPacket(
                    net.minecraft.world.InteractionHand.MAIN_HAND));
        }
    }

    private void send(ServerboundPlayerActionPacket.Action action, BlockPos pos) {
        if (mc.gameMode == null || mc.level == null) return;

        Direction face = BlockUtils.getDirection(pos);
        BlockPos at = pos.immutable();
        ((GameModeAccessor) mc.gameMode).newAddon$startPrediction(mc.level,
            sequence -> new ServerboundPlayerActionPacket(action, at, face, sequence));
    }

    /** Lets go of whatever was being broken, so switching off does not leave a held button. */
    private void stopBreaking() {
        if (mc.gameMode == null) return;

        if (mode.get() == Mode.SpeedMine) {
            if (phase == Phase.HOLD_FIRST || phase == Phase.HOLD_SECOND) {
                stop(first);
                stop(second);
            }
            return;
        }
        mc.gameMode.stopDestroyBlock();
    }

    // --- finding -------------------------------------------------------------

    private boolean isWanted(BlockPos pos) {
        if (pos == null || mc.level == null) return false;

        BlockState state = mc.level.getBlockState(pos);
        return state.getBlock() == wanted && BlockUtils.canBreak(pos, state);
    }

    /**
     * The nearest block of the right kind within reach, skipping one already spoken for.
     *
     * <p>A plain box scan of the reach cube, which is at most a few hundred blocks and is the
     * whole search: anything further away is Baritone's problem, not this one.
     */
    private BlockPos nearestInReach(BlockPos except) {
        if (mc.player == null || mc.level == null) return null;

        int r = Mth.ceil(range.get());
        BlockPos feet = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);

                    if (except != null && cursor.equals(except)) continue;
                    if (!isWanted(cursor)) continue;
                    if (supportsMe(cursor)) continue;
                    if (!Reach.canReach(mc, cursor, false, range.get())) continue;

                    double dist = eye.distanceToSqr(
                        cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5);
                    if (dist >= bestDist) continue;

                    bestDist = dist;
                    best = cursor.immutable();
                }
            }
        }
        return best;
    }

    /**
     * Whether breaking this would drop us.
     *
     * <p>The block under the feet, across the whole footprint, since standing on the edge of two
     * blocks means either of them counts. Everything else is fair game - a job whose blocks
     * happen to be the floor is common, and the only part of that floor worth protecting is the
     * part currently holding somebody up.
     */
    private boolean supportsMe(BlockPos pos) {
        if (!noSpleef.get() || mc.player == null) return false;

        AABB box = mc.player.getBoundingBox();
        if (pos.getY() != Mth.floor(box.minY - 0.06)) return false;

        return pos.getX() >= Mth.floor(box.minX) && pos.getX() <= Mth.floor(box.maxX)
            && pos.getZ() >= Mth.floor(box.minZ) && pos.getZ() <= Mth.floor(box.maxZ);
    }

    /** Ticks between two chunk scans, when the last one found nowhere to go. */
    private static final long SCAN_EVERY_MS = 1000;

    /** Sends Baritone after the nearest one, or says there is nothing left. */
    private void goFind() {
        if (!walk.get() || !BaritoneBridge.isUsable()) return;

        if (walkTarget != null) {
            // Arrived, or the block went away while we were on our way to it.
            if (!isWanted(walkTarget)) {
                BaritoneBridge.cancel();
                walkTarget = null;
            } else if (BaritoneBridge.isPathing()) {
                return;
            } else {
                // Baritone stopped without getting us there. Blacklisting is not worth the
                // bookkeeping here: the scan below will pick something, and if it picks the same
                // unreachable block again the user can see it standing still and say so.
                walkTarget = null;
            }
        }

        // Baritone's scanner walks loaded chunks, which is cheap but not free, and the answer
        // does not change between two ticks. Once a second is faster than anybody can walk out
        // of range of what it last found.
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_EVERY_MS) return;
        lastScan = now;

        List<BlockPos> found = BaritoneBridge.scanFor(
            wanted, 64, yRange.get(), searchChunks.get());
        if (found.isEmpty()) return;

        BlockPos player = mc.player.blockPosition();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;

        for (BlockPos pos : found) {
            if (supportsMe(pos)) continue;

            double dist = pos.distSqr(player);
            if (dist >= best) continue;

            best = dist;
            nearest = pos;
        }

        if (nearest == null) return;

        walkTarget = nearest;
        BaritoneBridge.pathTo(nearest, walkRadius.get());
        if (debug.get()) log("walking to %s", nearest);
    }

    // --- odds and ends -------------------------------------------------------

    private BlockPos lookingAt() {
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return block.getBlockPos();
    }

    /** Whether we arrived somewhere rather than walked there. */
    private boolean movedByForce() {
        Vec3 now = mc.player.position();
        Vec3 before = lastPos;
        lastPos = now;

        if (!stopOnTeleport.get() || before == null) return false;

        double limit = teleportDistance.get();
        return before.distanceToSqr(now) > limit * limit;
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[AutoBreak] " + String.format(fmt, args));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        if (first != null) {
            event.renderer.box(first, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
        if (second != null) {
            event.renderer.box(second, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
