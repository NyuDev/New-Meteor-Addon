package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.modules.ServerStats;
import fr.nyuway.newaddon.utils.vc.Markers;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Expands markers on a sign, on the way out.
 *
 * <p>A sign is the place markers are most worth having: it is written once and read for years,
 * so {@code {queue}} on it should be the number at the moment it was signed, not a brace.
 *
 * <p>At the head of {@code onDone}, which is the last moment the lines are still text this
 * client owns - after it they are a packet. The array is final and its contents are not, which
 * is what lets this rewrite them in place.
 *
 * <p>{@code onDone} is private with no arguments on every version this builds for, checked
 * against the jars from 1.20.1 to 26.1.2; only the lambda beside it was ever renamed.
 */
@Mixin(AbstractSignEditScreen.class)
public class AbstractSignEditScreenMixin {

    @Shadow
    private String[] messages;

    @Inject(method = "onDone", at = @At("HEAD"), require = 0)
    private void newAddon$expandMarkers(CallbackInfo info) {
        if (messages == null || !ServerStats.expandMarkers()) return;

        for (int i = 0; i < messages.length; i++) {
            if (Markers.present(messages[i])) messages[i] = Markers.expand(messages[i]);
        }
    }
}
