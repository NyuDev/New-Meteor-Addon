package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.utils.Allies;
import fr.nyuway.newaddon.utils.Enemies;
import fr.nyuway.newaddon.utils.Profiles;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Colours enemies in the tab list, the way Meteor colours friends.
 *
 * <p>The list already told you who was a friend and said nothing about the other side, so the
 * one place you actually look before deciding whether to land was the one place the enemy list
 * did not reach.
 *
 * <h2>At RETURN, not HEAD</h2>
 * Meteor's own mixin sits at the head of this method and replaces the name outright when
 * BetterTab is on. Injecting there too would be two mixins arguing over the same return value
 * with no say in which goes first. Taking whatever comes back and recolouring it means this
 * works with BetterTab and without it, and cannot win a race it should not be in.
 *
 * <p>The method is {@code getNameForDisplay} on every version this builds for, checked against
 * the jars from 1.20.1 to 26.1.2.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true, require = 0)
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

        // Flattened, not recoloured in place: a component's children keep their own styles and
        // a colour set on the parent loses to them, which is a quiet way to draw nothing.
        TextColor colour = TextColor.fromRgb(marked);
        callback.setReturnValue(Component.literal(drawn.getString())
            .withStyle(style -> style.withColor(colour)));
    }
}
