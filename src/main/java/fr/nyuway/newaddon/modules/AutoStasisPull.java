package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.player.Player;

/**
 * AutoStasisPull - pulls you to safety when things go wrong, instead of logging out.
 *
 * <p>Same idea as Meteor's AutoLog, different ending. AutoLog saves you by dropping the
 * connection, which leaves your body in the world for however long the server takes to
 * despawn it. A stasis pull moves you somewhere safe instead, and you stay logged in.
 *
 * <p>This is a separate module rather than a patch to AutoLog: hooking Meteor's own module
 * would have to survive twelve Meteor versions from 0.5.4 to 26.1.2. The conditions here are
 * self-contained, and {@code disable-autolog} keeps the two from firing at once - being
 * pulled and disconnected in the same tick helps nobody.
 *
 * <p>Firing is delegated to {@link StasisPull}, so whichever transport you configured there
 * (chat, whisper, or the encrypted API) is what gets used.
 */
public class AutoStasisPull extends Module {

    /** Vanilla entity event id for "this entity popped a totem". */
    private static final byte TOTEM_POP_EVENT = 35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("Triggers");

    private final Setting<Boolean> disableAutoLog = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-autolog")
        .description("Switch Meteor's AutoLog off while this is on, so you are not pulled and " +
                     "disconnected at the same time.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> toggleOff = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-off")
        .description("Turn this module off after a pull, so it does not keep firing while you " +
                     "are still hurt.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> healthTrigger = sgTriggers.add(new BoolSetting.Builder()
        .name("health")
        .description("Pull when your health drops to the threshold.")
        .defaultValue(true)
        .build());

    private final Setting<Double> healthLevel = sgTriggers.add(new DoubleSetting.Builder()
        .name("health-level")
        .description("Health at or below which to pull. Half a heart is 1.")
        .defaultValue(8.0).min(1.0).max(19.0).sliderMin(1.0).sliderMax(19.0)
        .visible(healthTrigger::get)
        .build());

    private final Setting<Boolean> countAbsorption = sgTriggers.add(new BoolSetting.Builder()
        .name("count-absorption")
        .description("Count golden-apple absorption as health, so it does not fire while you " +
                     "still have a buffer.")
        .defaultValue(true)
        .visible(healthTrigger::get)
        .build());

    private final Setting<Integer> totemPops = sgTriggers.add(new IntSetting.Builder()
        .name("totem-pops")
        .description("Pull after this many totems pop. 0 disables the trigger.")
        .defaultValue(1).min(0).max(10).sliderMin(0).sliderMax(5)
        .build());

    private final Setting<Boolean> playersTrigger = sgTriggers.add(new BoolSetting.Builder()
        .name("players")
        .description("Pull when a player who is not a friend comes close.")
        .defaultValue(false)
        .build());

    private final Setting<Double> playerRange = sgTriggers.add(new DoubleSetting.Builder()
        .name("player-range")
        .description("How close a stranger has to get.")
        .defaultValue(16.0).min(1.0).max(64.0).sliderMin(4.0).sliderMax(48.0)
        .visible(playersTrigger::get)
        .build());

    private int pops;

    public AutoStasisPull() {
        super(NewAddon.CATEGORY, "auto-stasis-pull",
            "Triggers a stasis pull when you are about to die, instead of logging out.");
    }

    @Override
    public void onActivate() {
        pops = 0;

        // Looked up by name, not by class: AutoLog has moved package between Meteor
        // versions, and this addon has to compile against all twelve of them.
        if (disableAutoLog.get()) {
            Module autoLog = Modules.get().get("auto-log");
            if (autoLog != null && autoLog.isActive()) {
                autoLog.toggle();
                info("Turned AutoLog off - this pulls instead.");
            }
        }

        if (!Modules.get().get(StasisPull.class).isConfigured()) {
            warning("StasisPull has no trigger words or endpoint set; a pull would do nothing.");
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (totemPops.get() == 0 || mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != TOTEM_POP_EVENT) return;
        if (p.getEntity(mc.level) != mc.player) return;

        pops++;
        if (pops >= totemPops.get()) fire("popped " + pops + " totem" + (pops == 1 ? "" : "s"));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (healthTrigger.get()) {
            float health = mc.player.getHealth();
            if (countAbsorption.get()) health += mc.player.getAbsorptionAmount();
            if (health <= healthLevel.get()) {
                fire(String.format("health %.1f", health));
                return;
            }
        }

        if (playersTrigger.get()) {
            double range = playerRange.get();
            for (Player player : mc.level.players()) {
                if (player == mc.player || Friends.get().isFriend(player)) continue;
                if (mc.player.distanceTo(player) <= range) {
                    fire(player.getName().getString() + " is close");
                    return;
                }
            }
        }
    }

    private void fire(String reason) {
        warning("Pulling out: %s.", reason);
        Modules.get().get(StasisPull.class).pull();
        if (toggleOff.get()) toggle();
    }
}
