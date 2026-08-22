package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/**
 * Crafts bone meal in the 2x2 grid, one click at a time, waiting for the server between each.
 *
 * <h2>How the server answers a click, and why silence is the good answer</h2>
 * A click packet carries the client's own prediction: the carried stack and every slot it
 * thinks changed. The server applies the click, then writes that prediction straight into its
 * mirror of what the client is showing. {@code broadcastChanges} only sends a slot whose real
 * value differs from that mirror.
 *
 * <p>So a click the server agreed with produces <b>no packet at all</b>. Only a disagreement
 * comes back. Waiting for a reply before believing a click therefore waits forever on success,
 * and waiting a single tick believes everything - including clicks that were about to be
 * corrected.
 *
 * <p>What works is a settle window: click, wait longer than a round trip, then look. A
 * correction has landed by then if there was going to be one, and what the menu shows is what
 * the server has.
 *
 * <h2>Why the result slot needs its own wait</h2>
 * {@code CraftingMenu#slotChangedCraftingGrid} is guarded by {@code !level.isClientSide}. The
 * client never works out what a grid produces; that slot is only ever filled by the server, so
 * seeing bone meal in it is positive proof the ingredients arrived.
 *
 * <p>{@code InvUtils.move} is unusable here - two clicks, sometimes three, in one call, all
 * carrying the same stale state.
 */
public final class BoneCrafter {

    /** Slot numbering of {@code InventoryMenu}: result, then the four grid cells. */
    private static final int RESULT_SLOT = 0;
    private static final int GRID_FIRST = 1;
    private static final int GRID_LAST = 4;

    /** Most bone meal one craft can yield, used when checking a destination has room. */
    public static final int MAX_YIELD = 9;

    /**
     * Ticks to leave between a click and reading its outcome. Six is 300ms, comfortably past a
     * round trip on a distant server, and crafting is in no hurry.
     */
    private static final int SETTLE = 6;

    /** Settle windows to wait for a result before deciding none is coming. */
    private static final int RESULT_TRIES = 8;

    private enum Step { IDLE, GRAB, PLACE, WAIT, TAKE, STORE, UNLOAD }

    private final Consumer<String> debug;

    private Step step = Step.IDLE;
    private int settle;
    private int tries;
    private Item ingredient = Items.BONE_BLOCK;

    public BoneCrafter(Consumer<String> debug) {
        this.debug = debug;
    }

    public boolean isBusy() {
        return step != Step.IDLE;
    }

    /**
     * Whether the grid can be driven at all.
     *
     * <p>One condition, and it is the only one that matters: the open menu has to be the
     * player's own, because that is where the two-by-two grid lives. A screen being up is not
     * the same question - the chat and the pause menu leave the menu exactly where it was, and
     * refusing to craft while either is open meant that opening chat stopped a run.
     */
    public static boolean usable(Minecraft mc) {
        return mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu;
    }

    /**
     * Advances the sequence by at most one click.
     *
     * @param item      what is being crafted from, for the checks and the log
     * @param sourceId  container slot holding it, or -1 when there is none
     * @param depositId container slot the bone meal goes to, or -1 when there is no room
     * @param unloadAfter empty the grid after every craft rather than between batches
     * @return crafts the server did not contradict this tick
     */
    public int tick(Minecraft mc, Item item, int sourceId, int depositId, boolean unloadAfter) {
        if (!usable(mc)) {
            if (step != Step.IDLE) note("aborted, the inventory menu is no longer the open one");
            step = Step.IDLE;
            return 0;
        }

        // Nothing is read until a correction would have had time to arrive.
        if (settle > 0) {
            settle--;
            return 0;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        ItemStack carried = menu.getCarried();
        ItemStack grid = menu.getSlot(GRID_FIRST).getItem();

        switch (step) {
            case IDLE -> {
                if (sourceId < 0 || depositId < 0) return 0;
                if (!carried.isEmpty()) {
                    note("cursor holds " + name(carried) + ", waiting for it to clear");
                    return 0;
                }
                if (!gridEmpty(menu)) {
                    note("grid is not empty, clearing it first");
                    enter(Step.UNLOAD);
                    return 0;
                }

                ingredient = item;
                click(() -> InvUtils.click().slotId(sourceId));
                enter(Step.GRAB);
            }

            case GRAB -> {
                if (carried.is(ingredient)) {
                    note("holding " + carried.getCount() + " " + ingredient);
                    click(() -> InvUtils.click().slotId(GRID_FIRST));
                    enter(Step.PLACE);
                } else {
                    note("server undid the pickup, cursor is " + name(carried));
                    enter(Step.UNLOAD);
                }
            }

            case PLACE -> {
                if (grid.is(ingredient)) {
                    note("grid holds " + grid.getCount() + " " + ingredient);
                    tries = 0;
                    enter(Step.WAIT);
                } else {
                    note("server undid the grid load, grid is " + name(grid)
                        + " and cursor is " + name(carried));
                    enter(Step.UNLOAD);
                }
            }

            case WAIT -> {
                ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
                if (result.is(Items.BONE_MEAL)) {
                    note("server offers " + result.getCount() + " bone meal");
                    click(() -> InvUtils.click().slotId(RESULT_SLOT));
                    enter(Step.TAKE);
                } else if (grid.isEmpty()) {
                    note("grid is empty, nothing left to craft");
                    enter(Step.UNLOAD);
                } else if (++tries > RESULT_TRIES) {
                    note("no result after " + tries + " waits, result slot is " + name(result));
                    enter(Step.UNLOAD);
                } else {
                    settle = SETTLE;
                }
            }

            case TAKE -> {
                if (carried.is(Items.BONE_MEAL)) {
                    note("took " + carried.getCount() + " bone meal");
                    click(() -> InvUtils.click().slotId(depositId));
                    enter(Step.STORE);
                } else {
                    note("server undid the craft, cursor is " + name(carried));
                    enter(Step.UNLOAD);
                }
            }

            case STORE -> {
                if (carried.isEmpty()) {
                    note("stored; grid now holds " + name(grid));
                    enter(unloadAfter || grid.isEmpty() ? Step.UNLOAD : Step.WAIT);
                    tries = 0;
                    return 1;
                }
                note("server undid the deposit, cursor still holds " + name(carried));
                enter(Step.UNLOAD);
            }

            case UNLOAD -> {
                if (!carried.isEmpty() && depositId >= 0) {
                    click(() -> InvUtils.click().slotId(depositId));
                } else if (gridEmpty(menu)) {
                    note("grid clear, sequence finished");
                    step = Step.IDLE;
                } else if (++tries > RESULT_TRIES) {
                    note("could not clear the grid, giving up");
                    step = Step.IDLE;
                } else {
                    for (int i = GRID_FIRST; i <= GRID_LAST; i++) {
                        if (!menu.getSlot(i).getItem().isEmpty()) {
                            int slot = i;
                            click(() -> InvUtils.shiftClick().slotId(slot));
                            break;
                        }
                    }
                }
            }
        }

        return 0;
    }

    /** Brings the ingredients home; called when the module stops or the quota is met. */
    public void finish() {
        if (step != Step.IDLE) {
            tries = 0;
            enter(Step.UNLOAD);
        }
    }

    /** Sends one click and starts the window before its outcome may be read. */
    private void click(Runnable action) {
        action.run();
        settle = SETTLE;
    }

    private void enter(Step next) {
        step = next;
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
