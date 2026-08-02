package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AutoMoss - bone meals moss blocks, but only when it actually converts something.
 *
 * <h2>What vanilla does</h2>
 * Bone mealing a moss block runs {@code MossBlock#performBonemeal}, which places the
 * {@code moss_patch_bonemeal} vegetation patch feature at the block <i>above</i> the moss:
 * <ul>
 *   <li>{@code MossBlock#isValidBonemealTarget} requires the block above the moss to be
 *       <b>air</b>. Any other block - not just a solid one - makes the moss an invalid
 *       target and the bone meal does nothing. In practice wild moss is almost always
 *       covered in grass, moss carpet or azalea, which is what {@code clear-obstructions}
 *       exists to deal with.</li>
 *   <li>The patch walks every column in a small radius around that origin, drops down
 *       through air, and converts the first block it lands on if it is in
 *       {@code #minecraft:moss_replaceable} (the stone and dirt families). A block with
 *       no air above it is never reached, so it never converts.</li>
 *   <li>{@code VegetationPatchFeature#placeGround} skips columns whose ground block is
 *       already moss. Those are not a gain, so this module does not count them.</li>
 * </ul>
 *
 * <h2>What this module adds</h2>
 * Bone meal is consumed whenever the patch places, even if it only grows decorative
 * vegetation on moss that is already there. So a moss block having air above it is
 * necessary but not sufficient - this module also verifies at least
 * {@code min-conversions} nearby columns would really turn into moss, and only then
 * spends an item.
 *
 * <h2>Cost</h2>
 * The search offsets are precomputed once per range change and sorted by distance, so a
 * scan walks blocks nearest-first and returns on the first usable target. Nothing is
 * allocated per tick, and the scan is skipped entirely on cooldown ticks unless the
 * highlight or the debug readout needs refreshing.
 */
public class AutoMoss extends Module {

    /**
     * {@code verticalRange} of the {@code moss_patch_bonemeal} feature: how far the patch
     * may travel up or down a column while looking for the surface to replace.
     */
    private static final int VERTICAL_RANGE = 5;

    /** Offsets are packed into a single int, so each component is biased into a byte. */
    private static final int PACK_BIAS = 64;

    /** Air an azalea needs above it before growing one into a tree is worth a bone meal. */
    private static final int AZALEA_HEADROOM = 5;

    /** Ticks a walked-to spot stays blacklisted after turning out to be a dead end. */
    private static final int VISITED_TIMEOUT = 20 * 60;

    /** Bone meals spent on one block before giving up on it and blacklisting it. */
    private static final int MAX_ATTEMPTS = 4;

    /**
     * How far the explore heading turns after a leg that found nothing. Deliberately small:
     * a big turn sends the bot back across ground it just covered, which reads as wandering
     * off in the opposite direction. Small turns sweep outward instead.
     */
    private static final double EXPLORE_TURN = Math.toRadians(40.0);

    /** Chunk radius the search widens to before giving up and exploring. */
    private static final int WIDE_SEARCH_CHUNKS = 16;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgClear = settings.createGroup("Obstructions");
    private final SettingGroup sgAzalea = settings.createGroup("Azalea");
    private final SettingGroup sgBaritone = settings.createGroup("Baritone");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far to look for moss blocks, measured from your eyes.")
        .defaultValue(4.5).min(1.0).max(6.0).sliderMin(1.0).sliderMax(6.0)
        .build());

    private final Setting<Integer> patchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("patch-radius")
        .description("Horizontal radius counted as convertible. The feature rolls 1 or 2 at " +
                     "random, so radius 1 is what every bone meal is guaranteed to reach; " +
                     "2 also counts columns that only convert about half the time.")
        .defaultValue(1).min(1).max(2)
        .build());

    private final Setting<Integer> minConversions = sgGeneral.add(new IntSetting.Builder()
        .name("min-conversions")
        .description("Minimum number of blocks that must actually turn into moss before " +
                     "spending a bone meal. Raise it to trade speed for efficiency.")
        .defaultValue(1).min(1).max(8).sliderMin(1).sliderMax(8)
        .build());

    private final Setting<Boolean> stoneOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("stone-only")
        .description("Count only stone-family blocks (#base_stone_overworld). Off also counts " +
                     "the dirt family, which converts too.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between two actions.")
        .defaultValue(4).min(0).max(20).sliderMin(0).sliderMax(20)
        .build());

    private final Setting<Boolean> pauseOnKillAura = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-killaura")
        .description("Stop entirely while KillAura is active, so hotbar swaps and rotations " +
                     "never fight with combat.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Face the block before acting on it.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Return to your previous hotbar slot after each bone meal.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoRefill = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-refill")
        .description("When the hotbar runs out of bone meal, move a stack up from your " +
                     "inventory into a free hotbar slot.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Turn the module off once there is no bone meal left anywhere in your " +
                     "inventory, instead of idling and scanning for nothing.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your hand client-side. Off still sends the swing packet.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> placeMoss = sgGeneral.add(new BoolSetting.Builder()
        .name("place-moss")
        .description("Put moss blocks from your inventory down next to exposed stone, making " +
                     "a spot that converts where there was nothing to work with. Needs moss " +
                     "blocks on you; does nothing otherwise.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> clearObstructions = sgClear.add(new BoolSetting.Builder()
        .name("clear-obstructions")
        .description("Break the grass, carpet or plant sitting on a moss block when that moss " +
                     "would otherwise be worth bone mealing. Only blocks that break instantly " +
                     "are touched, so this never digs through real blocks.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> growAzalea = sgAzalea.add(new BoolSetting.Builder()
        .name("grow-azalea")
        .description("Occasionally bone meal an azalea bush into an azalea tree. Off by " +
                     "default: every bone meal spent here is one not spent converting stone.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> azaleaInterval = sgAzalea.add(new IntSetting.Builder()
        .name("azalea-interval")
        .description("Seconds between two azalea attempts. Vanilla only succeeds about 45% of " +
                     "the time, so a bush usually takes a few tries.")
        .defaultValue(15).min(1).max(300).sliderMin(5).sliderMax(120)
        .visible(growAzalea::get)
        .build());

    private final Setting<Integer> azaleaSpacing = sgAzalea.add(new IntSetting.Builder()
        .name("azalea-spacing")
        .description("Skip a bush when azalea leaves are already within this many blocks, so " +
                     "trees do not crowd each other. 0 disables the check.")
        .defaultValue(3).min(0).max(8).sliderMin(0).sliderMax(8)
        .visible(growAzalea::get)
        .build());

    private final Setting<Boolean> baritone = sgBaritone.add(new BoolSetting.Builder()
        .name("baritone")
        .description("Walk to moss worth working on when nothing is in reach, turning the " +
                     "module into a bot. Needs Meteor's Baritone fork (mod id baritone-meteor); " +
                     "official Baritone will not work.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> searchChunks = sgBaritone.add(new IntSetting.Builder()
        .name("search-chunks")
        .description("Radius in chunks that Baritone's scanner sweeps looking for moss.")
        .defaultValue(4).min(1).max(16).sliderMin(1).sliderMax(8)
        .visible(baritone::get)
        .build());

    private final Setting<Integer> clusterRadius = sgBaritone.add(new IntSetting.Builder()
        .name("cluster-radius")
        .description("How far around a spot still counts as the same patch of work. Bigger " +
                     "values make the bot judge a whole area rather than one block.")
        .defaultValue(6).min(1).max(16).sliderMin(2).sliderMax(12)
        .visible(baritone::get)
        .build());

    private final Setting<Integer> minCluster = sgBaritone.add(new IntSetting.Builder()
        .name("min-cluster")
        .description("Blocks a patch must be worth before it counts as a real patch. The bot " +
                     "goes to the nearest patch that clears this, and only falls back to lone " +
                     "blocks when there is no patch at all.")
        .defaultValue(4).min(1).max(32).sliderMin(1).sliderMax(16)
        .visible(baritone::get)
        .build());

    private final Setting<Integer> rescanDelay = sgBaritone.add(new IntSetting.Builder()
        .name("rescan-cooldown")
        .description("Floor on how often the search may run, in seconds. Retargeting is driven " +
                     "by events - finishing a spot, or arriving - so this only stops the " +
                     "scanner being hammered when there is genuinely nothing to find.")
        .defaultValue(3).min(1).max(30).sliderMin(1).sliderMax(15)
        .visible(baritone::get)
        .build());

    private final Setting<Boolean> explore = sgBaritone.add(new BoolSetting.Builder()
        .name("explore")
        .description("When nothing nearby is worth working on, head somewhere new instead of " +
                     "standing still. This is what keeps the bot covering ground.")
        .defaultValue(true)
        .visible(baritone::get)
        .build());

    private final Setting<Integer> exploreDistance = sgBaritone.add(new IntSetting.Builder()
        .name("explore-distance")
        .description("How far to strike out when exploring, in blocks.")
        .defaultValue(64).min(16).max(256).sliderMin(32).sliderMax(160)
        .visible(() -> baritone.get() && explore.get())
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlight the block currently being targeted.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the highlight is drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the bone meal target.")
        .defaultValue(new SettingColor(89, 204, 108, 40))
        .visible(render::get)
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of the bone meal target.")
        .defaultValue(new SettingColor(89, 204, 108, 190))
        .visible(render::get)
        .build());

    private final Setting<SettingColor> clearSideColor = sgRender.add(new ColorSetting.Builder()
        .name("clear-side-color")
        .description("Fill colour of a block about to be cleared away.")
        .defaultValue(new SettingColor(225, 145, 55, 40))
        .visible(render::get)
        .build());

    private final Setting<SettingColor> clearLineColor = sgRender.add(new ColorSetting.Builder()
        .name("clear-line-color")
        .description("Outline colour of a block about to be cleared away.")
        .defaultValue(new SettingColor(225, 145, 55, 190))
        .visible(render::get)
        .build());

    private final Setting<Boolean> debug = sgDebug.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log what the scan is finding to the game log. Use this when the module " +
                     "looks idle: it says whether moss was seen at all, and why it was skipped.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> debugInterval = sgDebug.add(new IntSetting.Builder()
        .name("debug-interval")
        .description("Ticks between two log lines (20 ticks = 1 second).")
        .defaultValue(20).min(5).max(200).sliderMin(10).sliderMax(100)
        .visible(debug::get)
        .build());

    /** Search offsets around the player, sorted nearest-first. Rebuilt when range changes. */
    private int[] offsets = new int[0];
    private int builtRadius = -1;

    /** Reused across the whole scan so a tick allocates nothing. */
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos columnPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos auxPos = new BlockPos.MutableBlockPos();

    private BlockPos mossTarget;
    private BlockPos clearTarget;
    private BlockPos azaleaTarget;
    private Block azaleaBlock;

    private BlockPos placeTarget;

    /** Where Baritone is currently walking us, and spots that turned out to be dead ends. */
    private BlockPos walkTarget;
    private boolean exploring;
    private double exploreBearing;

    /** Drives retargeting off events rather than a timer: set when the situation changed. */
    private boolean hadWork;
    private boolean retargetNow;
    private final Map<BlockPos, Integer> blacklist = new HashMap<>();

    /** Guards against pouring bone meal into one block that never changes. */
    private BlockPos lastActionPos;
    private int actionAttempts;

    private int timer;
    private int azaleaTimer;
    private int walkTimer;
    private int ticks;

    // Scan counters, only meaningful when debug is on (the scan runs in full then).
    private int seenMoss, seenMossWithAir, seenMossObstructed, seenMossTooPoor;

    public AutoMoss() {
        super(NewAddon.CATEGORY, "auto-moss",
            "Bone meals nearby moss, but only when stone around it would really turn to moss.");
    }

    @Override
    public void onActivate() {
        mossTarget = null;
        clearTarget = null;
        azaleaTarget = null;
        placeTarget = null;
        walkTarget = null;
        exploring = false;
        hadWork = false;
        retargetNow = true;
        blacklist.clear();
        lastActionPos = null;
        actionAttempts = 0;
        timer = 0;
        azaleaTimer = 0;
        walkTimer = 0;
        ticks = 0;
        builtRadius = -1;

        if (mc.player != null && !InvUtils.findInHotbar(Items.BONE_MEAL).found()
            && !(autoRefill.get() && InvUtils.find(Items.BONE_MEAL).found())) {
            warning("No bone meal in your inventory.");
        }

        if (baritone.get() && !BaritoneBridge.isPresent()) {
            warning("Baritone control needs Meteor's Baritone fork, which is not installed.");
        }
    }

    @Override
    public void onDeactivate() {
        mossTarget = null;
        clearTarget = null;
        azaleaTarget = null;
        placeTarget = null;

        // Only stop pathing we started ourselves.
        if (walkTarget != null || exploring) {
            BaritoneBridge.cancel();
            walkTarget = null;
            exploring = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (pauseOnKillAura.get() && Modules.get().isActive(KillAura.class)) {
            mossTarget = clearTarget = azaleaTarget = placeTarget = null;
            return;
        }

        ticks++;

        boolean ready = timer <= 0;
        if (!ready) timer--;
        if (azaleaTimer > 0) azaleaTimer--;

        // On cooldown ticks the scan buys nothing unless something still needs it.
        if (!ready && !render.get() && !debug.get()) return;

        FindItemResult bonemeal = InvUtils.findInHotbar(Items.BONE_MEAL);
        if (!bonemeal.found()) {
            mossTarget = clearTarget = azaleaTarget = placeTarget = null;

            if (ready && autoRefill.get() && refillHotbar()) {
                // Give the move a tick to land before looking for the stack again.
                timer = delay.get();
                if (debug.get()) log("refilled bone meal from inventory");
                return;
            }

            // Nothing in the hotbar and nothing left to refill from: genuinely out.
            if (autoDisable.get() && !InvUtils.find(Items.BONE_MEAL).found()) {
                error("Out of bone meal.");
                toggle();
                return;
            }

            if (debugDue()) log("idle: no bone meal in hotbar");
            return;
        }

        if (ticks % 100 == 0) blacklist.values().removeIf(expiry -> expiry <= ticks);

        FindItemResult moss = placeMoss.get() ? InvUtils.findInHotbar(Items.MOSS_BLOCK) : null;
        boolean hasMoss = moss != null && moss.found();

        boolean azaleaDue = growAzalea.get() && azaleaTimer <= 0;
        scan(azaleaDue, hasMoss);

        if (debugDue()) {
            log("moss=%d withAir=%d obstructed=%d tooPoor=%d | target=%s clear=%s place=%s azalea=%s",
                seenMoss, seenMossWithAir, seenMossObstructed, seenMossTooPoor,
                mossTarget, clearTarget, placeTarget, azaleaTarget);
        }

        // Finishing everything in reach is the event that should hand Baritone its next
        // destination, immediately. Waiting out a timer here is what made it feel stop-start.
        boolean hasWork = mossTarget != null || clearTarget != null || placeTarget != null;
        if (hadWork && !hasWork) retargetNow = true;
        hadWork = hasWork;

        if (baritone.get()) handleRoaming(hasWork);

        if (!ready) return;

        // Azalea is rare by construction, so let it go first when its timer is up.
        if (azaleaDue && azaleaTarget != null && keepTrying(azaleaTarget)) {
            azaleaTimer = azaleaInterval.get() * 20;
            timer = delay.get();
            useBonemeal(azaleaTarget, azaleaBlock);
            return;
        }

        if (mossTarget != null && keepTrying(mossTarget)) {
            timer = delay.get();
            useBonemeal(mossTarget, Blocks.MOSS_BLOCK);
            return;
        }

        if (clearTarget != null && keepTrying(clearTarget)) {
            timer = delay.get();
            clearBlock(clearTarget);
            return;
        }

        if (placeTarget != null && hasMoss && keepTrying(placeTarget)) {
            timer = delay.get();
            BlockUtils.place(placeTarget, moss, 50);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        BlockPos bonemealBox = mossTarget != null ? mossTarget : azaleaTarget;
        if (bonemealBox != null) {
            event.renderer.box(bonemealBox, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
        if (clearTarget != null) {
            event.renderer.box(clearTarget, clearSideColor.get(), clearLineColor.get(), shapeMode.get(), 0);
        }
        if (placeTarget != null) {
            event.renderer.box(placeTarget, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    /**
     * Single pass over the search volume, picking the closest of each kind of target:
     * moss ready to bone meal, a block to clear off a moss that would otherwise qualify,
     * and (when due) an azalea worth growing.
     *
     * <p>Rejections are ordered cheapest-first: reach, then the block itself, then what is
     * above it, and only then the column walk that decides whether anything would convert.
     * The pass stops at the first bone meal target unless debug is on, in which case it
     * runs in full so the counters mean something.
     */
    private void scan(boolean azaleaDue, boolean wantPlace) {
        mossTarget = null;
        clearTarget = null;
        azaleaTarget = null;
        placeTarget = null;
        seenMoss = seenMossWithAir = seenMossObstructed = seenMossTooPoor = 0;

        int radius = Mth.ceil(range.get());
        if (radius != builtRadius) rebuildOffsets(radius);

        Vec3 eye = mc.player.getEyePosition();
        double maxDistSq = range.get() * range.get();
        BlockPos origin = mc.player.blockPosition();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int needed = minConversions.get();
        boolean full = debug.get();
        boolean wantClear = clearObstructions.get();

        for (int packed : offsets) {
            int x = ox + (((packed >> 16) & 0xFF) - PACK_BIAS);
            int y = oy + (((packed >> 8) & 0xFF) - PACK_BIAS);
            int z = oz + ((packed & 0xFF) - PACK_BIAS);

            double dx = x + 0.5 - eye.x, dy = y + 0.5 - eye.y, dz = z + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

            scanPos.set(x, y, z);
            BlockState state = mc.level.getBlockState(scanPos);

            if (state.is(Blocks.MOSS_BLOCK)) {
                seenMoss++;

                scanPos.set(x, y + 1, z);
                BlockState above = mc.level.getBlockState(scanPos);

                if (above.isAir()) {
                    // MossBlock#isValidBonemealTarget is satisfied; is it worth an item?
                    seenMossWithAir++;
                    if (countConversions(x, y + 1, z, needed) >= needed) {
                        if (mossTarget == null) mossTarget = notBlacklisted(x, y, z);
                    } else {
                        seenMossTooPoor++;
                    }
                } else if (wantClear && isClearable(scanPos, above)) {
                    // Blocked, but only worth uncovering if the patch would then convert
                    // something. Neighbouring columns read the same either way, so the
                    // count is a valid prediction of the post-break result.
                    seenMossObstructed++;
                    if (clearTarget == null && countConversions(x, y + 1, z, needed) >= needed) {
                        clearTarget = notBlacklisted(x, y + 1, z);
                    }
                }
            } else if (azaleaDue && azaleaTarget == null
                       && (state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA))) {
                if (isAzaleaWorthGrowing(x, y, z)) {
                    azaleaTarget = notBlacklisted(x, y, z);
                    if (azaleaTarget != null) azaleaBlock = state.getBlock();
                }
            } else if (wantPlace && placeTarget == null && state.isAir()) {
                // A spot to drop a moss block so it makes work where there was none.
                // Cheap gates first: something to stand the block on, and air above so the
                // moss we place is immediately a valid bone meal target.
                scanPos.set(x, y - 1, z);
                if (mc.level.getBlockState(scanPos).isAir()) continue;

                scanPos.set(x, y + 1, z);
                if (!mc.level.getBlockState(scanPos).isAir()) continue;

                scanPos.set(x, y, z);
                if (!BlockUtils.canPlace(scanPos)) continue;

                if (countConversions(x, y + 1, z, needed) >= needed) {
                    placeTarget = notBlacklisted(x, y, z);
                }
            }

            if (mossTarget != null && !full) return;
        }
    }

    /**
     * Counts how many columns around the patch origin would really become moss, stopping
     * as soon as {@code needed} of them are found.
     *
     * <p>Mirrors {@code VegetationPatchFeature#placeGroundPatch}: from the origin, walk down
     * through air, then back up out of solid, both capped at {@link #VERTICAL_RANGE}. That
     * leaves the air gap sitting on the column's surface; the block underneath it is the one
     * the feature would replace.
     *
     * @param ox x of the patch origin (the block above the moss)
     * @param oy y of the patch origin
     * @param oz z of the patch origin
     * @param needed stop and return once this many conversions are confirmed
     */
    private int countConversions(int ox, int oy, int oz, int needed) {
        int radius = patchRadius.get();
        int found = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // VegetationPatchFeature never places the four corner columns, and only
                // places the edges with extraEdgeColumnChance. Counting corners made the
                // module spend bone meal on targets that could not convert anything.
                if ((dx == -radius || dx == radius) && (dz == -radius || dz == radius)) continue;

                // The centre column is whatever we are standing the patch on - the moss
                // itself, or the block we are about to replace with moss. Never a gain.
                if (dx == 0 && dz == 0) continue;

                int x = ox + dx, z = oz + dz, y = oy;

                int steps = 0;
                columnPos.set(x, y, z);
                while (steps < VERTICAL_RANGE && mc.level.getBlockState(columnPos).isAir()) {
                    columnPos.set(x, --y, z);
                    steps++;
                }

                steps = 0;
                while (steps < VERTICAL_RANGE && !mc.level.getBlockState(columnPos).isAir()) {
                    columnPos.set(x, ++y, z);
                    steps++;
                }

                // Ran out of vertical range without finding the gap: nothing converts here.
                if (!mc.level.getBlockState(columnPos).isAir()) continue;

                columnPos.set(x, y - 1, z);
                if (isConvertible(mc.level.getBlockState(columnPos)) && ++found >= needed) {
                    return found;
                }
            }
        }

        return found;
    }

    /** Returns the position, or null when it is on the give-up list. */
    private BlockPos notBlacklisted(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return blacklist.containsKey(pos) ? null : pos;
    }

    /**
     * Records an action against a position and decides whether to keep trying it.
     *
     * <p>Prediction can be wrong - the patch rolls a radius and skips edge columns at random,
     * and the server has the last word. Without this the module would pour bone meal into one
     * block forever and the bot would never move on, which is exactly what standing still
     * looks like from the outside.
     *
     * @return false once the position has burned through {@link #MAX_ATTEMPTS} and is dropped
     */
    private boolean keepTrying(BlockPos pos) {
        if (pos.equals(lastActionPos)) {
            if (++actionAttempts > MAX_ATTEMPTS) {
                blacklist.put(pos.immutable(), ticks + VISITED_TIMEOUT);
                lastActionPos = null;
                actionAttempts = 0;
                if (debug.get()) log("giving up on %s after %d tries", pos, MAX_ATTEMPTS);
                return false;
            }
        } else {
            lastActionPos = pos.immutable();
            actionAttempts = 1;
        }
        return true;
    }

    /** True if the patch would replace this block with moss and that is an actual gain. */
    private boolean isConvertible(BlockState state) {
        // Already moss: placeGround leaves it alone, so it is not worth any bone meal.
        if (state.is(Blocks.MOSS_BLOCK)) return false;

        return stoneOnly.get()
            ? state.is(BlockTags.BASE_STONE_OVERWORLD)
            : state.is(BlockTags.MOSS_REPLACEABLE);
    }

    /**
     * True if this block may be swept off a moss block. Restricted to blocks that break
     * instantly, which covers grass, ferns, flowers and azalea bushes while never touching
     * anything that would mean actually digging.
     */
    private boolean isClearable(BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        // Fluids have no collision and cannot be broken.
        if (!state.getFluidState().isEmpty()) return false;
        // Carpets are cheap but not instant, so they stall the module mid-break. Skipped
        // explicitly rather than relying on the tool-dependent instant-break check.
        if (state.is(Blocks.MOSS_CARPET) || state.is(BlockTags.WOOL_CARPETS)) return false;
        // Keep the bushes we are farming into trees.
        if (growAzalea.get() && (state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA))) {
            return false;
        }
        return BlockUtils.canBreak(pos, state) && BlockUtils.canInstaBreak(pos);
    }

    /**
     * Moves a stack of bone meal from the main inventory into a free hotbar slot.
     *
     * <p>Only ever targets an empty slot, so nothing already in the hotbar is displaced -
     * in practice that is the slot the last stack was used up from.
     *
     * @return true when a move was issued, so the caller can let it land before acting
     */
    private boolean refillHotbar() {
        FindItemResult stack = InvUtils.find(Items.BONE_MEAL);
        if (!stack.found() || stack.isHotbar()) return false;

        int free = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                free = i;
                break;
            }
        }
        if (free == -1) return false;

        InvUtils.move().from(stack.slot()).toHotbar(free);
        return true;
    }

    /** True if an azalea here has room to become a tree and is not crowding another one. */
    private boolean isAzaleaWorthGrowing(int x, int y, int z) {
        for (int i = 1; i <= AZALEA_HEADROOM; i++) {
            auxPos.set(x, y + i, z);
            if (!mc.level.getBlockState(auxPos).isAir()) return false;
        }

        int spacing = azaleaSpacing.get();
        if (spacing == 0) return true;

        for (int dx = -spacing; dx <= spacing; dx++) {
            for (int dy = -spacing; dy <= spacing; dy++) {
                for (int dz = -spacing; dz <= spacing; dz++) {
                    auxPos.set(x + dx, y + dy, z + dz);
                    BlockState state = mc.level.getBlockState(auxPos);
                    if (state.is(Blocks.AZALEA_LEAVES) || state.is(Blocks.FLOWERING_AZALEA_LEAVES)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Keeps the player moving toward somewhere worth working when nothing is in reach.
     *
     * <p>Baritone is cancelled the instant real work shows up, so pathing never fights the
     * bone mealing. A spot we walked all the way to and found nothing at is blacklisted for
     * a minute, otherwise the bot would ping-pong back to the same dead end forever.
     */
    private void handleRoaming(boolean hasWork) {
        if (!BaritoneBridge.isUsable()) return;

        if (hasWork) {
            if (walkTarget != null || exploring) {
                BaritoneBridge.cancel();
                walkTarget = null;
                exploring = false;
            }
            return;
        }

        boolean enRoute = walkTarget != null || exploring;

        // Arriving, or Baritone giving up, is an event too - react to it on the tick it
        // happens rather than waiting for the next scheduled look.
        if (enRoute && !BaritoneBridge.isPathing()) {
            if (walkTarget != null) blacklist.put(walkTarget, ticks + VISITED_TIMEOUT);
            // A leg that ended with nothing found: turn a little before the next one.
            if (exploring) exploreBearing += EXPLORE_TURN;
            walkTarget = null;
            exploring = false;
            enRoute = false;
            retargetNow = true;
        }

        if (!retargetNow) {
            // Committed to a real target: let it walk.
            if (walkTarget != null) return;
            // Exploring, or idle: keep looking on the cooldown. Exploring especially must
            // keep scanning, otherwise the bot commits to a long leg and walks straight past
            // convertible stone it should have stopped for.
            if (walkTimer > 0) {
                walkTimer--;
                return;
            }
        }

        retargetNow = false;
        walkTimer = rescanDelay.get() * 20;

        BlockPos dest = findRemoteWork();
        if (dest != null) {
            // Found real work, including part-way through an explore leg: drop the sweep and
            // go to it. This is what stops the bot marching past convertible stone.
            walkTarget = dest;
            exploring = false;
            BaritoneBridge.pathTo(dest, 2);
            if (debug.get()) log("walking to %s", dest);
            return;
        }

        // Already sweeping and still nothing found: carry straight on rather than re-issuing
        // the same goal, which would stutter the path every cooldown.
        if (exploring) return;

        // Nothing worth converting anywhere in range. Standing still achieves nothing, so
        // sweep outward on the current heading until something turns up.
        if (explore.get()) {
            int distance = exploreDistance.get();
            int x = Mth.floor(mc.player.getX() + Math.cos(exploreBearing) * distance);
            int z = Mth.floor(mc.player.getZ() + Math.sin(exploreBearing) * distance);

            exploring = BaritoneBridge.exploreTo(x, z);
            if (debug.get()) log("nothing nearby, sweeping toward %d %d", x, z);
        } else if (debug.get()) {
            log("no reachable moss worth walking to (explore is off)");
        }
    }

    /**
     * Best scanned moss position to go and work on.
     *
     * <p>Ranked by how much it would actually convert, not merely by how close it is. Walking
     * an extra ten blocks to a spot that turns four blocks beats standing on one that turns a
     * single block, and it keeps the bot away from moss that is already surrounded by moss.
     */
    private BlockPos findRemoteWork() {
        int near = searchChunks.get();
        BlockPos found = searchWork(near);
        if (found != null) return found;

        // Widen before giving up. Wandering off on a blind heading when there is still
        // convertible stone a bit further out is exactly what makes the bot look lost.
        int wide = Math.min(near * 3, WIDE_SEARCH_CHUNKS);
        return wide > near ? searchWork(wide) : null;
    }

    /** Nearest workable patch within a chunk radius, or null. See {@link #findRemoteWork()}. */
    private BlockPos searchWork(int chunks) {
        List<BlockPos> candidates = BaritoneBridge.scanFor(Blocks.MOSS_BLOCK, 128, 48, chunks);
        if (candidates.isEmpty()) return null;

        blacklist.values().removeIf(expiry -> expiry <= ticks);

        int n = candidates.size();
        int needed = minConversions.get();
        int[] values = new int[n];

        for (int i = 0; i < n; i++) {
            BlockPos pos = candidates.get(i);
            if (blacklist.containsKey(pos)) continue;
            int value = remoteValue(pos, needed);
            if (value >= needed) values[i] = value;
        }

        Vec3 eye = mc.player.getEyePosition();
        int reach = clusterRadius.get();
        int reachSq = reach * reach;
        int threshold = minCluster.get();

        BlockPos nearestPatch = null;
        double nearestPatchDistSq = Double.MAX_VALUE;
        BlockPos nearestAny = null;
        double nearestAnyDistSq = Double.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            if (values[i] == 0) continue;
            BlockPos pos = candidates.get(i);

            double dx = pos.getX() + 0.5 - eye.x;
            double dy = pos.getY() + 0.5 - eye.y;
            double dz = pos.getZ() + 0.5 - eye.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < nearestAnyDistSq) {
                nearestAny = pos;
                nearestAnyDistSq = distSq;
            }

            // Cannot beat the patch we already have, so skip the neighbourhood sum.
            if (distSq >= nearestPatchDistSq) continue;

            int patch = 0;
            for (int j = 0; j < n; j++) {
                if (values[j] == 0) continue;
                BlockPos other = candidates.get(j);
                int ex = other.getX() - pos.getX();
                int ey = other.getY() - pos.getY();
                int ez = other.getZ() - pos.getZ();
                if (ex * ex + ey * ey + ez * ez <= reachSq) patch += values[j];
            }

            if (patch >= threshold) {
                nearestPatch = pos;
                nearestPatchDistSq = distSq;
            }
        }

        BlockPos chosen = nearestPatch != null ? nearestPatch : nearestAny;
        return chosen == null ? null : chosen.immutable();
    }

    /**
     * How many blocks working this position would convert, or 0 if it is not workable.
     *
     * <p>Same test as the in-reach scan but tolerant of moss that is still covered: by the
     * time we walk there, {@code clear-obstructions} will have uncovered it. Counts in full
     * rather than stopping at the threshold, because the number is used for ranking.
     */
    private int remoteValue(BlockPos pos, int needed) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        auxPos.set(x, y + 1, z);
        BlockState above = mc.level.getBlockState(auxPos);
        if (!above.isAir() && !(clearObstructions.get() && isClearable(auxPos, above))) {
            return 0;
        }

        return countConversions(x, y + 1, z, Integer.MAX_VALUE);
    }

    private void useBonemeal(BlockPos pos, Block expected) {
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 50,
                () -> interact(pos, expected));
        } else {
            interact(pos, expected);
        }
    }

    private void interact(BlockPos pos, Block expected) {
        // With rotation this runs a tick or more later, so re-check everything: the module
        // may have been toggled off, the block changed, or the hotbar rearranged since.
        if (!isActive() || mc.player == null || mc.level == null) return;
        if (!mc.level.getBlockState(pos).is(expected)) return;

        FindItemResult bonemeal = InvUtils.findInHotbar(Items.BONE_MEAL);
        if (!bonemeal.found()) return;

        InvUtils.swap(bonemeal.slot(), swapBack.get());
        BlockUtils.interact(
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false),
            InteractionHand.MAIN_HAND,
            swing.get()
        );
        if (swapBack.get()) InvUtils.swapBack();
    }

    private void clearBlock(BlockPos pos) {
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 50, () -> doClear(pos));
        } else {
            doClear(pos);
        }
    }

    private void doClear(BlockPos pos) {
        if (!isActive() || mc.player == null || mc.level == null) return;

        BlockState state = mc.level.getBlockState(pos);
        if (!isClearable(pos, state)) return;

        BlockUtils.breakBlock(pos, swing.get());
    }

    private boolean debugDue() {
        return debug.get() && ticks % debugInterval.get() == 0;
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[AutoMoss] " + String.format(fmt, args));
    }

    /**
     * Precomputes every offset in the search cube, ordered by distance from the player.
     *
     * <p>Each offset is packed into the low 24 bits of an int and paired with its squared
     * distance in the high bits of a long, so a single primitive sort orders the whole table
     * nearest-first with no boxing. Scans then walk the table and stop at the first hit,
     * which is by construction the closest one.
     */
    private void rebuildOffsets(int radius) {
        int side = radius * 2 + 1;
        long[] keys = new long[side * side * side];
        int i = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long distSq = dx * dx + dy * dy + dz * dz;
                    int packed = ((dx + PACK_BIAS) << 16) | ((dy + PACK_BIAS) << 8) | (dz + PACK_BIAS);
                    keys[i++] = (distSq << 24) | packed;
                }
            }
        }

        Arrays.sort(keys);

        int[] result = new int[keys.length];
        for (int j = 0; j < keys.length; j++) result[j] = (int) (keys[j] & 0xFFFFFF);

        offsets = result;
        builtRadius = radius;
    }
}
