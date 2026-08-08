package fr.nyuway.newaddon.utils;

import net.minecraft.world.item.ItemStack;

/**
 * Questions about two stacks that Minecraft asks differently depending on the version.
 *
 * <p>Only one so far, and it is the one that matters for deciding whether something will merge:
 * the check was renamed when NBT tags became data components, at 1.21.1.
 */
public final class Stacks {

    private Stacks() { }

    /** Whether two stacks are the same item with the same data, so they would stack together. */
    public static boolean same(ItemStack a, ItemStack b) {
        //? if <1.21.1 {
        /*return ItemStack.isSameItemSameTags(a, b);
        *///?} else {
        return ItemStack.isSameItemSameComponents(a, b);
        //?}
    }

    /** Whether a stack has room for more of itself. */
    public static boolean hasRoom(ItemStack stack) {
        return stack.getCount() < stack.getMaxStackSize();
    }
}
