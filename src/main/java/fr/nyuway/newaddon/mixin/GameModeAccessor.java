package fr.nyuway.newaddon.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens the game's own block-action sender, for {@link fr.nyuway.newaddon.modules.AutoBreak}.
 *
 * <h2>Why not just send the packet</h2>
 * Every block interaction carries a sequence number the server acknowledges, and the game hands
 * them out here. Building the packet by hand means sending zero for it, which works but leaves
 * the server acknowledging a sequence that never advances - a small, permanent difference from
 * what a normal client looks like, on a module whose entire purpose is to look like a normal
 * client mining beside another one.
 *
 * <p>So the private method is borrowed rather than replaced. It is {@code private void
 * startPrediction(ClientLevel, PredictiveAction)} on every version this builds for, checked
 * against the jars from 1.20.1 to 26.1.2, and {@code PredictiveAction} is public on all of them.
 *
 * <p>An interface, because that is what an invoker needs; the class mixin on the same target
 * lives in {@link MultiPlayerGameModeMixin} and the two do not overlap.
 */
@Mixin(MultiPlayerGameMode.class)
public interface GameModeAccessor {

    @Invoker("startPrediction")
    void newAddon$startPrediction(ClientLevel level, PredictiveAction action);
}
