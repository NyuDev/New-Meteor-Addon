package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.StasisControl;
import fr.nyuway.newaddon.gui.PasswordRenderer;
import fr.nyuway.newaddon.utils.StasisBots;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * StasisPull - asks a stasis bot to pull you home, however you like to ask.
 *
 * <p>Three ways to ask, because they suit different setups:
 * <ul>
 *   <li>{@code Chat} - say a trigger word in public chat. Works with any bot that watches
 *       chat, but everyone sees it.</li>
 *   <li>{@code Whisper} - the same word sent as {@code /msg &lt;bot&gt; &lt;word&gt;}.
 *       Private, but still a chat packet the server can rate-limit.</li>
 *   <li>{@code Http} - StasisBot's encrypted control channel. Nothing goes through the game
 *       server at all, so there is nothing to see, log, or rate-limit.</li>
 * </ul>
 *
 * <h2>More than one bot</h2>
 * A pearl is a place, and anyone who has played long enough has several: home, the base, the
 * stash three thousand blocks out. The {@code bots} list holds one line each, one of them is
 * marked the default, and the default is the one everything automatic uses - the escape pull
 * {@link StasisProtection} fires when an ambush starts, and {@link AutoStasisPull} when you are
 * about to die. Leave the list empty and the plain settings underneath are used instead, which
 * is the whole configuration for anyone with one pearl.
 *
 * <h2>Armed, or a button</h2>
 * The module has always been a button: switch it on, one request goes out, it switches itself
 * back off. That works for exactly one bot, because a module has exactly one keybind. So it now
 * has two behaviours - {@code Armed}, where the module stays on and each bot in the list has its
 * own key, and {@code Button}, the old way. Armed is the default, and is what "the bots are
 * available while the module is on" means: the keys are read on the tick, and a module that is
 * off has no ticks.
 *
 * <p>Several trigger words can be listed and one is picked at random per pull, which is how
 * StasisBot itself suggests dodging server anti-spam on repeated identical lines.
 */
public class StasisPull extends Module {

    /**
     * Fixed client-side spacing between two pulls. Not a setting: there is no good reason to
     * ask a bot twice in five seconds, and every reason not to hand the server a burst.
     */
    private static final long COOLDOWN_MS = 5_000L;

    /** How many bots can have a key of their own. Past this, use a macro on {@code .stasis}. */
    private static final int KEY_SLOTS = 6;

    /** What switching the module on does. */
    public enum Behaviour {
        /** Stays on, and each bot in the list answers to its own key. */
        Armed,
        /** Pulls with the default bot straight away, then switches off. The original way. */
        Button
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBots = settings.createGroup("Bots");
    private final SettingGroup sgKeys = settings.createGroup("Keys");
    private final SettingGroup sgChat = settings.createGroup("Chat");
    private final SettingGroup sgHttp = settings.createGroup("HTTP");

    private final Setting<Behaviour> behaviour = sgGeneral.add(new EnumSetting.Builder<Behaviour>()
        .name("behaviour")
        .description("Armed keeps the module on so the per-bot keys work. Button pulls with the " +
                     "default bot the moment it is switched on, then switches off again.")
        .defaultValue(Behaviour.Armed)
        .build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Say in your own chat that a pull was requested. Deliberately does not " +
                     "print the trigger word or the bot name: those are what someone reading " +
                     "over your shoulder, or a screenshot, would need.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Write the trigger word, the bot name and the endpoint to the game log.")
        .defaultValue(false)
        .build());

    private final Setting<List<String>> bots = sgBots.add(new StringListSetting.Builder()
        .name("bots")
        .description("One bot per line: label | mode | target | secret. Mode is chat, whisper " +
                     "or http; target is the account to whisper or the URL to call; the secret " +
                     "is for http only. Everything after the label may be left out. Leave the " +
                     "whole list empty to use the single bot in the settings below. Note that a " +
                     "secret written here is shown in full in this box, unlike the one below.")
        .defaultValue(List.of())
        .build());

    private final Setting<String> defaultBot = sgBots.add(new StringSetting.Builder()
        .name("default-bot")
        .description("Label of the bot used when nothing says otherwise - the module's own key, " +
                     "and every automatic pull. Empty means the first one in the list.")
        .defaultValue("")
        .visible(() -> !bots.get().isEmpty())
        .build());

    /** One per bot, in list order. Slot N belongs to the Nth line of {@code bots}. */
    private final List<Setting<Keybind>> keys = new ArrayList<>();

    private final Setting<StasisBots.Mode> mode = sgChat.add(new EnumSetting.Builder<StasisBots.Mode>()
        .name("mode")
        .description("How to ask the bot, when the list above is empty. Http goes straight to " +
                     "StasisBot and never touches the game server.")
        .defaultValue(StasisBots.Mode.Chat)
        .visible(() -> bots.get().isEmpty())
        .build());

    private final Setting<List<String>> messages = sgChat.add(new StringListSetting.Builder()
        .name("messages")
        .description("Trigger words the bot listens for. One is picked at random per pull, " +
                     "which keeps repeated pulls from looking like spam. Shared by every bot " +
                     "in the list that is asked by chat or whisper.")
        .defaultValue(List.of("!home"))
        .build());

    private final Setting<String> whisperCommand = sgChat.add(new StringSetting.Builder()
        .name("whisper-command")
        .description("Command used to whisper. Servers vary: /msg, /w, /tell.")
        .defaultValue("/msg")
        .build());

    private final Setting<String> botName = sgChat.add(new StringSetting.Builder()
        .name("bot-name")
        .description("Account the whisper goes to, when the list above is empty.")
        .defaultValue("")
        .visible(() -> bots.get().isEmpty() && mode.get() == StasisBots.Mode.Whisper)
        .build());

    private final Setting<String> endpoint = sgHttp.add(new StringSetting.Builder()
        .name("endpoint")
        .description("Full URL of the bot's control server, including the protocol.")
        .defaultValue("http://localhost:6969")
        .visible(() -> bots.get().isEmpty() && mode.get() == StasisBots.Mode.Http)
        .wide()
        .build());

    private final Setting<String> secret = sgHttp.add(new StringSetting.Builder()
        .name("secret")
        .description("Shared secret, identical to the bot's. Hidden on screen, but Meteor " +
                     "still stores it in plain text in its config like any other setting.")
        .defaultValue("")
        .visible(() -> bots.get().isEmpty() && mode.get() == StasisBots.Mode.Http)
        .renderer(PasswordRenderer.class)
        .wide()
        .build());

    private boolean fired;
    private long lastPull;

    /** Last state of each key, so a held key pulls once rather than twenty times a second. */
    private final boolean[] keyHeld = new boolean[KEY_SLOTS];

    public StasisPull() {
        super(NewAddon.CATEGORY, "stasis-pull",
            "Asks a stasis bot to pull you home, by chat, whisper, or StasisBot's encrypted API.");

        // Built in a loop because they only differ by number, and a slot is only shown once the
        // list has a bot for it: an unexplained row of six empty keybinds is not a setting, it is
        // a puzzle.
        for (int i = 0; i < KEY_SLOTS; i++) {
            final int index = i;
            keys.add(sgKeys.add(new KeybindSetting.Builder()
                .name("key-" + (i + 1))
                .description("Pulls with bot number " + (i + 1) + " in the list. Only read while " +
                             "the module is on and behaviour is Armed.")
                .defaultValue(Keybind.none())
                .visible(() -> parsed().size() > index)
                .build()));
        }
    }

    // --- the bots ------------------------------------------------------------

    /** The configured bots, or an empty list when the plain settings are being used instead. */
    public List<StasisBots.Bot> parsed() {
        return StasisBots.parse(bots.get());
    }

    /** Label of the bot used when nothing says otherwise. */
    public String defaultLabel() {
        List<StasisBots.Bot> list = parsed();
        if (list.isEmpty()) return "";

        StasisBots.Bot bot = StasisBots.pick(list, null, defaultBot.get());
        return bot == null ? "" : bot.label();
    }

    /**
     * Marks a bot as the default.
     *
     * @return false when no bot has that label, so the caller can say so rather than silently
     *         writing down a name that means nothing
     */
    public boolean setDefault(String label) {
        for (StasisBots.Bot bot : parsed()) {
            if (bot.label().equalsIgnoreCase(label)) {
                defaultBot.set(bot.label());
                return true;
            }
        }
        return false;
    }

    /**
     * The bot to use for this pull.
     *
     * <p>When the list is empty the plain settings are read as a single unnamed bot, which is
     * what every existing config is: nobody has to learn the line format to keep what they had.
     */
    private StasisBots.Bot resolve(String label) {
        List<StasisBots.Bot> list = parsed();
        if (!list.isEmpty()) return StasisBots.pick(list, label, defaultBot.get());

        StasisBots.Mode m = mode.get();
        String target = switch (m) {
            case Chat -> "";
            case Whisper -> botName.get().trim();
            case Http -> endpoint.get().trim();
        };
        return new StasisBots.Bot("default", m, target, secret.get());
    }

    // --- firing --------------------------------------------------------------

    @Override
    public void onActivate() {
        fired = false;
        for (int i = 0; i < KEY_SLOTS; i++) keyHeld[i] = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (behaviour.get() == Behaviour.Button) {
            // Fire once, on the tick after activation, then switch off: toggling out of
            // onActivate itself would run inside Meteor's own activation.
            if (fired) return;
            fired = true;

            try {
                pull();
            } finally {
                toggle();
            }
            return;
        }

        pollKeys();
    }

    /** Reads the per-bot keys, one pull per press. */
    private void pollKeys() {
        List<StasisBots.Bot> list = parsed();
        if (list.isEmpty() || mc.player == null) return;

        for (int i = 0; i < KEY_SLOTS && i < list.size(); i++) {
            Keybind key = keys.get(i).get();
            boolean down = key.isSet() && key.isPressed();

            // The screen check is on the press rather than the poll, so letting go of a key while
            // a screen is open does not leave the slot stuck down.
            if (down && !keyHeld[i] && mc.screen == null) pull(list.get(i).label());
            keyHeld[i] = down;
        }
    }

    /**
     * When the last pull was requested, in epoch millis, or 0 if never.
     *
     * <p>Read by {@link StasisProtection} so a teleport we asked for ourselves is not
     * mistaken for someone else firing our chamber.
     */
    public long lastPullMillis() {
        return lastPull;
    }

    /** True if the default bot has everything it needs to actually send something. */
    public boolean isConfigured() {
        StasisBots.Bot bot = resolve(null);
        if (!StasisBots.usable(bot)) return false;

        // Chat and whisper both need something to say, and that is shared rather than per bot.
        return bot.mode() == StasisBots.Mode.Http || !messages.get().isEmpty();
    }

    /** Sends one pull request to the default bot. Public so other modules can trigger it. */
    public void pull() {
        pull(null);
    }

    /**
     * Sends one pull request.
     *
     * @param label which bot, or null for the default one
     */
    public void pull(String label) {
        if (mc.player == null) return;

        StasisBots.Bot bot = resolve(label);
        if (bot == null) {
            error("No stasis bots configured.");
            return;
        }

        if (label != null && !label.isBlank() && !bot.label().equalsIgnoreCase(label.trim())) {
            error("No bot called %s; using %s.", label, bot.label());
        }

        if (!StasisBots.usable(bot)) {
            error("%s is missing %s.", bot.label(),
                bot.mode() == StasisBots.Mode.Http ? "its endpoint or secret" : "a bot name");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPull < COOLDOWN_MS) {
            if (notify.get()) warning("On cooldown.");
            return;
        }
        lastPull = now;

        switch (bot.mode()) {
            case Chat -> {
                String message = pickMessage();
                if (message == null) return;
                ChatUtils.sendPlayerMsg(message);
                if (notify.get()) info("Pull requested.");
                if (debug.get()) log("chat as %s: %s", bot.label(), message);
            }

            case Whisper -> {
                String message = pickMessage();
                if (message == null) return;

                String command = whisperCommand.get().trim();
                if (!command.startsWith("/")) command = "/" + command;

                ChatUtils.sendPlayerMsg(command + " " + bot.target() + " " + message);
                if (notify.get()) info("Pull requested.");
                if (debug.get()) log("whisper as %s to %s: %s", bot.label(), bot.target(), message);
            }

            case Http -> {
                // Entity#getName is stable across every target; the player's game profile is
                // not reachable the same way from 1.21.10 onward.
                String target = mc.player.getName().getString();
                if (notify.get()) info("Pull requested.");
                if (debug.get()) log("http pull as %s for %s at %s", bot.label(), target, bot.target());

                StasisControl.homeRequest(bot.target(), bot.secret(), target, reply -> {
                    // Callback lands on the HTTP thread; chat must be touched on the game one.
                    if (notify.get()) mc.execute(() -> info(reply));
                });
            }
        }
    }

    /** The module, or null before Meteor has built it. */
    public static StasisPull get() {
        return Modules.get() == null ? null : Modules.get().get(StasisPull.class);
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[StasisPull] " + String.format(fmt, args));
    }

    private String pickMessage() {
        List<String> list = messages.get();
        if (list == null || list.isEmpty()) {
            error("No trigger words configured.");
            return null;
        }
        return list.size() == 1 ? list.get(0) : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}
