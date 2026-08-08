package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.modules.InvFix;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swallows a drag that is carrying something unstackable, for {@link InvFix}.
 *
 * <p>A drag spreads what is on the cursor over the slots it passes, which is a thing only a
 * stackable item can do. With a pickaxe on the cursor the client and the server end up with
 * different ideas about where it went, and the client's idea is the one drawn - a ghost that
 * looks like an item until you touch it.
 *
 * <h2>The one version split</h2>
 * {@code mouseDragged} took loose doubles and a button until 1.21.8, and a
 * {@code MouseButtonEvent} from 1.21.10. Same method, same place in the class; only the
 * signature moved, and it moved for every screen at once.
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    @Shadow
    protected AbstractContainerMenu menu;

    //? if <1.21.10 {
    /*@Inject(method = "mouseDragged(DDIDD)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void newAddon$noUnstackableDrag(double mouseX, double mouseY, int button,
                                            double dragX, double dragY,
                                            CallbackInfoReturnable<Boolean> info) {
        if (newAddon$draggingSomethingThatCannotStack()) info.setReturnValue(true);
    }
    *///?} else {
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true, require = 0)
    private void newAddon$noUnstackableDrag(net.minecraft.client.input.MouseButtonEvent event,
                                            double dragX, double dragY,
                                            CallbackInfoReturnable<Boolean> info) {
        if (newAddon$draggingSomethingThatCannotStack()) info.setReturnValue(true);
    }
    //?}

    private boolean newAddon$draggingSomethingThatCannotStack() {
        if (!InvFix.shouldFixUnstackableDrag()) return false;
        if (menu == null) return false;

        ItemStack carried = menu.getCarried();
        return !carried.isEmpty() && !carried.isStackable();
    }
}
