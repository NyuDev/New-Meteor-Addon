package fr.nyuway.newaddon.utils;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Reads what a shulker box <em>item</em> is carrying, without placing it.
 *
 * <p>Three storage formats have to be supported: NBT {@code BlockEntityTag} before 1.20.5, the
 * {@code CONTAINER} data component after it, and a further change in 26.1 where the component
 * yields item templates rather than stacks. Isolating that here keeps the version-conditional
 * source out of the modules.
 */
public final class ShulkerContents {

    private ShulkerContents() {
    }

    /** True when a shulker box item stores at least one of the given item. */
    public static boolean contains(ItemStack shulker, Item item) {
        //? if >=1.21 {
        var contents = shulker.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return false;
        //? if <26.1 {
        for (ItemStack stack : contents.nonEmptyItems()) {
            if (stack.is(item)) return true;
        }
        //?} else {
        /*for (var tmpl : contents.nonEmptyItems()) {
            if (tmpl.item().value() == item) return true;
        }
        *///?}
        return false;
        //?} else {
        /*var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        var tag = shulker.getTag();
        if (tag == null || !tag.contains("BlockEntityTag")) return false;
        var list = tag.getCompound("BlockEntityTag").getList("Items", 10);
        for (int i = 0; i < list.size(); i++) {
            if (list.getCompound(i).getString("id").equals(id)) return true;
        }
        return false;
        *///?}
    }
}
