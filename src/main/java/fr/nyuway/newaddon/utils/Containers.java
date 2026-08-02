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

    /** First empty player-inventory slot in this menu, or -1. */
    public static int findEmptyInPlayerPart(AbstractContainerMenu menu) {
        for (int i = containerSize(menu); i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Moves a whole stack from one menu slot to another.
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
