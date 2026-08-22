package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.Containers;
import fr.nyuway.newaddon.utils.Enchants;
import fr.nyuway.newaddon.utils.Interactions;
import fr.nyuway.newaddon.utils.PlayerInv;
import fr.nyuway.newaddon.utils.ShulkerContents;
import fr.nyuway.newaddon.utils.SpotFinder;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * ObsidianSupply - keeps obsidian and ender chests in the pack, out of the pack itself.
 *
 * <h2>Where obsidian comes from</h2>
 * An ender chest broken without Silk Touch drops eight obsidian, and an ender chest is the one
 * container that follows you everywhere. So the supply chain is a circle that never needs a
 * base: place a chest, break it with the wrong pickaxe on purpose, pick up eight obsidian, and
 * do it again. Eight at a time is not much, and it is available at the bottom of the world with
 * nothing else in sight, which is the point.
 *
 * <p>When the chests themselves run out, the ender chest is where the next ones are - loose if
 * you keep them loose, or in a shulker if you keep them tidily. Both are handled, and the shulker
 * goes back where it was found: a shulker of ender chests left on the ground is the trip's whole
 * supply left on the ground.
 *
 * <h2>Why the pickaxe matters</h2>
 * Silk Touch is exactly the wrong tool here - it gives the chest back rather than the obsidian,
 * so the loop would run for ever converting one chest into one chest. The pickaxe is checked and
 * swapped before a chest is broken, and swapped back after.
 *
 * <h2>Waste</h2>
 * Everything placed is picked up again, and nothing is placed while the last thing is still on
 * the ground. Obsidian is not a resource this can afford to leave lying about: the reason to be
 * doing any of this is that there is no more of it.
 */
public class ObsidianSupply extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgKeys = settings.createGroup("Keys");

    private final Setting<Boolean> autoObsidian = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-obsidian")
        .description("Make more obsidian on its own when it runs low.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> minObsidian = sgGeneral.add(new IntSetting.Builder()
        .name("min-obsidian")
        .description("Start making more at this many. Not zero: running out mid-job means " +
                     "stopping mid-job, and the chest that would have fixed it takes a while.")
        .defaultValue(32).min(0).max(512).sliderRange(0, 128)
        .build());

    private final Setting<Integer> targetObsidian = sgGeneral.add(new IntSetting.Builder()
        .name("target-obsidian")
        .description("How much to carry away. Eight per chest, so this is really a number of " +
                     "chests dressed up as a number of blocks.")
        .defaultValue(128).min(8).max(1024).sliderRange(16, 320)
        .build());

    private final Setting<Boolean> autoChests = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-chests")
        .description("Fetch more ender chests from the ender chest when they run low. This is " +
                     "the one that must not fail: no chests means no obsidian and no way to get " +
                     "any, which is a trip over.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> minChests = sgGeneral.add(new IntSetting.Builder()
        .name("min-chests")
        .description("Fetch more at this many. Kept well above zero on purpose - the fetching " +
                     "itself needs a chest to place and open, so hitting zero is a hole you " +
                     "cannot climb out of.")
        .defaultValue(8).min(2).max(32).sliderRange(2, 16)
        .build());

    private final Setting<Integer> targetChests = sgGeneral.add(new IntSetting.Builder()
        .name("target-chests")
        .description("How many to carry. A stack is the most an ender chest will hand over in " +
                     "one go without this becoming an inventory-sorting exercise.")
        .defaultValue(64).min(8).max(64).sliderRange(16, 64)
        .build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Say what it is doing and what it ended up with.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Write every phase change to the game log.")
        .defaultValue(false)
        .build());

    private final Setting<Keybind> obsidianKey = sgKeys.add(new KeybindSetting.Builder()
        .name("refill-obsidian")
        .description("Make obsidian now, whatever the count says.")
        .defaultValue(Keybind.none())
        .build());

    private final Setting<Keybind> chestKey = sgKeys.add(new KeybindSetting.Builder()
        .name("refill-chests")
        .description("Fetch ender chests now, whatever the count says.")
        .defaultValue(Keybind.none())
        .build());

    // --- the machine -----------------------------------------------------------

    private enum Phase {
        IDLE,
        /** Making obsidian: put a chest down, break it, pick up what it leaves. */
        PLACE_CHEST, BREAK_CHEST, COLLECT,
        /** Fetching chests: put one down, open it, find them, take them, tidy up. */
        PLACE_STORE, OPEN_STORE, TAKE_CHESTS,
        /** The shulker route, when the chests are inside one. */
        TAKE_SHULKER, PLACE_SHULKER, OPEN_SHULKER, TAKE_FROM_SHULKER, BREAK_SHULKER, RETURN_SHULKER,
        /** Taking the placed chest back up. */
        RECOVER
    }

    /** Ticks a phase may take before the run is written off and tidied up. */
    private static final int PHASE_TIMEOUT = 20 * 20;

    /** Ticks between two actions, so a run is a sequence rather than a burst. */
    private static final int PACE = 4;

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int paceTicks;

    /** Where the chest or the shulker was put down. */
    private BlockPos chestPos;
    private BlockPos shulkerPos;

    /** Which run this is, since the two share almost all of their steps. */
    private boolean forObsidian;

    /** The hotbar slot the pickaxe came from, and the one it went to. */
    private int pickaxeHome = -1;

    /** Where the shulker came from in the ender chest, so it goes back there. */
    private int shulkerHome = -1;

    private boolean obsidianHeld, chestHeld;

    public ObsidianSupply() {
        super(NewAddon.CATEGORY, "obsidian-supply",
            "Turns ender chests into obsidian, and the ender chest into more ender chests.");
    }

    public static ObsidianSupply get() {
        return Modules.get() == null ? null : Modules.get().get(ObsidianSupply.class);
    }

    /** Whether a run is under way, so AutoBreak knows to leave the world alone. */
    public boolean isBusy() {
        return phase != Phase.IDLE;
    }

    /**
     * Asks for a run, from another module rather than from a key.
     *
     * @param obsidian true for obsidian, false for ender chests
     * @return true when a run is now under way, including one that was already going
     */
    public boolean request(boolean obsidian) {
        if (!isActive()) return false;
        if (isBusy()) return true;

        start(obsidian);
        return isBusy();
    }

    @Override
    public String getInfoString() {
        if (phase == Phase.IDLE) return obsidian() + " obby";
        return phase.name().toLowerCase().replace('_', ' ');
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        returnPickaxe();

        phase = Phase.IDLE;
        phaseTicks = 0;
        chestPos = null;
        shulkerPos = null;
        shulkerHome = -1;
        PlayerInv.closeInventory(mc);
    }

    private void to(Phase next) {
        if (debug.get()) log("%s -> %s", phase, next);
        phase = next;
        phaseTicks = 0;
    }

    // --- counting ----------------------------------------------------------------

    private int obsidian() {
        return mc.player == null ? 0 : PlayerInv.count(mc, Items.OBSIDIAN);
    }

    private int chests() {
        return mc.player == null ? 0 : PlayerInv.count(mc, Items.ENDER_CHEST);
    }

    // --- the tick ----------------------------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        boolean wantObsidian = pressed(obsidianKey, obsidianHeld);
        obsidianHeld = down(obsidianKey);
        boolean wantChests = pressed(chestKey, chestHeld);
        chestHeld = down(chestKey);

        if (phase == Phase.IDLE) {
            // Chests first, always. Obsidian is made out of them, so being out of chests is the
            // deeper of the two problems and fixing obsidian first would spend the last one.
            if (wantChests || (autoChests.get() && chests() < minChests.get())) {
                start(false);
            } else if (wantObsidian || (autoObsidian.get() && obsidian() < minObsidian.get())) {
                start(true);
            }
            return;
        }

        if (++phaseTicks > PHASE_TIMEOUT) {
            warning("Supply run timed out in %s; tidying up.", phase);
            recoverOrStop();
            return;
        }

        // Paced. Every one of these is a click the server counts, and a burst of them on 2b2t is
        // the connection rather than the inventory.
        if (++paceTicks < PACE) return;
        paceTicks = 0;

        switch (phase) {
            case PLACE_CHEST, PLACE_STORE -> placeChest();
            case BREAK_CHEST -> breakChest();
            case COLLECT -> collect();
            case OPEN_STORE -> openPlaced(chestPos, Phase.TAKE_CHESTS);
            case TAKE_CHESTS -> takeChests();
            case TAKE_SHULKER -> takeShulker();
            case PLACE_SHULKER -> placeShulker();
            case OPEN_SHULKER -> openPlaced(shulkerPos, Phase.TAKE_FROM_SHULKER);
            case TAKE_FROM_SHULKER -> takeFromShulker();
            case BREAK_SHULKER -> breakShulker();
            case RETURN_SHULKER -> returnShulker();
            case RECOVER -> recover();
            default -> { }
        }
    }

    private boolean down(Setting<Keybind> key) {
        return key.get().isSet() && key.get().isPressed();
    }

    private boolean pressed(Setting<Keybind> key, boolean was) {
        return down(key) && !was && mc.screen == null;
    }

    private void start(boolean obsidianRun) {
        if (chests() == 0) {
            warning("No ender chests at all; nothing can be done from here.");
            return;
        }

        forObsidian = obsidianRun;
        if (notify.get()) {
            info(obsidianRun ? "Making obsidian." : "Fetching ender chests.");
        }
        to(obsidianRun ? Phase.PLACE_CHEST : Phase.PLACE_STORE);
    }

    // --- the shared steps --------------------------------------------------------

    /** Puts an ender chest down somewhere it can be reached and opened. */
    private void placeChest() {
        if (chestPos != null && isChest(chestPos)) {
            to(forObsidian ? Phase.BREAK_CHEST : Phase.OPEN_STORE);
            return;
        }

        // Still usable, and still empty: put the next one straight back into it. This is the
        // rebreak - the square has just had a block taken out of it, so the next one placed
        // there comes apart almost at once.
        if (chestPos != null && mc.level.getBlockState(chestPos).isAir()
            && !mc.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(chestPos))) {
            FindItemResult again = InvUtils.findInHotbar(Items.ENDER_CHEST);
            if (again.found()) {
                Interactions.place(chestPos, again, true, false, true, false);
                return;
            }
        }

        if (!PlayerInv.moveToHotbar(mc, stack -> stack.is(Items.ENDER_CHEST))) {
            error("Could not get an ender chest into the hotbar.");
            recoverOrStop();
            return;
        }

        FindItemResult chest = InvUtils.findInHotbar(Items.ENDER_CHEST);
        if (!chest.found()) return;

        BlockPos spot = new SpotFinder(mc, 4.0, 2, null, null).find(false);
        if (spot == null) {
            error("Nowhere to put a chest down.");
            recoverOrStop();
            return;
        }

        chestPos = spot;
        Interactions.place(spot, chest, true, false, true, false);
    }

    /**
     * Breaks the chest with a pickaxe that is deliberately not Silk Touch.
     *
     * <p>Silk Touch gives the chest back instead of the obsidian, which turns the loop into one
     * chest becoming one chest for ever. The right tool here is the wrong one.
     */
    private void breakChest() {
        if (chestPos == null) {
            to(Phase.IDLE);
            return;
        }

        if (!isChest(chestPos)) {
            to(Phase.COLLECT);
            return;
        }

        if (!holdPlainPickaxe()) {
            error("No pickaxe without Silk Touch; that would give the chest back, not obsidian.");
            recoverOrStop();
            return;
        }

        Interactions.mine(mc, chestPos, false, true);
    }

    /**
     * Waits for the eight obsidian to be picked up, then puts the next chest in the same square.
     *
     * <p>The same square on purpose. A block placed where one was just broken is re-broken
     * almost instantly - the client already has the progress for that position - so the loop
     * runs at the speed of placing rather than the speed of mining obsidian. Finding a fresh
     * spot each time would be slower and would spread the work over ground that has to be
     * checked again.
     *
     * <p>Nothing moves on while the obsidian is still on the floor. Leaving it there is the one
     * outcome this module cannot have: the whole reason to be doing any of this is that there
     * is no more of it.
     */
    private void collect() {
        returnPickaxe();

        if (!nothingOnTheGround()) {
            // Standing still is enough - the drop is at our feet and the pickup radius does the
            // rest. Patience here is cheap and the alternative is losing eight obsidian.
            if (phaseTicks < 120) return;

            if (debug.get()) log("drop has not come to us; carrying on without it");
        }

        if (forObsidian && obsidian() < targetObsidian.get()) {
            if (chests() == 0) {
                if (notify.get()) {
                    warning("Out of ender chests at %d obsidian; fetching more.", obsidian());
                }
                forObsidian = false;
                to(Phase.PLACE_STORE);
                return;
            }

            // Back in the same hole, which is what makes the next one break at once.
            to(Phase.PLACE_CHEST);
            return;
        }

        if (notify.get()) info("Carrying %d obsidian.", obsidian());
        chestPos = null;
        to(Phase.IDLE);
    }

    private boolean nothingOnTheGround() {
        for (var entity : mc.level.getEntitiesOfClass(ItemEntity.class,
            mc.player.getBoundingBox().inflate(6))) {
            ItemStack stack = entity.getItem();
            if (stack.is(Items.OBSIDIAN) || stack.is(Items.ENDER_CHEST)) return false;
        }
        return true;
    }

    // --- fetching chests ---------------------------------------------------------

    private void openPlaced(BlockPos pos, Phase then) {
        if (pos == null) {
            recoverOrStop();
            return;
        }

        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            to(then);
            return;
        }
        Interactions.interact(mc, pos, false, true);
    }

    /**
     * Takes ender chests straight out of the ender chest, if that is where they are.
     *
     * <p>Otherwise looks for a shulker that has some in it. Both are how people keep them, and
     * the difference is four more steps rather than a different idea.
     */
    private void takeChests() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) {
            to(Phase.PLACE_STORE);
            return;
        }

        if (chests() >= targetChests.get()) {
            mc.player.closeContainer();
            if (notify.get()) info("Carrying %d ender chests.", chests());
            to(Phase.RECOVER);
            return;
        }

        int loose = Containers.findInContainer(menu, Items.ENDER_CHEST);
        if (loose != -1) {
            Containers.quickMove(menu, loose);
            return;
        }

        // None loose. A shulker with chests in it is the other way people keep them, and the
        // stack itself says what is inside without opening anything.
        int shulker = Containers.findInContainer(menu,
            stack -> Containers.isShulker(stack) && ShulkerContents.contains(stack, Items.ENDER_CHEST));
        if (shulker == -1) {
            warning("No ender chests in the ender chest, loose or in a shulker.");
            mc.player.closeContainer();
            to(Phase.RECOVER);
            return;
        }

        shulkerHome = shulker;
        to(Phase.TAKE_SHULKER);
    }

    private void takeShulker() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu || shulkerHome == -1) {
            to(Phase.PLACE_STORE);
            return;
        }

        if (!Containers.slotHas(menu, shulkerHome, Items.ENDER_CHEST)
            && Containers.isShulker(menu.getSlot(shulkerHome).getItem())) {
            Containers.quickMove(menu, shulkerHome);
            mc.player.closeContainer();
            to(Phase.PLACE_SHULKER);
            return;
        }

        mc.player.closeContainer();
        to(Phase.PLACE_STORE);
    }

    private void placeShulker() {
        if (shulkerPos != null && !mc.level.getBlockState(shulkerPos).isAir()) {
            to(Phase.OPEN_SHULKER);
            return;
        }

        if (!PlayerInv.moveToHotbar(mc, Containers::isShulker)) {
            error("Could not get the shulker into the hotbar.");
            recoverOrStop();
            return;
        }

        FindItemResult box = InvUtils.findInHotbar(Containers::isShulker);
        if (!box.found()) return;

        BlockPos spot = new SpotFinder(mc, 4.0, 2, chestPos, null).find(true);
        if (spot == null) {
            error("Nowhere to put the shulker down.");
            recoverOrStop();
            return;
        }

        shulkerPos = spot;
        Interactions.place(spot, box, true, false, true, false);
    }

    private void takeFromShulker() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) {
            to(Phase.PLACE_SHULKER);
            return;
        }

        if (chests() >= targetChests.get()) {
            mc.player.closeContainer();
            to(Phase.BREAK_SHULKER);
            return;
        }

        int slot = Containers.findInContainer(menu, Items.ENDER_CHEST);
        if (slot == -1) {
            mc.player.closeContainer();
            if (notify.get()) info("Carrying %d ender chests.", chests());
            to(Phase.BREAK_SHULKER);
            return;
        }
        Containers.quickMove(menu, slot);
    }

    private void breakShulker() {
        if (shulkerPos == null || mc.level.getBlockState(shulkerPos).isAir()) {
            to(Phase.RETURN_SHULKER);
            return;
        }
        Interactions.mine(mc, shulkerPos, false, true);
    }

    /**
     * Puts the shulker back where it came from.
     *
     * <p>A shulker of ender chests left on the ground is the whole supply left on the ground -
     * and this module exists because there is nowhere to go and get more. So it goes back in the
     * ender chest, which is the one container that comes with you.
     */
    private void returnShulker() {
        if (mc.player.containerMenu == mc.player.inventoryMenu) {
            if (chestPos != null && isChest(chestPos)) {
                Interactions.interact(mc, chestPos, false, true);
                return;
            }
            to(Phase.PLACE_STORE);
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        int carried = Containers.findInPlayerPart(menu, Containers::isShulker);
        if (carried == -1) {
            mc.player.closeContainer();
            to(Phase.RECOVER);
            return;
        }
        Containers.quickMove(menu, carried);
    }

    /** Takes the placed ender chest back up, so nothing is left behind. */
    private void recover() {
        if (chestPos == null || !isChest(chestPos)) {
            if (nothingOnTheGround() || phaseTicks > 60) {
                chestPos = null;
                to(Phase.IDLE);
            }
            return;
        }

        if (!holdPlainPickaxe()) {
            // Without the right pickaxe, leaving it placed is better than mining it into
            // obsidian we did not ask for and cannot put back.
            chestPos = null;
            to(Phase.IDLE);
            return;
        }
        Interactions.mine(mc, chestPos, false, true);
    }

    private void recoverOrStop() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        to(chestPos == null ? Phase.IDLE : Phase.RECOVER);
    }

    // --- tools -------------------------------------------------------------------

    /** Whether that position still holds the chest we put there. */
    private boolean isChest(BlockPos pos) {
        return mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.ENDER_CHEST);
    }

    /**
     * Holds a pickaxe that is not Silk Touch, remembering what was in hand.
     *
     * @return false when there is no such pickaxe, which makes the whole thing pointless
     */
    private boolean holdPlainPickaxe() {
        ItemStack held = mc.player.getMainHandItem();
        if (isPlainPickaxe(held)) return true;

        for (int i = 0; i < PlayerInv.HOTBAR_SIZE; i++) {
            if (!isPlainPickaxe(mc.player.getInventory().getItem(i))) continue;

            if (pickaxeHome == -1) pickaxeHome = heldSlot();
            InvUtils.swap(i, false);
            return true;
        }
        return false;
    }

    /**
     * Which hotbar slot is in hand.
     *
     * <p>Found by looking rather than asked for: the accessor behind it was renamed part way
     * through the versions this builds for, and the held stack is the same object as the one in
     * its slot on every one of them.
     */
    private int heldSlot() {
        ItemStack held = mc.player.getMainHandItem();
        for (int i = 0; i < PlayerInv.HOTBAR_SIZE; i++) {
            if (mc.player.getInventory().getItem(i) == held) return i;
        }
        return 0;
    }

    private void returnPickaxe() {
        if (pickaxeHome == -1 || mc.player == null) return;

        InvUtils.swap(pickaxeHome, false);
        pickaxeHome = -1;
    }

    /**
     * A tool that mines an ender chest fast, and does not have Silk Touch.
     *
     * <p>Asked by what it does rather than by what it is. {@code PickaxeItem} stopped existing
     * part way through the versions this builds for - tools became data - and "how quickly would
     * this break an ender chest" is both stable across all of them and the actual question.
     */
    private static boolean isPlainPickaxe(ItemStack stack) {
        if (stack.isEmpty() || Enchants.hasSilkTouch(stack)) return false;
        return stack.getDestroySpeed(
            net.minecraft.world.level.block.Blocks.ENDER_CHEST.defaultBlockState()) > 1.5f;
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[ObsidianSupply] " + String.format(fmt, args));
    }
}
