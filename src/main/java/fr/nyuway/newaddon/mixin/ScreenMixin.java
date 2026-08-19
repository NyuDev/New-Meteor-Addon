package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.gui.live.LiveCanvas;
import fr.nyuway.newaddon.modules.LiveMessage;
import net.minecraft.client.gui.screens.Screen;
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
 * Puts LiveMessage's toasts over whatever screen is up.
 *
 * <h2>Why a mixin and not the HUD event</h2>
 * The HUD is drawn before any open screen and therefore underneath it, so a toast drawn there
 * while a chest is open is behind the chest. It was easier to suppress the toast than to solve
 * that, which is why it used to be suppressed - but a message arriving while you are in a chest,
 * in the pause menu, or looking at one conversation out of eight is exactly a message you want
 * to be told about.
 *
 * <h2>Why this method</h2>
 * Not {@code render}: nearly every screen overrides it and draws its own contents after calling
 * super, so the return of the base method is early, not late. This is the wrapper the game calls
 * instead - it runs the screen's whole render and then its tooltips - and it is final, so there
 * is one of it and nothing can slip in after. That also puts the toast over this addon's own
 * windows rather than under them.
 *
 * <p>Three names for the same method across the versions built for: {@code renderWithTooltip}
 * up to 1.21.8, {@code renderWithTooltipAndSubtitles} from 1.21.10, and the render-state form
 * at 26.1, where drawing moved from {@code GuiGraphics} to {@code GuiGraphicsExtractor}.
 * Checked against the jars at every step.
 */
@Mixin(Screen.class)
public class ScreenMixin {

    //? if <1.21.10 {
    /*@Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At("RETURN"), require = 0)
    private void newAddon$toastsOverScreen(GuiGraphics graphics, int mouseX, int mouseY,
                                           float delta, CallbackInfo info) {
        LiveMessage.renderOverScreen(new LiveCanvas(graphics), ((Screen) (Object) this).width);
    }
    *///?} elif <26.1 {
    @Inject(method = "renderWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At("RETURN"), require = 0)
    private void newAddon$toastsOverScreen(GuiGraphics graphics, int mouseX, int mouseY,
                                           float delta, CallbackInfo info) {
        LiveMessage.renderOverScreen(new LiveCanvas(graphics), ((Screen) (Object) this).width);
    }
    //?} else {
    /*@Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
        at = @At("RETURN"), require = 0)
    private void newAddon$toastsOverScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                           float delta, CallbackInfo info) {
        LiveMessage.renderOverScreen(new LiveCanvas(graphics), ((Screen) (Object) this).width);
    }
    *///?}
}
