package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/**
 * Crafts bone meal in the 2x2 grid, one click at a time, never touching a hotbar slot.
 *
 * <h2>Why hotbar slots are poison here</h2>
 * {@code ClientPacketListener#handleContainerSetSlot} special-cases container 0 hotbar slots:
 * instead of {@code menu.setItem(slot, stateId, stack)} it takes a path that updates neither
 * the visible slot the normal way nor the menu's {@code stateId}. So a correction the server
 * sends for a hotbar slot never really lands, the client keeps believing whatever it predicted,
 * and its {@code stateId} stays stale forever - which makes every later click mismatch.
 *
 * <p>That is a client showing a bone block stack that has not moved in a hundred crafts while
 * the server has been quietly disagreeing the whole time. Everything here therefore works in
 * the main inventory, slots 9 to 35, where corrections arrive properly; ingredients sitting in
 * the hotbar are stowed there first.
 *
 * <h2>Why every step waits for the server</h2>
 * A click is applied to the client's own menu immediately, so reading that menu back tells you
 * what you predicted, not what happened. The honest acknowledgement is {@code stateId}: the
 * server bumps it on every change it broadcasts, and the client adopts the new value from the
 * packet. Waiting for it to move is waiting for the server to have spoken.
 *
 * <p>It matters twice over, because a click sent while the id is stale is answered with
 * {@code broadcastFullState} - a resync that discards the prediction. Pacing by round trip
 * rather than by tick count is what keeps the two in step.
 *
 * <p>{@code InvUtils.move} is unusable here: it is two clicks, sometimes three, in one call.
 *
 * <h2>Why the result slot needs a round trip of its own</h2>
 * {@code CraftingMenu#slotChangedCraftingGrid} is guarded by {@code !level.isClientSide}. The
 * client never works out what a grid produces; that slot is only ever filled by the server.
 */
public final class BoneCrafter {

    /** Slot numbering of {@code InventoryMenu}: result, then the four grid cells. */
    private static final int RESULT_SLOT = 0;
    private static final int GRID_FIRST = 1;
    private static final int GRID_LAST = 4;

    /** Container slot ids of the main inventory - the ones the server can correct. */
    public static final int MAIN_FIRST = 9;
    public static final int MAIN_LAST = 35;

    /** Most bone meal one craft can yield, used when checking a destination has room. */
    public static final int MAX_YIELD = 9;

    /** Ticks to wait for the server to answer a click before giving up on it. */
    private static final int ACK_TIMEOUT = 100;

    private enum Step { IDLE, STOW, GRAB, PLACE, WAIT, TAKE, STORE, UNLOAD }

    private final Consumer<String> debug;

    private Step step = Step.IDLE;
    private int waited;

    /** Menu state when the pending click went out; the server moves it when it answers. */
    private int sentAt = -1;
    private boolean pending;

    private Item ingredient = Items.BONE_BLOCK;

    public BoneCrafter(Consumer<String> debug) {
        this.debug = debug;
    }

    public boolean isBusy() {
        return step != Step.IDLE;
    }

    /** Whether the grid can be driven at all: our own menu, and no screen in the way. */
    public static boolean usable(Minecraft mc) {
        return mc.player != null
            && mc.screen == null
            && mc.player.containerMenu == mc.player.inventoryMenu;
    }

    /** True for the hotbar slot ids of the player menu, whose corrections do not land. */
    public static boolean isHotbarId(int id) {
        return id >= 36 && id <= 44;
    }

    /**
     * Advances the sequence by at most one click.
     *
     * @param item          what is being crafted from, for the checks and the log
     * @param sourceId      container slot holding it, or -1 when there is none
     * @param depositId     main-inventory slot the bone meal goes to, or -1
     * @param unloadAfter   empty the grid after every craft rather than between batches
     * @return crafts the server confirmed this tick
     */
    public int tick(Minecraft mc, Item item, int sourceId, int depositId, boolean unloadAfter) {
        if (!usable(mc)) {
            if (step != Step.IDLE) note("aborted, the inventory menu is no longer the open one");
            step = Step.IDLE;
            pending = false;
            return 0;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;

        // Nothing is read as confirmation until the server has answered the last click.
        if (pending) {
            if (menu.getStateId() == sentAt) {
                if (++waited > ACK_TIMEOUT) {
                    note("no answer to the last click after " + waited + " ticks, backing out");
                    pending = false;
                    enter(Step.UNLOAD);
                }
                return 0;
            }
            pending = false;
            waited = 0;
        }

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

                // Corrections to hotbar slots never land, so move the stack down first.
                if (isHotbarId(sourceId)) {
                    note("stowing " + item + " out of the hotbar first");
                    send(menu, () -> InvUtils.shiftClick().slotId(sourceId));
                    enter(Step.STOW);
                } else {
                    send(menu, () -> InvUtils.click().slotId(sourceId));
                    enter(Step.GRAB);
                }
            }

            // Nothing to verify: the next pass simply looks the stack up again, wherever it is.
            case STOW -> enter(Step.IDLE);

            case GRAB -> {
                if (carried.is(ingredient)) {
                    note("holding " + carried.getCount() + " " + ingredient);
                    send(menu, () -> InvUtils.click().slotId(GRID_FIRST));
                    enter(Step.PLACE);
                } else {
                    note("server refused the pickup, cursor is " + name(carried));
                    enter(Step.UNLOAD);
                }
            }

            case PLACE -> {
                if (grid.is(ingredient)) {
                    note("grid holds " + grid.getCount() + " " + ingredient
                        + ", waiting for the result");
                    enter(Step.WAIT);
                } else {
                    note("server refused the grid load, grid is " + name(grid)
                        + " and cursor is " + name(carried));
                    enter(Step.UNLOAD);
                }
            }

            case WAIT -> {
                ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
                if (result.is(Items.BONE_MEAL)) {
                    note("server offers " + result.getCount() + " bone meal");
                    send(menu, () -> InvUtils.click().slotId(RESULT_SLOT));
                    enter(Step.TAKE);
                } else if (grid.isEmpty()) {
                    note("grid is empty, nothing left to craft");
                    enter(Step.UNLOAD);
                } else if (++waited > ACK_TIMEOUT) {
                    note("no result after " + waited + " ticks, result slot is " + name(result));
                    enter(Step.UNLOAD);
                }
            }

            case TAKE -> {
                if (carried.is(Items.BONE_MEAL)) {
                    note("took " + carried.getCount() + " bone meal");
                    send(menu, () -> InvUtils.click().slotId(depositId));
                    enter(Step.STORE);
                } else {
                    note("server refused the craft, cursor is " + name(carried));
                    enter(Step.UNLOAD);
                }
            }

            case STORE -> {
                if (carried.isEmpty()) {
                    note("stored, grid now holds " + name(grid));
                    enter(unloadAfter || grid.isEmpty() ? Step.UNLOAD : Step.WAIT);
                    return 1;
                }
                note("server refused the deposit, cursor still holds " + name(carried));
                enter(Step.UNLOAD);
            }

            case UNLOAD -> {
                if (!carried.isEmpty() && depositId >= 0) {
                    send(menu, () -> InvUtils.click().slotId(depositId));
                } else if (gridEmpty(menu)) {
                    note("grid clear, sequence finished");
                    step = Step.IDLE;
                } else {
                    for (int i = GRID_FIRST; i <= GRID_LAST; i++) {
                        if (!menu.getSlot(i).getItem().isEmpty()) {
                            int slot = i;
                            send(menu, () -> InvUtils.shiftClick().slotId(slot));
                            break;
                        }
                    }
                    if (++waited > ACK_TIMEOUT) {
                        note("could not clear the grid, giving up");
                        step = Step.IDLE;
                    }
                }
            }
        }

        return 0;
    }

    /** Brings the ingredients home; called when the module stops or the quota is met. */
    public void finish() {
        if (step != Step.IDLE) enter(Step.UNLOAD);
    }

    /** Sends one click and remembers the state it went out on, so its answer is detectable. */
    private void send(AbstractContainerMenu menu, Runnable click) {
        sentAt = menu.getStateId();
        pending = true;
        click.run();
    }

    private void enter(Step next) {
        step = next;
        waited = 0;
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
