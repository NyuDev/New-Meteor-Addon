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

    /**
     * What a shulker holds, in the one form a glance needs it.
     *
     * @param dominant the item there is most of
     * @param count    how many of it
     * @param types    how many different items are in there, so a mixed box can say so
     */
    public record Summary(Item dominant, int count, int types) { }

    /**
     * The most common item in a shulker box, and how mixed the rest is.
     *
     * <p>Counted rather than merely found: a box with one stray cobblestone and a stack of
     * shulker shells should show the shells. Null when there is nothing inside.
     */
    public static Summary summarise(ItemStack shulker) {
        java.util.Map<Item, Integer> counts = new java.util.HashMap<>();

        //? if >=1.21 {
        var contents = shulker.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return null;
        //? if <26.1 {
        for (ItemStack stack : contents.nonEmptyItems()) {
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        //?} else {
        /*for (var tmpl : contents.nonEmptyItems()) {
            counts.merge(tmpl.item().value(), tmpl.count(), Integer::sum);
        }
        *///?}
        //?} else {
        /*var tag = shulker.getTag();
        if (tag == null || !tag.contains("BlockEntityTag")) return null;
        var list = tag.getCompound("BlockEntityTag").getList("Items", 10);
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            var id = net.minecraft.resources.ResourceLocation.tryParse(entry.getString("id"));
            if (id == null) continue;
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            counts.merge(item, (int) entry.getByte("Count"), Integer::sum);
        }
        *///?}

        Item best = null;
        int bestCount = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }

        return best == null ? null : new Summary(best, bestCount, counts.size());
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
