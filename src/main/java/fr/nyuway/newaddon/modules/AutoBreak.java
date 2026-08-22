package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import fr.nyuway.newaddon.utils.Interactions;
import fr.nyuway.newaddon.utils.Reach;
import fr.nyuway.newaddon.utils.Unstuck;
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
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
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
 * <p><b>SpeedMine</b> works two at a time. It double-clicks the first block, holds it for a
 * moment - a fifth of a second is plenty - then clicks the other and holds that, and then
 * <em>stops and waits</em> for both to come down on their own.
 *
 * <p>The double click is on the first block only, which is where it is needed; the second comes
 * down on one. It is a real double click, release included, because pressing twice without
 * letting go does nothing: the game sees the same block already being broken and returns without
 * sending anything.
 *
 * <h2>The waiting is the technique</h2>
 * Going back to poke at the blocks is what stops them from ever getting there, so once both have
 * been started nothing is sent at all until one of two things happens: they break, or they are
 * overdue. Overdue is worked out from the blocks themselves - the same figure the breaking bar
 * is filled from, for the tool actually in your hand - plus a margin, because netherrack and
 * obsidian are the same job to this module and nothing alike to a pickaxe.
 *
 * <h2>Nothing is sent by hand</h2>
 * No packets are built here, on purpose. These are the game's own two calls, the ones a held
 * mouse button makes - which is the whole point, because the client working the other block
 * beside you is what makes the pair work, and it is watching for a player doing an ordinary
 * thing. Anything clever on this side would be a second, different story for it to make sense of.
 *
 * <p>It also cannot go through Meteor's breaking helper, which lets go of a block on the first
 * tick it is not asked to break it again. That is right for one block at a time and fatal here:
 * letting go is how you tell the server to forget about a block, and the wait exists precisely
 * so that it does not.
 *
 * <p>For the same reason nothing rotates by default. A player who turns to face each block in
 * turn is doing something visibly different from one who does not, and the two clients have to
 * look alike.
 */
public class AutoBreak extends Module {

    /** How to break. */
    public enum Mode {
        /** Face it, hold, wait. One block at a time. */
        Vanilla,
        /** Hold one, hold the other, then wait for both to come down. */
        SpeedMine
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed mine");
    private final SettingGroup sgWalk = settings.createGroup("Walking");
    private final SettingGroup sgFight = settings.createGroup("Combat");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Vanilla breaks one block at a time and waits for each. SpeedMine holds " +
                     "one, then the other, then waits for both to come down together - which is " +
                     "what lets a second client work the other block beside you.")
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
        .description("Turn to face each block. Off, so this looks like the client working " +
                     "beside you, which is not turning either.")
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
        .description("Write each pair and each switch to the game log.")
        .defaultValue(false)
        .build());

    // --- speed mine ----------------------------------------------------------

    private final Setting<Integer> holdMs = sgSpeed.add(new IntSetting.Builder()
        .name("hold-ms")
        .description("How long to hold each of the two blocks. Where to start, rather than the " +
                     "final word: a fifth of a second is enough to have begun a block and " +
                     "nowhere near enough for crying obsidian, so this is raised on its own " +
                     "when pairs do not come down.")
        .defaultValue(1000).min(20).max(20000).sliderRange(200, 4000)
        .build());

    private final Setting<Boolean> adapt = sgSpeed.add(new BoolSetting.Builder()
        .name("adapt")
        .description("Learn the hold from what happens. A pair that does not fully come down " +
                     "doubles it; three clean pairs in a row ease it back down. The right number " +
                     "depends on the block, the tool and the server, which is three things this " +
                     "can find out and you would have to guess.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxHoldMs = sgSpeed.add(new IntSetting.Builder()
        .name("max-hold-ms")
        .description("As far as the hold is allowed to grow.")
        .defaultValue(6000).min(200).max(30000).sliderRange(1000, 12000)
        .visible(adapt::get)
        .build());

    private final Setting<Integer> retries = sgSpeed.add(new IntSetting.Builder()
        .name("retries")
        .description("How many times to start the same blocks again when they do not come down " +
                     "in time, before giving up on them and picking another pair.")
        .defaultValue(2).min(0).max(10).sliderRange(0, 5)
        .build());

    private final Setting<Boolean> soloFallback = sgSpeed.add(new BoolSetting.Builder()
        .name("solo-fallback")
        .description("When the hold has grown as far as it goes and the pairs are still coming " +
                     "down one block out of two, stop pairing and finish them one at a time. " +
                     "Half a pair is not the trick working badly, it is the trick not working - " +
                     "and one block at a time still clears the job.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> firstClicks = sgSpeed.add(new IntSetting.Builder()
        .name("first-clicks")
        .description("How many times to click the first block before moving to the second. Two: " +
                     "the first block is the one that needs telling twice, and the second comes " +
                     "down on one. One turns the double click off.")
        .defaultValue(2).min(1).max(5).sliderRange(1, 3)
        .build());

    private final Setting<Integer> clickGapMs = sgSpeed.add(new IntSetting.Builder()
        .name("click-gap-ms")
        .description("Time between those clicks. Short - this is a double click, not two " +
                     "attempts, and a long gap is just the first click again.")
        .defaultValue(50).min(10).max(500).sliderRange(20, 200)
        .visible(() -> firstClicks.get() > 1)
        .build());

    private final Setting<Integer> graceMs = sgSpeed.add(new IntSetting.Builder()
        .name("grace-ms")
        .description("How much longer than the block should have taken to give it before " +
                     "starting another pair. The wait itself is worked out from the block and " +
                     "the tool in your hand, so this is only the margin on top - latency, and " +
                     "the moment the server takes to agree.")
        .defaultValue(500).min(0).max(5000).sliderRange(0, 2000)
        .build());

    private final Setting<Boolean> release = sgSpeed.add(new BoolSetting.Builder()
        .name("release")
        .description("Let go of the button before waiting, the way you would take your finger " +
                     "off the mouse. Off, because letting go is also how you tell the server to " +
                     "forget the block, and the whole point of the wait is that it does not.")
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
        .description("How close Baritone has to get before this takes over. One: standing far " +
                     "enough back to only reach a single block is what turns a pair into a solo, " +
                     "and the whole point is to have two within arm's length.")
        .defaultValue(1).min(0).max(6).sliderRange(0, 4)
        .build());

    private final Setting<Boolean> pairFirst = sgWalk.add(new BoolSetting.Builder()
        .name("pair-first")
        .description("Walk to a block that has another of its kind beside it, in preference to " +
                     "a lone one. A block on its own can only ever be mined solo, so going to " +
                     "the edge of the job first and working inwards is slower for no reason.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> stuckTicks = sgWalk.add(new IntSetting.Builder()
        .name("stuck-ticks")
        .description("Ticks of Baritone pathing without actually moving before the route is " +
                     "given up on. It happens: a ledge it will not step off, a block it cannot " +
                     "get round. The block is set aside for a while and another is tried.")
        .defaultValue(60).min(20).max(600).sliderRange(20, 200)
        .build());

    private final Setting<Boolean> allowPlace = sgWalk.add(new BoolSetting.Builder()
        .name("allow-place")
        .description("Let Baritone place blocks to get somewhere while this module is on, and " +
                     "put the setting back afterwards. Knocked off an obsidian platform there " +
                     "is no way back up without it, and standing at the bottom of a wall is the " +
                     "commonest way for a job to end.")
        .defaultValue(true)
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

    // --- combat ----------------------------------------------------------------

    private final Setting<Boolean> fightBack = sgFight.add(new BoolSetting.Builder()
        .name("fight-back")
        .description("Hit hostile mobs that come within reach. A skeleton shooting at you from " +
                     "across the island is somebody else's problem; one standing next to you is " +
                     "the reason the job stops.")
        .defaultValue(true)
        .build());

    private final Setting<Double> fightRange = sgFight.add(new DoubleSetting.Builder()
        .name("fight-range")
        .description("How close a mob has to be to be worth turning on. Deliberately short - " +
                     "chasing one across the map is how a job becomes a walk.")
        .defaultValue(5).min(2).max(16).sliderRange(3, 8)
        .visible(fightBack::get)
        .build());

    private final Setting<Boolean> pauseWhileFighting = sgFight.add(new BoolSetting.Builder()
        .name("pause-while-fighting")
        .description("Stop mining while there is something to hit. Doing both at once means " +
                     "doing neither: the pair being held is dropped every time an arm swings " +
                     "somewhere else.")
        .defaultValue(true)
        .build());

    // --- safety --------------------------------------------------------------

    private final Setting<Boolean> pauseWhileUsing = sgSafety.add(new BoolSetting.Builder()
        .name("pause-while-using")
        .description("Stop mining while you are using something - eating, drinking, drawing a " +
                     "bow. Holding a block down through a carrot cancels the carrot.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> logoutOnAttack = sgSafety.add(new BoolSetting.Builder()
        .name("logout-on-attack")
        .description("Disconnect when a player or an end crystal hurts you. Off by default, " +
                     "because leaving is a decision. Mobs do not count - that is what fight-back " +
                     "is for, and a creeper is not a reason to lose your place.")
        .defaultValue(false)
        .build());

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
    /** How far through the pair we are, and when that stage began. */
    private Stage stage = Stage.PICK;
    private long stageAt;

    /** When to stop waiting, worked out from how long the blocks should have taken. */
    private long deadline;

    /** Clicks landed on the first block so far, and when the last one went out. */
    private int clicks;
    private long clickedAt;

    /** The hold actually in use, which starts at the setting and is learned from there. */
    private int holdNow;

    /** Attempts spent on the pair in hand, and clean pairs in a row, for the adapting. */
    private int tries;
    private int cleanPairs;

    /** Pairs that came down one block out of two, which is the sign pairing is not working. */
    private int halfPairs;

    /** True once the pairing has been given up on for this run. */
    private boolean solo;

    private enum Stage {
        /** Nothing in hand: choose two. */
        PICK,
        /** Holding the first. */
        FIRST,
        /** Holding the second. */
        SECOND,
        /** Holding nothing, watching. */
        WAIT
    }

    /** Where Baritone is taking us, if anywhere. */
    private BlockPos walkTarget;

    /** Last position, to tell walking from being moved. */
    private Vec3 lastPos;

    private boolean pickHeld;

    /** When Baritone's scanner was last asked, so it is not asked twenty times a second. */
    private long lastScan;

    /** Ticks of pathing without moving, and where we were when that started. */
    private int stillTicks;
    private Vec3 stillAt;

    /** Blocks a route already failed to reach, and when they may be tried again. */
    private final java.util.Map<BlockPos, Long> givenUp = new java.util.HashMap<>();

    /** Reused by the trapped-block search so a tick allocates nothing. */
    private final BlockPos.MutableBlockPos unstickCursor = new BlockPos.MutableBlockPos();

    /** Last seen hurt frame, to catch the tick damage lands on. */
    private int lastHurt;

    /** When a block last actually came down, so a job that is going nowhere can say so. */
    private long lastBreak;

    /** Baritone's own block-placing setting, put back when the module is switched off. */
    private Object placeBefore;

    public AutoBreak() {
        super(NewAddon.CATEGORY, "auto-break",
            "Right-click a block; every block of that kind nearby comes down.");
    }

    @Override
    public String getInfoString() {
        if (wanted == null) return "pick one";

        String name = wanted.getName().getString();
        if (mode.get() != Mode.SpeedMine) return name;
        if (solo) return name + " (solo)";

        // The learned hold, in the module list, so the adapting is visible without the log.
        return name + " " + hold() + "ms";
    }

    @Override
    public void onActivate() {
        wanted = null;
        clearPair();
        walkTarget = null;
        lastPos = mc.player == null ? null : mc.player.position();
        pickHeld = false;
        lastScan = 0;

        // Learned per run, not remembered. The block and the tool are both likely to be
        // different next time, and a hold learned for obsidian is nonsense for netherrack.
        holdNow = holdMs.get();
        tries = 0;
        cleanPairs = 0;
        halfPairs = 0;
        solo = false;
        stillTicks = 0;
        stillAt = null;
        lastHurt = 0;
        lastBreak = System.currentTimeMillis();
        givenUp.clear();

        // Borrowed, and given back in onDeactivate. Turning somebody's pathfinder setting on and
        // leaving it on is the kind of thing that gets blamed on the pathfinder a week later.
        if (allowPlace.get() && BaritoneBridge.isUsable()) {
            placeBefore = BaritoneBridge.setting("allowPlace");
            BaritoneBridge.setSetting("allowPlace", true);
        }

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

        if (placeBefore != null) {
            BaritoneBridge.setSetting("allowPlace", placeBefore);
            placeBefore = null;
        }
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

        watchForAttack();

        // Using something - eating, drinking, drawing a bow - is a thing you cannot do while
        // holding a block down, so the block waits. It is a fifth of a second of mining lost
        // against a carrot cancelled every single time.
        if (pauseWhileUsing.get() && mc.player.isUsingItem()) {
            letGo();
            return;
        }

        if (fightBack.get() && fight() && pauseWhileFighting.get()) {
            letGo();
            return;
        }

        if (nothingIsHappening()) return;

        if (mode.get() == Mode.SpeedMine) tickPair();
        else tickOne();
    }

    /** How long a job may go without a single block coming down before it is prodded. */
    private static final long STALL_MS = 60_000;

    /**
     * Notices a job that has stopped making progress, and moves it along.
     *
     * <p>Every individual thing here has its own way out - a pair times out, a route is given
     * up on, a block that cannot be reached is dropped. This is the one that catches the
     * combinations nobody thought of: whatever the reason, a minute without a single block
     * coming down is not a job in progress, and the answer is always the same. Put the current
     * targets aside, let go of the route, and look somewhere else.
     *
     * @return true when this tick was spent doing that rather than mining
     */
    private boolean nothingIsHappening() {
        long now = System.currentTimeMillis();
        if (now - lastBreak < STALL_MS) return false;

        lastBreak = now;
        long until = now + GIVEN_UP_MS;
        if (first != null) givenUp.put(first.immutable(), until);
        if (second != null) givenUp.put(second.immutable(), until);
        if (walkTarget != null) givenUp.put(walkTarget.immutable(), until);

        stopBreaking();
        clearPair();

        if (walkTarget != null) {
            BaritoneBridge.cancel();
            walkTarget = null;
        }

        stillTicks = 0;
        lastScan = 0;
        prune(now);

        if (notify.get()) warning("Nothing has come down in a while; moving on.");
        return true;
    }

    /** Drops served sentences, so the list cannot grow for the length of a session. */
    private void prune(long now) {
        givenUp.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    /**
     * Drops whatever was being held, for a pause that is about to last more than a tick.
     *
     * <p>The pair is abandoned rather than frozen. Its blocks are counting down on the server
     * and will have finished or expired long before a fight is over, and coming back to a
     * half-remembered pair is how a stale block position gets mined at for thirty seconds.
     */
    private void letGo() {
        if (stage == Stage.PICK && first == null) return;

        stopBreaking();
        clearPair();
    }

    // --- fighting --------------------------------------------------------------

    /**
     * Hits the nearest hostile within reach.
     *
     * <p>Nearest rather than weakest or most dangerous: the one that can hit you is the one
     * standing next to you, and a skeleton across the island is somebody else's problem. There
     * is no chasing here at all - if it walks out of range it stops being a target, which is
     * what keeps a job from turning into a walk.
     *
     * @return true when there was something to hit, whether or not the swing landed this tick
     */
    private boolean fight() {
        LivingEntity foe = nearestThreat();
        if (foe == null) return false;

        // Vanilla's own cooldown. Swinging early is a swing that does almost nothing, and the
        // server sees a player attacking faster than a player can.
        if (mc.player.getAttackStrengthScale(0.5f) < 1.0f) return true;

        Rotations.rotate(Rotations.getYaw(foe), Rotations.getPitch(foe), () -> {
            mc.gameMode.attack(mc.player, foe);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        });

        if (debug.get()) log("hitting %s", foe.getName().getString());
        return true;
    }

    private LivingEntity nearestThreat() {
        if (mc.level == null || mc.player == null) return null;

        double limit = fightRange.get() * fightRange.get();
        LivingEntity best = null;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !(entity instanceof Enemy)) continue;
            if (!living.isAlive() || living == mc.player) continue;

            double dist = mc.player.distanceToSqr(living);
            if (dist > limit) continue;

            limit = dist;
            best = living;
        }
        return best;
    }

    // --- being attacked ----------------------------------------------------------

    /**
     * Leaves when a player or an end crystal lands a hit.
     *
     * <p>The damage source arrives from the server with the hurt itself, so this is what hit you
     * rather than a guess from who happens to be standing about. Mobs are deliberately not in
     * it: a creeper is not a reason to lose your place, and fight-back is the answer to those.
     */
    private void watchForAttack() {
        int hurt = mc.player.hurtTime;
        int before = lastHurt;
        lastHurt = hurt;

        if (!logoutOnAttack.get() || hurt <= before) return;

        var source = mc.player.getLastDamageSource();
        if (source == null) return;

        boolean byPlayer = source.getEntity() instanceof net.minecraft.world.entity.player.Player;
        boolean byCrystal = source.getDirectEntity()
            instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal;
        if (!byPlayer && !byCrystal) return;

        warning("Hit by %s; disconnecting.", byPlayer ? "a player" : "a crystal");
        toggle();
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(
                net.minecraft.network.chat.Component.literal("Attacked while mining"));
        }
    }

    /** One block, faced and held until it breaks, then the next. */
    private void tickOne() {
        if (first != null && !workable(first)) first = null;
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
        // Given up on pairing for this run: one at a time still clears the job, and half a pair
        // over and over does not.
        if (solo) {
            tickOne();
            return;
        }

        long now = System.currentTimeMillis();

        // Either half can stop being ours at any moment - broken by us, by the other client, by
        // somebody else, or simply left behind by a step - so both are checked before anything
        // is done with them. Out of reach counts: insisting on a block the server will not
        // accept is not persistence, it is a stall.
        if (first != null && !workable(first)) first = null;
        if (second != null && !workable(second)) second = null;

        // Nothing left in hand part way through. Start over rather than carrying an empty pair
        // through the rest of the cycle, which used to sit in WAIT until it timed out.
        if (first == null && second == null && stage != Stage.PICK && stage != Stage.WAIT) {
            clearPair();
        }

        switch (stage) {
            case PICK -> {
                first = nearestInReach(null);
                if (first == null) {
                    goFind();
                    return;
                }

                // A second is wanted but not required. The last block of a job has nobody to
                // pair with, and refusing to break it would leave the job unfinished.
                second = nearestInReach(first);
                tries = 0;
                begin(now);
                if (debug.get()) log("pair %s + %s, hold %d ms", first, second, hold());
            }

            case FIRST -> {
                if (first == null) {
                    // Gone already - an instamine, or the other client got there first.
                    startSecond(now);
                    return;
                }

                // A real double click: let go, then press again. Pressing twice without the
                // release in between does nothing at all - the game sees the same block already
                // being broken and returns without sending anything.
                if (clicks < firstClicks.get() && now - clickedAt >= clickGapMs.get()) {
                    if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                    press(first, true);
                    clicks++;
                    clickedAt = now;
                    if (debug.get()) log("click %d on %s", clicks, first);
                    return;
                }

                if (now - stageAt < hold()) {
                    press(first, false);
                    return;
                }
                startSecond(now);
            }

            case SECOND -> {
                if (second != null && now - stageAt < hold()) {
                    press(second, false);
                    return;
                }
                startWaiting(now);
            }

            case WAIT -> {
                // Nothing is sent here, and that is the point. Both blocks have been started and
                // are counting down on their own; going back to poke at them is what stops them
                // from ever getting there.
                if (first == null && second == null) {
                    if (debug.get()) log("pair done in %d ms", now - stageAt);
                    settle(2);
                    clearPair();
                    return;
                }

                if (now < deadline) return;

                // Past the time the slower of the two should have taken, plus the margin.
                int broke = (first == null ? 1 : 0) + (second == null ? 1 : 0);
                if (debug.get()) log("pair overdue, %d of 2 down, try %d", broke, tries + 1);
                settle(broke);

                // Again on what is left, rather than walking away from a block that is half
                // mined. Picking a fresh pair here would usually pick the same blocks anyway,
                // only having forgotten how many attempts they have already had.
                if (++tries <= retries.get()) {
                    BlockPos left = first != null ? first : second;
                    if (left != null) {
                        first = left;
                        second = nearestInReach(left);
                        begin(now);
                        return;
                    }
                }

                // Out of attempts. Whatever is left is put aside for a while, or the very next
                // pick takes it straight back and the whole thing happens again - which is what
                // "stuck" looks like from the outside, since the module is busy the entire time.
                long until = System.currentTimeMillis() + GIVEN_UP_MS;
                if (first != null) givenUp.put(first.immutable(), until);
                if (second != null) givenUp.put(second.immutable(), until);
                if (debug.get()) log("giving up on the pair for now");

                clearPair();
            }
        }
    }

    /** Starts the pair: first click on the first block, and the clock running. */
    private void begin(long now) {
        press(first, true);
        clicks = 1;
        clickedAt = now;
        enter(Stage.FIRST, now);
    }

    /** The hold in force, which is the setting until something is learned from it. */
    private int hold() {
        if (holdNow < holdMs.get()) holdNow = holdMs.get();
        return holdNow;
    }

    /**
     * Takes in how a pair ended and changes the hold accordingly.
     *
     * <p>Two out of two is the hold being long enough, and three of those in a row ease it back
     * down - the shortest hold that works is also the fastest. Anything less doubles it, because
     * the usual reason a pair does not come down is that it was not held long enough to have
     * really started, and doubling finds the right order of magnitude in a few pairs rather than
     * creeping towards it.
     *
     * <p>One out of two is watched separately. It is not a hold that is slightly too short: it
     * is the pair being answered one block at a time, and no amount of waiting fixes that.
     */
    private void settle(int broke) {
        if (broke > 0) lastBreak = System.currentTimeMillis();

        if (broke == 1) halfPairs++;
        else if (broke == 2) halfPairs = 0;

        if (!adapt.get()) return;

        if (broke == 2) {
            tries = 0;
            if (++cleanPairs < 3) return;

            cleanPairs = 0;
            int eased = Math.max(holdMs.get(), hold() * 3 / 4);
            if (eased == holdNow) return;

            holdNow = eased;
            if (debug.get()) log("three clean pairs; hold down to %d ms", holdNow);
            return;
        }

        cleanPairs = 0;

        int grown = Math.min(maxHoldMs.get(), hold() * 2);
        if (grown != holdNow) {
            holdNow = grown;
            if (notify.get()) info("Holding each block %d ms now.", holdNow);
            return;
        }

        // As long as the hold is allowed to get, and still half a pair every time. More waiting
        // is not the answer to that, so stop pairing and clear the job one block at a time.
        if (soloFallback.get() && halfPairs >= 3 && !solo) {
            solo = true;
            if (notify.get()) {
                warning("Only one of every two is coming down; finishing them one at a time.");
            }
        }
    }

    /** Moves to the second block, or straight to waiting when the pair is a single. */
    private void startSecond(long now) {
        if (second == null) {
            startWaiting(now);
            return;
        }

        press(second, true);
        enter(Stage.SECOND, now);
    }

    /**
     * Stops holding, and works out how long the pair is worth waiting for.
     *
     * <p>The wait is the block's own breaking time rather than a number picked in advance:
     * netherrack and obsidian are the same job to this module and nothing alike to a pickaxe.
     * The slower of the two decides, since the pair is only done when both are.
     */
    private void startWaiting(long now) {
        if (release.get() && mc.gameMode != null) mc.gameMode.stopDestroyBlock();

        // The blocks' own breaking time, plus the margin, and never less than the two holds
        // just spent on them - a wait shorter than the work is a pair given up on before it
        // could have finished.
        deadline = now + Math.max(expectedMs(), 2L * hold()) + graceMs.get();
        enter(Stage.WAIT, now);
    }

    private void enter(Stage next, long now) {
        stage = next;
        stageAt = now;
    }

    /** How long the slower half of the pair should take, in milliseconds. */
    private long expectedMs() {
        long worst = 0;

        for (BlockPos pos : new BlockPos[] { first, second }) {
            if (pos == null || mc.level == null || mc.player == null) continue;

            // The same figure the breaking bar is filled from: how much of the block one tick of
            // the tool in hand takes off. Zero means it is not going to break at all, which the
            // cap below turns into a bounded wait rather than a hang.
            float perTick = mc.level.getBlockState(pos).getDestroyProgress(mc.player, mc.level, pos);
            long ms = perTick <= 0 ? MAX_WAIT_MS : (long) Math.ceil(1.0f / perTick) * 50L;
            worst = Math.max(worst, Math.min(ms, MAX_WAIT_MS));
        }
        return worst;
    }

    private void clearPair() {
        first = null;
        second = null;
        stage = Stage.PICK;
        stageAt = 0;
        deadline = 0;
        clicks = 0;
        clickedAt = 0;
        tries = 0;
    }

    // --- breaking ------------------------------------------------------------

    /** Longest a single block is ever waited for, so an unbreakable one is not waited for ever. */
    private static final long MAX_WAIT_MS = 60_000;

    /**
     * Breaks a block the way Vanilla mode does: the same call, every tick, until it gives.
     *
     * <p>Meteor's helper, which lets go by itself on the first tick this is not called - right
     * for one block at a time, and the reason SpeedMine cannot use it.
     */
    private void mine(BlockPos pos) {
        if (pos == null) return;

        if (rotate.get()) Interactions.mine(mc, pos, false, swing.get());
        else BlockUtils.breakBlock(pos, swing.get());
    }

    /**
     * Presses, or keeps pressing, on a block - the game's own two calls and nothing else.
     *
     * @param start true for the first press on a block, false to keep holding it
     */
    private void press(BlockPos pos, boolean start) {
        if (pos == null || mc.gameMode == null || mc.player == null) return;

        Direction face = BlockUtils.getDirection(pos);
        if (rotate.get()) {
            Interactions.lookAt(pos, false, () -> hit(pos, face, start));
            return;
        }
        hit(pos, face, start);
    }

    private void hit(BlockPos pos, Direction face, boolean start) {
        if (start) mc.gameMode.startDestroyBlock(pos, face);
        else mc.gameMode.continueDestroyBlock(pos, face);

        // Every tick while the button is down, which is what the game itself does.
        if (swing.get()) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** Lets go, so switching off does not leave the game holding a block down. */
    private void stopBreaking() {
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    // --- finding -------------------------------------------------------------

    /**
     * Whether a block is still worth holding on to: the right kind, safe, and within reach.
     *
     * <p>Reach is the half that was missing. It used to be asked once, when the block was
     * picked, and never again - so a step in any direction could leave the pair pressing on
     * something the server was never going to accept. Which is exactly what happened: walking to
     * the first block took the second out of range, nothing came down, the hold doubled and
     * doubled, and the job stopped on a block that could not have been reached from where the
     * player was standing. Nothing about a block is permanent when the player moves.
     */
    private boolean workable(BlockPos pos) {
        return isWanted(pos) && !supportsMe(pos) && Reach.canReach(mc, pos, false, range.get());
    }

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
                    if (!workable(cursor)) continue;
                    if (setAside(cursor)) continue;

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
                stillTicks = 0;
            } else if (BaritoneBridge.isPathing()) {
                if (!goingNowhere()) return;

                // Pathing and not moving. A ledge it will not step off, a block it cannot get
                // round, a platform it cannot climb - whatever it is, waiting longer has never
                // once helped. Set the block aside, dig out if something has closed around us,
                // and let the scan below pick somewhere else.
                if (debug.get()) log("stuck on the way to %s; trying elsewhere", walkTarget);
                BaritoneBridge.cancel();
                givenUp.put(walkTarget, System.currentTimeMillis() + GIVEN_UP_MS);
                walkTarget = null;
                stillTicks = 0;

                BlockPos trap = Unstuck.find(mc, unstickCursor);
                if (trap != null) mine(trap);
            } else {
                // Baritone stopped without getting us there, and said so by not pathing.
                givenUp.put(walkTarget, System.currentTimeMillis() + GIVEN_UP_MS);
                walkTarget = null;
                stillTicks = 0;
            }
        }

        // Baritone's scanner walks loaded chunks, which is cheap but not free, and the answer
        // does not change between two ticks. Once a second is faster than anybody can walk out
        // of range of what it last found.
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_EVERY_MS) return;
        lastScan = now;
        prune(now);

        List<BlockPos> found = BaritoneBridge.scanFor(
            wanted, 64, yRange.get(), searchChunks.get());
        if (found.isEmpty()) return;

        BlockPos player = mc.player.blockPosition();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        boolean bestPairs = false;

        for (BlockPos pos : found) {
            if (supportsMe(pos)) continue;

            if (setAside(pos)) continue;

            // A block with a neighbour of its own kind can be mined as a pair; a lone one never
            // can. Walking to the edge of a job and working inwards is slower for no reason, so
            // any pairable block beats any lone one, and distance decides within each group.
            boolean pairs = !pairFirst.get() || hasPartner(pos);
            if (bestPairs && !pairs) continue;

            double dist = pos.distSqr(player);
            if (pairs == bestPairs && dist >= best) continue;

            best = dist;
            nearest = pos;
            bestPairs = pairs;
        }

        if (nearest == null) return;

        walkTarget = nearest;
        stillTicks = 0;
        stillAt = mc.player.position();
        BaritoneBridge.pathTo(nearest, walkRadius.get());
        if (debug.get()) log("walking to %s%s", nearest, bestPairs ? " (pairable)" : " (alone)");
    }

    /** How long a block that could not be reached is left alone before being tried again. */
    private static final long GIVEN_UP_MS = 60_000;

    /** Whether this block is serving its time, and cheap enough to ask inside a scan. */
    private boolean setAside(BlockPos pos) {
        if (givenUp.isEmpty()) return false;

        Long until = givenUp.get(pos);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Whether Baritone is pathing but we are not actually going anywhere.
     *
     * <p>Measured from the position rather than from anything Baritone says about itself: it
     * reports pathing perfectly happily while walking into a wall, and the only honest answer to
     * "is this working" is whether the player has moved.
     */
    private boolean goingNowhere() {
        Vec3 here = mc.player.position();
        if (stillAt == null || here.distanceToSqr(stillAt) > 0.25) {
            stillAt = here;
            stillTicks = 0;
            return false;
        }
        return ++stillTicks > stuckTicks.get();
    }

    /**
     * Whether another block of the same kind is close enough to this one to be its pair.
     *
     * <p>Within reach of somewhere a player could stand beside both, which is roughly the mining
     * range - so the box is the range across and the test is simply "is there another one in
     * it". Cheap enough at once a second over the handful of candidates a scan returns.
     */
    private boolean hasPartner(BlockPos pos) {
        if (mc.level == null) return false;

        int r = Math.max(1, Mth.floor(range.get()) - 1);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (mc.level.getBlockState(cursor).getBlock() == wanted) return true;
                }
            }
        }
        return false;
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
