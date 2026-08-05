package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Turns bone blocks into bone meal in the 2x2 grid, without opening anything.
 *
 * <h2>Why this cannot happen in one tick</h2>
 * {@code CraftingMenu#slotChangedCraftingGrid} is guarded by {@code !level.isClientSide}: the
 * client never works out what a grid produces. The result slot is filled by the server, in a
 * {@code ClientboundContainerSetSlotPacket} that arrives a round trip after the ingredients go
 * in. Reading that slot in the same tick you loaded the grid always finds it empty - and code
 * that then gives up and pulls the ingredients back out looks, from the outside, exactly like a
 * craft being rolled back.
 *
 * <p>So this waits. Every step is confirmed against what the server actually sent before the
 * next one is taken, which is also what makes the sequence indistinguishable from a player
 * clicking: the same clicks, in the same order, at the speed the round trips allow.
 *
 * <h2>One craft per round</h2>
 * A shift-click on the result crafts as many times as the grid allows - a stack of 64 bone
 * blocks would become 576 bone meal in a single action. Taking the result with a normal click
 * takes exactly one craft, and each one needs a fresh round trip before the next, so the rate
 * is bounded by the server rather than by us.
 *
 * <h2>The cursor</h2>
 * Taking a craft puts items on the cursor, and a loaded cursor is items waiting to be dropped
 * by a death or a disconnect. The take and the put-away happen in the same tick, always, so
 * the cursor is empty at every tick boundary.
 */
public final class BoneCrafter {

    /** Slot numbering of {@code InventoryMenu}: result, then the four grid cells. */
    private static final int RESULT_SLOT = 0;
    private static final int GRID_FIRST = 1;
    private static final int GRID_LAST = 4;

    /** Bone meal a single bone block yields. */
    public static final int PER_BLOCK = 9;

    /** Ticks to wait for a result before concluding the server is not going to send one. */
    private static final int RESULT_TIMEOUT = 60;

    private boolean loaded;
    private int waited;

    /** True while bone blocks are sitting in the grid and still have to come back out. */
    public boolean isLoaded() {
        return loaded;
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
            && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.containerMenu.getCarried().isEmpty();
    }

    /**
     * Advances the sequence by one step.
     *
     * @param blockSlotId  container slot holding bone blocks, or -1 when there are none left
     * @param depositSlotId container slot the bone meal goes to
     * @param unloadEachRound empty the grid after every craft instead of between batches
     * @return crafts completed this tick, or -1 when the tick was spent waiting or moving
     */
    public int tick(Minecraft mc, int blockSlotId, int depositSlotId, boolean unloadEachRound) {
        if (!usable(mc)) return 0;

        AbstractContainerMenu menu = mc.player.containerMenu;

        if (!loaded) {
            if (blockSlotId < 0 || depositSlotId < 0) return 0;

            // Someone else's items in the grid: leave them alone rather than craft with them.
            if (!gridEmpty(menu)) return 0;

            InvUtils.move().fromId(blockSlotId).toId(GRID_FIRST);
            loaded = true;
            waited = 0;
            return -1;
        }

        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (!result.is(Items.BONE_MEAL)) {
            // Either the round trip has not landed yet, or the grid ran dry.
            if (menu.getSlot(GRID_FIRST).getItem().isEmpty() || ++waited > RESULT_TIMEOUT) {
                unload(mc);
            }
            return -1;
        }

        waited = 0;

        if (depositSlotId < 0) {
            unload(mc);
            return -1;
        }

        InvUtils.click().slotId(RESULT_SLOT);

        // Put it away in this same tick, so no tick ever ends with a loaded cursor.
        if (!menu.getCarried().isEmpty()) InvUtils.click().slotId(depositSlotId);
        if (!menu.getCarried().isEmpty()) InvUtils.click().slotId(RESULT_SLOT);

        if (unloadEachRound) unload(mc);

        return 1;
    }

    /**
     * Empties the grid back into the inventory.
     *
     * <p>Shift-click rather than a move to a remembered slot: the slot the blocks came from may
     * hold something else by now, and a move would swap that onto the cursor. Quick-move finds
     * its own destination and merges into a stack that is already there.
     */
    public void unload(Minecraft mc) {
        loaded = false;
        waited = 0;

        if (mc.player == null || mc.player.containerMenu != mc.player.inventoryMenu) return;

        AbstractContainerMenu menu = mc.player.containerMenu;
        for (int i = GRID_FIRST; i <= GRID_LAST; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) InvUtils.shiftClick().slotId(i);
        }
    }

    private static boolean gridEmpty(AbstractContainerMenu menu) {
        for (int i = GRID_FIRST; i <= GRID_LAST; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) return false;
        }
        return true;
    }
}
