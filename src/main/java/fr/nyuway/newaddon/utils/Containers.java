package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.function.Predicate;

/**
 * Slot-level helpers for an open container.
 *
 * <p>Meteor's {@code InvUtils} finders assume the player's own inventory, so their indices are
 * wrong once a chest or shulker is open. These work against whatever
 * {@link net.minecraft.world.entity.player.Player#containerMenu} currently is.
 *
 * <p>Moves go through {@code InvUtils.move().fromId().toId()} rather than a hand-rolled
 * {@code handleInventoryMouseClick}: Meteor's action already targets the open container, and
 * because Meteor is compiled per Minecraft version it absorbs the container-click rework that
 * removed {@code ClickType} and that method in 26.x. Raw slot ids are used so an item can be
 * put back in the exact slot it came from, which a shift-click cannot promise.
 */
public final class Containers {

    private Containers() {
    }

    /** Number of slots belonging to the container itself, above the player inventory rows. */
    public static int containerSize(AbstractContainerMenu menu) {
        // The player's 36 inventory slots are always the last ones in a container menu.
        return Math.max(0, menu.slots.size() - 36);
    }

    /**
     * Any shulker box, dyed or not.
     *
     * <p>Each colour is a separate item, so {@code is(Items.SHULKER_BOX)} only ever matches
     * the undyed one - which is how a full ender chest reads as empty. {@code ItemTags
     * .SHULKER_BOXES} would be the obvious fix but does not exist before 1.21, so this goes
     * through the block type instead, which is the same on every supported version.
     */
    public static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item
            && item.getBlock() instanceof ShulkerBoxBlock;
    }

    /** First container slot holding this item, or -1. */
    public static int findInContainer(AbstractContainerMenu menu, Item item) {
        return findInContainer(menu, stack -> stack.is(item));
    }

    /** First container slot whose stack matches, or -1. */
    public static int findInContainer(AbstractContainerMenu menu, Predicate<ItemStack> match) {
        int size = containerSize(menu);
        for (int i = 0; i < size; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && match.test(stack)) return i;
        }
        return -1;
    }

    /** True when the container has nothing in it at all. */
    public static boolean isContainerEmpty(AbstractContainerMenu menu) {
        int size = containerSize(menu);
        for (int i = 0; i < size; i++) {
            if (!menu.slots.get(i).getItem().isEmpty()) return false;
        }
        return true;
    }

    /** First empty container slot, or -1. */
    public static int findEmptyInContainer(AbstractContainerMenu menu) {
        int size = containerSize(menu);
        for (int i = 0; i < size; i++) {
            if (menu.slots.get(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    /** Total count of an item across the container's own slots. */
    public static int countInContainer(AbstractContainerMenu menu, Item item) {
        int size = containerSize(menu);
        int total = 0;
        for (int i = 0; i < size; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** First player-inventory slot in this menu holding the item, or -1. */
    public static int findInPlayerPart(AbstractContainerMenu menu, Item item) {
        return findInPlayerPart(menu, stack -> stack.is(item));
    }

    /** First player-inventory slot in this menu whose stack matches, or -1. */
    public static int findInPlayerPart(AbstractContainerMenu menu, Predicate<ItemStack> match) {
        for (int i = containerSize(menu); i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && match.test(stack)) return i;
        }
        return -1;
    }

    /**
     * First empty hotbar slot in this menu, or -1.
     *
     * <p>The hotbar is the last nine slots of a container menu, after the three storage rows -
     * so the plain "first empty player slot" search fills storage first and leaves the bar
     * empty, which is useless for anything that has to be held.
     */
    public static int findEmptyInHotbarPart(AbstractContainerMenu menu) {
        for (int i = menu.slots.size() - 9; i < menu.slots.size(); i++) {
            if (i >= 0 && menu.slots.get(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    /**
     * The menu slot showing a given inventory index, or -1 when this menu does not show it.
     *
     * <p>The two numberings disagree, and not in a way that lines up: a container menu lists its
     * own slots, then the three storage rows - inventory indices 9 to 35 - and only then the
     * hotbar, indices 0 to 8. So the hotbar, which is first in the inventory, is last in the menu.
     *
     * <p>Needed because an inventory index is the only thing about a stack that survives a
     * container being closed and reopened, which is what following one particular shulker box
     * through a run requires.
     */
    public static int playerSlotId(AbstractContainerMenu menu, int inventoryIndex) {
        int base = containerSize(menu);
        if (inventoryIndex < 0 || inventoryIndex >= 36) return -1;

        int id = inventoryIndex >= 9
            ? base + (inventoryIndex - 9)
            : base + 27 + inventoryIndex;
        return id < menu.slots.size() ? id : -1;
    }

    /** First empty player-inventory slot in this menu, or -1. */
    public static int findEmptyInPlayerPart(AbstractContainerMenu menu) {
        for (int i = containerSize(menu); i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Shift-clicks a slot, the way a player moves a stack between a container and their pack.
     *
     * <h2>Why not pick up and put down</h2>
     * Meteor's {@code move()} fires both clicks in the same tick, and a third if the cursor is
     * left holding something. Every click after the first carries a state id the server has
     * already moved past, so the server answers by sending the slot back as it really is - which
     * is the item visibly blinking into place and out again, and a move that never happens
     * however many times it is asked for. A phase would time out that way, having asked two
     * hundred times and got nowhere.
     *
     * <p>A shift-click is one click. The server decides where the stack lands, which happens to
     * be exactly where these callers wanted it: out of a container it fills the hotbar first, and
     * within the player's own inventory it moves between the bar and the pack. There is nothing
     * left for the two sides to disagree about.
     *
     * @return false when the slot is out of range or empty, so a caller can tell that nothing
     *         was asked for rather than assume it was
     */
    public static boolean quickMove(AbstractContainerMenu menu, int slot) {
        if (slot < 0 || slot >= menu.slots.size()) return false;
        if (menu.slots.get(slot).getItem().isEmpty()) return false;

        InvUtils.shiftClick().slotId(slot);
        return true;
    }

    /**
     * Moves a whole stack from one menu slot to another.
     *
     * <p>Two clicks in one tick; see {@link #quickMove}, which is what should be used instead
     * wherever the server's own choice of destination will do.
     *
     * @return false when either index is out of range, so callers can abort rather than fire
     *         clicks into nothing
     */
    public static boolean moveStack(AbstractContainerMenu menu, int from, int to) {
        if (from < 0 || to < 0 || from >= menu.slots.size() || to >= menu.slots.size()) return false;

        InvUtils.move().fromId(from).toId(to);
        return true;
    }

    /** True if the slot currently holds the given item. */
    public static boolean slotHas(AbstractContainerMenu menu, int slot, Item item) {
        if (slot < 0 || slot >= menu.slots.size()) return false;
        return menu.slots.get(slot).getItem().is(item);
    }
}
