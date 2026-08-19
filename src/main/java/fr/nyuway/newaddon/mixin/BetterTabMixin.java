package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.utils.Allies;
import fr.nyuway.newaddon.utils.Enemies;
import fr.nyuway.newaddon.utils.Profiles;
// Meteor moved the module from misc to render at its 0.5.6, which is the build for 1.20.4.
// The method itself has been there the whole time.
//? if <1.20.4 {
/*import meteordevelopment.meteorclient.systems.modules.misc.BetterTab;
*///?} else {
import meteordevelopment.meteorclient.systems.modules.render.BetterTab;
//?}
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Colours enemies in the tab list when Meteor's BetterTab is the one drawing it.
 *
 * <h2>Why the vanilla mixin was not enough</h2>
 * Meteor injects at the <em>head</em> of {@code PlayerTabOverlay.getNameForDisplay} and, when
 * BetterTab is on, sets the return value there. A mixin that sets a return value ends the method
 * at that point - so the vanilla RETURN injector this addon had never ran at all, and the colour
 * never appeared. The name was already decided somewhere else entirely.
 *
 * <p>So this one sits at the return of BetterTab's own {@code getPlayerName}, which is where the
 * name actually comes from. Between the two, the colour applies with BetterTab and without it.
 *
 * <p>Rebuilt as a flat literal rather than recoloured in place: a component's children keep
 * their own styles, and a colour set on the parent loses to them. Taking the finished text and
 * colouring that is the only way to be sure the whole name changes.
 *
 * <p>Not remapped - the target is Meteor's class, not Minecraft's, so its name must be left
 * exactly as written. The method exists on every Meteor build here except the one for 1.20.1,
 * where the vanilla mixin is what does the work.
 */
@Mixin(value = BetterTab.class, remap = false)
public class BetterTabMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true, require = 0)
    private void newAddon$colourEnemies(PlayerInfo info,
                                        CallbackInfoReturnable<Component> callback) {
        if (info == null) return;

        var profile = info.getProfile();
        java.util.UUID id = Profiles.idOf(profile);
        String who = Profiles.nameOf(profile);

        // Enemy first; then ally, which has to be decided here because an ally is on Meteor's
        // friend list and would otherwise be drawn as an ordinary friend.
        Integer marked = Enemies.isEnemy(id, who) ? Enemies.color()
            : Allies.isAlly(id, who) ? Allies.color() : null;
        if (marked == null) return;

        Component drawn = callback.getReturnValue();
        if (drawn == null) return;

        int colour = marked;
        callback.setReturnValue(Component.literal(drawn.getString())
            .withStyle(style -> style.withColor(colour)));
    }
}
