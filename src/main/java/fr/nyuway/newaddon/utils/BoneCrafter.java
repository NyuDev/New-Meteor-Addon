package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Turns bone blocks into bone meal in the 2x2 grid, without opening anything.
 *
 * <h2>Why a whole class</h2>
 * The player's own inventory menu is always live, so its crafting grid can be used with no
 * screen on screen. But its slot numbering is only valid while that menu is the open one, and
 * every step here can fail halfway, so the sequence needs to be written once and carefully
 * rather than inlined into a module.
 *
 * <h2>Everything in one tick</h2>
 * A craft picks items up onto the cursor. A cursor left loaded between ticks is items waiting
 * to be lost - to a disconnect, a screen opening, the module being switched off - so the whole
 * sequence runs and empties the cursor within the single call. Nothing is ever carried across.
 *
 * <h2>Why not simply shift-click the result</h2>
 * A shift-click on a crafting result crafts as many times as the grid allows. With a stack of
 * 64 bone blocks in the grid that is 576 bone meal in one action: a burst of packets and an
 * inventory with no room left for anything else. Taking the result one craft at a time is what
 * makes the amount controllable.
 */
public final class BoneCrafter {

    /** Slot numbering of {@code InventoryMenu}: result, then the four grid cells. */
    private static final int RESULT_SLOT = 0;
    private static final int GRID_FIRST = 1;
    private static final int GRID_LAST = 4;

    /** Bone meal a single bone block yields. */
    public static final int PER_BLOCK = 9;

    private BoneCrafter() {
    }

    /** Whether the grid is usable right now: our own menu, open, and empty. */
    public static boolean ready(Minecraft mc) {
        if (mc.player == null || mc.screen != null) return false;

        // With a chest or anything else open, slots 0-4 are that container's, not the grid's.
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != mc.player.inventoryMenu) return false;
        if (!menu.getCarried().isEmpty()) return false;

        for (int i = GRID_FIRST; i <= GRID_LAST; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) return false;
        }

        return true;
    }

    /**
     * Crafts up to {@code maxCrafts} bone blocks into bone meal.
     *
     * @param blockSlotId container slot id holding the bone blocks
     * @param dropSlotId  container slot id the bone meal goes to; must be empty or bone meal
     *                    with room
     * @return how many crafts actually happened
     */
    public static int craft(Minecraft mc, int blockSlotId, int dropSlotId, int maxCrafts) {
        if (!ready(mc)) return 0;

        AbstractContainerMenu menu = mc.player.containerMenu;

        InvUtils.move().fromId(blockSlotId).toId(GRID_FIRST);

        int done = 0;
        while (done < maxCrafts) {
            // The result is computed by the menu, so this also proves the recipe matched and
            // there are still ingredients left.
            ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
            if (!result.is(Items.BONE_MEAL)) break;

            // One more craft has to fit on the cursor beside what is already there.
            int carried = menu.getCarried().getCount();
            if (carried + result.getCount() > result.getMaxStackSize()) break;

            InvUtils.click().slotId(RESULT_SLOT);

            // If the click changed nothing, something refused it; stop rather than spin.
            if (menu.getCarried().getCount() == carried) break;
            done++;
        }

        // Put the bone meal down before anything else can go wrong with it.
        if (!menu.getCarried().isEmpty()) InvUtils.click().slotId(dropSlotId);

        // And give back whatever bone blocks were not used.
        if (!menu.getSlot(GRID_FIRST).getItem().isEmpty()) {
            InvUtils.move().fromId(GRID_FIRST).toId(blockSlotId);
        }

        // Last resort: if the cursor still holds something the sequence would have dropped it
        // on the next screen open, so put it anywhere it fits.
        if (!menu.getCarried().isEmpty()) InvUtils.click().slotId(dropSlotId);

        return done;
    }
}
