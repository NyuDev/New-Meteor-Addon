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

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your hand client-side. Off still sends the swing packet.")
        .defaultValue(true)
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

    private final Setting<Integer> rescanDelay = sgBaritone.add(new IntSetting.Builder()
        .name("rescan-delay")
        .description("Seconds between two searches for somewhere new to walk to.")
        .defaultValue(3).min(1).max(30).sliderMin(1).sliderMax(15)
        .visible(baritone::get)
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

    /** Where Baritone is currently walking us, and spots that turned out to be dead ends. */
    private BlockPos walkTarget;
    private final Map<BlockPos, Integer> visited = new HashMap<>();

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
        walkTarget = null;
        visited.clear();
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

        // Only stop pathing we started ourselves.
        if (walkTarget != null) {
            BaritoneBridge.cancel();
            walkTarget = null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (pauseOnKillAura.get() && Modules.get().isActive(KillAura.class)) {
            mossTarget = clearTarget = azaleaTarget = null;
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
            mossTarget = clearTarget = azaleaTarget = null;

            if (ready && autoRefill.get() && refillHotbar()) {
                // Give the move a tick to land before looking for the stack again.
                timer = delay.get();
                if (debug.get()) log("refilled bone meal from inventory");
            } else if (debugDue()) {
                log("idle: no bone meal in hotbar");
            }
            return;
        }

        boolean azaleaDue = growAzalea.get() && azaleaTimer <= 0;
        scan(azaleaDue);

        if (debugDue()) {
            log("moss=%d withAir=%d obstructed=%d tooPoor=%d | target=%s clear=%s azalea=%s",
                seenMoss, seenMossWithAir, seenMossObstructed, seenMossTooPoor,
                mossTarget, clearTarget, azaleaTarget);
        }

        if (baritone.get()) handleRoaming();

        if (!ready) return;

        // Azalea is rare by construction, so let it go first when its timer is up.
        if (azaleaDue && azaleaTarget != null) {
            azaleaTimer = azaleaInterval.get() * 20;
            timer = delay.get();
            useBonemeal(azaleaTarget, azaleaBlock);
            return;
        }

        if (mossTarget != null) {
            timer = delay.get();
            useBonemeal(mossTarget, Blocks.MOSS_BLOCK);
            return;
        }

        if (clearTarget != null) {
            timer = delay.get();
            clearBlock(clearTarget);
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
    private void scan(boolean azaleaDue) {
        mossTarget = null;
        clearTarget = null;
        azaleaTarget = null;
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
                        if (mossTarget == null) mossTarget = new BlockPos(x, y, z);
                    } else {
                        seenMossTooPoor++;
                    }
                } else if (wantClear && isClearable(scanPos, above)) {
                    // Blocked, but only worth uncovering if the patch would then convert
                    // something. Neighbouring columns read the same either way, so the
                    // count is a valid prediction of the post-break result.
                    seenMossObstructed++;
                    if (clearTarget == null && countConversions(x, y + 1, z, needed) >= needed) {
                        clearTarget = new BlockPos(x, y + 1, z);
                    }
                }
            } else if (azaleaDue && azaleaTarget == null
                       && (state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA))) {
                if (isAzaleaWorthGrowing(x, y, z)) {
                    azaleaTarget = new BlockPos(x, y, z);
                    azaleaBlock = state.getBlock();
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
    private void handleRoaming() {
        if (!BaritoneBridge.isUsable()) return;

        if (mossTarget != null || clearTarget != null) {
            if (walkTarget != null) {
                BaritoneBridge.cancel();
                walkTarget = null;
            }
            return;
        }

        if (walkTimer > 0) {
            walkTimer--;
            return;
        }

        // Still on the way and Baritone has not given up: leave it alone.
        if (walkTarget != null && BaritoneBridge.isPathing()) return;

        // Arrived, or Baritone gave up, and there is still nothing to do here.
        if (walkTarget != null) {
            visited.put(walkTarget, ticks + VISITED_TIMEOUT);
            walkTarget = null;
        }

        walkTimer = rescanDelay.get() * 20;

        BlockPos dest = findRemoteWork();
        if (dest != null) {
            walkTarget = dest;
            BaritoneBridge.pathTo(dest, 2);
            if (debug.get()) log("walking to %s", dest);
        } else if (debug.get()) {
            log("no reachable moss worth walking to");
        }
    }

    /** Closest scanned moss position that would actually be worth standing next to. */
    private BlockPos findRemoteWork() {
        List<BlockPos> candidates =
            BaritoneBridge.scanFor(Blocks.MOSS_BLOCK, 64, 32, searchChunks.get());
        if (candidates.isEmpty()) return null;

        visited.values().removeIf(expiry -> expiry <= ticks);

        Vec3 eye = mc.player.getEyePosition();
        int needed = minConversions.get();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : candidates) {
            if (visited.containsKey(pos)) continue;

            double dx = pos.getX() + 0.5 - eye.x;
            double dy = pos.getY() + 0.5 - eye.y;
            double dz = pos.getZ() + 0.5 - eye.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq >= bestDistSq) continue;

            if (!isRemoteWorthIt(pos, needed)) continue;

            best = pos.immutable();
            bestDistSq = distSq;
        }

        return best;
    }

    /**
     * Same test as the in-reach scan, but tolerant of moss that is still covered: by the
     * time we walk there, {@code clear-obstructions} will have uncovered it.
     */
    private boolean isRemoteWorthIt(BlockPos pos, int needed) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        auxPos.set(x, y + 1, z);
        BlockState above = mc.level.getBlockState(auxPos);
        if (!above.isAir() && !(clearObstructions.get() && isClearable(auxPos, above))) {
            return false;
        }

        return countConversions(x, y + 1, z, needed) >= needed;
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
