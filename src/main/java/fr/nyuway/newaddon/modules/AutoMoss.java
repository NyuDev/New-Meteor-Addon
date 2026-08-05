package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.modules.moss.MossPatch;
import fr.nyuway.newaddon.modules.moss.MossSettings;
import fr.nyuway.newaddon.utils.BoneCrafter;
import fr.nyuway.newaddon.utils.Combat;
import fr.nyuway.newaddon.utils.Interactions;
import fr.nyuway.newaddon.utils.OffsetTable;
import fr.nyuway.newaddon.utils.Reach;
import fr.nyuway.newaddon.utils.Unstuck;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

    private final MossSettings cfg = new MossSettings(settings);

    private final OffsetTable offsets = new OffsetTable();

    /** Holds the crafting sequence across the round trips it needs. */
    private final BoneCrafter crafter = new BoneCrafter(m -> {
        if (cfg.debug.get()) log("craft: %s", m);
    });

    /** Conversion predictor, rebuilt only when its inputs change. See {@link #patch()}. */
    private MossPatch patch;
    private int patchRadius = -1;
    private boolean patchConvertDirt;
    private net.minecraft.world.level.Level patchLevel;
    /** Reused across the whole scan so a tick allocates nothing. */
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
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
    private int craftTimer;
    /** Bone meal held at the previous craft tick, so a gain is measured across the step. */
    private int craftMealBefore;
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
        craftTimer = 0;
        craftMealBefore = 0;
        ticks = 0;
        offsets.invalidate();

        if (mc.player != null && !InvUtils.findInHotbar(Items.BONE_MEAL).found()
            && !(cfg.autoRefill.get() && InvUtils.find(Items.BONE_MEAL).found())) {
            warning("No bone meal in your inventory.");
        }

        if (cfg.baritone.get() && !BaritoneBridge.isPresent()) {
            warning("Baritone control needs Meteor's Baritone fork, which is not installed.");
        }

        if (cfg.debug.get() && mc.player != null) {
            log("enabled: range=%.1f patchRadius=%d minConversions=%d convertDirt=%s "
                + "clearObstructions=%s placeMoss=%s airPlace=%s baritone=%s",
                cfg.range.get(), cfg.patchRadius.get(), cfg.minConversions.get(),
                cfg.convertDirt.get(), cfg.clearObstructions.get(), cfg.placeMoss.get(),
                cfg.airPlace.get(), cfg.baritone.get());
            log("enabled: vanillaReach=%s breakPlaceReach=%.1f escapeStuck=%s",
                cfg.vanillaReach.get(), cfg.breakPlaceReach.get(), cfg.escapeStuck.get());
            log("enabled: boneMealHotbar=%d boneMealTotal=%d mossHotbar=%d",
                InvUtils.findInHotbar(Items.BONE_MEAL).count(),
                InvUtils.find(Items.BONE_MEAL).count(),
                InvUtils.findInHotbar(Items.MOSS_BLOCK).count());
        }
    }

    @Override
    public void onDeactivate() {
        mossTarget = null;
        clearTarget = null;
        azaleaTarget = null;
        placeTarget = null;

        // Never leave bone blocks sitting in the crafting grid: a death drops them.
        if (crafter.isBusy()) crafter.finish();

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

        ticks++;

        if (cfg.pauseOnKillAura.get() && Combat.killAuraFighting()) {
            mossTarget = clearTarget = azaleaTarget = placeTarget = null;
            if (debugDue()) log("paused: KillAura is fighting");
            return;
        }

        if (handleStuck()) {
            mossTarget = clearTarget = azaleaTarget = placeTarget = null;
            return;
        }

        // After the escape, deliberately: an interrupted meal is an annoyance, suffocating
        // while eating is not.
        if (cfg.pauseWhileUsing.get() && mc.player.isUsingItem()) {
            mossTarget = clearTarget = azaleaTarget = placeTarget = null;
            if (debugDue()) log("paused: you are using an item");
            return;
        }

        // Separate from the above on purpose: a swap ruining your meal and a screen being up
        // are different situations, and only the first is always worth stopping for.
        if (cfg.pauseInGui.get() && mc.screen != null) {
            mossTarget = clearTarget = azaleaTarget = placeTarget = null;
            if (debugDue()) log("paused: a screen is open");
            return;
        }

        if (craftTimer > 0 && !crafter.isBusy()) craftTimer--;
        else if (maybeCraft()) return;

        boolean ready = timer <= 0;
        if (!ready) timer--;
        if (azaleaTimer > 0) azaleaTimer--;

        // On cooldown ticks the scan buys nothing unless something still needs it.
        if (!ready && !cfg.render.get() && !cfg.debug.get()) return;

        FindItemResult bonemeal = InvUtils.findInHotbar(Items.BONE_MEAL);
        if (!bonemeal.found()) {
            mossTarget = clearTarget = azaleaTarget = placeTarget = null;

            if (ready && cfg.autoRefill.get() && !crafter.isBusy() && refillHotbar()) {
                // Give the move a tick to land before looking for the stack again.
                timer = cfg.delay.get();
                if (cfg.debug.get()) log("refilled bone meal from inventory");
                return;
            }

            // Nothing in the hotbar and nothing left to refill from: genuinely out.
            if (cfg.autoDisable.get() && !InvUtils.find(Items.BONE_MEAL).found()) {
                error("Out of bone meal.");
                toggle();
                return;
            }

            if (debugDue()) log("idle: no bone meal in hotbar");
            return;
        }

        if (ticks % 100 == 0) blacklist.values().removeIf(expiry -> expiry <= ticks);

        FindItemResult moss = cfg.placeMoss.get() ? InvUtils.findInHotbar(Items.MOSS_BLOCK) : null;
        boolean hasMoss = moss != null && moss.found();

        boolean azaleaDue = cfg.growAzalea.get() && azaleaTimer <= 0;
        scan(azaleaDue, hasMoss);

        if (debugDue()) {
            log("scan: moss=%d withAir=%d obstructed=%d tooPoor=%d blacklisted=%d "
                + "| target=%s clear=%s place=%s azalea=%s",
                seenMoss, seenMossWithAir, seenMossObstructed, seenMossTooPoor, blacklist.size(),
                mossTarget, clearTarget, placeTarget, azaleaTarget);

            // Say what the numbers mean, so an idle module never needs interpreting.
            if (seenMoss == 0) {
                log("  -> no moss within range %.1f; nothing to work with here",
                    cfg.range.get());
            } else if (seenMossWithAir == 0 && seenMossObstructed == 0) {
                log("  -> all %d moss blocks are covered by something that cannot be cleared",
                    seenMoss);
            } else if (seenMossWithAir > 0 && seenMossTooPoor == seenMossWithAir) {
                log("  -> %d moss blocks are usable but none has %d convertible column(s) "
                    + "nearby (patch-radius=%d convert-dirt=%s); everything around them is "
                    + "already moss or buried",
                    seenMossWithAir, cfg.minConversions.get(), cfg.patchRadius.get(),
                    cfg.convertDirt.get());
            } else if (seenMossObstructed > 0 && clearTarget == null && mossTarget == null) {
                log("  -> %d covered moss blocks, but uncovering none of them would convert "
                    + "anything", seenMossObstructed);
            }
        }

        // Finishing everything in reach is the event that should hand Baritone its next
        // destination, immediately. Waiting out a timer here is what made it feel stop-start.
        boolean hasWork = mossTarget != null || clearTarget != null || placeTarget != null;
        if (hadWork && !hasWork) retargetNow = true;
        hadWork = hasWork;

        if (cfg.baritone.get()) handleRoaming(hasWork);

        if (!ready) return;

        // Azalea is rare by construction, so let it go first when its timer is up.
        if (azaleaDue && azaleaTarget != null && keepTrying(azaleaTarget)) {
            azaleaTimer = cfg.azaleaInterval.get() * 20;
            timer = cfg.delay.get();
            if (cfg.debug.get()) log("action: bone mealing azalea at %s", azaleaTarget);
            useBonemeal(azaleaTarget, azaleaBlock);
            return;
        }

        if (mossTarget != null && keepTrying(mossTarget)) {
            timer = cfg.delay.get();
            if (cfg.debug.get()) log("action: bone mealing moss at %s", mossTarget);
            useBonemeal(mossTarget, Blocks.MOSS_BLOCK);
            return;
        }

        if (clearTarget != null && keepTrying(clearTarget)) {
            timer = cfg.delay.get();
            if (cfg.debug.get()) log("action: clearing cover at %s", clearTarget);
            clearBlock(clearTarget);
            return;
        }

        if (placeTarget != null && hasMoss && keepTrying(placeTarget)) {
            timer = cfg.delay.get();
            if (cfg.debug.get()) log("action: placing moss at %s", placeTarget);

            boolean placed = Interactions.place(placeTarget, moss, cfg.rotate.get(),
                cfg.silentRotations.get(), cfg.swing.get(), cfg.airPlace.get());

            // Nothing to click against and air-place is off: blacklist it rather than come
            // straight back to the same impossible spot on the next scan.
            if (!placed) {
                blacklist.put(placeTarget.immutable(), ticks + VISITED_TIMEOUT);
                if (cfg.debug.get()) log("skipped %s: nothing to place against", placeTarget);
                placeTarget = null;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!cfg.render.get()) return;

        BlockPos bonemealBox = mossTarget != null ? mossTarget : azaleaTarget;
        if (bonemealBox != null) {
            event.renderer.box(bonemealBox, cfg.sideColor.get(), cfg.lineColor.get(), cfg.shapeMode.get(), 0);
        }
        if (clearTarget != null) {
            event.renderer.box(clearTarget, cfg.clearSideColor.get(), cfg.clearLineColor.get(), cfg.shapeMode.get(), 0);
        }
        if (placeTarget != null) {
            event.renderer.box(placeTarget, cfg.sideColor.get(), cfg.lineColor.get(), cfg.shapeMode.get(), 0);
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

        int radius = Mth.ceil(cfg.range.get());
        offsets.ensureRadius(radius);

        Vec3 eye = mc.player.getEyePosition();
        double maxDistSq = cfg.range.get() * cfg.range.get();
        BlockPos origin = mc.player.blockPosition();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int needed = cfg.minConversions.get();
        boolean full = cfg.debug.get();
        boolean wantClear = cfg.clearObstructions.get();

        for (int packed : offsets.packed()) {
            int x = ox + OffsetTable.dx(packed);
            int y = oy + OffsetTable.dy(packed);
            int z = oz + OffsetTable.dz(packed);

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
                    if (patch().countConversions(x, y + 1, z, needed) >= needed) {
                        if (mossTarget == null) mossTarget = notBlacklisted(x, y, z);
                    } else {
                        seenMossTooPoor++;
                    }
                } else if (wantClear && isClearable(scanPos, above)) {
                    // Blocked, but only worth uncovering if the patch would then convert
                    // something. Neighbouring columns read the same either way, so the
                    // count is a valid prediction of the post-break result.
                    seenMossObstructed++;
                    scanPos.set(x, y + 1, z);
                    if (clearTarget == null && canTouch(scanPos)
                        && patch().countConversions(x, y + 1, z, needed) >= needed) {
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
                if (!canTouch(scanPos)) continue;

                // Something solid to click against. The block below already passed, but it can
                // be a fluid or a container, neither of which a placement can be aimed at.
                if (!cfg.airPlace.get() && BlockUtils.getPlaceSide(scanPos) == null) continue;

                if (patch().countConversions(x, y + 1, z, needed) >= needed
                    && !mossCouldServe(x, y, z)) {
                    placeTarget = notBlacklisted(x, y, z);
                }
            }

            if (mossTarget != null && !full) return;
        }
    }

    /**
     * A predictor bound to the current world and settings.
     *
     * <p>Rebuilt per use rather than cached: patch-radius and convert-dirt can change between
     * ticks from the GUI, and a stale predictor would quietly spend bone meal against rules
     * the user had already turned off.
     */
    private MossPatch patch() {
        int radius = cfg.patchRadius.get();
        boolean convertDirt = cfg.convertDirt.get();

        // Cached, but thrown away the moment the settings or the world change. Rebuilding it
        // per call was allocating one predictor - and its cursor - for every candidate block
        // in a scan that walks over a thousand offsets, in a module written specifically to
        // allocate nothing per tick.
        if (patch == null || radius != patchRadius || convertDirt != patchConvertDirt
            || patchLevel != mc.level) {
            patch = new MossPatch(mc.level, radius, convertDirt);
            patchRadius = radius;
            patchConvertDirt = convertDirt;
            patchLevel = mc.level;
        }
        return patch;
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
                if (cfg.debug.get()) log("giving up on %s after %d tries", pos, MAX_ATTEMPTS);
                return false;
            }
        } else {
            lastActionPos = pos.immutable();
            actionAttempts = 1;
        }
        return true;
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
        if (cfg.growAzalea.get() && (state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA))) {
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
    /**
     * Tops the bone meal back up from bone blocks.
     *
     * <p>Runs before the hotbar check rather than after it: crafting once you are already at
     * zero means a visible stall every time, and the module may well have disabled itself by
     * then. Keeping a margin means you never notice.
     *
     * @return true when a round happened and the tick is spent
     */
    private boolean maybeCraft() {
        if (!BoneCrafter.usable(mc)) return false;

        int before = InvUtils.find(Items.BONE_MEAL).count();
        boolean wanted = cfg.craftBoneMeal.get() && before < cfg.craftBelow.get();

        // Anything left in the grid is dropped by a death, so bringing it home wins over every
        // reason to stop: the quota being met, the setting going off, running out of blocks.
        if (!wanted && crafter.isBusy()) crafter.finish();
        else if (!wanted) return false;

        Item source = Items.BONE_BLOCK;
        int sourceIndex = findIngredient(Items.BONE_BLOCK);
        if (sourceIndex == -1 && cfg.craftFromBones.get()) {
            source = Items.BONE;
            sourceIndex = findIngredient(Items.BONE);
        }
        if (sourceIndex == -1 && !crafter.isBusy()) return false;

        int deposit = depositSlot();
        if (deposit == -1 && !crafter.isBusy() && debugDue()) {
            log("craft: no free inventory slot to put the bone meal in");
        }

        int made = crafter.tick(mc, source,
            sourceIndex == -1 ? -1 : SlotUtils.indexToId(sourceIndex),
            deposit == -1 ? -1 : SlotUtils.indexToId(deposit),
            cfg.craftSafe.get());

        if (made > 0) {
            craftTimer = cfg.craftDelay.get();
            // Measured across the step the server confirmed, not twice in the same tick.
            if (cfg.debug.get()) log("craft: bone meal was %d, now %d", craftMealBefore, before);
        }
        craftMealBefore = before;

        // Keep the tick while a sequence is in flight, so nothing else clicks over it.
        return crafter.isBusy();
    }

    /** Finds an ingredient anywhere in the hotbar or main inventory. */
    private int findIngredient(Item item) {
        for (int i = 0; i <= SlotUtils.MAIN_END; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    /**
     * Where a finished craft should go.
     *
     * <p>An existing bone meal stack with room comes first, so repeated rounds pile into one
     * slot instead of scattering a few at a time across the inventory. A full inventory with a
     * nearly full bone meal stack legitimately has nowhere to put a craft, which is what the
     * caller reports.
     */
    private int depositSlot() {
        for (int i = 0; i <= SlotUtils.MAIN_END; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.BONE_MEAL)
                && stack.getCount() + BoneCrafter.MAX_YIELD <= stack.getMaxStackSize()) {
                return i;
            }
        }
        for (int i = 0; i <= SlotUtils.MAIN_END; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

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

        int spacing = cfg.azaleaSpacing.get();
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
        walkTimer = cfg.rescanDelay.get() * 20;

        BlockPos dest = findRemoteWork();
        if (dest != null) {
            // Found real work, including part-way through an explore leg: drop the sweep and
            // go to it. This is what stops the bot marching past convertible stone.
            walkTarget = dest;
            exploring = false;
            BaritoneBridge.pathTo(dest, 2);
            if (cfg.debug.get()) log("walking to %s", dest);
            return;
        }

        // Already sweeping and still nothing found: carry straight on rather than re-issuing
        // the same goal, which would stutter the path every cooldown.
        if (exploring) return;

        // Nothing worth converting anywhere in range. Standing still achieves nothing, so
        // sweep outward on the current heading until something turns up.
        if (cfg.explore.get()) {
            int distance = cfg.exploreDistance.get();
            int x = Mth.floor(mc.player.getX() + Math.cos(exploreBearing) * distance);
            int z = Mth.floor(mc.player.getZ() + Math.sin(exploreBearing) * distance);

            exploring = BaritoneBridge.exploreTo(x, z);
            if (cfg.debug.get()) log("nothing nearby, sweeping toward %d %d", x, z);
        } else if (cfg.debug.get()) {
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
        int near = cfg.searchChunks.get();
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
        int needed = cfg.minConversions.get();
        int[] values = new int[n];

        for (int i = 0; i < n; i++) {
            BlockPos pos = candidates.get(i);
            if (blacklist.containsKey(pos)) continue;
            int value = remoteValue(pos, needed);
            if (value >= needed) values[i] = value;
        }

        Vec3 eye = mc.player.getEyePosition();
        int reach = cfg.clusterRadius.get();
        int reachSq = reach * reach;
        int threshold = cfg.minCluster.get();

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
        if (!above.isAir() && !(cfg.clearObstructions.get() && isClearable(auxPos, above))) {
            return 0;
        }

        return patch().countConversions(x, y + 1, z, Integer.MAX_VALUE);
    }

    /**
     * Whether moss that is already there would convert the same ground anyway.
     *
     * <p>Placing moss is for stone no existing patch can reach - an outcrop with nothing green
     * near it, where turning one block into moss is the only way in. Next to moss that is
     * already there it converts nothing new: the same columns were already covered, so the
     * moss block is spent for nothing.
     *
     * <p>Two patches overlap when their origins are within twice the patch radius, so that is
     * the distance searched. Moss under a layer of grass still counts when
     * {@code clear-obstructions} is on, since the module can uncover it - and uncovering costs
     * nothing where placing costs a block.
     */
    private boolean mossCouldServe(int x, int y, int z) {
        int r = 2 * cfg.patchRadius.get();

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    auxPos.set(x + dx, y + dy, z + dz);
                    if (!mc.level.getBlockState(auxPos).is(Blocks.MOSS_BLOCK)) continue;

                    auxPos.set(x + dx, y + dy + 1, z + dz);
                    BlockState above = mc.level.getBlockState(auxPos);

                    if (above.isAir()) return true;
                    if (cfg.clearObstructions.get() && isClearable(auxPos, above)) return true;
                }
            }
        }

        return false;
    }

    /** Whether a break or a place at this position is within the configured reach. */
    private boolean canTouch(BlockPos pos) {
        return Reach.canReach(mc, pos, cfg.vanillaReach.get(), cfg.breakPlaceReach.get());
    }

    /**
     * Digs out of a block that has closed around the player.
     *
     * <p>Runs before the bone meal check on purpose: being walled in is worth escaping whether
     * or not there is anything left to work with, and the module going idle for lack of bone
     * meal is no reason to sit there taking suffocation damage.
     *
     * @return true when this took the tick
     */
    private boolean handleStuck() {
        if (!cfg.escapeStuck.get()) return false;

        BlockPos trap = Unstuck.find(mc, auxPos);
        if (trap == null) return false;

        if (debugDue()) log("stuck: breaking %s to get free", trap);

        // Reach is not consulted here - the block is inside us, so no limit can exclude it,
        // and refusing to dig out on a technicality would be the worst possible reading.
        Interactions.mine(mc, trap, cfg.silentRotations.get(), cfg.swing.get());

        return cfg.pauseWhileStuck.get();
    }

    private void useBonemeal(BlockPos pos, Block expected) {
        if (cfg.rotate.get()) {
            Interactions.lookAt(pos, cfg.silentRotations.get(), () -> interact(pos, expected));
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

        InvUtils.swap(bonemeal.slot(), cfg.swapBack.get());
        // Already facing the block; click the face turned toward us rather than always the top.
        Interactions.clickBlock(mc, pos, cfg.swing.get());
        if (cfg.swapBack.get()) InvUtils.swapBack();
    }

    private void clearBlock(BlockPos pos) {
        if (cfg.rotate.get()) {
            Interactions.lookAt(pos, cfg.silentRotations.get(), () -> doClear(pos));
        } else {
            doClear(pos);
        }
    }

    private void doClear(BlockPos pos) {
        if (!isActive() || mc.player == null || mc.level == null) return;

        BlockState state = mc.level.getBlockState(pos);
        if (!isClearable(pos, state)) return;

        BlockUtils.breakBlock(pos, cfg.swing.get());
    }

    private boolean debugDue() {
        return cfg.debug.get() && ticks % cfg.debugInterval.get() == 0;
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[AutoMoss] " + String.format(fmt, args));
    }

}
