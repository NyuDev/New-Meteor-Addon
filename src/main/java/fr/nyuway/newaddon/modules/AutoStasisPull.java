package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.WorldBounds;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.Deque;

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

    /**
     * Ticks to wait after a pop before trusting the inventory count. The server decrements
     * the totem stack and syncs the new slot contents at the end of the same tick the pop
     * happens on; that sync packet is not guaranteed to have been processed yet by the time
     * our handler for the entity-status packet runs, so reading the inventory immediately
     * can still see the pre-pop count.
     */
    private static final int RECOUNT_DELAY_TICKS = 2;

    public enum TotemMode {
        /** Fire when a pop leaves you with few totems left - the useful low-supply warning. */
        Remaining,
        /** Fire on a burst of pops close together in time, regardless of supply. */
        PopsInWindow
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("Triggers");

    private final Setting<Boolean> disableAutoLog = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-autolog")
        .description("Switch Meteor's AutoLog off while this is on, so you are not pulled and " +
                     "disconnected at the same time.")
        .defaultValue(true)
        .build());

    /** Ticks a second pull is refused after one goes out, however this module gets ticked. */
    private static final int REFIRE_GUARD = 20 * 10;

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Write the exact trigger - health, height, who was near - to the game log. " +
                     "Chat only says that a pull happened.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> toggleOff = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-off")
        .description("Turn this module off after a pull, so it does not keep firing while you " +
                     "are still hurt.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> voidTrigger = sgTriggers.add(new BoolSetting.Builder()
        .name("void")
        .description("Pull the instant you are low enough to take void damage. On by default " +
                     "and checked before every other trigger, because void damage kills " +
                     "straight through a totem.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> voidAuto = sgTriggers.add(new BoolSetting.Builder()
        .name("void-y-auto")
        .description("Work the height out from the dimension instead of using a fixed number. " +
                     "Vanilla starts void damage 64 below the world floor, which is about " +
                     "-128 in the Overworld but -64 in the Nether and the End.")
        .defaultValue(true)
        .visible(voidTrigger::get)
        .build());

    private final Setting<Integer> voidY = sgTriggers.add(new IntSetting.Builder()
        .name("void-y")
        .description("Height to pull at when the automatic one is off. Set it well above the " +
                     "damage line to leave the bot time to answer.")
        .defaultValue(-128).min(-256).max(320).sliderMin(-160).sliderMax(64)
        .visible(() -> voidTrigger.get() && !voidAuto.get())
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

    private final Setting<Boolean> totemTrigger = sgTriggers.add(new BoolSetting.Builder()
        .name("totem")
        .description("Pull when you pop a totem, gated by one of the modes below.")
        .defaultValue(true)
        .build());

    private final Setting<TotemMode> totemMode = sgTriggers.add(new EnumSetting.Builder<TotemMode>()
        .name("totem-mode")
        .description("Remaining checks how many totems you have left after the pop. " +
                     "PopsInWindow instead counts pops within a time window, ignoring supply.")
        .defaultValue(TotemMode.Remaining)
        .visible(totemTrigger::get)
        .build());

    private final Setting<Integer> totemRemaining = sgTriggers.add(new IntSetting.Builder()
        .name("totem-remaining")
        .description("Pull when a pop leaves you with this many totems or fewer, anywhere in " +
                     "your inventory.")
        .defaultValue(3).min(0).max(64).sliderMin(0).sliderMax(10)
        .visible(() -> totemTrigger.get() && totemMode.get() == TotemMode.Remaining)
        .build());

    private final Setting<Integer> totemWindowPops = sgTriggers.add(new IntSetting.Builder()
        .name("totem-window-pops")
        .description("Pops within the time window needed to pull.")
        .defaultValue(3).min(2).max(10).sliderMin(2).sliderMax(6)
        .visible(() -> totemTrigger.get() && totemMode.get() == TotemMode.PopsInWindow)
        .build());

    private final Setting<Integer> totemWindowSeconds = sgTriggers.add(new IntSetting.Builder()
        .name("totem-window-seconds")
        .description("Time window the pops above must fall within.")
        .defaultValue(300).min(5).max(1800).sliderMin(30).sliderMax(600)
        .visible(() -> totemTrigger.get() && totemMode.get() == TotemMode.PopsInWindow)
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

    /** Recent pop timestamps (ms), oldest first. Only used by {@link TotemMode#PopsInWindow}. */
    private final Deque<Long> popTimes = new ArrayDeque<>();

    private int ticks;
    /** Tick the last pull went out on, or -1 when none has. */
    private int firedAtTick = -1;
    /** Tick to recount the inventory on, or -1 when no recount is pending. */
    private int recountAtTick = -1;

    public AutoStasisPull() {
        super(NewAddon.CATEGORY, "auto-stasis-pull",
            "Triggers a stasis pull when you are about to die, instead of logging out.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        recountAtTick = -1;
        firedAtTick = -1;
        popTimes.clear();

        // Looked up by name, not by class: AutoLog has moved package between Meteor
        // versions, and this addon has to compile against all twelve of them.
        if (disableAutoLog.get()) {
            Module autoLog = Modules.get().get("auto-log");
            if (autoLog != null && autoLog.isActive()) {
                autoLog.toggle();
                info("Turned AutoLog off - this pulls instead.");
            }
        }

        StasisPull pull = Modules.get().get(StasisPull.class);
        if (!pull.isActive()) warning("stasis-pull is off; a pull would do nothing.");
        else if (!pull.isConfigured()) warning("The default stasis bot is not configured.");
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!totemTrigger.get() || mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != TOTEM_POP_EVENT) return;
        if (p.getEntity(mc.level) != mc.player) return;

        if (totemMode.get() == TotemMode.Remaining) {
            // The slot-update packet for the consumed totem may not have landed yet -
            // recount a couple of ticks from now instead of trusting the count right away.
            recountAtTick = ticks + RECOUNT_DELAY_TICKS;
        } else {
            popTimes.addLast(System.currentTimeMillis());
            long windowStart = System.currentTimeMillis() - totemWindowSeconds.get() * 1000L;
            while (!popTimes.isEmpty() && popTimes.peekFirst() < windowStart) popTimes.pollFirst();

            if (popTimes.size() >= totemWindowPops.get()) {
                fire("totems popping", popTimes.size() + " within " + totemWindowSeconds.get() + "s");
                popTimes.clear();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        ticks++;

        // Checked before everything else, and without any of the usual gating. A totem does
        // not save you from void damage - it kills through them - so there is nothing to
        // weigh up: either the pull happens now or it does not happen.
        if (voidTrigger.get() && mc.player.getY() < voidThreshold()) {
            fire("falling out of the world",
                String.format("y %.1f, void damage below %.0f in %s", mc.player.getY(),
                    voidThreshold(), mc.level.dimension()));
            return;
        }

        if (recountAtTick != -1 && ticks >= recountAtTick) {
            recountAtTick = -1;
            int left = countTotems();
            if (left <= totemRemaining.get()) {
                fire("totem popped", left + " totems left");
                return;
            }
        }

        if (healthTrigger.get()) {
            float health = mc.player.getHealth();
            if (countAbsorption.get()) health += mc.player.getAbsorptionAmount();
            if (health <= healthLevel.get()) {
                fire("hurt", String.format("health %.1f", health));
                return;
            }
        }

        if (playersTrigger.get()) {
            double range = playerRange.get();
            for (Player player : mc.level.players()) {
                if (player == mc.player || Friends.get().isFriend(player)) continue;
                if (mc.player.distanceTo(player) <= range) {
                    fire("someone is close", player.getName().getString() + " within range");
                    return;
                }
            }
        }
    }

    /** Height at or below which to pull, from the dimension or from the manual setting. */
    private double voidThreshold() {
        return voidAuto.get() ? WorldBounds.voidDamageY(mc.level) : voidY.get();
    }

    /** Totems anywhere on the player: hotbar, main inventory, armor and offhand slots. */
    private int countTotems() {
        var inv = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) total += stack.getCount();
        }
        return total;
    }

    /**
     * @param reason short, vague, for chat
     * @param detail the numbers behind it, for the log only - chat is public the moment it is
     *               screenshotted, and a height or a name is most of a location
     */
    private void fire(String reason, String detail) {
        // Toggling off from inside an event handler does not stop this tick's dispatch, and
        // apparently not the next few either: one fall into the void fired this thirteen
        // times in a second and a half. Only StasisPull's own cooldown kept that from being
        // thirteen messages to the bot. A guard here does not depend on the toggle landing.
        if (firedAtTick != -1 && ticks - firedAtTick < REFIRE_GUARD) return;
        firedAtTick = ticks;

        warning("Pulling out: %s.", reason);

        // Not behind the debug switch: this decision costs whatever trip you were on, and
        // being unable to say afterwards why it fired is worse than a line in the log.
        NewAddon.LOG.info("[AutoStasisPull] pulling out: " + reason + " (" + detail + ")");

        Modules.get().get(StasisPull.class).pull();
        if (toggleOff.get()) toggle();
    }
}
