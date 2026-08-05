package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import fr.nyuway.newaddon.modules.elytra.ResupplySettings;
import fr.nyuway.newaddon.utils.Combat;
import fr.nyuway.newaddon.utils.Containers;
import fr.nyuway.newaddon.utils.Enchants;
import fr.nyuway.newaddon.utils.Interactions;
import fr.nyuway.newaddon.utils.PlayerInv;
import fr.nyuway.newaddon.utils.ShulkerContents;
import fr.nyuway.newaddon.utils.SpotFinder;
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
        PLACE_CHEST, OPEN_CHEST, TAKE_SHULKER, PLACE_SHULKER, OPEN_SHULKER, TAKE_SUPPLIES,
        REPAIR, SWAP_ELYTRA,
        RETURN_SUPPLIES, BREAK_SHULKER, RETURN_SHULKER, BREAK_CHEST,
        TAKEOFF, WAIT_DISCONNECT, RESUME
    }

    /** Ticks any single phase may take before the run is treated as failed. */
    private static final int PHASE_TIMEOUT = 200;

    /**
     * How far a new elytra destination must be before it is believed to be a new trip rather
     * than Baritone choosing somewhere close by to put down.
     */
    private static final int RETARGET_MIN_DIST = 256;

    /** Ticks spent chasing a dropped item before writing it off and carrying on. */
    private static final int COLLECT_GIVEUP = 160;

    /** Ticks of jump attempts before a stuck takeoff is given up rather than hopping forever. */
    private static final int TAKEOFF_GIVEUP = 60;

    /** Failed takeoffs in a row before the module bows out and hands control back to the player. */
    private static final int MAX_TAKEOFF_FAILURES = 3;

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

    public ElytraResupply() {
        super(NewAddon.CATEGORY, "elytra-resupply",
            "Restocks fireworks and mends the elytra when Baritone lands mid-flight, then flies on.");
    }

    @Override
    public void onActivate() {
        reset();
        takeoffFailures = 0;
        if (!BaritoneBridge.isPresent()) {
            warning("This needs Meteor's Baritone fork; nothing will happen without it.");
        }
    }

    @Override
    public void onDeactivate() {
        reset();
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

        // The moment the player grabs the controls mid-routine, bow out entirely and give the
        // controls back. Scoped to an active routine so ordinary flight is left alone.
        if (cfg.releaseOnInput.get() && phase != Phase.IDLE && manualMovementRequested()) {
            info("Manual input detected - releasing control.");
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

        if (phase == Phase.IDLE) {
            watchForLanding();
            return;
        }

        if (++phaseTicks > PHASE_TIMEOUT) {
            if (phase == Phase.WAIT_DISCONNECT) {
                phaseTicks = PHASE_TIMEOUT; // keep waiting until landed
                return;
            }
            warning("Phase %s timed out; cleaning up.", phase);
            abort();
            return;
        }

        switch (phase) {
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
                cfg.autoTakeoff.get() ? Phase.TAKEOFF : Phase.RESUME);
            case TAKEOFF -> takeoff();
            case WAIT_DISCONNECT -> waitAndDisconnect();
            case SWAP_ELYTRA -> swapElytra();
            case RESUME -> resume();
            default -> { }
        }
    }

    /** True on ticks where an action is allowed, so clicks are paced rather than instant. */
    private boolean ready() {
        int delay = cfg.actionDelay.get();
        return delay <= 0 || phaseTicks % delay == 1;
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

        // Still airborne, whether or not the process admits to being active.
        if (elytraActive || !mc.player.onGround()) {
            elytraWasActive = true;
            return;
        }
        elytraWasActive = false;

        if (resumeTarget == null || !mc.player.onGround()) {
            reportIdle(elytraActive, dest);
            return;
        }

        boolean lowFireworks = PlayerInv.count(mc, Items.FIREWORK_ROCKET) <= cfg.minFireworks.get();
        boolean equippedDamaged = PlayerInv.wornElytraDurability(mc) < cfg.minElytraDurability.get();

        // Baritone only puts down when short on one of these; if neither is low it reached the
        // goal and there is nothing to do here.
        if (!lowFireworks && !equippedDamaged) {
            if (cfg.disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return;
        }

        // One setup, everything fixed: top fireworks back up to target and mend every elytra we
        // carry - not just whichever shortage tripped the landing.
        needFireworks = PlayerInv.count(mc, Items.FIREWORK_ROCKET) < cfg.targetFireworks.get();
        needMending = equippedDamaged || PlayerInv.findDamagedMendingElytra(mc) != -1;
        needElytraSwap = PlayerInv.wornElytraDurability(mc) <= 0;

        if (cfg.requireSilkTouch.get() && PlayerInv.findSilkTouch(mc) == -1) {
            error("Landed and low on supplies, but no Silk Touch pickaxe - not placing a chest.");
            resumeTarget = null;
            if (cfg.disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return;
        }
        if (!InvUtils.find(Items.ENDER_CHEST).found()) {
            error("Landed and low on supplies, but no ender chest on me.");
            resumeTarget = null;
            if (cfg.disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return;
        }

        info("Landed short. Resupplying (fireworks=%s, mending=%s).", needFireworks, needMending);
        to(Phase.PLACE_CHEST);
    }

    // --- phases -------------------------------------------------------------

    private void placeChest() {
        if (chestPos == null) {
            chestPos = new SpotFinder(mc, cfg.searchRadius.get(), cfg.voidClearance.get(), chestPos, shulkerPos).find(false);
            if (chestPos == null) {
                error("Nowhere safe to place; need solid ground with headroom.");
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
                if (PlayerInv.freeHotbarSlot(mc)) return;
                error("Could not get the ender chest into the hotbar.");
                abort();
            }
            return;
        }

        BlockUtils.place(chestPos, chest, true, 50);
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

        // Faces the block first. Opening a container the server thinks we are not looking at
        // is one of the plainest anticheat flags there is.
        Interactions.interact(mc, pos, cfg.silentRotations.get(), true);
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
                error("Ender chest has items but no shulker box in it.");
            } else {
                error("No shulker in ender chest contains fireworks, XP bottles, or spare elytras.");
            }
            abort();
            return;
        }

        if (!ready()) return;

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
                error("Nowhere safe to place the shulker.");
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
                if (PlayerInv.freeHotbarSlot(mc)) return;
                error("Could not get the shulker into the hotbar.");
                abort();
            }
            return;
        }

        BlockUtils.place(shulkerPos, shulker, true, 50);
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
                info("Swapping in a Mending elytra to repair.");
                phaseTicks = 0;
                return;
            }
            if (!equippedIsElytra) warning("No elytra equipped; nothing to repair.");
            else if (!equippedMends) warning("Equipped elytra lacks Mending; cannot repair with XP.");
            // Nothing to mend with here; move on to the fireworks pass.
            beginFireworksPass();
            to(Phase.RETURN_SUPPLIES);
            return;
        }

        if (equippedFull) {
            int damaged = PlayerInv.findDamagedMendingElytra(mc);
            if (damaged != -1 && PlayerInv.count(mc, Items.EXPERIENCE_BOTTLE) > 0) {
                swapEquippedWithInventorySlot(damaged);
                info("Elytra full; swapping in a damaged spare.");
                phaseTicks = 0;
                return;
            }
            info("All elytras fully mended.");
            // Repair fully done — now the fireworks pass may run.
            beginFireworksPass();
            to(Phase.RETURN_SUPPLIES);
            return;
        }

        FindItemResult bottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!bottle.found()) {
            if (!PlayerInv.moveToHotbar(mc, s -> s.is(Items.EXPERIENCE_BOTTLE))) {
                warning("Out of XP bottles; some elytras may still be damaged.");
                // No bottles left; give up mending and move on to fireworks.
                beginFireworksPass();
                to(PlayerInv.findSpareElytra(mc) != -1 ? Phase.SWAP_ELYTRA : Phase.RETURN_SUPPLIES);
            }
            return;
        }

        if (phaseTicks % 4 != 1) return;

        // Straight down, or the bottle sails off and the orbs land somewhere else.
        Rotations.rotate(mc.player.getYRot(), 90.0f, 50, () -> {
            InvUtils.swap(bottle.slot(), true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
        });
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
                problem("Drop not collected, walking to %s to get it.", pos);
                BaritoneBridge.pathTo(pos, 0);
            }

            // Chasing it forever would strand the trip. Write it off and carry on, rather
            // than letting the phase timeout tear down a run that is otherwise finished.
            if (phaseTicks > COLLECT_GIVEUP) {
                problem("Could not recover the drop at %s; carrying on without it.", pos);
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
    private void takeoff() {
        if (mc.player.isFallFlying()) {
            mc.options.keyJump.setDown(false);
            takeoffFailures = 0;
            to(Phase.RESUME);
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
            warning("Couldn't get airborne here; handing the route back to Baritone.");
            to(Phase.RESUME);
            return;
        }

        // Cycle the jump sequence; if still grounded after a full attempt, restart automatically.
        int tick = phaseTicks % 20;
        if (phaseTicks >= 20 && tick == 0 && mc.player.onGround()) {
            if (cfg.debug.get()) log("Still on ground after jump attempt; retrying takeoff.");
        }
        if (tick < 3) mc.options.keyJump.setDown(true);
        else if (tick < 6) mc.options.keyJump.setDown(false);
        else mc.options.keyJump.setDown(!mc.player.onGround());
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
        info("Resupplied. Flying on to %s.", target);
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
        if (chestPos != null && !mc.level.getBlockState(chestPos).isAir()) {
            to(Phase.BREAK_CHEST);
            return;
        }

        if (resumeTarget != null) {
            to(cfg.autoTakeoff.get() ? Phase.TAKEOFF : Phase.RESUME);
            return;
        }
        if (cfg.disconnectWhenDone.get()) {
            to(Phase.WAIT_DISCONNECT);
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
        return dest.distSqr(mc.player.blockPosition())
            > (double) RETARGET_MIN_DIST * RETARGET_MIN_DIST;
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
