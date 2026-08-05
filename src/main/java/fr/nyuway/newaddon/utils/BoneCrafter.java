package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/**
 * Turns bone blocks into bone meal in the 2x2 grid, one click per tick.
 *
 * <h2>Why one click per tick</h2>
 * Every click carries the menu's {@code stateId}. The server compares it against its own and,
 * when they differ, answers with {@code broadcastFullState} - a full resync that throws away
 * whatever the client had predicted. Its own state advances the moment it handles a click, so
 * a second click sent in the same tick still carries the stale id and buys a resync. Taking a
 * craft result advances it twice, because refilling the result slot is itself a change.
 *
 * <p>That is what a burst of clicks in one tick looks like from the server: a client whose
 * predictions keep having to be corrected. Sending one click per tick, each after seeing the
 * effect of the last, is both what a player does and what stays in step with the server.
 *
 * <p>{@code InvUtils.move} is therefore unusable here - it is two clicks, sometimes three, in a
 * single call. Every step below is a single {@code click}.
 *
 * <h2>Why each step is confirmed</h2>
 * {@code CraftingMenu#slotChangedCraftingGrid} is guarded by {@code !level.isClientSide}: the
 * client never works out what a grid produces, so the result slot is only ever filled by the
 * server. Reading it in the tick you loaded the grid always finds it empty. Beyond that slot,
 * checking the outcome of a click on the following tick is the only way to tell a click that
 * worked from one the server quietly undid - the difference this class exists to respect.
 */
public final class BoneCrafter {

    /** Slot numbering of {@code InventoryMenu}: result, then the four grid cells. */
    private static final int RESULT_SLOT = 0;
    private static final int GRID_FIRST = 1;
    private static final int GRID_LAST = 4;

    /** Bone meal a single bone block yields. */
    public static final int PER_BLOCK = 9;

    /** Ticks a step may go unconfirmed before the attempt is written off. */
    private static final int STEP_TIMEOUT = 60;

    private enum Step {
        /** Nothing in flight. */
        IDLE,
        /** Clicked the bone blocks; waiting to see them on the cursor. */
        GRAB,
        /** Clicked the grid; waiting to see them land there. */
        PLACE,
        /** Grid loaded; waiting for the server to send a result. */
        WAIT,
        /** Clicked the result; waiting to see bone meal on the cursor. */
        TAKE,
        /** Clicked the destination; waiting to see the cursor empty. */
        STORE,
        /** Emptying the grid back into the inventory. */
        UNLOAD
    }

    private final Consumer<String> debug;

    private Step step = Step.IDLE;
    private int waited;
    private int crafts;

    public BoneCrafter(Consumer<String> debug) {
        this.debug = debug;
    }

    /** True while something is in flight and the sequence must keep being ticked. */
    public boolean isBusy() {
        return step != Step.IDLE;
    }

    /**
     * Whether the grid can be driven at all right now.
     *
     * <p>The slot numbers above belong to {@code InventoryMenu}. With a chest open they address
     * that chest instead, so this refuses rather than clicking blind.
     */
    public static boolean usable(Minecraft mc) {
        return mc.player != null
            && mc.screen == null
            && mc.player.containerMenu == mc.player.inventoryMenu;
    }

    /**
     * Advances the sequence by at most one click.
     *
     * @param blockSlotId   container slot holding bone blocks, or -1 when there are none
     * @param depositSlotId container slot the bone meal goes to, or -1 when there is no room
     * @param unloadAfter   empty the grid after every craft rather than between batches
     * @return crafts confirmed this tick
     */
    public int tick(Minecraft mc, int blockSlotId, int depositSlotId, boolean unloadAfter) {
        if (!usable(mc)) {
            if (step != Step.IDLE) note("aborted: inventory menu is no longer the open one");
            step = Step.IDLE;
            return 0;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        ItemStack carried = menu.getCarried();
        ItemStack grid = menu.getSlot(GRID_FIRST).getItem();
        int made = 0;

        switch (step) {
            case IDLE -> {
                if (blockSlotId < 0 || depositSlotId < 0) return 0;

                // Someone else's items in the grid: put them back before using it.
                if (!gridEmpty(menu)) {
                    note("grid was not empty, emptying it first");
                    enter(Step.UNLOAD);
                    return 0;
                }
                if (!carried.isEmpty()) {
                    note("cursor is not empty (" + name(carried) + "), waiting");
                    return 0;
                }

                crafts = 0;
                InvUtils.click().slotId(blockSlotId);
                enter(Step.GRAB);
            }

            case GRAB -> {
                if (carried.is(Items.BONE_BLOCK)) {
                    note("picked up " + carried.getCount() + " bone blocks");
                    InvUtils.click().slotId(GRID_FIRST);
                    enter(Step.PLACE);
                } else {
                    stall("bone blocks never reached the cursor (cursor=" + name(carried) + ")");
                }
            }

            case PLACE -> {
                if (grid.is(Items.BONE_BLOCK) && carried.isEmpty()) {
                    note("grid loaded with " + grid.getCount() + " bone blocks");
                    enter(Step.WAIT);
                } else {
                    stall("blocks never landed in the grid (grid=" + name(grid)
                        + " cursor=" + name(carried) + ")");
                }
            }

            case WAIT -> {
                ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
                if (result.is(Items.BONE_MEAL)) {
                    note("server offered " + result.getCount() + " bone meal");
                    InvUtils.click().slotId(RESULT_SLOT);
                    enter(Step.TAKE);
                } else if (grid.isEmpty()) {
                    note("grid is empty, nothing left to craft");
                    enter(Step.UNLOAD);
                } else {
                    stall("server sent no result (result=" + name(result)
                        + " grid=" + name(grid) + ")");
                }
            }

            case TAKE -> {
                if (carried.is(Items.BONE_MEAL)) {
                    if (depositSlotId < 0) {
                        note("nowhere to put the bone meal, putting it back");
                        InvUtils.click().slotId(RESULT_SLOT);
                        enter(Step.UNLOAD);
                    } else {
                        InvUtils.click().slotId(depositSlotId);
                        enter(Step.STORE);
                    }
                } else {
                    // The click on the result was undone: the craft did not happen.
                    stall("result did not come to the cursor (cursor=" + name(carried) + ")");
                }
            }

            case STORE -> {
                if (carried.isEmpty()) {
                    crafts++;
                    made = 1;
                    note("craft confirmed (" + crafts + " this run)");
                    enter(unloadAfter ? Step.UNLOAD : Step.WAIT);
                } else {
                    stall("bone meal stuck on the cursor (" + name(carried) + ")");
                }
            }

            case UNLOAD -> {
                if (!carried.isEmpty() && depositSlotId >= 0) {
                    InvUtils.click().slotId(depositSlotId);
                    waited = 0;
                } else if (gridEmpty(menu)) {
                    note("grid empty, done");
                    step = Step.IDLE;
                } else {
                    for (int i = GRID_FIRST; i <= GRID_LAST; i++) {
                        if (!menu.getSlot(i).getItem().isEmpty()) {
                            InvUtils.shiftClick().slotId(i);
                            break;
                        }
                    }
                    if (++waited > STEP_TIMEOUT) {
                        note("could not empty the grid, giving up");
                        step = Step.IDLE;
                    }
                }
            }
        }

        return made;
    }

    /** Brings everything home: called when the module stops or the quota is met. */
    public void finish(Minecraft mc) {
        if (step == Step.IDLE) return;
        enter(Step.UNLOAD);
    }

    private void enter(Step next) {
        step = next;
        waited = 0;
    }

    /** A step that has not shown its effect yet: wait, then recover the items. */
    private void stall(String what) {
        if (++waited <= STEP_TIMEOUT) return;

        note("stalled - " + what);
        enter(Step.UNLOAD);
    }

    private void note(String message) {
        if (debug != null) debug.accept(message);
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getCount() + "x" + stack.getItem();
    }

    private static boolean gridEmpty(AbstractContainerMenu menu) {
        for (int i = GRID_FIRST; i <= GRID_LAST; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) return false;
        }
        return true;
    }
}
