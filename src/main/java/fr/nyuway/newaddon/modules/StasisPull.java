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
import meteordevelopment.meteorclient.systems.Systems;
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
 * <h2>A bot is a whole configuration</h2>
 * A pearl is a place, and anyone who has played long enough has several: home, the base, the
 * stash three thousand blocks out. They are not the same bot with a different address, either -
 * one might be a StasisBot on a box you own, answering an encrypted HTTP frame, and the next a
 * spare account you whisper. So each line of {@code bots} carries the lot: its mode, its own
 * trigger words, its own whisper command, its own endpoint and secret.
 *
 * <pre>
 * home; mode=http; url=http://nyuway.fr:6969; secret=hunter2
 * base; mode=whisper; to=Shasync; say=!home
 * spawn; mode=chat; say=!spawn
 * </pre>
 *
 * <p>The settings underneath the list are the <em>defaults</em>: anything a line does not say
 * is taken from them. That is what lets {@code spawn; mode=chat} be a legal bot, and it is also
 * the entire configuration when the list is empty, which is every setup with one pearl.
 *
 * <p>One of them is the default bot, and that is what everything automatic uses - the escape
 * pull {@link StasisProtection} fires when an ambush starts, and {@link AutoStasisPull} when you
 * are about to die.
 *
 * <h2>Armed, or a button</h2>
 * The module has always been a button: switch it on, one request goes out, it switches itself
 * back off. That works for exactly one bot, because a module has exactly one keybind. So it now
 * has two behaviours - {@code Armed}, where the module stays on and each bot in the list has its
 * own key, and {@code Button}, the old way. Armed is the default, and is what "the bots are
 * available while the module is on" means: the keys are read on the tick, and a module that is
 * off has no ticks.
 *
 * <p>Several trigger words can be listed per bot and one is picked at random per pull, which is
 * how StasisBot itself suggests dodging server anti-spam on repeated identical lines.
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
    private final SettingGroup sgDefaults = settings.createGroup("Defaults");
    private final SettingGroup sgHttp = settings.createGroup("HTTP defaults");

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
        .description("One bot per line, each with its own configuration: " +
                     "label; mode=http; url=...; secret=... - or mode=whisper with to= and say=, " +
                     "or mode=chat with say=. Anything a line leaves out comes from the defaults " +
                     "below. Empty list means the defaults are the one bot. Easier from chat: " +
                     ".stasis add, .stasis set. Note that a secret typed here is shown in full.")
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

    private final Setting<StasisBots.Mode> mode = sgDefaults.add(new EnumSetting.Builder<StasisBots.Mode>()
        .name("mode")
        .description("How to ask a bot that does not say. Http goes straight to StasisBot and " +
                     "never touches the game server.")
        .defaultValue(StasisBots.Mode.Chat)
        .build());

    private final Setting<List<String>> messages = sgDefaults.add(new StringListSetting.Builder()
        .name("messages")
        .description("Trigger words for a bot with no say= of its own. One is picked at random " +
                     "per pull, which keeps repeated pulls from looking like spam.")
        .defaultValue(List.of("!home"))
        .build());

    private final Setting<String> whisperCommand = sgDefaults.add(new StringSetting.Builder()
        .name("whisper-command")
        .description("Command used to whisper, for a bot with no cmd= of its own. Servers vary: " +
                     "/msg, /w, /tell.")
        .defaultValue("/msg")
        .build());

    private final Setting<String> botName = sgDefaults.add(new StringSetting.Builder()
        .name("bot-name")
        .description("Account a whisper goes to, for a bot with no to= of its own.")
        .defaultValue("")
        .visible(() -> !bots.get().isEmpty() || mode.get() == StasisBots.Mode.Whisper)
        .build());

    private final Setting<String> endpoint = sgHttp.add(new StringSetting.Builder()
        .name("endpoint")
        .description("Control server for a bot with no url= of its own. Full URL, protocol and all.")
        .defaultValue("http://localhost:6969")
        .visible(() -> !bots.get().isEmpty() || mode.get() == StasisBots.Mode.Http)
        .wide()
        .build());

    private final Setting<String> secret = sgHttp.add(new StringSetting.Builder()
        .name("secret")
        .description("Shared secret for a bot with no secret= of its own, identical to the " +
                     "bot's. Hidden on screen, but Meteor still stores it in plain text in its " +
                     "config like any other setting.")
        .defaultValue("")
        .visible(() -> !bots.get().isEmpty() || mode.get() == StasisBots.Mode.Http)
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

    /**
     * The configured bots, exactly as written.
     *
     * <p>Unfilled: what a line does not say is still missing here, which is what {@code .stasis
     * show} needs to tell inherited from set. Use {@link #filled} for a bot about to be used.
     */
    public List<StasisBots.Bot> parsed() {
        return StasisBots.parse(bots.get());
    }

    /** Writes the list back, so the commands can edit bots without hand-typing a line. */
    public void store(List<StasisBots.Bot> list) {
        bots.set(StasisBots.write(list));

        // Meteor writes its config out on its own schedule, and the config is what survives a
        // crash. A bot added from chat should not depend on the game closing tidily.
        Systems.save();
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
        StasisBots.Bot bot = StasisBots.byLabel(parsed(), label);
        if (bot == null) return false;

        defaultBot.set(bot.label());
        Systems.save();
        return true;
    }

    /**
     * A bot with every blank filled in from the defaults, ready to be used.
     *
     * <p>Kept separate from the parsed form on purpose. A bot that says nothing about its
     * trigger words and a bot whose trigger words happen to match the default look identical
     * once filled, and the difference matters when you are reading the config to work out why
     * two bots answered at once.
     */
    public StasisBots.Bot filled(StasisBots.Bot bot) {
        if (bot == null) return null;

        StasisBots.Mode m = bot.mode() == null ? mode.get() : bot.mode();
        List<String> say = bot.messages().isEmpty() ? messages.get() : bot.messages();
        String cmd = bot.command().isBlank() ? whisperCommand.get() : bot.command();

        String target = bot.target();
        if (target.isBlank()) target = m == StasisBots.Mode.Http ? endpoint.get() : botName.get();

        String key = bot.secret().isBlank() ? secret.get() : bot.secret();

        return new StasisBots.Bot(bot.label(), m, say, cmd, target.trim(), key.trim());
    }

    /**
     * The bot to use for this pull, filled in and ready.
     *
     * <p>When the list is empty the defaults are read as a single unnamed bot, which is what
     * every existing config is: nobody has to learn the line format to keep what they had.
     */
    private StasisBots.Bot resolve(String label) {
        List<StasisBots.Bot> list = parsed();
        if (!list.isEmpty()) return filled(StasisBots.pick(list, label, defaultBot.get()));

        return filled(new StasisBots.Bot("default", null, List.of(), "", "", ""));
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
        return StasisBots.usable(resolve(null));
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

        String missing = StasisBots.missing(bot);
        if (missing != null) {
            error("%s has no %s.", bot.label(), missing);
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
                String message = pick(bot);
                ChatUtils.sendPlayerMsg(message);
                if (notify.get()) info("Pull requested.");
                if (debug.get()) log("chat as %s: %s", bot.label(), message);
            }

            case Whisper -> {
                String message = pick(bot);

                String command = bot.command().trim();
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

    /** One of the bot's trigger words. Never empty: {@code missing} has already run. */
    private static String pick(StasisBots.Bot bot) {
        List<String> list = bot.messages();
        return list.size() == 1 ? list.get(0) : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}
