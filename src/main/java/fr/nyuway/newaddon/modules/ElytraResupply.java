package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import fr.nyuway.newaddon.utils.Containers;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("Triggers");

    private final Setting<Integer> minFireworks = sgTriggers.add(new IntSetting.Builder()
        .name("min-fireworks")
        .description("Resupply once you are down to this many fireworks.")
        .defaultValue(8).min(0).max(128).sliderMin(0).sliderMax(64)
        .build());

    private final Setting<Integer> targetFireworks = sgTriggers.add(new IntSetting.Builder()
        .name("target-fireworks")
        .description("How many fireworks to carry away. Several stacks: one is barely a leg " +
                     "of a long crossing, and the whole point is not to land again shortly.")
        .defaultValue(320).min(1).max(1024).sliderMin(64).sliderMax(640)
        .build());

    private final Setting<Integer> minElytraDurability = sgTriggers.add(new IntSetting.Builder()
        .name("min-elytra-durability")
        .description("Mend the elytra once its remaining durability drops below this.")
        .defaultValue(80).min(1).max(400).sliderMin(10).sliderMax(300)
        .build());

    private final Setting<Integer> xpBottles = sgTriggers.add(new IntSetting.Builder()
        .name("xp-bottles")
        .description("How many XP bottles to take out for a mending session. Leftovers go " +
                     "back in the shulker, so taking plenty costs nothing.")
        .defaultValue(256).min(1).max(1024).sliderMin(64).sliderMax(640)
        .build());

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks between two actions. Firing container clicks and placements as " +
                     "fast as the client allows looks nothing like a player and gives the " +
                     "server no time to answer.")
        .defaultValue(4).min(0).max(40).sliderMin(0).sliderMax(20)
        .build());

    private final Setting<Integer> containerSettle = sgGeneral.add(new IntSetting.Builder()
        .name("container-settle")
        .description("Ticks to wait after a container opens before reading it. Contents arrive " +
                     "in a packet after the menu itself, so reading straight away sees an " +
                     "empty chest.")
        .defaultValue(10).min(0).max(60).sliderMin(2).sliderMax(30)
        .build());

    private final Setting<Boolean> autoTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Jump and open the elytra after resupplying. Baritone is handed the " +
                     "destination but will not get you off the ground by itself.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> requireSilkTouch = sgGeneral.add(new BoolSetting.Builder()
        .name("require-silk-touch")
        .description("Refuse to place the ender chest unless a Silk Touch pickaxe is on you. " +
                     "Without one the chest breaks into obsidian and is lost.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> voidClearance = sgGeneral.add(new IntSetting.Builder()
        .name("void-clearance")
        .description("Solid blocks that must sit under the spot before anything is placed, so " +
                     "nothing you drop falls into the void or lava.")
        .defaultValue(2).min(1).max(16).sliderMin(1).sliderMax(8)
        .build());

    private final Setting<Double> searchRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("search-radius")
        .description("How far around you to look for somewhere to set up. Must stay within " +
                     "reach, since the blocks have to be clicked.")
        .defaultValue(4.0).min(1.5).max(5.0).sliderMin(2.0).sliderMax(5.0)
        .build());

    private final Setting<Boolean> hotbarFirst = sgGeneral.add(new BoolSetting.Builder()
        .name("fireworks-to-hotbar")
        .description("Put fireworks in the hotbar before the main inventory. Baritone can only " +
                     "fly with what it can reach, so a stack buried in storage is no use.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pauseOnKillAura = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-killaura")
        .description("Freeze wherever it is while KillAura is fighting, and pick up from the " +
                     "same phase afterwards.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log every phase transition. Leave this on until you trust it.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> disconnectWhenDone = sgGeneral.add(new BoolSetting.Builder()
        .name("disconnect-when-done")
        .description("Disconnect once the trip ends or when unable to continue (waits until landed).")
        .defaultValue(false)
        .build());

    private Phase phase = Phase.IDLE;
    private int phaseTicks;

    /** Reused by the spot search so scanning allocates nothing. */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    /** True while Baritone is walking us onto a drop we failed to collect from where we stood. */
    private boolean walkingToDrop;
    /** Whether the elytra process was flying last tick, to tell a new trip from a retarget. */
    private boolean elytraWasActive;
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

    public ElytraResupply() {
        super(NewAddon.CATEGORY, "elytra-resupply",
            "Restocks fireworks and mends the elytra when Baritone lands mid-flight, then flies on.");
    }

    @Override
    public void onActivate() {
        reset();
        if (!BaritoneBridge.isPresent()) {
            warning("This needs Meteor's Baritone fork; nothing will happen without it.");
        }
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        chestPos = null;
        shulkerPos = null;
        shulkerHomeSlot = -1;
        pickaxeHomeSlot = -1;
        resumeTarget = null;
        needFireworks = needMending = needElytraSwap = false;
        triedShulkerSlots.clear();

        if (walkingToDrop) {
            BaritoneBridge.cancel();
            walkingToDrop = false;
        }
    }

    private void to(Phase next) {
        // Any phase change ends a walk-to-drop; the next phase drives its own movement.
        if (walkingToDrop && next != phase) {
            BaritoneBridge.cancel();
            walkingToDrop = false;
        }
        if (debug.get()) log("%s -> %s", phase, next);
        phase = next;
        phaseTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || !BaritoneBridge.isUsable()) return;

        // Freeze rather than reset: phaseTicks is not advanced either, so a long fight does
        // not trip the phase timeout and throw away a run that was going fine.
        if (pauseOnKillAura.get() && Modules.get().isActive(KillAura.class)) {
            if (isContainerOpen()) mc.player.closeContainer();
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
                autoTakeoff.get() ? Phase.TAKEOFF : Phase.RESUME);
            case TAKEOFF -> takeoff();
            case WAIT_DISCONNECT -> waitAndDisconnect();
            case SWAP_ELYTRA -> swapElytra();
            case RESUME -> resume();
            default -> { }
        }
    }

    /** True on ticks where an action is allowed, so clicks are paced rather than instant. */
    private boolean ready() {
        int delay = actionDelay.get();
        return delay <= 0 || phaseTicks % delay == 1;
    }

    // --- trigger ------------------------------------------------------------

    /**
     * Watches an elytra flight and steps in once it has stopped short of its destination.
     * The destination is captured while the process is still running, because once it gives
     * up there is nothing left to ask.
     */
    private void watchForLanding() {
        if (BaritoneBridge.isElytraActive()) {
            BlockPos dest = BaritoneBridge.elytraDestination();

            if (dest != null) {
                if (!elytraWasActive || resumeTarget == null) {
                    // Start of a flight: whatever it is aiming at now is what was asked for.
                    resumeTarget = dest;
                    log("travel goal noted: %s", dest);
                } else if (!dest.equals(resumeTarget)
                    && dest.distSqr(mc.player.blockPosition()) > RETARGET_MIN_DIST * RETARGET_MIN_DIST) {
                    // A genuinely new long-range goal, so honour it. Anything nearby is
                    // Baritone picking somewhere to set down, and taking that as the trip's
                    // destination is what made it try to land where it already was.
                    resumeTarget = dest;
                    log("travel goal changed: %s", dest);
                }
            }

            elytraWasActive = true;
            return;
        }
        elytraWasActive = false;

        if (resumeTarget == null || !mc.player.onGround()) return;

        needFireworks = countInventory(Items.FIREWORK_ROCKET) <= minFireworks.get();
        needMending = elytraDamageLeft() < minElytraDurability.get();
        needElytraSwap = elytraDamageLeft() <= 0;
        if (!needFireworks && !needMending) {
            if (disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return;
        }

        if (requireSilkTouch.get() && findSilkTouch() == -1) {
            error("Landed and low on supplies, but no Silk Touch pickaxe - not placing a chest.");
            resumeTarget = null;
            if (disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return;
        }
        if (!InvUtils.find(Items.ENDER_CHEST).found()) {
            error("Landed and low on supplies, but no ender chest on me.");
            resumeTarget = null;
            if (disconnectWhenDone.get()) to(Phase.WAIT_DISCONNECT);
            return;
        }

        info("Landed short. Resupplying (fireworks=%s, mending=%s).", needFireworks, needMending);
        to(Phase.PLACE_CHEST);
    }

    // --- phases -------------------------------------------------------------

    private void placeChest() {
        if (chestPos == null) {
            chestPos = findSpot(false);
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
            if (!moveToHotbar(Items.ENDER_CHEST)) {
                error("Could not get the ender chest into the hotbar.");
                abort();
            }
            return;
        }

        BlockUtils.place(chestPos, chest, 50);
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
        BlockUtils.interact(
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false),
            InteractionHand.MAIN_HAND, true);
    }

    private void takeShulker() {
        if (!isContainerOpen()) {
            to(Phase.OPEN_CHEST);
            return;
        }

        // Contents arrive after the menu does; reading immediately sees an empty chest.
        if (phaseTicks < containerSettle.get()) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        // Already holding one from a previous pass through this phase.
        if (shulkerHomeSlot != -1 && Containers.findInPlayerPart(menu, Containers::isShulker) != -1) {
            mc.player.closeContainer();
            to(Phase.PLACE_SHULKER);
            return;
        }

        int from = findUsefulShulker(menu);
        if (from == -1) {
            // Keep waiting on a slow sync; only abort once we know the chest has content.
            if (Containers.isContainerEmpty(menu)) return;
            // We've already emptied at least one shulker this trip; press on with what we got.
            if (!triedShulkerSlots.isEmpty()) {
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
        triedShulkerSlots.add(from);
        Containers.moveStack(menu, from, dest);
    }

    private void placeShulker() {
        if (shulkerPos == null) {
            shulkerPos = findSpot(true);
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

        FindItemResult shulker = InvUtils.findInHotbar(Containers::isShulker);
        if (!shulker.found()) {
            if (!moveToHotbar(Containers::isShulker)) {
                error("Could not get the shulker into the hotbar.");
                abort();
            }
            return;
        }

        BlockUtils.place(shulkerPos, shulker, 50);
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

        if (phaseTicks < containerSettle.get()) return;
        if (!ready()) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        if (needMending && countInventory(Items.EXPERIENCE_BOTTLE) < xpBottles.get()) {
            if (pullOne(menu, Items.EXPERIENCE_BOTTLE, false)) return;
        }
        if (needFireworks && countInventory(Items.FIREWORK_ROCKET) < targetFireworks.get()) {
            if (pullOne(menu, Items.FIREWORK_ROCKET, hotbarFirst.get())) return;
        }

        // Proactively grab a spare elytra if the current one is broken and we don't already carry one.
        if (needElytraSwap && findSpareElytra() == -1) {
            if (pullOne(menu, Items.ELYTRA, false)) return;
        }

        mc.player.closeContainer();
        if (stillNeedSupplies()) {
            // This box didn't have everything; skip returning supplies (we want to keep what we got)
            // and go break/return it so the next iteration can open another shulker.
            to(Phase.BREAK_SHULKER);
        } else if (needMending && countInventory(Items.EXPERIENCE_BOTTLE) > 0) {
            to(Phase.REPAIR);
        } else {
            to(Phase.RETURN_SUPPLIES);
        }
    }

    /**
     * Throws XP bottles straight down so the orbs land on us and mend the elytra.
     * Requires the elytra to actually carry Mending; without it this would empty the
     * bottles for nothing, so it stops as soon as durability stops improving.
     */
    private void repair() {
        if (elytraDamageLeft() >= minElytraDurability.get()) {
            info("Elytra mended.");
            to(Phase.RETURN_SUPPLIES);
            return;
        }

        FindItemResult bottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!bottle.found()) {
            if (!moveToHotbar(Items.EXPERIENCE_BOTTLE)) {
                warning("Out of XP bottles; elytra still damaged.");
                to(findSpareElytra() != -1 ? Phase.SWAP_ELYTRA : Phase.RETURN_SUPPLIES);
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
                to(needMending && countInventory(Items.EXPERIENCE_BOTTLE) > 0 ? Phase.REPAIR : Phase.BREAK_CHEST);
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
        if (phaseTicks <= 1) collectBaseline = countMatching(expected);

        if (mc.level.getBlockState(pos).isAir()) {
            // Give the item entity a moment to fly into us before declaring anything.
            if (phaseTicks < 20) return;

            if (countMatching(expected) > collectBaseline) {
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

        BlockUtils.breakBlock(pos, true);
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
            to(Phase.RESUME);
            return;
        }

        if (elytraDamageLeft() <= 0) {
            warning("Elytra has no durability left; not taking off.");
            mc.options.keyJump.setDown(false);
            to(Phase.RESUME);
            return;
        }

        // Cycle the jump sequence; if still grounded after a full attempt, restart automatically.
        int tick = phaseTicks % 20;
        if (phaseTicks >= 20 && tick == 0 && mc.player.onGround()) {
            if (debug.get()) log("Still on ground after jump attempt; retrying takeoff.");
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
                if (!stack.isEmpty() && hasSilkTouch(stack)) {
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
            to(autoTakeoff.get() ? Phase.TAKEOFF : Phase.RESUME);
            return;
        }
        if (disconnectWhenDone.get()) {
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

    private boolean moveToHotbar(Item item) {
        return moveToHotbar(stack -> stack.is(item));
    }

    private boolean moveToHotbar(Predicate<ItemStack> match) {
        FindItemResult found = InvUtils.find(match);
        if (!found.found()) return false;
        if (found.isHotbar()) return true;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                InvUtils.move().from(found.slot()).toHotbar(i);
                return true;
            }
        }
        return false;
    }

    /** Total items on the player matching a predicate, counting stack sizes. */
    private int countMatching(Predicate<ItemStack> match) {
        var inv = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && match.test(stack)) total += stack.getCount();
        }
        return total;
    }

    private int countInventory(Item item) {
        var inv = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Remaining durability of the worn elytra, or a large number when none is worn. */
    private int elytraDamageLeft() {
        ItemStack equipped = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (equipped.is(Items.ELYTRA)) return equipped.getMaxDamage() - equipped.getDamageValue();
        return Integer.MAX_VALUE;
    }

    /**
     * Inventory slot of a Silk Touch tool, anywhere on the player, or -1.
     *
     * <p>Scans the whole inventory rather than just the hotbar: a pickaxe kept in storage is
     * still a pickaxe, and refusing to start because it was not on the bar was needless.
     * {@link #silkTouchInHotbar()} is what brings it down when it is actually needed.
     */
    private int findSilkTouch() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (hasSilkTouch(stack)) return i;
        }
        return -1;
    }

    /**
     * Hotbar slot holding a Silk Touch tool, bringing it down from storage if needed.
     *
     * @return the hotbar slot, or -1 if there is no such tool or no room for it
     */
    private int silkTouchInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && hasSilkTouch(stack)) return i;
        }

        int stored = findSilkTouch();
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

    /**
     * The one place in this addon that needs per-version source.
     *
     * <p>Enchantments stopped being plain objects in 1.21: {@code Enchantments.SILK_TOUCH} is
     * an {@code Enchantment} on 1.20.x but a {@code ResourceKey} afterwards, and
     * {@code EnchantmentHelper} changed to match. Meteor's own helper follows the version it
     * was built against, so there is no single call that satisfies both - hence the split.
     */
    private boolean hasSilkTouch(ItemStack stack) {
        //? if >=1.21 {
        return Utils.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH) > 0;
        //?} else {
        /*return net.minecraft.world.item.enchantment.EnchantmentHelper
            .getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
        *///?}
    }

    /**
     * A spot to put a block: air, with air above it, over ground that stays solid for
     * {@code void-clearance} blocks. That last check is what stops a shulker full of supplies
     * being dropped off a ledge.
     *
     * <p>Searches the whole reachable area nearest-first rather than only the four blocks
     * touching us. Checking just those meant the chest took one of them and the shulker then
     * had nowhere to go, which is exactly how a run stalled after placing the chest.
     */
    private BlockPos findSpot(boolean forShulker) {
        // Prefer somewhere ringed by solid ground, so a dropped item cannot roll off an edge
        // into the void. Fall back to any valid spot rather than refusing to work: on a
        // narrow ledge a slightly risky placement still beats a stranded flight.
        BlockPos safe = findSpot(forShulker, true);
        return safe != null ? safe : findSpot(forShulker, false);
    }

    private BlockPos findSpot(boolean forShulker, boolean requireSurrounded) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int radius = Mth.ceil(searchRadius.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    scratch.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!isUsableSpot(scratch, forShulker)) continue;
                    if (requireSurrounded && !isRingedByGround(scratch)) continue;

                    // Must be close enough to actually click, not just close enough to see.
                    double dist = mc.player.getEyePosition()
                        .distanceToSqr(scratch.getX() + 0.5, scratch.getY() + 0.5, scratch.getZ() + 0.5);
                    if (dist > searchRadius.get() * searchRadius.get()) continue;

                    if (dist < bestDist) {
                        bestDist = dist;
                        best = scratch.immutable();
                    }
                }
            }
        }

        return best;
    }

    /** True when the floor extends under all eight blocks around the spot, edge included. */
    private boolean isRingedByGround(BlockPos pos) {
        BlockPos base = pos.below();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockState floor = mc.level.getBlockState(base.offset(dx, 0, dz));
                if (floor.isAir() || !floor.getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * @param forShulker apply the extra rules a shulker box needs to be openable at all
     */
    private boolean isUsableSpot(BlockPos pos, boolean forShulker) {
        if (pos.equals(chestPos) || pos.equals(shulkerPos)) return false;
        if (pos.equals(mc.player.blockPosition())) return false;
        if (pos.equals(mc.player.blockPosition().above())) return false;

        if (!mc.level.getBlockState(pos).isAir()) return false;
        if (!mc.level.getBlockState(pos.above()).isAir()) return false;

        // Solid floor directly under it, so the block gets placed against the ground and a
        // shulker ends up facing up. Placed against a wall - or against the ender chest -
        // it faces sideways and vanilla refuses to open it, because the lid has nowhere
        // to go. That is what stalled OPEN_SHULKER until the phase timed out.
        BlockState floor = mc.level.getBlockState(pos.below());
        if (floor.isAir() || !floor.getFluidState().isEmpty()) return false;

        for (int d = 1; d <= voidClearance.get(); d++) {
            if (mc.level.getBlockState(pos.below(d)).isAir()) return false;
        }

        if (forShulker) {
            // Keep clear of the chest, or the placement clicks its face and the shulker
            // orients against it.
            if (chestPos != null && pos.distSqr(chestPos) < 3.0) return false;

            // The lid opens into the block above; vanilla's own check fails if anything
            // collides there, including us standing on top of it.
            if (mc.player.getBoundingBox().intersects(new AABB(pos.above()))) return false;
        }

        return true;
    }

    private void swapElytra() {
        if (isContainerOpen()) {
            mc.player.closeContainer();
            return;
        }
        if (!ready()) return;

        int spare = findSpareElytra();
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
        boolean lookFireworks = needFireworks && countInventory(Items.FIREWORK_ROCKET) < targetFireworks.get();
        boolean lookBottles = needMending && countInventory(Items.EXPERIENCE_BOTTLE) < xpBottles.get();
        boolean lookElytra = needElytraSwap && findSpareElytra() == -1;
        if (!lookFireworks && !lookBottles && !lookElytra) return false;
        //? if >=1.21 {
        var contents = shulker.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return false;
        for (ItemStack stack : contents.nonEmptyItems()) {
            if (lookFireworks && stack.is(Items.FIREWORK_ROCKET)) return true;
            if (lookBottles && stack.is(Items.EXPERIENCE_BOTTLE)) return true;
            if (lookElytra && stack.is(Items.ELYTRA)) return true;
        }
        return false;
        //?} else {
        /*var tag = shulker.getTag();
        if (tag == null || !tag.contains("BlockEntityTag")) return false;
        var list = tag.getCompound("BlockEntityTag").getList("Items", 10);
        for (int i = 0; i < list.size(); i++) {
            var id = list.getCompound(i).getString("id");
            if (lookFireworks && id.equals("minecraft:firework_rocket")) return true;
            if (lookBottles && id.equals("minecraft:experience_bottle")) return true;
            if (lookElytra && id.equals("minecraft:elytra")) return true;
        }
        return false;
        *///?}
    }

    /** Finds a spare elytra with remaining durability in the main inventory (not armor slot). */
    private int findSpareElytra() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.ELYTRA) && (stack.getMaxDamage() - stack.getDamageValue()) > 0) return i;
        }
        return -1;
    }

    /** True while any target is still under quota; drives the multi-shulker loop. */
    private boolean stillNeedSupplies() {
        if (needFireworks && countInventory(Items.FIREWORK_ROCKET) < targetFireworks.get()) return true;
        if (needMending && countInventory(Items.EXPERIENCE_BOTTLE) < xpBottles.get()) return true;
        if (needElytraSwap && findSpareElytra() == -1) return true;
        return false;
    }

    /** Called from takeShulker when no untried shulker in the ender chest can help any further. */
    private void finishAfterGathering() {
        if (needMending && countInventory(Items.EXPERIENCE_BOTTLE) > 0) {
            to(Phase.REPAIR);
        } else {
            to(Phase.BREAK_CHEST);
        }
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
