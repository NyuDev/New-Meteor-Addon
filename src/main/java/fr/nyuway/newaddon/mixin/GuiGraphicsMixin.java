package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.modules.ShulkerOverview;
import net.minecraft.client.gui.Font;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}

/**
 * Hangs the shulker badge off the one call every drawn item passes through.
 *
 * <p>Item decorations - the stack count and the durability bar - are drawn for every slot in
 * every screen and for the hotbar, by this one method. Injecting here means the badge appears
 * everywhere an item does, without a mixin per screen and without knowing anything about which
 * screen is up.
 *
 * <p>At the return, so the badge sits over the count rather than under it.
 *
 * <p>The method was renamed from {@code renderItemDecorations} to {@code itemDecorations} at
 * 26.1, along with the class it lives on; the signature is the same on both sides.
 */
//? if <26.1 {
@Mixin(GuiGraphics.class)
//?} else {
/*@Mixin(GuiGraphicsExtractor.class)
*///?}
public class GuiGraphicsMixin {

    //? if <26.1 {
    @Inject(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At("RETURN"), require = 0)
    private void newAddon$shulkerBadge(Font font, ItemStack stack, int x, int y, String count,
                                       CallbackInfo info) {
        ShulkerOverview.render((GuiGraphics) (Object) this, stack, x, y);
    }
    //?} else {
    /*@Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At("RETURN"), require = 0)
    private void newAddon$shulkerBadge(Font font, ItemStack stack, int x, int y, String count,
                                       CallbackInfo info) {
        ShulkerOverview.render((GuiGraphicsExtractor) (Object) this, stack, x, y);
    }
    *///?}
}
