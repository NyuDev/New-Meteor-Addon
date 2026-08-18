package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.StasisControl;
import fr.nyuway.newaddon.gui.PasswordRenderer;
import fr.nyuway.newaddon.utils.StasisBots;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.IntSetting;
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
 * StasisPull - asks a stasis bot to pull you home.
 *
 * <h2>One section per bot</h2>
 * A pearl is a place, and most people have several: home, the base, the stash three thousand
 * blocks out. They are not one bot with several addresses either - one might be a StasisBot on a
 * box you own answering an encrypted HTTP frame, and the next a spare account you whisper. So a
 * bot is configured whole, in its own section: a name, a key, whether it is the default one, how
 * to reach it, and then whatever that way of reaching it needs.
 *
 * <p>The number of sections follows the {@code bots} slider, which is what "add a bot" is here.
 * Meteor has no repeatable settings group, but it does drop a section whose settings are all
 * hidden - so the sections all exist and the ones past the count are simply not drawn. Real
 * controls either way: a keybind widget for the key, a checkbox for the default, a masked box
 * for the secret.
 *
 * <h2>Three ways to ask</h2>
 * <ul>
 *   <li>{@code Chat} - say a trigger word in public chat. Works with any bot that watches
 *       chat, but everyone sees it.</li>
 *   <li>{@code Whisper} - the same word sent as {@code /msg &lt;bot&gt; &lt;word&gt;}.
 *       Private, but still a chat packet the server can rate-limit.</li>
 *   <li>{@code Http} - StasisBot's encrypted control channel. Nothing goes through the game
 *       server at all, so there is nothing to see, log, or rate-limit.</li>
 * </ul>
 *
 * <p>Several trigger words can be listed per bot and one is picked at random per pull, which is
 * how StasisBot itself suggests dodging server anti-spam on repeated identical lines.
 *
 * <h2>Off means off</h2>
 * Nothing can ask a bot for anything while this module is switched off - not the keys, not the
 * button, not {@link StasisProtection}'s escape pull, not {@link AutoStasisPull}. One switch, and
 * it is the module's own, rather than a set of things that each have to be remembered separately.
 */
public class StasisPull extends Module {

    /**
     * Fixed client-side spacing between two pulls. Not a setting: there is no good reason to
     * ask a bot twice in five seconds, and every reason not to hand the server a burst.
     */
    private static final long COOLDOWN_MS = 5_000L;

    /** How many bots can be configured. Sections past the count are not drawn. */
    private static final int MAX_BOTS = 8;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> count = sgGeneral.add(new IntSetting.Builder()
        .name("bots")
        .description("How many bots you have. Raising this adds a section below to configure " +
                     "the new one in.")
        .defaultValue(1).min(1).max(MAX_BOTS).sliderRange(1, MAX_BOTS)
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

    // --- what an older version wrote, read once and thrown away ---------------

    private final Setting<Boolean> migrated = sgGeneral.add(new BoolSetting.Builder()
        .name("migrated")
        .description("Whether the bots from an older version have been read in already.")
        .defaultValue(false)
        .visible(() -> false)
        .build());

    private final SettingGroup sgOldList = settings.createGroup("Bots");
    private final SettingGroup sgOldDefaults = settings.createGroup("Defaults");
    private final SettingGroup sgOldHttp = settings.createGroup("HTTP defaults");

    private final Setting<List<String>> oldBots = sgOldList.add(new StringListSetting.Builder()
        .name("bots").description("").defaultValue(List.of()).visible(() -> false).build());

    private final Setting<StasisBots.Mode> oldMode = sgOldDefaults.add(new EnumSetting.Builder<StasisBots.Mode>()
        .name("mode").description("").defaultValue(StasisBots.Mode.Chat).visible(() -> false).build());

    private final Setting<List<String>> oldMessages = sgOldDefaults.add(new StringListSetting.Builder()
        .name("messages").description("").defaultValue(List.of("!home")).visible(() -> false).build());

    private final Setting<String> oldCommand = sgOldDefaults.add(new StringSetting.Builder()
        .name("whisper-command").description("").defaultValue("/msg").visible(() -> false).build());

    private final Setting<String> oldAccount = sgOldDefaults.add(new StringSetting.Builder()
        .name("bot-name").description("").defaultValue("").visible(() -> false).build());

    private final Setting<String> oldEndpoint = sgOldHttp.add(new StringSetting.Builder()
        .name("endpoint").description("").defaultValue("http://localhost:6969").visible(() -> false).build());

    private final Setting<String> oldSecret = sgOldHttp.add(new StringSetting.Builder()
        .name("secret").description("").defaultValue("").visible(() -> false).build());

    // --- the bots ------------------------------------------------------------

    /** One bot: its own section, and everything it needs in it. */
    private final class Slot {

        private final int index;

        private final Setting<String> name;
        private final Setting<Boolean> preferred;
        private final Setting<Keybind> key;
        private final Setting<StasisBots.Mode> mode;
        private final Setting<List<String>> messages;
        private final Setting<String> command;
        private final Setting<String> account;
        private final Setting<String> endpoint;
        private final Setting<String> secret;

        /** Last state of this bot's key, so a held key pulls once rather than every tick. */
        private boolean held;

        private Slot(int index) {
            this.index = index;

            // Only the first section starts open. Eight expanded sections is a wall, and the
            // one you are configuring is the one you just added.
            SettingGroup sg = settings.createGroup("Bot " + (index + 1), index == 0);
            IVisible exists = () -> index < count.get();

            name = sg.add(new StringSetting.Builder()
                .name("name")
                .description("What to call this bot. Used by .stasis and on its button.")
                .defaultValue("bot-" + (index + 1))
                .visible(exists)
                .build());

            preferred = sg.add(new BoolSetting.Builder()
                .name("default")
                .description("Use this one when nothing says otherwise - the escape pull " +
                             "StasisProtection fires, and AutoStasisPull. Ticking it unticks " +
                             "the others: there is only one default.")
                .defaultValue(index == 0)
                .onChanged(on -> { if (on) keepOnlyDefault(index); })
                .visible(exists)
                .build());

            key = sg.add(new KeybindSetting.Builder()
                .name("key")
                .description("Pulls with this bot. Read only while the module is on.")
                .defaultValue(Keybind.none())
                .visible(exists)
                .build());

            mode = sg.add(new EnumSetting.Builder<StasisBots.Mode>()
                .name("mode")
                .description("How to reach this bot. Http goes straight to StasisBot and never " +
                             "touches the game server.")
                .defaultValue(StasisBots.Mode.Chat)
                .visible(exists)
                .build());

            messages = sg.add(new StringListSetting.Builder()
                .name("messages")
                .description("Trigger words this bot listens for. One is picked at random per " +
                             "pull, which keeps repeated pulls from looking like spam.")
                .defaultValue(List.of("!home"))
                .visible(() -> exists.isVisible() && mode.get() != StasisBots.Mode.Http)
                .build());

            command = sg.add(new StringSetting.Builder()
                .name("whisper-command")
                .description("Command used to whisper. Servers vary: /msg, /w, /tell.")
                .defaultValue("/msg")
                .visible(() -> exists.isVisible() && mode.get() == StasisBots.Mode.Whisper)
                .build());

            account = sg.add(new StringSetting.Builder()
                .name("bot-name")
                .description("Account the whisper goes to.")
                .defaultValue("")
                .visible(() -> exists.isVisible() && mode.get() == StasisBots.Mode.Whisper)
                .build());

            endpoint = sg.add(new StringSetting.Builder()
                .name("endpoint")
                .description("Full URL of this bot's control server, including the protocol.")
                .defaultValue("http://localhost:6969")
                .visible(() -> exists.isVisible() && mode.get() == StasisBots.Mode.Http)
                .wide()
                .build());

            secret = sg.add(new StringSetting.Builder()
                .name("secret")
                .description("Shared secret, identical to this bot's. Hidden on screen, but " +
                             "Meteor still stores it in plain text in its config like any " +
                             "other setting.")
                .defaultValue("")
                .visible(() -> exists.isVisible() && mode.get() == StasisBots.Mode.Http)
                .renderer(PasswordRenderer.class)
                .wide()
                .build());
        }

        /** What to call it. Never blank, so there is always something to type at {@code .stasis}. */
        private String label() {
            String written = name.get().trim();
            return written.isEmpty() ? "bot-" + (index + 1) : written;
        }

        /** What this bot is missing before it could send anything, or null when it is fine. */
        private String missing() {
            return switch (mode.get()) {
                case Chat -> messages.get().isEmpty() ? "a trigger word" : null;
                case Whisper -> {
                    if (account.get().isBlank()) yield "a bot name to whisper";
                    yield messages.get().isEmpty() ? "a trigger word" : null;
                }
                case Http -> {
                    if (endpoint.get().isBlank()) yield "an endpoint";
                    yield secret.get().isBlank() ? "a secret" : null;
                }
            };
        }

        /** One of its trigger words. Only called once {@link #missing} has come back null. */
        private String word() {
            List<String> list = messages.get();
            return list.size() == 1
                ? list.get(0)
                : list.get(ThreadLocalRandom.current().nextInt(list.size()));
        }
    }

    private final List<Slot> slots = new ArrayList<>(MAX_BOTS);

    /** True while a default checkbox is being unticked by another one being ticked. */
    private boolean settling;

    private long lastPull;

    public StasisPull() {
        super(NewAddon.CATEGORY, "stasis-pull",
            "Asks a stasis bot to pull you home, by chat, whisper, or StasisBot's encrypted API.");

        for (int i = 0; i < MAX_BOTS; i++) slots.add(new Slot(i));
    }

    /** The module, or null before Meteor has built it. */
    public static StasisPull get() {
        return Modules.get() == null ? null : Modules.get().get(StasisPull.class);
    }

    // --- which one is the default --------------------------------------------

    /** Unticks every other default box, so ticking one is choosing rather than adding. */
    private void keepOnlyDefault(int index) {
        if (settling) return;

        settling = true;
        for (Slot slot : slots) {
            if (slot.index != index && slot.preferred.get()) slot.preferred.set(false);
        }
        settling = false;
    }

    /** The bot everything automatic uses: the ticked one, or the first if none is ticked. */
    private Slot preferred() {
        for (int i = 0; i < count.get(); i++) {
            if (slots.get(i).preferred.get()) return slots.get(i);
        }
        return slots.get(0);
    }

    /** The bot with that name, or null. */
    private Slot byName(String name) {
        if (name == null || name.isBlank()) return null;

        String wanted = name.trim();
        for (int i = 0; i < count.get(); i++) {
            if (slots.get(i).label().equalsIgnoreCase(wanted)) return slots.get(i);
        }
        return null;
    }

    /** Every bot that exists, in order. Read by {@code .stasis list}. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count.get(); i++) out.add(slots.get(i).label());
        return out;
    }

    /** How a bot is set up, as one line, for {@code .stasis list}. The secret is never in it. */
    public String describe(String name) {
        Slot slot = byName(name);
        if (slot == null) return null;

        StringBuilder out = new StringBuilder(slot.label());
        out.append(" (").append(slot.mode.get().name().toLowerCase()).append(")");

        switch (slot.mode.get()) {
            case Chat -> out.append(" says ").append(String.join(", ", slot.messages.get()));
            case Whisper -> out.append(" whispers ").append(slot.account.get());
            case Http -> out.append(" at ").append(slot.endpoint.get());
        }

        if (slot.preferred.get()) out.append(" - default");
        if (slot.key.get().isSet()) out.append(" - bound");

        String missing = slot.missing();
        if (missing != null) out.append(" - needs ").append(missing);

        return out.toString();
    }

    /** Names the default bot, for {@code .stasis default}. */
    public boolean setDefault(String name) {
        Slot slot = byName(name);
        if (slot == null) return false;

        slot.preferred.set(true);
        keepOnlyDefault(slot.index);
        Systems.save();
        return true;
    }

    // --- the buttons ---------------------------------------------------------

    /**
     * A row per bot under the settings: its name, and a button that pulls with it.
     *
     * <p>Meteor gives a module one widget below its settings rather than one per section, so
     * this is the closest thing to a trigger next to each bot - and it is worth having, because
     * a key you have to bind before you can test a bot is a key you bind before you know the
     * bot works.
     */
    @Override
    public WWidget getWidget(GuiTheme theme) {
        migrate();

        WTable table = theme.table();

        for (int i = 0; i < count.get(); i++) {
            Slot slot = slots.get(i);
            String label = slot.label();

            table.add(theme.label(label));

            WButton button = table.add(theme.button("Pull")).expandCellX().widget();
            button.action = () -> pull(label);

            table.row();
        }

        return table;
    }

    // --- firing --------------------------------------------------------------

    @Override
    public void onActivate() {
        migrate();
        for (Slot slot : slots) slot.held = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        for (int i = 0; i < count.get(); i++) {
            Slot slot = slots.get(i);
            Keybind key = slot.key.get();
            boolean down = key.isSet() && key.isPressed();

            // The screen check is on the press rather than the poll, so letting go of a key
            // while a screen is open does not leave the slot stuck down.
            if (down && !slot.held && mc.screen == null) pull(slot.label());
            slot.held = down;
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
        return preferred().missing() == null;
    }

    /** Sends one pull request to the default bot. Public so other modules can trigger it. */
    public void pull() {
        pull(null);
    }

    /**
     * Sends one pull request.
     *
     * @param name which bot, or null for the default one
     */
    public void pull(String name) {
        // Off means off, for everything: the keys, the button, and the two modules that fire a
        // pull on your behalf. A module that keeps working while switched off is not a switch.
        if (!isActive()) {
            warning("stasis-pull is off; nothing was sent.");
            return;
        }

        if (mc.player == null) return;

        Slot slot = byName(name);
        if (slot == null) {
            if (name != null && !name.isBlank()) {
                error("No bot called %s; using the default.", name);
            }
            slot = preferred();
        }

        String missing = slot.missing();
        if (missing != null) {
            error("%s has no %s.", slot.label(), missing);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPull < COOLDOWN_MS) {
            if (notify.get()) warning("On cooldown.");
            return;
        }
        lastPull = now;

        switch (slot.mode.get()) {
            case Chat -> {
                String message = slot.word();
                ChatUtils.sendPlayerMsg(message);
                if (notify.get()) info("Pull requested.");
                if (debug.get()) log("chat as %s: %s", slot.label(), message);
            }

            case Whisper -> {
                String message = slot.word();

                String command = slot.command.get().trim();
                if (!command.startsWith("/")) command = "/" + command;

                ChatUtils.sendPlayerMsg(command + " " + slot.account.get().trim() + " " + message);
                if (notify.get()) info("Pull requested.");
                if (debug.get()) log("whisper as %s to %s: %s", slot.label(), slot.account.get(), message);
            }

            case Http -> {
                // Entity#getName is stable across every target; the player's game profile is
                // not reachable the same way from 1.21.10 onward.
                String me = mc.player.getName().getString();
                if (notify.get()) info("Pull requested.");
                if (debug.get()) log("http pull as %s for %s at %s", slot.label(), me, slot.endpoint.get());

                StasisControl.homeRequest(slot.endpoint.get(), slot.secret.get(), me, reply -> {
                    // Callback lands on the HTTP thread; chat must be touched on the game one.
                    if (notify.get()) mc.execute(() -> info(reply));
                });
            }
        }
    }

    // --- reading an older config ---------------------------------------------

    /**
     * Fills the sections in from whatever an older version left behind, once.
     *
     * <p>The bots used to be lines of text, and before that a single set of settings. Upgrading
     * should not quietly empty a configuration somebody typed out - and the one thing in there
     * that is genuinely annoying to replace is a shared secret nobody has written down anywhere
     * else. Runs on activation and on opening the module's page, and marks itself done.
     */
    private void migrate() {
        if (migrated.get()) return;
        migrated.set(true);

        List<StasisBots.Bot> old = StasisBots.parse(oldBots.get());

        // No list: the settings that used to describe a single bot, if any of them was touched.
        if (old.isEmpty()) {
            boolean touched = !oldSecret.get().isBlank() || !oldAccount.get().isBlank()
                || oldMode.get() != StasisBots.Mode.Chat;
            if (!touched) return;

            old = List.of(new StasisBots.Bot("home", oldMode.get(), oldMessages.get(),
                oldCommand.get(), oldMode.get() == StasisBots.Mode.Http
                    ? oldEndpoint.get() : oldAccount.get(), oldSecret.get()));
        }

        int taken = Math.min(old.size(), MAX_BOTS);
        for (int i = 0; i < taken; i++) {
            StasisBots.Bot bot = old.get(i);
            Slot slot = slots.get(i);

            slot.name.set(bot.label());
            slot.mode.set(bot.mode() == null ? oldMode.get() : bot.mode());
            slot.messages.set(bot.messages().isEmpty() ? oldMessages.get() : bot.messages());
            slot.command.set(bot.command().isBlank() ? oldCommand.get() : bot.command());

            if (slot.mode.get() == StasisBots.Mode.Http) {
                slot.endpoint.set(bot.target().isBlank() ? oldEndpoint.get() : bot.target());
                slot.secret.set(bot.secret().isBlank() ? oldSecret.get() : bot.secret());
            } else {
                slot.account.set(bot.target().isBlank() ? oldAccount.get() : bot.target());
            }

            slot.preferred.set(i == 0);
        }

        count.set(Math.max(taken, 1));
        Systems.save();

        NewAddon.LOG.info("[StasisPull] read {} bot(s) from the old configuration", taken);
    }

    private void log(String fmt, Object... args) {
        NewAddon.LOG.info("[StasisPull] " + String.format(fmt, args));
    }
}
