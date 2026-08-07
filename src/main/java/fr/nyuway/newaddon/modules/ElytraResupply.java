package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import fr.nyuway.newaddon.modules.elytra.ResupplySettings;
import fr.nyuway.newaddon.utils.Combat;
import fr.nyuway.newaddon.utils.Containers;
import fr.nyuway.newaddon.utils.Enchants;
import fr.nyuway.newaddon.utils.GroundFinder;
import fr.nyuway.newaddon.utils.Interactions;
import fr.nyuway.newaddon.utils.PlayerInv;
import fr.nyuway.newaddon.utils.ShulkerContents;
import fr.nyuway.newaddon.utils.SlotLoans;
import fr.nyuway.newaddon.utils.SpotFinder;
import fr.nyuway.newaddon.utils.WorldBounds;
import fr.nyuway.newaddon.utils.Unstuck;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * ElytraResupply - keeps a Baritone elytra flight going without you babysitting it.
 *
 * <p>Baritone's elytra process lands when it runs out of fireworks or the elytra wears out.
 * On a long crossing that means coming back to a stranded character. This module notices the
 * landing, sets up on the spot, tops itself back up, clears away every trace, and sends
 * Baritone on to the same destination.
 *
 * <p>The full round is: place an ender chest, take out a shulker, place it, take what is
 * needed, throw XP bottles at your own feet to mend the elytra, put the leftovers back in the
 * same shulker, break it, return it to the slot it came from, then break the ender chest with
 * a Silk Touch pickaxe and pick it up. Nothing is left behind.
 *
 * <h2>Shape of the code</h2>
 * Every step is a server round trip - a block has to actually appear, a container has to
 * actually open, an item entity has to actually be collected - so this is a state machine
 * with one phase per round trip and a timeout on each. {@code debug} logs every transition;
 * when something goes wrong that log says which phase stalled.
 *
 * <p>On any timeout the module runs its cleanup path rather than stopping where it is, so a
 * failure does not leave your ender chest sitting in the open.
 */
public class ElytraResupply extends Module {

    private enum Phase {
        IDLE,
        /** Coming down before anything can be placed. Only a manual trigger starts here. */
        LAND,
        PLACE_CHEST, OPEN_CHEST, TAKE_SHULKER, PLACE_SHULKER, OPEN_SHULKER, TAKE_SUPPLIES,
        REPAIR, SWAP_ELYTRA,
        RETURN_SUPPLIES, BREAK_SHULKER, RETURN_SHULKER, BREAK_CHEST,
        /** Putting borrowed hotbar slots back before letting go. */
        RESTORE_HOTBAR,
        /** Walking off a spot that will not let us take off. */
        REPOSITION,
        /** Coming down on solid ground before the void gets us. */
        VOID_LAND,
        TAKEOFF, WAIT_DISCONNECT, RESUME
    }

    /** Ticks any single phase may take before the run is treated as failed. */
    private static final int PHASE_TIMEOUT = 200;

    /**
     * How far a new elytra destination must be before it is believed to be a new trip rather
     * than Baritone choosing somewhere close by to put down.
     */
    private static final int RETARGET_MIN_DIST = 256;

    /**
     * Ticks spent chasing a dropped item before writing it off and carrying on. Generous:
     * what is on the ground is an ender chest or a full shulker, and eight seconds of walking
     * over broken End terrain was not enough to reach one.
     */
    private static final int COLLECT_GIVEUP = 20 * 30;

    /** Ticks of jump attempts before a stuck takeoff is given up rather than hopping forever. */
    private static final int TAKEOFF_GIVEUP = 60;

    /**
     * Failed takeoffs in a row before the module bows out and hands control back to the player.
     * Each attempt now walks somewhere new first, so being patient costs a few seconds rather
     * than hopping in the same hole.
     */
    private static final int MAX_TAKEOFF_FAILURES = 5;

    /** Baritone's terrain clearance for elytra paths; the only lever on how high it flies. */
    private static final String AVOIDANCE = "elytraMinimumAvoidance";

    /** Ticks between two ground sweeps while below the void margin. */
    private static final int VOID_SCAN_INTERVAL = 20;

    /** Relaunches from one spot before walking somewhere else, and before giving up. */
    private static final int RELAUNCH_BEFORE_WALK = 2;
    private static final int RELAUNCH_GIVEUP = 6;

    /** Beyond this a goal cannot be a destination in this world; 2b2t's border is 30M. */
    private static final double MAX_TRAVEL_DIST = 30_000_000.0;

    /** Ticks spent walking off a bad takeoff spot before trying again. */
    private static final int REPOSITION_TICKS = 20 * 8;

    /** Ticks allowed to glide down after a mid-flight trigger before giving up. */
    private static final int LANDING_TIMEOUT = 20 * 60;

    /** How far off the setup block counts as having been pushed rather than having stepped. */
    private static final double PUSH_TOLERANCE = 1.6;

    /** Rotation priority for the idle downward look: below every interaction here. */
    private static final int PARK_PRIORITY = 10;

    /** Ticks after a bottle before its effect is read off the XP bar. */
    private static final int XP_READ_DELAY = 8;

    /** Ticks to let an elytra swap settle once XP was seen going to the bar instead. */
    private static final int XP_SETTLE = 20;

    private final ResupplySettings cfg = new ResupplySettings(settings);

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    /** Consecutive failed takeoffs; too many and the module disables itself. Not reset() cleared. */
    private int takeoffFailures;

    /** True while Baritone is walking us onto a drop we failed to collect from where we stood. */
    private boolean walkingToDrop;
    /** Whether the elytra process was flying last tick, to tell a new trip from a retarget. */
    private boolean elytraWasActive;
    /** Paces the idle diagnostic so it explains itself without flooding the log. */
    private int idleTicks;
    /** Paces debugDue, kept apart from the idle report's own counter. */
    private int noteTicks;
    /** Paces the combat-pause diagnostic the same way. */
    private int pauseTicks;
    /** How many of the item we already carried before mining, so a real pickup is detectable. */
    private int collectBaseline;

    private BlockPos chestPos;
    private BlockPos shulkerPos;
    /** Container slot the shulker came from, so it goes back exactly where it was. */
    private int shulkerHomeSlot = -1;
    /** Inventory slot the Silk Touch tool was pulled from, so it can be put back. */
    private int pickaxeHomeSlot = -1;
    /** Destination Baritone was flying to, captured before it gave up. */
    private BlockPos resumeTarget;
    /** What this run is for; a run may need only one of the two. */
    private boolean needFireworks, needMending, needElytraSwap;
    /** Ender-chest slots we already opened this trip, so we do not keep grabbing the same box. */
    private final java.util.Set<Integer> triedShulkerSlots = new java.util.HashSet<>();
    /** Supply the shulker we just pulled is expected to hold, so we place that exact box and not
     *  some other shulker we already carried. */
    private Item wantedShulkerItem;
    /** False during the mending pass (bottles + repair), true once we move on to fireworks. */
    private boolean gatheringFireworks;

    /** Block the routine set up on, so being shoved off it can be undone. */
    private BlockPos anchor;
    /** Total XP when the last bottle was thrown, and the tick to read it back on. */
    private int xpSnapshot = -1;
    private int xpCheckAt = -1;
    /** phaseTicks before which no further bottle is thrown, while a swap settles. */
    private int repairSettleUntil;
    /** True while spending supplies already carried, before any chest has been placed. */
    private boolean localPass;
    /** Set when a run failed for want of supplies, which is its own reason to log out. */
    private boolean stranded;
    /** Guards the abort-time shulker return, so a failing return cannot loop. */
    private boolean returnTried;
    /** Ticks spent on the ground while a trip is unfinished, before relaunching. */
    private int groundTicks;
    /** Consecutive relaunches that never got us off the ground. */
    private int relaunchTries;
    /** Ticks before the ground sweep may run again. */
    private int voidScanCooldown;
    /** World the destination was captured in. */
    private Object lastLevel;
    /** Edge detection for the manual trigger key. */
    private boolean triggerHeld;
    /**
     * Ticks spent gliding down. Separate from phaseTicks, which the phase timeout pins at its
     * ceiling to keep this phase alive - and a counter that cannot advance cannot expire.
     */
    private int landTicks;
    /** Baritone's own clearance before we raised it, so it can be handed back untouched. */
    /** Hotbar slots borrowed for the routine, to be handed back before it lets go. */
    private final SlotLoans loans = new SlotLoans();
    /** Scratch position for the stuck check, so a per-tick test allocates nothing. */
    private final BlockPos.MutableBlockPos unstickCursor = new BlockPos.MutableBlockPos();

    public ElytraResupply() {
        super(NewAddon.CATEGORY, "elytra-resupply",
            "Restocks fireworks and mends the elytra when Baritone lands mid-flight, then flies on.");
    }

    @Override
    public void onActivate() {
        reset();
        loans.clear();
        takeoffFailures = 0;
        repairAvoidance();
        if (!BaritoneBridge.isPresent()) {
            warning("This needs Meteor's Baritone fork; nothing will happen without it.");
        }
    }

    @Override
    public void onDeactivate() {
        // Last chance to hand the hotbar back. Normally this is paced a move per tick, but a
        // module being switched off has no next tick, and a burst of clicks that might get
        // resynced still beats leaving someone's sword in the wrong slot.
        for (int i = 0; i <= PlayerInv.HOTBAR_SIZE && loans.restoreOne(mc); i++) {
            // restoreOne does the work; the bound just stops a broken state looping.
        }
        loans.clear();

        reset();
    }

    /**
     * Undoes damage an earlier build of this module did to Baritone.
     *
     * <p>It used to raise {@code elytraMinimumAvoidance} on the belief that it was terrain
     * clearance in blocks. It is not - Baritone ships it at 0.2 - and forcing it to tens left
     * the elytra solver unable to find any acceptable path, so the flight circled its last
     * point instead of advancing. Worse, Baritone persists its settings, so the bad value
     * survived a restart.
     *
     * <p>Anything above one is far outside what that knob is for and can only have come from
     * here, so it goes back to the default.
     */
    private void repairAvoidance() {
        Object current = BaritoneBridge.setting(AVOIDANCE);
        if (!(current instanceof Double d) || d <= 1.0) return;

        BaritoneBridge.setSetting(AVOIDANCE, 0.2);
        warning("Reset Baritone's %s from %s to 0.2; an earlier build of this module set it "
            + "and it was stopping elytra flights from advancing.", AVOIDANCE, d);
    }

    private void reset() {
        // Drop anything in-flight so a disable mid-routine leaves the player free, not stuck
        // in a container screen, holding jump, or on a stale phase.
        if (mc.player != null && isContainerOpen()) mc.player.closeContainer();
        if (mc.options != null) mc.options.keyJump.setDown(false);

        phase = Phase.IDLE;
        phaseTicks = 0;
        chestPos = null;
        shulkerPos = null;
        shulkerHomeSlot = -1;
        pickaxeHomeSlot = -1;
        resumeTarget = null;
        needFireworks = needMending = needElytraSwap = false;
        triedShulkerSlots.clear();
        wantedShulkerItem = null;
        gatheringFireworks = false;
        anchor = null;
        xpSnapshot = -1;
        xpCheckAt = -1;
        repairSettleUntil = 0;
        localPass = false;
        stranded = false;
        returnTried = false;
        relaunchTries = 0;
        voidScanCooldown = 0;
        landTicks = 0;
        groundTicks = 0;

        if (walkingToDrop) {
            BaritoneBridge.cancel();
            walkingToDrop = false;
        }
    }

    /** True when the player is actively steering, so the module should get out of the way. */
    private boolean manualMovementRequested() {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    private void to(Phase next) {
        // Any phase change ends a walk-to-drop; the next phase drives its own movement.
        if (walkingToDrop && next != phase) {
            BaritoneBridge.cancel();
            walkingToDrop = false;
        }
        if (cfg.debug.get()) log("%s -> %s", phase, next);
        phase = next;
        phaseTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || !BaritoneBridge.isUsable()) return;

        // A destination belongs to the world it was captured in. Being pulled out of the End
        // by a stasis chamber leaves a goal that is now tens of millions of blocks away and
        // can never be reached or "arrived" at - which is a relaunch that never stops.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            if (resumeTarget != null) {
                if (cfg.debug.get()) log("world changed; dropping the old destination %s", resumeTarget);
                resumeTarget = null;
            }
            relaunchTries = 0;
            if (phase != Phase.IDLE) abort();
            return;
        }

        // The moment the player grabs the controls mid-routine, bow out entirely and give the
        // controls back. Scoped to an active routine so ordinary flight is left alone.
        if (cfg.releaseOnInput.get() && phase != Phase.IDLE && manualMovementRequested()) {
            info("Releasing control.");
            toggle();
            return;
        }

        // Freeze rather than reset: phaseTicks is not advanced either, so a long fight does
        // not trip the phase timeout and throw away a run that was going fine.
        if (cfg.pauseOnKillAura.get() && Combat.killAuraFighting()) {
            if (isContainerOpen()) mc.player.closeContainer();
            if (cfg.debug.get() && pauseTicks++ % 20 == 0) log("paused: KillAura is fighting");
            return;
        }

        if (manualTriggerPressed() && phase == Phase.IDLE) {
            beginRun(true);
            return;
        }

        if (phase == Phase.IDLE) {
            watchForLanding();
            return;
        }

        holdPosition();
        parkView();

        if (++phaseTicks > PHASE_TIMEOUT) {
            if (phase == Phase.REPOSITION) {
                // Its own clock decides when to stop walking.
                phaseTicks = PHASE_TIMEOUT;
                reposition();
                return;
            }
            if (phase == Phase.WAIT_DISCONNECT || phase == Phase.LAND
                || phase == Phase.VOID_LAND) {
                // Both of these wait on the ground arriving, which takes as long as it takes.
                phaseTicks = PHASE_TIMEOUT;
                if (phase == Phase.LAND) land();
                else if (phase == Phase.VOID_LAND) voidLand();
                return;
            }
            warning("Timed out; cleaning up.");
            if (cfg.debug.get()) log("phase %s timed out", phase);
            abort();
            return;
        }

        switch (phase) {
            case LAND -> land();
            case PLACE_CHEST -> placeChest();
            case OPEN_CHEST -> openBlock(chestPos, Phase.TAKE_SHULKER);
            case TAKE_SHULKER -> takeShulker();
            case PLACE_SHULKER -> placeShulker();
            case OPEN_SHULKER -> openBlock(shulkerPos, Phase.TAKE_SUPPLIES);
            case TAKE_SUPPLIES -> takeSupplies();
            case REPAIR -> repair();
            case RETURN_SUPPLIES -> returnSupplies();
            case BREAK_SHULKER -> breakAndCollect(shulkerPos, Containers::isShulker, false, Phase.RETURN_SHULKER);
            case RETURN_SHULKER -> returnShulker();
            case BREAK_CHEST -> breakAndCollect(chestPos, s -> s.is(Items.ENDER_CHEST), true,
                Phase.RESTORE_HOTBAR);
            case RESTORE_HOTBAR -> restoreHotbar();
            case REPOSITION -> reposition();
            case VOID_LAND -> voidLand();
            case TAKEOFF -> takeoff();
            case WAIT_DISCONNECT -> waitAndDisconnect();
            case SWAP_ELYTRA -> swapElytra();
            case RESUME -> resume();
            default -> { }
        }
    }

    /**
     * Rising edge of the trigger key.
     *
     * <p>Polled rather than driven by a key event so it costs nothing when unbound, and so it
     * can only ever fire while this module is the one ticking - a bind that did something with
     * the module switched off would be a surprise.
     */
    private boolean manualTriggerPressed() {
        boolean down = cfg.triggerKey.get().isSet() && cfg.triggerKey.get().isPressed();
        boolean fired = down && !triggerHeld;
        triggerHeld = down;
        return fired;
    }

    /**
     * Walks back to the block the routine set up on.
     *
     * <p>An enderman or a piston can shove you a couple of blocks while a container is open,
     * and every position the routine remembers - the chest, the shulker, the spot it checked
     * for headroom - is measured from where it started. The anchor is only ever set at the
     * start of a run and cleared with it, so the routine's own walking (chasing a dropped
     * item) never fights this.
     */
    private void holdPosition() {
        if (!cfg.holdPosition.get() || anchor == null || walkingToDrop) return;

        // Only meaningful with both feet on the ground. In the air the distance from the
        // anchor grows every tick by design, and asking Baritone to walk back mid-glide hands
        // its elytra process a goal it refuses outright.
        if (!mc.player.onGround() || BaritoneBridge.isElytraActive()) return;

        double dx = mc.player.getX() - (anchor.getX() + 0.5);
        double dz = mc.player.getZ() - (anchor.getZ() + 0.5);
        if (dx * dx + dz * dz < PUSH_TOLERANCE * PUSH_TOLERANCE) return;

        if (phaseTicks % 20 != 0) return;
        if (cfg.debug.get()) log("pushed off the setup spot, walking back");
        BaritoneBridge.pathTo(anchor, 0);
    }

    /**
     * Points at the ground when nothing else wants the view.
     *
     * <p>Meteor's rotation system takes the highest priority asked for in a tick, so this sits
     * well below every interaction and simply fills the gaps. In the End those gaps are what
     * decides whether a resupply is quiet: looking at an enderman is the whole of the crime.
     */
    private void parkView() {
        if (!cfg.lookDown.get()) return;

        Rotations.rotate(mc.player.getYRot(), 90.0f, PARK_PRIORITY,
            !cfg.silentRotations.get(), () -> { });
    }

    /** Paces an occasional debug line. Its own counter: sharing one starves both. */
    private boolean debugDue() {
        return cfg.debug.get() && noteTicks++ % 40 == 0;
    }

    /** Horizontal distance to the destination, or infinity when there is none. */
    private double distanceToTarget() {
        if (resumeTarget == null || mc.player == null) return Double.POSITIVE_INFINITY;
        double dx = mc.player.getX() - (resumeTarget.getX() + 0.5);
        double dz = mc.player.getZ() - (resumeTarget.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Whether we are close enough to the destination to call the trip finished. */
    private boolean arrived() {
        return distanceToTarget() <= cfg.arrivalRadius.get();
    }

    /** True on ticks where an action is allowed, so clicks are paced rather than instant. */
    private boolean ready() {
        int delay = cfg.actionDelay.get();
        if (delay <= 0) return true;

        // The wait applies before the first action of a phase too, not only between actions.
        // Firing on tick one meant a phase change cost nothing, so a whole run - place, open,
        // take, place, open - went past in a single second with the server never given room
        // to disagree with any of it.
        return phaseTicks >= delay && phaseTicks % delay == 0;
    }

    // --- trigger ------------------------------------------------------------

    /**
     * Watches an elytra flight and steps in once it has stopped short of its destination.
     * The destination is captured while the process is still running, because once it gives
     * up there is nothing left to ask.
     */
    private void watchForLanding() {
        boolean elytraActive = BaritoneBridge.isElytraActive();

        // Two independent sources for the destination. Reading it only from the elytra
        // process meant that when that process reported inactive - which it does on some
        // builds even mid-glide - nothing was ever captured and the module sat idle in
        // total silence. Baritone's own goal is the fallback.
        BlockPos dest = elytraActive ? BaritoneBridge.elytraDestination() : null;
        if (dest == null) dest = BaritoneBridge.currentGoalPos();

        if (dest != null && isRealTravelGoal(dest) && !dest.equals(resumeTarget)) {
            // Only long-range goals count. Anything nearby is Baritone picking somewhere to
            // set down, and taking that for the trip destination is what once made it try to
            // fly to where it already stood.
            resumeTarget = dest;
            log("travel goal noted: %s", dest);
        }

        // Whether we are flying is decided by the ground under our feet, not by what the
        // elytra process claims. It reports active while stuck - the process is running, the
        // player is not moving - and treating that as "still flying" meant returning here
        // every tick without so much as a log line, which is exactly what being stuck in a
        // field looks like from outside.
        if (!mc.player.onGround()) {
            elytraWasActive = true;
            groundTicks = 0;
            // Off the ground for real, so whatever it took worked and the count starts over.
            relaunchTries = 0;
            voidGuard();
            return;
        }

        if (elytraActive && debugDue()) {
            log("on the ground with the elytra process still claiming active - treating it "
                + "as stuck");
        }
        elytraWasActive = false;

        if (resumeTarget == null || !mc.player.onGround()) {
            reportIdle(elytraActive, dest);
            return;
        }

        boolean lowFireworks = PlayerInv.count(mc, Items.FIREWORK_ROCKET) <= cfg.minFireworks.get();
        boolean equippedDamaged = PlayerInv.wornElytraDurability(mc) < cfg.minElytraDurability.get();

        // Nothing is low, so there is no resupply to do. Whether the trip is over is a
        // separate question: touching the ground is not arriving. Baritone puts down for
        // terrain, an emergency, or because you took the controls back, and treating any of
        // those as "done" logs you out in the middle of nowhere.
        if (!lowFireworks && !equippedDamaged) {
            if (cfg.disconnectWhenDone.get() && arrived()) {
                to(Phase.WAIT_DISCONNECT);
                return;
            }

            // Well short of the destination with nothing to fix: this is a landing that was
            // not meant to happen - clipped terrain, an emergency put-down - and nothing else
            // will undo it. Baritone's elytra process never takes off on its own.
            if (cfg.autoRelaunch.get() && !arrived()) {
                if (++groundTicks < cfg.relaunchDelay.get()) return;
                groundTicks = 0;

                // Repeating something that did not work is not persistence. After a few tries
                // the spot is the problem, so walk; after a few more, stop and say so rather
                // than loop until someone notices.
                if (++relaunchTries > RELAUNCH_GIVEUP) {
                    if (relaunchTries == RELAUNCH_GIVEUP + 1) {
                        warning("Cannot get airborne here; stopping. Move somewhere open and "
                            + "toggle me off and on.");
                    }
                    return;
                }
                if (relaunchTries > RELAUNCH_BEFORE_WALK) {
                    if (cfg.debug.get()) log("relaunch %d failed; walking first", relaunchTries);
                    to(Phase.REPOSITION);
                    return;
                }

                // Clear whatever the process thinks it is doing first. A run that believes it
                // is mid-flight while standing still will not accept a fresh destination, and
                // RESUME hands it one the moment we are airborne again.
                if (elytraActive) BaritoneBridge.cancel();

                info("Back in the air.");
                if (cfg.debug.get()) {
                    log("relaunching, %d blocks from the destination (processActive=%s)",
                        (int) distanceToTarget(), elytraActive);
                }
                to(Phase.TAKEOFF);
                return;
            }

            if (debugDue()) log("landed with nothing low, %s from the destination",
                resumeTarget == null ? "no idea how far" : (int) distanceToTarget() + " blocks");
            return;
        }

        groundTicks = 0;

        beginRun(false);
    }

    /**
     * Starts a resupply, from a landing or from the trigger key.
     *
     * <p>Carried supplies are spent first when {@code use-carried-first} is on. A bag that
     * still holds XP bottles can often finish the mending on its own, and then no ender chest
     * is placed, nothing is broken, and nothing is left behind to find - which is the whole
     * point of the module. Only what is still missing afterwards is worth opening storage for.
     */
    private void beginRun(boolean manual) {
        needFireworks = PlayerInv.count(mc, Items.FIREWORK_ROCKET) < cfg.targetFireworks.get();
        needMending = PlayerInv.wornElytraDurability(mc) < cfg.minElytraDurability.get()
            || PlayerInv.findDamagedMendingElytra(mc) != -1;
        needElytraSwap = PlayerInv.wornElytraDurability(mc) <= 0;

        if (manual && !needFireworks && !needMending) {
            info("Nothing to resupply.");
            return;
        }

        // Pressed mid-flight, which is the normal way to use it: the ask is "put down, sort
        // this out, carry on", not "place an ender chest at this altitude". Come down first
        // and let the landing pick up where this leaves off.
        if (!mc.player.onGround() || BaritoneBridge.isElytraActive()) {
            // Capture whatever Baritone was aiming at before touching its goal, whichever
            // process holds it. Losing this is losing the trip.
            BlockPos going = BaritoneBridge.isElytraActive()
                ? BaritoneBridge.elytraDestination() : null;
            if (going == null) going = BaritoneBridge.currentGoalPos();
            if (going != null && isRealTravelGoal(going)) resumeTarget = going;

            // Land by retargeting rather than cancelling. Cancelling stops the elytra process
            // outright, which reads as the module switching itself off and leaves nothing to
            // resume; pointing it at where we already are brings it down and keeps it running.
            info("Landing to resupply.");
            if (cfg.debug.get()) {
                log("triggered in the air; retargeting to here, will resume %s", resumeTarget);
            }
            BaritoneBridge.elytraPathTo(mc.player.blockPosition());
            landTicks = 0;
            to(Phase.LAND);
            return;
        }

        anchor = mc.player.blockPosition();
        if (cfg.debug.get()) {
            log("run starting at %s (manual=%s fireworks=%s mending=%s)",
                anchor, manual, needFireworks, needMending);
        }

        // Mend with what is already on us before opening anything.
        if (cfg.useCarriedFirst.get() && needMending
            && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0) {
            localPass = true;
            info("Resupplying.");
            to(Phase.REPAIR);
            return;
        }

        if (!openStorageAllowed()) return;

        info("Resupplying.");
        to(Phase.PLACE_CHEST);
    }

    /**
     * Breaks off the flight before it sinks past the height where the world kills you.
     *
     * <p>Baritone's elytra process has no notion of the void as a hazard: over the End it will
     * glide out from under the islands and only think about landing once the fireworks run
     * low, by which point there is nothing left to land on. The margin has to cover the
     * descent, not just the moment of noticing - you are still falling while you look for
     * somewhere to put down.
     */
    private void voidGuard() {
        if (!cfg.voidGuard.get()) return;

        double floor = WorldBounds.voidDamageY(mc.level) + cfg.voidMargin.get();
        if (mc.player.getY() >= floor) {
            voidScanCooldown = 0;
            return;
        }

        // The sweep reads tens of thousands of blocks. Once a second is plenty while falling,
        // and running it every tick would cost more than the drop it is trying to prevent.
        if (voidScanCooldown > 0) {
            voidScanCooldown--;
            return;
        }
        voidScanCooldown = VOID_SCAN_INTERVAL;

        // Save the trip before touching the goal, exactly as the manual trigger does.
        BlockPos going = BaritoneBridge.isElytraActive()
            ? BaritoneBridge.elytraDestination() : null;
        if (going == null) going = BaritoneBridge.currentGoalPos();
        if (going != null && isRealTravelGoal(going)) resumeTarget = going;

        BlockPos ground = GroundFinder.find(mc, WorldBounds.voidDamageY(mc.level)
            + cfg.voidMargin.get());
        if (ground == null) {
            // Nothing within reach. Saying so beats silently carrying on into the drop.
            if (debugDue()) {
                log("void guard: below %.0f with no ground in range", floor);
            }
            return;
        }

        warning("Too low - landing before the void.");
        if (cfg.debug.get()) {
            log("void guard: y=%.1f floor=%.0f, landing at %s, will resume %s",
                mc.player.getY(), floor, ground, resumeTarget);
        }

        BaritoneBridge.elytraPathTo(ground);
        landTicks = 0;
        to(Phase.VOID_LAND);
    }

    /**
     * Waits out the descent onto the spot the guard picked, then goes straight back up.
     *
     * <p>No resupply here: nothing was low, the flight was simply pointed at nothing. Once
     * there is ground underfoot the only thing owed is a takeoff.
     */
    private void voidLand() {
        if (!mc.player.onGround()) {
            if (++landTicks > LANDING_TIMEOUT) {
                warning("Could not reach ground before the void.");
                landTicks = 0;
                to(Phase.IDLE);
            }
            return;
        }

        landTicks = 0;
        BaritoneBridge.cancel();
        info("Clear of the void.");
        to(Phase.TAKEOFF);
    }

    /**
     * Waits out the glide down after a mid-flight trigger.
     *
     * <p>The destination was captured before the flight was cancelled, so the run finishes the
     * way an automatic one does: resupply, then hand the same goal back to Baritone.
     */
    private void land() {
        if (!mc.player.onGround()) {
            // Long: coming down from cruising altitude is a glide, not a fall.
            if (++landTicks > LANDING_TIMEOUT) {
                warning("Could not get down; giving up.");
                abort();
            }
            return;
        }

        landTicks = 0;
        anchor = mc.player.blockPosition();

        // Down safely, so stop the process now rather than leaving it pathing to a goal we
        // are already standing on while the routine places blocks around it. Safe to do here
        // and nowhere earlier: the real destination is already saved.
        BaritoneBridge.cancel();
        if (cfg.debug.get()) log("landed, setting up");

        if (cfg.useCarriedFirst.get() && needMending
            && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0) {
            localPass = true;
            to(Phase.REPAIR);
            return;
        }

        if (!openStorageAllowed()) {
            abort();
            return;
        }
        to(Phase.PLACE_CHEST);
    }

    /**
     * Whether a chest run can go ahead at all, complaining once if not.
     *
     * <p>Deliberately says nothing about where anything is: chat is public the moment you
     * screenshot it, and this module runs in places you would rather not have pinned down.
     */
    private boolean openStorageAllowed() {
        if (cfg.requireSilkTouch.get() && PlayerInv.findSilkTouch(mc) == -1) {
            error("Low on supplies, but no Silk Touch pickaxe - not placing a chest.");
            resumeTarget = null;
            stranded = true;
            if (cfg.disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return false;
        }
        if (!InvUtils.find(Items.ENDER_CHEST).found()) {
            error("Low on supplies, but no ender chest on me.");
            resumeTarget = null;
            stranded = true;
            if (cfg.disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return false;
        }
        return true;
    }

    // --- phases -------------------------------------------------------------

    private void placeChest() {
        if (chestPos == null) {
            chestPos = new SpotFinder(mc, cfg.searchRadius.get(), cfg.voidClearance.get(), chestPos, shulkerPos).find(false);
            if (chestPos == null) {
                error("Nowhere safe to set up here.");
                abort();
                return;
            }
        }

        if (mc.level.getBlockState(chestPos).is(Blocks.ENDER_CHEST)) {
            to(Phase.OPEN_CHEST);
            return;
        }

        FindItemResult chest = InvUtils.findInHotbar(Items.ENDER_CHEST);
        if (!chest.found()) {
            // Bring it down to the hotbar first; BlockUtils.place needs it there.
            if (!PlayerInv.moveToHotbar(mc, s -> s.is(Items.ENDER_CHEST))) {
                if (PlayerInv.freeHotbarSlot(mc, loans)) return;
                error("Could not get the ender chest into the hotbar.");
                abort();
            }
            return;
        }

        // Through Interactions so the rotation honours silent-rotations; BlockUtils.place
        // can only turn the visible camera.
        Interactions.place(chestPos, chest, true, cfg.silentRotations.get(), true, true);
    }

    /** Right-clicks a placed block and moves on once its container menu is really open. */
    private void openBlock(BlockPos pos, Phase next) {
        if (pos == null) {
            abort();
            return;
        }

        if (isContainerOpen()) {
            to(next);
            return;
        }

        // Only knock once every few ticks: the server needs time to answer.
        if (phaseTicks % 10 != 1) return;

        // An empty hand first. A held item turns the right-click into a use of that item, and
        // servers that check for it refuse the open outright rather than quietly doing both.
        if (cfg.emptyHandToOpen.get() && !emptyHand()) return;

        // Faces the block first. Opening a container the server thinks we are not looking at
        // is one of the plainest anticheat flags there is.
        Interactions.interact(mc, pos, cfg.silentRotations.get(), true);
    }

    /**
     * Puts an empty slot in hand, making one if the hotbar is full.
     *
     * @return true once the hand really is empty; false while still arranging it
     */
    private boolean emptyHand() {
        if (mc.player.getMainHandItem().isEmpty()) return true;

        int empty = PlayerInv.firstEmptyHotbarSlot(mc);
        if (empty != -1) {
            // Through InvUtils: the selected-slot field was renamed between the versions this
            // builds for, and its swap also syncs the choice to the server.
            InvUtils.swap(empty, false);
            return mc.player.getMainHandItem().isEmpty();
        }

        // Nothing spare: clear a slot, then come back for it on a later tick.
        if (!PlayerInv.freeHotbarSlot(mc, loans) && cfg.debug.get()) {
            log("could not free a hotbar slot to open empty handed");
        }
        return false;
    }

    private void takeShulker() {
        if (!isContainerOpen()) {
            to(Phase.OPEN_CHEST);
            return;
        }

        // Contents arrive after the menu does; reading immediately sees an empty chest.
        if (phaseTicks < cfg.containerSettle.get()) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        // Already holding the shulker we picked on a previous pass through this phase. Match on
        // its contents, not just "a shulker": a spare box we already carried must not stand in.
        if (shulkerHomeSlot != -1 && Containers.findInPlayerPart(menu, this::isWantedShulker) != -1) {
            mc.player.closeContainer();
            to(Phase.PLACE_SHULKER);
            return;
        }

        int from = findUsefulShulker(menu);
        if (from == -1) {
            // Keep waiting on a slow sync; only abort once we know the chest has content.
            if (Containers.isContainerEmpty(menu)) return;
            // Current pass is out of boxes. Hand off if we took anything, or if the mending pass
            // still has a fireworks pass left to run; only error when nothing here is ever useful.
            if (!triedShulkerSlots.isEmpty() || (!gatheringFireworks && stillNeedFireworks())) {
                mc.player.closeContainer();
                finishAfterGathering();
                return;
            }
            if (Containers.findInContainer(menu, Containers::isShulker) == -1) {
                error("No shulker box in the ender chest.");
            } else {
                error("No shulker here has what I need.");
            }
            abort();
            return;
        }

        if (!ready()) return;

        // Check there is somewhere to stand it before pulling it out. Finding out afterwards
        // means holding a box that came from someone's storage, with the chest about to be
        // broken and no phase left that puts it back - which is exactly how an unrelated
        // shulker ends up in the inventory for good.
        if (shulkerPos == null) {
            shulkerPos = new SpotFinder(mc, cfg.searchRadius.get(), cfg.voidClearance.get(),
                chestPos, null).find(true);
            if (shulkerPos == null) {
                error("Nowhere safe to put a shulker down here.");
                abort();
                return;
            }
        }

        int dest = Containers.findEmptyInPlayerPart(menu);
        if (dest == -1) {
            error("No free inventory slot for the shulker.");
            abort();
            return;
        }

        shulkerHomeSlot = from;
        // Remember what this box holds so PLACE_SHULKER puts down this exact one.
        wantedShulkerItem = firstNeededContentIn(menu.slots.get(from).getItem());
        triedShulkerSlots.add(from);
        Containers.moveStack(menu, from, dest);
    }

    private void placeShulker() {
        if (shulkerPos == null) {
            shulkerPos = new SpotFinder(mc, cfg.searchRadius.get(), cfg.voidClearance.get(), chestPos, shulkerPos).find(true);
            if (shulkerPos == null) {
                error("Nowhere safe to put the shulker.");
                abort();
                return;
            }
        }

        if (!mc.level.getBlockState(shulkerPos).isAir()) {
            to(Phase.OPEN_SHULKER);
            return;
        }

        FindItemResult shulker = InvUtils.findInHotbar(this::isWantedShulker);
        if (!shulker.found()) {
            if (!PlayerInv.moveToHotbar(mc, this::isWantedShulker)) {
                // Hotbar is likely packed with fireworks; open a slot and retry next tick.
                if (PlayerInv.freeHotbarSlot(mc, loans)) return;
                error("Could not get the shulker into the hotbar.");
                abort();
            }
            return;
        }

        Interactions.place(shulkerPos, shulker, true, cfg.silentRotations.get(), true, true);
    }

    /**
     * Empties what we need out of the shulker, a stack per action tick.
     *
     * <p>Mending comes before restocking on purpose: bottles are what the next phase spends,
     * and if the shulker turns out short of fireworks we would rather have already repaired
     * than be holding neither.
     */
    private void takeSupplies() {
        if (!isContainerOpen()) {
            to(Phase.OPEN_SHULKER);
            return;
        }

        if (phaseTicks < cfg.containerSettle.get()) return;
        if (!ready()) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        if (needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) < cfg.xpBottles.get()) {
            if (pullOne(menu, Items.EXPERIENCE_BOTTLE, false)) return;
        }
        // Fireworks only once mending is finished: repairing spends the bottles and frees the
        // room the fireworks then need, so grabbing them first would just crowd the inventory.
        if (gatheringFireworks && needFireworks && PlayerInv.count(mc, Items.FIREWORK_ROCKET) < cfg.targetFireworks.get()) {
            if (pullOne(menu, Items.FIREWORK_ROCKET, cfg.hotbarFirst.get())) return;
        }

        // Proactively grab a spare elytra if the current one is broken and we don't already carry one.
        if (needElytraSwap && PlayerInv.findSpareElytra(mc) == -1) {
            if (pullOne(menu, Items.ELYTRA, false)) return;
        }

        mc.player.closeContainer();

        // Mend before chasing more fireworks: the instant the bottles are in hand, fix every
        // elytra now. A later hiccup (full inventory, no spot) must never leave us flying off
        // still damaged, and spending the bottles here frees the slots fireworks will need.
        if (!stillNeedBottles() && needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0
                && anyElytraNeedsMending()) {
            to(Phase.REPAIR);
            return;
        }

        if (stillNeedSupplies()) {
            // This box didn't have everything; skip returning supplies (we want to keep what we got)
            // and go break/return it so the next iteration can open another shulker.
            to(Phase.BREAK_SHULKER);
        } else if (needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0) {
            to(Phase.REPAIR);
        } else {
            to(Phase.RETURN_SUPPLIES);
        }
    }

    /**
     * Throws XP bottles straight down so the orbs land on us and mend the elytra.
     * Fully repairs the equipped elytra, then swaps in each damaged Mending elytra from
     * the inventory to repair them too, until either everything is full or we run out of
     * bottles.
     */
    private void repair() {
        ItemStack equipped = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        boolean equippedIsElytra = equipped.is(Items.ELYTRA);
        boolean equippedFull = equippedIsElytra && equipped.getDamageValue() == 0;
        boolean equippedMends = equippedIsElytra && Enchants.hasMending(equipped);

        // Equipped is unusable for XP repair (missing, wrong item, or no Mending) - try to swap in a Mending spare.
        if (!equippedIsElytra || !equippedMends) {
            int damaged = PlayerInv.findDamagedMendingElytra(mc);
            if (damaged != -1 && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0) {
                swapEquippedWithInventorySlot(damaged);
                if (cfg.debug.get()) log("swapping in a Mending elytra to repair");
                phaseTicks = 0;
                repairSettleUntil = XP_SETTLE;
                xpCheckAt = -1;
                return;
            }
            if (!equippedIsElytra) warning("No elytra equipped; nothing to repair.");
            else if (!equippedMends) warning("Equipped elytra lacks Mending; cannot repair with XP.");
            afterRepair();
            return;
        }

        if (equippedFull) {
            int damaged = PlayerInv.findDamagedMendingElytra(mc);
            if (damaged != -1 && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0) {
                swapEquippedWithInventorySlot(damaged);
                if (cfg.debug.get()) log("elytra full, swapping in a damaged spare");
                phaseTicks = 0;
                repairSettleUntil = XP_SETTLE;
                xpCheckAt = -1;
                return;
            }
            info("Elytras mended.");
            afterRepair();
            return;
        }

        FindItemResult bottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!bottle.found()) {
            if (!PlayerInv.moveToHotbar(mc, s -> s.is(Items.EXPERIENCE_BOTTLE))) {
                warning("Out of XP bottles.");
                if (localPass) afterRepair();
                else {
                    beginFireworksPass();
                    to(PlayerInv.findSpareElytra(mc) != -1
                        ? Phase.SWAP_ELYTRA : Phase.RETURN_SUPPLIES);
                }
            }
            return;
        }

        // A bottle thrown at a swap that has not landed yet is a bottle wasted: the orbs go
        // to the bar instead of the elytra. Read the bar back a moment after each throw and,
        // if it moved while the elytra is still damaged, wait for the swap to settle.
        if (xpCheckAt != -1 && phaseTicks >= xpCheckAt) {
            int now = totalXp();
            xpCheckAt = -1;
            if (now > xpSnapshot) {
                repairSettleUntil = phaseTicks + XP_SETTLE;
                if (cfg.debug.get()) {
                    log("xp went to the bar (%d -> %d) with the elytra still damaged; settling",
                        xpSnapshot, now);
                }
            }
        }

        if (phaseTicks < repairSettleUntil) return;
        if (phaseTicks % 4 != 1) return;

        xpSnapshot = totalXp();
        xpCheckAt = phaseTicks + XP_READ_DELAY;

        // Straight down, or the bottle sails off and the orbs land somewhere else.
        Rotations.rotate(mc.player.getYRot(), 90.0f, 50, () -> {
            InvUtils.swap(bottle.slot(), true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
        });
    }

    /**
     * Experience as one number, so a gain is comparable across a level boundary.
     *
     * <p>{@code totalExperience} alone is not enough: the client only refreshes it from the
     * server, while the level and the progress bar move as orbs arrive.
     */
    private int totalXp() {
        return mc.player.experienceLevel * 1000
            + Math.round(mc.player.experienceProgress * 1000.0f);
    }

    /**
     * Where the repair phase goes next.
     *
     * <p>On a carried-supplies pass there is no chest open and nothing placed, so the run can
     * simply stop if what is left on us is enough. Only a shortage that carried supplies could
     * not cover is worth setting up storage for.
     */
    private void afterRepair() {
        if (!localPass) {
            beginFireworksPass();
            to(Phase.RETURN_SUPPLIES);
            return;
        }

        localPass = false;

        boolean shortFireworks = PlayerInv.count(mc, Items.FIREWORK_ROCKET) < cfg.targetFireworks.get();
        boolean stillDamaged = PlayerInv.wornElytraDurability(mc) < cfg.minElytraDurability.get()
            || PlayerInv.findDamagedMendingElytra(mc) != -1;
        boolean canMendOnHand = PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0;

        if (!shortFireworks && !(stillDamaged && !canMendOnHand)) {
            info("Done from what I was carrying.");
            if (cfg.debug.get()) log("carried pass covered everything, no chest placed");
            needFireworks = false;
            needMending = false;
            anchor = null;
            to(Phase.RESTORE_HOTBAR);
            return;
        }

        needFireworks = shortFireworks;
        needMending = stillDamaged;

        if (!openStorageAllowed()) {
            abort();
            return;
        }
        to(Phase.PLACE_CHEST);
    }

    private void returnSupplies() {
        if (shulkerPos == null) {
            // No shulker to give leftovers back to; keep them and move on.
            to(Phase.BREAK_CHEST);
            return;
        }
        if (!isContainerOpen()) {
            openBlock(shulkerPos, Phase.RETURN_SUPPLIES);
            return;
        }

        // Paced like every other container step. Pushing a stack per tick is a burst the
        // server has no time to answer, and each click after the first carries a state id it
        // has already moved past.
        if (!ready()) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        // Leftovers go back where they came from rather than riding along as dead weight.
        if (pushOne(menu, Items.EXPERIENCE_BOTTLE)) return;

        mc.player.closeContainer();
        to(Phase.BREAK_SHULKER);
    }

    private void returnShulker() {
        if (!isContainerOpen()) {
            openBlock(chestPos, Phase.RETURN_SHULKER);
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;

        if (shulkerHomeSlot != -1 && shulkerHomeSlot < menu.slots.size()
            && Containers.isShulker(menu.slots.get(shulkerHomeSlot).getItem())) {
            // Shulker is safely back. If we still need something, keep the chest open and grab another.
            if (stillNeedSupplies()) {
                shulkerHomeSlot = -1;
                shulkerPos = null;
                to(Phase.TAKE_SHULKER);
            } else {
                mc.player.closeContainer();
                to(needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0 ? Phase.REPAIR : Phase.BREAK_CHEST);
            }
            return;
        }

        if (!ready()) return;

        int from = Containers.findInPlayerPart(menu, Containers::isShulker);
        if (from == -1) {
            // Nothing left to put back; move on rather than spin here.
            mc.player.closeContainer();
            to(Phase.BREAK_CHEST);
            return;
        }

        int dest = shulkerHomeSlot != -1 ? shulkerHomeSlot : Containers.findEmptyInContainer(menu);
        Containers.moveStack(menu, from, dest);
    }

    /** Mines a block we placed and waits until the drop is actually back in the inventory. */
    private void breakAndCollect(BlockPos pos, Predicate<ItemStack> expected, boolean needsSilk, Phase next) {
        if (pos == null) {
            to(next);
            return;
        }

        // Count what we already carry before mining. Asking merely whether an ender chest is
        // in the inventory is useless when a spare is being carried - which is the normal
        // case - because the answer is yes whether or not the dropped one was ever picked up.
        // That is why collection looked fine while the chest stayed on the ground.
        if (phaseTicks <= 1) collectBaseline = PlayerInv.countMatching(mc, expected);

        if (mc.level.getBlockState(pos).isAir()) {
            // Give the item entity a moment to fly into us before declaring anything.
            if (phaseTicks < 20) return;

            if (PlayerInv.countMatching(mc, expected) > collectBaseline) {
                if (walkingToDrop) {
                    BaritoneBridge.cancel();
                    walkingToDrop = false;
                }
                to(next);
                return;
            }

            // Not collected: the drop can be several blocks off, well outside the metre or so
            // vanilla sucks items in from. Walk onto it rather than shrug and move on - a lost
            // shulker takes its whole contents with it.
            if (!walkingToDrop) {
                walkingToDrop = true;
                problem("Drop not collected, walking to get it.");
                if (cfg.debug.get()) log("walking to the drop at %s", pos);
                BaritoneBridge.pathTo(pos, 0);
            }

            // Chasing it forever would strand the trip. Write it off and carry on, rather
            // than letting the phase timeout tear down a run that is otherwise finished.
            if (phaseTicks > COLLECT_GIVEUP) {
                problem("Could not recover the drop; carrying on without it.");
                if (cfg.debug.get()) log("gave up on the drop at %s", pos);
                to(next);
            }
            return;
        }

        if (needsSilk) {
            int silk = silkTouchInHotbar();
            // Still being fetched from storage: wait for it rather than mining the chest
            // into obsidian with whatever happens to be in hand.
            if (silk == -1) return;
            InvUtils.swap(silk, false);
        } else {
            // A shulker drops itself however it is broken, so no enchantment is needed - but
            // punching one takes seconds you spend standing still in the open. The pickaxe is
            // already on the bar for the ender chest; use it.
            FindItemResult tool = InvUtils.findFastestTool(mc.level.getBlockState(pos));
            if (tool.found()) InvUtils.swap(tool.slot(), false);
        }

        Interactions.mine(mc, pos, cfg.silentRotations.get(), true);
    }

    /**
     * Gets back off the ground. Baritone is given the destination but does not take off by
     * itself, so without this the flight resumes only in name.
     *
     * <p>Jump, then jump again in the air: that is the same input path a player uses, so
     * vanilla's own code sends the start-fall-flying packet and the server agrees we are
     * gliding. Setting the flag client-side would only desync.
     */
    /**
     * Gives back every hotbar slot the routine borrowed, one move per tick.
     *
     * <p>Paced rather than done in a burst: these are container clicks, and a burst of them
     * arrives with a stale state id and gets answered with a resync that undoes the lot.
     */
    private void restoreHotbar() {
        if (!ready()) return;

        if (loans.restoreOne(mc)) return;

        if (cfg.debug.get()) log("hotbar handed back");

        // No destination means there is no flight to get back to, so jumping would be for
        // nothing; RESUME simply tidies up and stops.
        boolean flyOn = resumeTarget != null && cfg.autoTakeoff.get();
        to(flyOn ? Phase.TAKEOFF : Phase.RESUME);
    }

    /**
     * Walks a little way towards the destination before trying to take off again.
     *
     * <p>A takeoff fails because of where it is being attempted: under an overhang, in a
     * one-block hole, on a slope that eats the second jump. Moving is the only thing that
     * changes the answer, and Baritone walking toward the destination moves us somewhere that
     * is at least no further away.
     */
    private void reposition() {
        if (phaseTicks == 1) {
            BaritoneBridge.exploreTo(resumeTarget.getX(), resumeTarget.getZ());
            if (cfg.debug.get()) log("walking toward the destination before retrying takeoff");
        }

        // Off the ground already - a slope did the work - or long enough walking.
        if (!mc.player.onGround() || phaseTicks > REPOSITION_TICKS) {
            BaritoneBridge.cancel();
            to(Phase.TAKEOFF);
        }
    }

    private void takeoff() {
        // Both conditions, not just the flag. While stuck the server keeps reporting us as
        // gliding, so isFallFlying alone declared success the instant this phase began: no
        // jump was ever sent, RESUME reset everything, and the whole thing started over - a
        // thousand times over, without once trying to move somewhere better.
        if (mc.player.isFallFlying() && !mc.player.onGround()) {
            mc.options.keyJump.setDown(false);
            takeoffFailures = 0;
            relaunchTries = 0;
            to(Phase.RESUME);
            return;
        }

        // A block closed around us - a landing inside terrain, or something placed where we
        // stand. No amount of jumping gets out of that, so dig first and come back to it. The
        // attempt clock is held back so the digging does not eat the takeoff's patience.
        BlockPos trap = Unstuck.find(mc, unstickCursor);
        if (trap != null) {
            mc.options.keyJump.setDown(false);
            if (phaseTicks % 20 == 1 && cfg.debug.get()) log("stuck at takeoff, breaking free");
            Interactions.mine(mc, trap, cfg.silentRotations.get(), true);
            phaseTicks = 1;
            return;
        }

        if (PlayerInv.wornElytraDurability(mc) <= 0) {
            warning("Elytra has no durability left; not taking off.");
            mc.options.keyJump.setDown(false);
            to(Phase.RESUME);
            return;
        }

        // Don't hop in place forever. Give a stuck takeoff a few seconds, then hand the route
        // back to Baritone; after several failed trips in a row, switch off so the player can
        // take over rather than looping between a bad landing spot and endless jumping.
        if (phaseTicks > TAKEOFF_GIVEUP && mc.player.onGround()) {
            mc.options.keyJump.setDown(false);
            if (++takeoffFailures >= MAX_TAKEOFF_FAILURES) {
                warning("Couldn't take off after several tries; stopping so you can take over.");
                toggle();
                return;
            }

            // Handing the route back was pointless: the elytra process does not take off from
            // standing, so it just left us here. Walk out of whatever this spot is - a hole, a
            // ceiling, a slope - and try again from somewhere else.
            if (cfg.autoRelaunch.get() && resumeTarget != null) {
                warning("Couldn't get airborne here; moving and trying again.");
                to(Phase.REPOSITION);
                return;
            }

            warning("Couldn't get airborne here; handing the route back to Baritone.");
            to(Phase.RESUME);
            return;
        }

        // Cycle the jump sequence. Landing again part-way through is normal - a clipped block,
        // a slope - and the only thing missing at that point is the second jump, so the cycle
        // simply restarts rather than counting the trip as failed.
        int tick = phaseTicks % 20;
        if (phaseTicks >= 20 && tick == 0 && mc.player.onGround() && cfg.debug.get()) {
            log("back on the ground; jumping again");
        }

        if (tick < 3) {
            mc.options.keyJump.setDown(true);
        } else if (tick < 6) {
            mc.options.keyJump.setDown(false);
        } else {
            // Airborne: this press is the second jump that starts the glide. Grounded: we came
            // back down, so hold off and let the next cycle start the sequence over.
            mc.options.keyJump.setDown(!mc.player.onGround());
        }
    }

    private void resume() {
        // Put the pickaxe back where it was found, so the hotbar is left as we got it.
        if (pickaxeHomeSlot != -1) {
            int silk = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && Enchants.hasSilkTouch(stack)) {
                    silk = i;
                    break;
                }
            }
            if (silk != -1) InvUtils.move().fromHotbar(silk).to(pickaxeHomeSlot);
        }

        BlockPos target = resumeTarget;
        reset();

        if (target == null) return;
        info("Resupplied. Flying on.");
        if (cfg.debug.get()) log("resuming to %s", target);
        BaritoneBridge.elytraPathTo(target);
    }

    /**
     * Tries to put things back and pick up what we placed, whatever went wrong.
     *
     * <p>Ends by resuming rather than resetting. A failed resupply is still a trip that was
     * interrupted, and throwing the destination away leaves you parked wherever the failure
     * happened - worse than carrying on short of supplies.
     */
    private void abort() {
        if (mc.player != null && isContainerOpen()) mc.player.closeContainer();

        if (shulkerPos != null && !mc.level.getBlockState(shulkerPos).isAir()) {
            to(Phase.BREAK_SHULKER);
            return;
        }

        // Holding a box taken out of the chest, with the chest still standing. Put it back
        // before breaking anything: it is not ours, it is full of someone's supplies, and
        // once the chest is gone there is nowhere left to return it to. Tried once - if the
        // return itself fails there is nothing further to try.
        if (!returnTried && shulkerHomeSlot != -1 && chestPos != null
            && !mc.level.getBlockState(chestPos).isAir()
            && InvUtils.find(this::isWantedShulker).found()) {
            returnTried = true;
            if (cfg.debug.get()) log("aborting while holding a shulker; returning it first");
            to(Phase.RETURN_SHULKER);
            return;
        }

        if (chestPos != null && !mc.level.getBlockState(chestPos).isAir()) {
            to(Phase.BREAK_CHEST);
            return;
        }

        if (resumeTarget != null) {
            to(Phase.RESTORE_HOTBAR);
            return;
        }
        if (cfg.disconnectWhenDone.get()) {
            to(Phase.WAIT_DISCONNECT);
            return;
        }
        // Even a run that ended with nothing to resume owes the hotbar back.
        if (!loans.isEmpty()) {
            to(Phase.RESTORE_HOTBAR);
            return;
        }
        reset();
    }

    // --- helpers ------------------------------------------------------------

    private boolean isContainerOpen() {
        return mc.player.containerMenu != mc.player.inventoryMenu
            && Containers.containerSize(mc.player.containerMenu) > 0;
    }

    /**
     * Moves one stack of an item from the container into us.
     *
     * @param preferHotbar put it on the bar if there is room there, for things that have to
     *                     be reachable in play rather than merely carried
     * @return true if it acted
     */
    private boolean pullOne(AbstractContainerMenu menu, Item item, boolean preferHotbar) {
        int from = Containers.findInContainer(menu, item);
        if (from == -1) return false;

        int dest = preferHotbar ? Containers.findEmptyInHotbarPart(menu) : -1;
        if (dest == -1) dest = Containers.findEmptyInPlayerPart(menu);
        if (dest == -1) return false;

        Containers.moveStack(menu, from, dest);
        return true;
    }

    /** Moves one stack of an item from us back into the container. Returns true if it acted. */
    private boolean pushOne(AbstractContainerMenu menu, Item item) {
        int from = Containers.findInPlayerPart(menu, item);
        if (from == -1) return false;

        int to = Containers.findEmptyInContainer(menu);
        if (to == -1) return false;

        Containers.moveStack(menu, from, to);
        return true;
    }

    /**
     * Hotbar slot holding a Silk Touch tool, bringing it down from storage if needed.
     *
     * @return the hotbar slot, or -1 if there is no such tool or no room for it
     */
    private int silkTouchInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && Enchants.hasSilkTouch(stack)) return i;
        }

        int stored = PlayerInv.findSilkTouch(mc);
        if (stored == -1) return -1;

        // Swap it against a hotbar slot rather than needing an empty one: a travelling
        // inventory is usually full, and the displaced stack comes straight back when the
        // pickaxe goes home after the chest is broken.
        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target == -1) target = 8;

        if (pickaxeHomeSlot == -1) pickaxeHomeSlot = stored;
        InvUtils.move().from(stored).toHotbar(target);
        return -1;
    }

    private void swapElytra() {
        if (isContainerOpen()) {
            mc.player.closeContainer();
            return;
        }
        if (!ready()) return;

        int spare = PlayerInv.findSpareElytra(mc);
        if (spare == -1) {
            warning("No spare elytra in inventory; continuing without swap.");
            to(Phase.RETURN_SUPPLIES);
            return;
        }
        // InventoryMenu: hotbar items[0-8] -> slots 36-44, main inventory items[9-35] -> slots 9-35, chest armor -> slot 6.
        int slotId = (spare < 9) ? spare + 36 : spare;
        Containers.moveStack(mc.player.inventoryMenu, slotId, 6);
        info("Swapped broken elytra for a fresh one from inventory.");
        needMending = false;
        needElytraSwap = false;
        to(Phase.RETURN_SUPPLIES);
    }

    private void waitAndDisconnect() {
        if (!mc.player.onGround()) return;

        // Checked again here, not only where this phase was entered. Dropping the connection
        // is the one thing in this module that cannot be undone, so it is worth being sure.
        //
        // Two reasons are good enough: the trip is actually finished, or it cannot continue
        // for want of supplies, where logging out beats standing in the open unable to fly.
        // Merely having touched the ground is neither.
        if (!stranded && !arrived()) {
            if (cfg.debug.get()) log("not disconnecting: landed, but not at the destination");
            reset();
            return;
        }

        info("Trip ended; disconnecting from server.");
        var conn = mc.getConnection();
        if (conn != null) conn.getConnection().disconnect(
            net.minecraft.network.chat.Component.literal("ElytraResupply: trip ended"));
    }

    private int findUsefulShulker(AbstractContainerMenu menu) {
        int size = Containers.containerSize(menu);
        for (int i = 0; i < size; i++) {
            if (triedShulkerSlots.contains(i)) continue;
            ItemStack s = menu.slots.get(i).getItem();
            if (!s.isEmpty() && Containers.isShulker(s) && shulkerHasNeededSupplies(s)) return i;
        }
        return -1;
    }

    /** Checks the stored contents of a shulker box item for at least one needed supply. */
    private boolean shulkerHasNeededSupplies(ItemStack shulker) {
        return firstNeededContentIn(shulker) != null;
    }

    /** True for a shulker holding the supply we pulled this box for, so PLACE_SHULKER can tell it
     *  apart from any unrelated shulker already in the inventory. */
    private boolean isWantedShulker(ItemStack stack) {
        if (!Containers.isShulker(stack)) return false;
        if (wantedShulkerItem == null) return true;
        return ShulkerContents.contains(stack, wantedShulkerItem);
    }

    /** The first still-needed supply this shulker holds, or null if it has nothing we want. */
    private Item firstNeededContentIn(ItemStack shulker) {
        // Mending pass first: only bottles and spare elytras count until the elytra is whole,
        // so fireworks-only boxes are left untouched and repairing keeps the inventory room.
        if (!gatheringFireworks) {
            if (needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) < cfg.xpBottles.get()
                    && ShulkerContents.contains(shulker, Items.EXPERIENCE_BOTTLE)) return Items.EXPERIENCE_BOTTLE;
            if (needElytraSwap && PlayerInv.findSpareElytra(mc) == -1
                    && ShulkerContents.contains(shulker, Items.ELYTRA)) return Items.ELYTRA;
            return null;
        }
        if (needFireworks && PlayerInv.count(mc, Items.FIREWORK_ROCKET) < cfg.targetFireworks.get()
                && ShulkerContents.contains(shulker, Items.FIREWORK_ROCKET)) return Items.FIREWORK_ROCKET;
        return null;
    }

    /**
     * Swaps the chest-armor slot with an inventory slot. The equipped item drops into the
     * source slot and the source item is worn. Used to cycle damaged elytras through the
     * armor slot for XP repair.
     */
    private void swapEquippedWithInventorySlot(int inventoryIndex) {
        int slotId = (inventoryIndex < 9) ? inventoryIndex + 36 : inventoryIndex;
        Containers.moveStack(mc.player.inventoryMenu, slotId, 6);
    }

    /** True while any target is still under quota; drives the multi-shulker loop. */
    private boolean stillNeedSupplies() {
        if (needFireworks && PlayerInv.count(mc, Items.FIREWORK_ROCKET) < cfg.targetFireworks.get()) return true;
        if (needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) < cfg.xpBottles.get()) return true;
        if (needElytraSwap && PlayerInv.findSpareElytra(mc) == -1) return true;
        return false;
    }

    /** Bottles still under quota, so the mending pass is not yet ready to repair. */
    private boolean stillNeedBottles() {
        return needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) < cfg.xpBottles.get();
    }

    /** Fireworks still under quota; only chased once the mending pass is done. */
    private boolean stillNeedFireworks() {
        return needFireworks && PlayerInv.count(mc, Items.FIREWORK_ROCKET) < cfg.targetFireworks.get();
    }

    /**
     * Switch from the mending pass to the fireworks pass. Fireworks are held back until now so
     * repairing has the most inventory room and spends the bottles first; clearing the tried set
     * lets a mixed box be reopened for the rockets we skipped while gathering bottles.
     */
    private void beginFireworksPass() {
        needMending = false;
        needElytraSwap = false;
        gatheringFireworks = true;
        wantedShulkerItem = null;
        triedShulkerSlots.clear();
    }

    /** True when the worn elytra or any spare could still soak up more XP (damaged + Mending). */
    private boolean anyElytraNeedsMending() {
        ItemStack equipped = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (equipped.is(Items.ELYTRA) && equipped.getDamageValue() > 0 && Enchants.hasMending(equipped)) return true;
        return PlayerInv.findDamagedMendingElytra(mc) != -1;
    }

    /** Called from takeShulker when no untried shulker in the ender chest can help any further. */
    private void finishAfterGathering() {
        if (!gatheringFireworks) {
            // Mending pass is out of boxes. Repair with what we have, then hand off to fireworks.
            if (needMending && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0) {
                to(Phase.REPAIR);
                return;
            }
            beginFireworksPass();
        }
        if (stillNeedSupplies()) {
            shulkerHomeSlot = -1;
            shulkerPos = null;
            to(Phase.TAKE_SHULKER);
            return;
        }
        to(Phase.BREAK_CHEST);
    }

    /** True for a destination far enough off to be the trip, not a landing spot. */
    private boolean isRealTravelGoal(BlockPos dest) {
        double away = Math.sqrt(dest.distSqr(mc.player.blockPosition()));

        // Far enough to be a trip, near enough to be real. Anything past the world border is
        // a leftover from another dimension, where the same numbers mean somewhere else.
        return away > RETARGET_MIN_DIST && away < MAX_TRAVEL_DIST;
    }

    /**
     * Says why nothing is happening, at most once a second.
     *
     * <p>Sitting idle used to produce no output whatever, so "it just does nothing" was
     * impossible to tell apart from a broken Baritone binding, a goal that was never seen, or
     * simply still being in the air. Each of those now says so.
     */
    private void reportIdle(boolean elytraActive, BlockPos seenGoal) {
        if (!cfg.debug.get() || idleTicks++ % 20 != 0) return;

        log("idle: baritone=%s elytraActive=%s goalSeen=%s resumeTarget=%s onGround=%s",
            BaritoneBridge.isUsable(), elytraActive, seenGoal, resumeTarget, mc.player.onGround());
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[ElytraResupply] " + String.format(fmt, args));
    }

    /**
     * Says it in chat and writes it to the log.
     *
     * <p>Meteor's {@code warning}/{@code error} only reach chat, which means a failed run
     * leaves nothing behind to read afterwards - the reason the first stall could only be
     * guessed at from phase transitions.
     */
    private void problem(String fmt, Object... args) {
        String message = String.format(fmt, args);
        warning(message);
        NewAddon.LOG.warn("[ElytraResupply] " + message);
    }
}
