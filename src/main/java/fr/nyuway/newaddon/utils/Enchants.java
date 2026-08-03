package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Enchantment lookups, and the only place in the addon that needs per-version source.
 *
 * <p>Enchantments stopped being plain objects in 1.21: {@code Enchantments.SILK_TOUCH} is an
 * {@code Enchantment} on 1.20.x and a {@code ResourceKey} afterwards, and
 * {@code EnchantmentHelper} changed to match. Meteor's helper follows whichever version it was
 * built against, so no single call satisfies both.
 *
 * <p>Keeping the split here means the modules never carry version-conditional source of their
 * own - they just ask a question and get an answer.
 */
public final class Enchants {

    private Enchants() {
    }

    public static boolean hasSilkTouch(ItemStack stack) {
        //? if >=1.21 {
        return Utils.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH) > 0;
        //?} else {
        /*return net.minecraft.world.item.enchantment.EnchantmentHelper
            .getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
        *///?}
    }

    public static boolean hasMending(ItemStack stack) {
        //? if >=1.21 {
        return Utils.getEnchantmentLevel(stack, Enchantments.MENDING) > 0;
        //?} else {
        /*return net.minecraft.world.item.enchantment.EnchantmentHelper
            .getItemEnchantmentLevel(Enchantments.MENDING, stack) > 0;
        *///?}
    }
}
