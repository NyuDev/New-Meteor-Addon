package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.compat.StasisControl;
import fr.nyuway.newaddon.gui.PasswordRenderer;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * StasisPull - asks a stasis bot to pull you home, however you like to ask.
 *
 * <p>Acts as a button rather than a toggle: bind a key, press it, one request goes out and
 * the module switches straight back off.
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
 * <p>Several trigger words can be listed and one is picked at random per pull, which is how
 * StasisBot itself suggests dodging server anti-spam on repeated identical lines.
 */
public class StasisPull extends Module {

    /**
     * Fixed client-side spacing between two pulls. Not a setting: there is no good reason to
     * ask a bot twice in five seconds, and every reason not to hand the server a burst.
     */
    private static final long COOLDOWN_MS = 5_000L;

    public enum Mode {
        /** Say a trigger word in public chat. */
        Chat,
        /** Whisper a trigger word to the bot. */
        Whisper,
        /** StasisBot's encrypted HTTP control channel. */
        Http
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChat = settings.createGroup("Chat");
    private final SettingGroup sgHttp = settings.createGroup("HTTP");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How to ask the bot. Http goes straight to StasisBot and never touches " +
                     "the game server.")
        .defaultValue(Mode.Chat)
        .build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Print what was sent, and what the bot answered, to your own chat.")
        .defaultValue(true)
        .build());

    private final Setting<List<String>> messages = sgChat.add(new StringListSetting.Builder()
        .name("messages")
        .description("Trigger words the bot listens for. One is picked at random per pull, " +
                     "which keeps repeated pulls from looking like spam.")
        .defaultValue(List.of("!home"))
        .visible(() -> mode.get() != Mode.Http)
        .build());

    private final Setting<String> whisperCommand = sgChat.add(new StringSetting.Builder()
        .name("whisper-command")
        .description("Command used to whisper. Servers vary: /msg, /w, /tell.")
        .defaultValue("/msg")
        .visible(() -> mode.get() == Mode.Whisper)
        .build());

    private final Setting<String> botName = sgChat.add(new StringSetting.Builder()
        .name("bot-name")
        .description("Account the whisper goes to.")
        .defaultValue("")
        .visible(() -> mode.get() == Mode.Whisper)
        .build());

    private final Setting<String> endpoint = sgHttp.add(new StringSetting.Builder()
        .name("endpoint")
        .description("Full URL of the bot's control server, including the protocol.")
        .defaultValue("http://localhost:6969")
        .visible(() -> mode.get() == Mode.Http)
        .wide()
        .build());

    private final Setting<String> secret = sgHttp.add(new StringSetting.Builder()
        .name("secret")
        .description("Shared secret, identical to the bot's. Hidden on screen, but Meteor " +
                     "still stores it in plain text in its config like any other setting.")
        .defaultValue("")
        .visible(() -> mode.get() == Mode.Http)
        .renderer(PasswordRenderer.class)
        .wide()
        .build());

    private boolean fired;
    private long lastPull;

    public StasisPull() {
        super(NewAddon.CATEGORY, "stasis-pull",
            "Asks a stasis bot to pull you home, by chat, whisper, or StasisBot's encrypted API.");
    }

    @Override
    public void onActivate() {
        fired = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Fire once, on the tick after activation, then switch off: the module is a button,
        // and toggling out of onActivate itself would run inside Meteor's own activation.
        if (fired) return;
        fired = true;

        try {
            pull();
        } finally {
            toggle();
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

    /** True if the current mode has everything it needs to actually send something. */
    public boolean isConfigured() {
        return switch (mode.get()) {
            case Chat -> !messages.get().isEmpty();
            case Whisper -> !messages.get().isEmpty() && !botName.get().isBlank();
            case Http -> !endpoint.get().isBlank() && !secret.get().isBlank();
        };
    }

    /** Sends one pull request. Public so other modules can trigger it. */
    public void pull() {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPull < COOLDOWN_MS) {
            if (notify.get()) warning("On cooldown.");
            return;
        }
        lastPull = now;

        switch (mode.get()) {
            case Chat -> {
                String message = pickMessage();
                if (message == null) return;
                ChatUtils.sendPlayerMsg(message);
                if (notify.get()) info("Sent \"%s\".", message);
            }

            case Whisper -> {
                String message = pickMessage();
                if (message == null) return;

                String target = botName.get().trim();
                if (target.isEmpty()) {
                    error("Set bot-name first.");
                    return;
                }

                String command = whisperCommand.get().trim();
                if (!command.startsWith("/")) command = "/" + command;

                ChatUtils.sendPlayerMsg(command + " " + target + " " + message);
                if (notify.get()) info("Whispered \"%s\" to %s.", message, target);
            }

            case Http -> {
                // Entity#getName is stable across every target; the player's game profile is
                // not reachable the same way from 1.21.10 onward.
                String target = mc.player.getName().getString();
                if (notify.get()) info("Asking the bot to pull %s...", target);

                StasisControl.homeRequest(endpoint.get(), secret.get(), target, reply -> {
                    // Callback lands on the HTTP thread; chat must be touched on the game one.
                    if (notify.get()) mc.execute(() -> info(reply));
                });
            }
        }
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
