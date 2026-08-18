package fr.nyuway.newaddon.mixin;

import fr.nyuway.newaddon.utils.Enemies;
import fr.nyuway.newaddon.utils.Profiles;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws enemies in the enemy colour wherever Meteor draws a player.
 *
 * <h2>One method, not three modules</h2>
 * Tracers, ESP and Nametags all ask the same question - what colour is this player - and they
 * all ask it here, in {@code PlayerUtils.getPlayerColor}. Colouring the three modules
 * separately would be three mixins that have to be kept in step with each other, and a fourth
 * the day Meteor adds another. This is the funnel; everything downstream of it follows.
 *
 * <p>It reads as the friend colour's opposite number because that is exactly what it is: the
 * method's own first act is to return the friend colour for a friend, and this is the same
 * question asked one step earlier for the other side. Friend and enemy are mutually exclusive
 * in this addon, so the two can never disagree about the same person.
 *
 * <h2>Two details worth not getting wrong</h2>
 * The alpha is taken from the colour the caller asked for, so the opacity sliders on Tracers and
 * ESP still mean something - returning a fully opaque colour would quietly override them.
 *
 * <p>And the colour object is reused rather than made fresh, which is what Meteor does here for
 * the same reason: this runs per player per frame, and a tracer scene with fifty players would
 * otherwise allocate fifty colours sixty times a second to say one thing.
 *
 * <p>Not remapped: the target is Meteor's class, so its name has to stay exactly as written.
 */
@Mixin(value = PlayerUtils.class, remap = false)
public class PlayerUtilsMixin {

    private static final Color newAddon$enemyColor = new Color();

    @Inject(method = "getPlayerColor", at = @At("HEAD"), cancellable = true, require = 0)
    private static void newAddon$colourEnemies(Player entity, Color defaultColor,
                                               CallbackInfoReturnable<Color> callback) {
        if (entity == null || defaultColor == null) return;

        // By id where there is one: a player who renamed is still the same player, and the
        // entity in front of us is exactly the case where the id is free to read.
        var profile = entity.getGameProfile();
        if (!Enemies.isEnemy(Profiles.idOf(profile), Profiles.nameOf(profile))) return;

        int rgb = Enemies.color();
        callback.setReturnValue(newAddon$enemyColor.set(
            (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, defaultColor.a));
    }
}
