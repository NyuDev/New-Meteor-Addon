package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.BaritoneBridge;
import fr.nyuway.newaddon.utils.Containers;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
        REPAIR,
        RETURN_SUPPLIES, BREAK_SHULKER, RETURN_SHULKER, BREAK_CHEST,
        RESUME
    }

    /** Ticks any single phase may take before the run is treated as failed. */
    private static final int PHASE_TIMEOUT = 200;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("Triggers");

    private final Setting<Integer> minFireworks = sgTriggers.add(new IntSetting.Builder()
        .name("min-fireworks")
        .description("Resupply once you are down to this many fireworks.")
        .defaultValue(8).min(0).max(128).sliderMin(0).sliderMax(64)
        .build());

    private final Setting<Integer> targetFireworks = sgTriggers.add(new IntSetting.Builder()
        .name("target-fireworks")
        .description("How many fireworks to carry away from a resupply.")
        .defaultValue(64).min(1).max(256).sliderMin(16).sliderMax(128)
        .build());

    private final Setting<Integer> minElytraDurability = sgTriggers.add(new IntSetting.Builder()
        .name("min-elytra-durability")
        .description("Mend the elytra once its remaining durability drops below this.")
        .defaultValue(80).min(1).max(400).sliderMin(10).sliderMax(300)
        .build());

    private final Setting<Integer> xpBottles = sgTriggers.add(new IntSetting.Builder()
        .name("xp-bottles")
        .description("How many XP bottles to take out for a mending session.")
        .defaultValue(64).min(1).max(256).sliderMin(16).sliderMax(128)
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
        .defaultValue(3).min(1).max(16).sliderMin(1).sliderMax(8)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log every phase transition. Leave this on until you trust it.")
        .defaultValue(true)
        .build());

    private Phase phase = Phase.IDLE;
    private int phaseTicks;

    private BlockPos chestPos;
    private BlockPos shulkerPos;
    /** Container slot the shulker came from, so it goes back exactly where it was. */
    private int shulkerHomeSlot = -1;
    /** Destination Baritone was flying to, captured before it gave up. */
    private BlockPos resumeTarget;
    /** What this run is for; a run may need only one of the two. */
    private boolean needFireworks, needMending;

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
        resumeTarget = null;
        needFireworks = needMending = false;
    }

    private void to(Phase next) {
        if (debug.get()) log("%s -> %s", phase, next);
        phase = next;
        phaseTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || !BaritoneBridge.isUsable()) return;

        if (phase == Phase.IDLE) {
            watchForLanding();
            return;
        }

        if (++phaseTicks > PHASE_TIMEOUT) {
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
            case BREAK_SHULKER -> breakAndCollect(shulkerPos, Items.SHULKER_BOX, Phase.RETURN_SHULKER);
            case RETURN_SHULKER -> returnShulker();
            case BREAK_CHEST -> breakAndCollect(chestPos, Items.ENDER_CHEST, Phase.RESUME);
            case RESUME -> resume();
            default -> { }
        }
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
            if (dest != null) resumeTarget = dest;
            return;
        }

        if (resumeTarget == null || !mc.player.onGround()) return;

        needFireworks = countInventory(Items.FIREWORK_ROCKET) <= minFireworks.get();
        needMending = elytraDamageLeft() < minElytraDurability.get();
        if (!needFireworks && !needMending) return;

        if (requireSilkTouch.get() && findSilkTouch() == -1) {
            error("Landed and low on supplies, but no Silk Touch pickaxe - not placing a chest.");
            resumeTarget = null;
            return;
        }
        if (!InvUtils.find(Items.ENDER_CHEST).found()) {
            error("Landed and low on supplies, but no ender chest on me.");
            resumeTarget = null;
            return;
        }

        info("Landed short. Resupplying (fireworks=%s, mending=%s).", needFireworks, needMending);
        to(Phase.PLACE_CHEST);
    }

    // --- phases -------------------------------------------------------------

    private void placeChest() {
        if (chestPos == null) {
            chestPos = findSpot();
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

        AbstractContainerMenu menu = mc.player.containerMenu;

        // Already holding one from a previous pass through this phase.
        if (InvUtils.find(Items.SHULKER_BOX).found() && shulkerHomeSlot != -1) {
            mc.player.closeContainer();
            to(Phase.PLACE_SHULKER);
            return;
        }

        int from = Containers.findInContainer(menu, Items.SHULKER_BOX);
        if (from == -1) {
            error("No shulker box in the ender chest.");
            abort();
            return;
        }

        int to = Containers.findEmptyInPlayerPart(menu);
        if (to == -1) {
            error("No free inventory slot for the shulker.");
            abort();
            return;
        }

        shulkerHomeSlot = from;
        Containers.moveStack(menu, from, to);
    }

    private void placeShulker() {
        if (shulkerPos == null) {
            shulkerPos = findSpot();
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

        FindItemResult shulker = InvUtils.findInHotbar(Items.SHULKER_BOX);
        if (!shulker.found()) {
            if (!moveToHotbar(Items.SHULKER_BOX)) {
                error("Could not get the shulker into the hotbar.");
                abort();
            }
            return;
        }

        BlockUtils.place(shulkerPos, shulker, 50);
    }

    private void takeSupplies() {
        if (!isContainerOpen()) {
            to(Phase.OPEN_SHULKER);
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;

        if (needMending && countInventory(Items.EXPERIENCE_BOTTLE) < xpBottles.get()) {
            if (pullOne(menu, Items.EXPERIENCE_BOTTLE)) return;
        }
        if (needFireworks && countInventory(Items.FIREWORK_ROCKET) < targetFireworks.get()) {
            if (pullOne(menu, Items.FIREWORK_ROCKET)) return;
        }

        mc.player.closeContainer();
        to(needMending ? Phase.REPAIR : Phase.RETURN_SUPPLIES);
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
                to(Phase.RETURN_SUPPLIES);
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

        if (Containers.slotHas(menu, shulkerHomeSlot, Items.SHULKER_BOX)) {
            mc.player.closeContainer();
            to(Phase.BREAK_CHEST);
            return;
        }

        int from = Containers.findInPlayerPart(menu, Items.SHULKER_BOX);
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
    private void breakAndCollect(BlockPos pos, Item expected, Phase next) {
        if (pos == null) {
            to(next);
            return;
        }

        if (mc.level.getBlockState(pos).isAir()) {
            // Give the item entity a moment to fly into us before declaring success.
            if (phaseTicks < 20) return;
            if (!InvUtils.find(expected).found()) warning("Broke it but did not pick it back up.");
            to(next);
            return;
        }

        if (expected == Items.ENDER_CHEST) {
            int silk = findSilkTouch();
            if (silk != -1) InvUtils.swap(silk, false);
        }

        BlockUtils.breakBlock(pos, true);
    }

    private void resume() {
        BlockPos target = resumeTarget;
        reset();

        if (target == null) return;
        info("Resupplied. Flying on to %s.", target);
        BaritoneBridge.elytraPathTo(target);
    }

    /** Tries to put things back and pick up what we placed, whatever went wrong. */
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
        reset();
    }

    // --- helpers ------------------------------------------------------------

    private boolean isContainerOpen() {
        return mc.player.containerMenu != mc.player.inventoryMenu
            && Containers.containerSize(mc.player.containerMenu) > 0;
    }

    /** Moves one stack of an item from the container into us. Returns true if it acted. */
    private boolean pullOne(AbstractContainerMenu menu, Item item) {
        int from = Containers.findInContainer(menu, item);
        if (from == -1) return false;

        int to = Containers.findEmptyInPlayerPart(menu);
        if (to == -1) return false;

        Containers.moveStack(menu, from, to);
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
        FindItemResult found = InvUtils.find(item);
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
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.ELYTRA)) return stack.getMaxDamage() - stack.getDamageValue();
        }
        return Integer.MAX_VALUE;
    }

    /** Hotbar slot of a Silk Touch pickaxe, or -1. */
    private int findSilkTouch() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (hasSilkTouch(stack)) return i;
        }
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
     * A spot beside us to put a block: air, with air above it, standing on solid ground that
     * goes down at least {@code void-clearance} blocks. That last check is what stops a
     * shulker full of supplies being dropped off a ledge.
     */
    private BlockPos findSpot() {
        BlockPos feet = mc.player.blockPosition();

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = feet.relative(dir);
            if (candidate.equals(chestPos) || candidate.equals(shulkerPos)) continue;

            if (!mc.level.getBlockState(candidate).isAir()) continue;
            if (!mc.level.getBlockState(candidate.above()).isAir()) continue;

            boolean solid = true;
            for (int d = 1; d <= voidClearance.get(); d++) {
                if (mc.level.getBlockState(candidate.below(d)).isAir()) {
                    solid = false;
                    break;
                }
            }
            if (solid) return candidate;
        }

        return null;
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[ElytraResupply] " + String.format(fmt, args));
    }
}
