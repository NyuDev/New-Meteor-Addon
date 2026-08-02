package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * StasisProtection - refuses to be teleported into an ambush.
 *
 * <p>The attack this defends against: someone finds your base, finds the stasis chamber you
 * left there, and fires it. You are yanked out of whatever you were doing and dropped in
 * front of them, at a spot they have had all the time in the world to prepare.
 *
 * <p>The defence is a <b>consent key</b>. Hold it and any teleport is treated as one you
 * asked for, whoever is standing at the far end. Let go, and an unexpected teleport that
 * lands you next to someone who is not on your Meteor friends list is treated as an ambush
 * and answered - by default with a stasis pull, which moves you somewhere else entirely
 * rather than merely logging you out of the fight.
 *
 * <p>A teleport you requested yourself through {@link StasisPull} is trusted automatically,
 * so pulling home does not trip your own alarm, and neither does the escape pull this module
 * fires.
 *
 * <h2>Why it watches for a moment instead of deciding instantly</h2>
 * The server sends your new position before it sends the entities around it. Checking who is
 * nearby on the very tick you land would often see an empty world. So a teleport opens a
 * short window, and the module reacts the moment a stranger shows up inside it.
 */
public class StasisProtection extends Module {

    public enum Reaction {
        /** Ask a stasis bot to pull you somewhere else. Keeps you in the game. */
        Pull,
        /** Drop the connection. Cruder, but needs no bot. */
        Disconnect
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");

    private final Setting<Keybind> consentKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("consent-key")
        .description("Hold this to accept being teleported. While it is down, no teleport is " +
                     "ever treated as an ambush.")
        .defaultValue(Keybind.none())
        .build());

    private final Setting<Reaction> reaction = sgGeneral.add(new EnumSetting.Builder<Reaction>()
        .name("reaction")
        .description("What to do about an ambush. Pull needs StasisPull configured.")
        .defaultValue(Reaction.Pull)
        .build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Say in chat what was detected and what was done about it.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> trustOwnPull = sgGeneral.add(new BoolSetting.Builder()
        .name("trust-own-pull")
        .description("Treat a teleport as consented if you asked StasisPull for one recently.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> ownPullGrace = sgGeneral.add(new IntSetting.Builder()
        .name("own-pull-grace")
        .description("How long after your own pull request a teleport is still trusted.")
        .defaultValue(30).min(5).max(300).sliderMin(5).sliderMax(120)
        .visible(trustOwnPull::get)
        .build());

    private final Setting<Double> teleportDistance = sgDetection.add(new DoubleSetting.Builder()
        .name("teleport-distance")
        .description("How far you must move in a single tick to count as teleported. Well " +
                     "above anything you can reach by moving normally.")
        .defaultValue(32.0).min(8.0).max(256.0).sliderMin(16.0).sliderMax(128.0)
        .build());

    private final Setting<Double> dangerRange = sgDetection.add(new DoubleSetting.Builder()
        .name("danger-range")
        .description("How close a stranger has to be at the far end to count as an ambush. " +
                     "Keep it tight: whoever fired the chamber is standing right there.")
        .defaultValue(8.0).min(1.0).max(64.0).sliderMin(2.0).sliderMax(32.0)
        .build());

    private final Setting<Double> watchSeconds = sgDetection.add(new DoubleSetting.Builder()
        .name("watch-seconds")
        .description("How long to keep watching after landing. Entities around you arrive " +
                     "after your new position does, so an instant check sees nothing.")
        .defaultValue(2.5).min(0.5).max(15.0).sliderMin(1.0).sliderMax(8.0)
        .build());

    /** Where we were last tick, and in which world, so a teleport can be told from walking. */
    private double lastX, lastY, lastZ;
    private Level lastLevel;
    private boolean seeded;

    private int ticks;
    /** Tick the current watch window ends on, or -1 when not watching. */
    private int watchUntil = -1;
    private boolean consented;

    public StasisProtection() {
        super(NewAddon.CATEGORY, "stasis-protection",
            "Answers an unwanted stasis teleport that drops you next to a stranger.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        watchUntil = -1;
        consented = false;
        seeded = false;
        lastLevel = null;

        if (reaction.get() == Reaction.Pull && !Modules.get().get(StasisPull.class).isConfigured()) {
            warning("StasisPull is not configured; an ambush would be detected but not answered.");
        }
        if (!consentKey.get().isSet()) {
            warning("No consent key bound - every teleport will be judged on who is nearby.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        ticks++;

        // A new world means a dimension change, a respawn or a reconnect. Position deltas
        // across that are meaningless, so start over rather than cry teleport.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            seeded = false;
        }

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        if (!seeded) {
            lastX = x; lastY = y; lastZ = z;
            seeded = true;
            return;
        }

        double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
        double movedSq = dx * dx + dy * dy + dz * dz;
        lastX = x; lastY = y; lastZ = z;

        double threshold = teleportDistance.get();
        if (movedSq >= threshold * threshold) onTeleported(Math.sqrt(movedSq));

        if (watchUntil != -1) {
            if (ticks > watchUntil) {
                watchUntil = -1;
                if (notify.get() && !consented) info("Nobody turned up. Staying put.");
                return;
            }

            Player threat = findThreat();
            if (threat != null) {
                watchUntil = -1;
                react(threat);
            }
        }
    }

    private void onTeleported(double distance) {
        consented = isConsented();

        if (consented) {
            if (notify.get()) info("Teleported %.0f blocks - consented, ignoring.", distance);
            watchUntil = -1;
            return;
        }

        watchUntil = ticks + (int) Math.round(watchSeconds.get() * 20.0);
        if (notify.get()) warning("Unrequested teleport, %.0f blocks. Checking who is here.", distance);
    }

    /** True when this teleport is one we asked for, by key or by our own pull request. */
    private boolean isConsented() {
        if (consentKey.get().isSet() && consentKey.get().isPressed()) return true;

        if (trustOwnPull.get()) {
            long last = Modules.get().get(StasisPull.class).lastPullMillis();
            if (last != 0 && System.currentTimeMillis() - last <= ownPullGrace.get() * 1000L) {
                return true;
            }
        }

        return false;
    }

    /** Closest player nearby who is not us and not a friend, or null. */
    private Player findThreat() {
        double range = dangerRange.get();
        Player closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (player == mc.player || Friends.get().isFriend(player)) continue;

            double dist = mc.player.distanceTo(player);
            if (dist <= range && dist < closestDist) {
                closest = player;
                closestDist = dist;
            }
        }

        return closest;
    }

    private void react(Player threat) {
        String name = threat.getName().getString();
        warning("Ambush: %s is %.1f blocks away.", name, mc.player.distanceTo(threat));

        if (reaction.get() == Reaction.Pull) {
            // StasisPull records the request, so the teleport it causes is trusted and
            // does not trip this module all over again.
            Modules.get().get(StasisPull.class).pull();
        } else {
            disconnect(name);
        }
    }

    private void disconnect(String name) {
        if (mc.player == null || mc.player.connection == null) return;
        mc.player.connection.getConnection()
            .disconnect(Component.literal("[StasisProtection] pulled into " + name));
    }
}
