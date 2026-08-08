package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.gui.DmScreen;
import fr.nyuway.newaddon.modules.dm.DmPatterns;
import fr.nyuway.newaddon.modules.dm.DmStore;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;

/**
 * LiveMessage - whispers kept as conversations instead of lines that scroll away.
 *
 * <p>Modelled on rebane2001's Livemessage: direct messages are collected out of chat, stored
 * locally, and shown in a window per person. Nothing is sent anywhere but the server, through
 * the same {@code /msg} the game already uses, so whoever you are talking to needs nothing
 * installed.
 *
 * <h2>Reading chat rather than packets</h2>
 * There is no flag on the wire that says "this is a whisper". Vanilla renders the line from a
 * translation key and sends the finished text, and every server with its own format sends
 * something else again. Matching the text is the only approach that works everywhere - which
 * is why the patterns are settings: an unrecognised server needs one line added, not a build.
 *
 * <h2>Why the key is polled</h2>
 * A module's own keybind toggles it. Opening the window is a second, separate action, so it
 * gets its own bind, read on the tick - which also means it can only ever fire while this
 * module is on.
 */
public class LiveMessage extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPatterns = settings.createGroup("Patterns");

    private final Setting<Keybind> openKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("open-key")
        .description("Opens the message window. Does nothing while this module is off.")
        .defaultValue(Keybind.none())
        .build());

    private final Setting<String> sendCommand = sgGeneral.add(new StringSetting.Builder()
        .name("send-command")
        .description("How a reply is sent. Whatever your server uses for private messages.")
        .defaultValue("/msg")
        .build());

    private final Setting<Boolean> hideFromChat = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-from-chat")
        .description("Keep matched whispers out of the chat feed, since they are in the " +
                     "window instead. Off by default: a message you cannot see anywhere is a " +
                     "worse failure than one shown twice.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Say in chat when a new conversation starts, so a first message from " +
                     "someone is not missed while the window is closed.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> historyLimit = sgGeneral.add(new IntSetting.Builder()
        .name("history-limit")
        .description("Messages kept per conversation in memory. The file on disk keeps them all.")
        .defaultValue(500).min(20).max(5000).sliderRange(50, 1000)
        .build());

    private final Setting<List<String>> incoming = sgPatterns.add(new StringListSetting.Builder()
        .name("incoming")
        .description("Patterns for messages sent to you. Group 1 is the sender, group 2 is " +
                     "the text.")
        .defaultValue(DmPatterns.DEFAULT_INCOMING)
        .build());

    private final Setting<List<String>> outgoing = sgPatterns.add(new StringListSetting.Builder()
        .name("outgoing")
        .description("Patterns for messages you sent. Group 1 is the recipient, group 2 is " +
                     "the text.")
        .defaultValue(DmPatterns.DEFAULT_OUTGOING)
        .build());

    private final DmStore store = new DmStore(500);

    private DmPatterns in;
    private DmPatterns out;
    private boolean keyHeld;
    private String openedFor;

    public LiveMessage() {
        super(NewAddon.CATEGORY, "live-message",
            "Keeps whispers as conversations, in a window, with history.");
    }

    @Override
    public void onActivate() {
        compilePatterns();
        openedFor = null;
        keyHeld = false;

        if (!openKey.get().isSet()) {
            warning("No open-key bound; set one to reach the message window.");
        }
    }

    private void compilePatterns() {
        in = new DmPatterns(incoming.get(), (p, why) ->
            error("Incoming pattern rejected (%s): %s", why, p));
        out = new DmPatterns(outgoing.get(), (p, why) ->
            error("Outgoing pattern rejected (%s): %s", why, p));

        if (in.isEmpty() && out.isEmpty()) {
            warning("No usable patterns; nothing will be collected.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // The store is per server: conversations from one place have no business showing up
        // in another, and the file is named after where they happened.
        String server = currentServer();
        if (!server.equals(openedFor)) {
            openedFor = server;
            store.openFor(server);
            compilePatterns();
        }

        boolean down = openKey.get().isSet() && openKey.get().isPressed();
        if (down && !keyHeld && mc.screen == null) {
            mc.setScreen(new DmScreen(GuiThemes.get(), this, store, null));
        }
        keyHeld = down;
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (in == null || out == null) return;

        String line = event.getMessage().getString();

        DmPatterns.Hit hit = in.match(line);
        boolean isIncoming = hit != null;
        if (hit == null) hit = out.match(line);
        if (hit == null) return;

        boolean isNew = store.thread(hit.peer()).isEmpty();
        store.record(hit.peer(), isIncoming, hit.text());

        if (isIncoming && isNew && announce.get()) {
            info("New conversation with %s.", hit.peer());
        }

        if (hideFromChat.get()) event.cancel();
    }

    /** Sends a reply through the server's own command, and records it straight away. */
    public void send(String peer, String text) {
        if (mc.player == null) return;

        String command = sendCommand.get().trim();
        if (!command.startsWith("/")) command = "/" + command;

        ChatUtils.sendPlayerMsg(command + " " + peer + " " + text);

        // Recorded here rather than waiting for the echo: not every server echoes a whisper
        // back, and a reply missing from your own history is the one thing this must not do.
        // A server that does echo is matched by the outgoing patterns and would double it, so
        // those default to the formats that pair with the incoming ones.
        store.record(peer, false, text);
    }

    private String currentServer() {
        if (mc.getCurrentServer() != null) {
            ServerData data = mc.getCurrentServer();
            if (data.ip != null && !data.ip.isBlank()) return data.ip;
        }
        return "singleplayer";
    }
}
