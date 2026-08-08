package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.gui.live.LiveScreen;
import fr.nyuway.newaddon.modules.dm.DmPatterns;
import fr.nyuway.newaddon.modules.dm.LiveStore;
import fr.nyuway.newaddon.utils.Profiles;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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

    /** Where an ignore takes effect. */
    public enum IgnoreMode {
        /** Hidden in this window only. Nobody else can tell. */
        Client,
        /** Run the server's own ignore command, so the message never arrives. */
        Server
    }


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

    private final SettingGroup sgPeople = settings.createGroup("People");

    private final Setting<Boolean> friendColor = sgPeople.add(new BoolSetting.Builder()
        .name("friend-colour")
        .description("Draw friends in Meteor's own friend colour instead of the colour " +
                     "generated from their UUID, so the list agrees with the rest of the client.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> greetOnFriend = sgPeople.add(new BoolSetting.Builder()
        .name("greet-on-friend")
        .description("Send a message when you add someone as a friend from this window.")
        .defaultValue(false)
        .build());

    private final Setting<String> greeting = sgPeople.add(new StringSetting.Builder()
        .name("greeting")
        .description("What that message says.")
        .defaultValue("Added you as a friend.")
        .visible(greetOnFriend::get)
        .build());

    private final Setting<Boolean> notifySound = sgPeople.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Play a sound when a message arrives while the window is closed.")
        .defaultValue(true)
        .build());

    private final Setting<IgnoreMode> ignoreMode = sgPeople.add(new EnumSetting.Builder<IgnoreMode>()
        .name("ignore-mode")
        .description("Client hides them here only, and nobody else knows. Server runs the " +
                     "ignore command, which also stops the messages arriving at all.")
        .defaultValue(IgnoreMode.Client)
        .build());

    private final Setting<String> ignoreCommand = sgPeople.add(new StringSetting.Builder()
        .name("ignore-command")
        .description("Command used for a server-side ignore. The name is appended.")
        .defaultValue("/ignore")
        .visible(() -> ignoreMode.get() == IgnoreMode.Server)
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

    private final LiveStore store = new LiveStore();

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

        // Whatever has already been taught to Livemessage itself applies here too.
        in.addFile(store.folder().resolve("patterns").resolve("fromPatterns.txt"),
            (p, why) -> error("fromPatterns.txt rejected (%s): %s", why, p));
        out.addFile(store.folder().resolve("patterns").resolve("toPatterns.txt"),
            (p, why) -> error("toPatterns.txt rejected (%s): %s", why, p));

        if (in.isEmpty() && out.isEmpty()) {
            warning("No usable patterns; nothing will be collected.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Loaded once. Unlike my first cut this is not per server: upstream files a
        // conversation under the player's UUID, not the address it happened on, so the same
        // person is one thread wherever you meet them.
        if (openedFor == null) {
            openedFor = "loaded";
            store.loadIndex();
            compilePatterns();
        }

        boolean down = openKey.get().isSet() && openKey.get().isPressed();
        if (down && !keyHeld && mc.screen == null) {
            mc.setScreen(new LiveScreen(this, store));
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

        java.util.UUID peer = resolveName(hit.peer());
        if (peer == null) {
            // No UUID means no key, and keying by name is what makes a rename split a thread.
            if (announce.get()) warning("Cannot place a message from %s: unknown player.", hit.peer());
            return;
        }

        // An ignored conversation is still recorded - ignoring someone is about not being
        // interrupted by them, not about losing what they said.
        boolean ignored = store.settingsOf(peer).isBlocked;

        boolean isNew = store.thread(peer).isEmpty();
        store.record(peer, hit.peer(), !isIncoming, hit.text(), selfId());

        if (isIncoming && isNew && announce.get() && !ignored) {
            info("New conversation with %s.", hit.peer());
        }

        if (isIncoming && !ignored && notifySound.get() && mc.screen == null && mc.player != null) {
            mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.6f);
        }

        // Always written to the game log, even when hidden from chat. Hiding a message from
        // the feed is a display choice; losing the record of it is not the same thing, and
        // the log is where you go when you need to know what was actually said.
        NewAddon.LOG.info("[messages] {} {}: {}",
            isIncoming ? "<-" : "->", hit.peer(), hit.text());

        if (hideFromChat.get() || ignored) event.cancel();
    }

    /**
     * Resolves a name to the UUID a conversation is filed under.
     *
     * <p>The tab list first, since that is authoritative for anyone online. Failing that, a
     * conversation already on disk whose last known name matches - which is how a reply to
     * someone who has since logged off still lands in the right thread.
     */
    public java.util.UUID resolveName(String name) {
        if (mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(name);
            if (info != null) return Profiles.idOf(info.getProfile());
        }
        return store.findByName(name);
    }

    private java.util.UUID selfId() {
        return mc.player == null ? null : mc.player.getUUID();
    }

    public LiveStore store() {
        return store;
    }

    /**
     * The colour a conversation is drawn in.
     *
     * <p>Friends take Meteor's friend colour when that is on, so this window agrees with
     * nametags and the rest of the client rather than inventing a second opinion about who
     * matters. Everyone else keeps the colour generated from their UUID.
     */
    public int colorOf(java.util.UUID peer) {
        if (friendColor.get() && isFriend(peer)) {
            var c = meteordevelopment.meteorclient.systems.config.Config.get().friendColor.get();
            return (c.r << 16) | (c.g << 8) | c.b;
        }
        return fr.nyuway.newaddon.gui.live.LiveColors.windowColor(store, peer);
    }

    public boolean isFriend(java.util.UUID peer) {
        return meteordevelopment.meteorclient.systems.friends.Friends.get()
            .get(store.nameOf(peer)) != null;
    }

    /** Adds or removes the friend, and greets them if that was asked for. */
    public void toggleFriend(java.util.UUID peer) {
        var friends = meteordevelopment.meteorclient.systems.friends.Friends.get();
        String name = store.nameOf(peer);
        var existing = friends.get(name);

        if (existing != null) {
            friends.remove(existing);
            info("Removed %s from friends.", name);
            return;
        }

        friends.add(new meteordevelopment.meteorclient.systems.friends.Friend(name, peer));
        info("Added %s to friends.", name);

        // Deliberately after the add, and only on the add: a greeting sent when someone is
        // removed would be the opposite of what was meant.
        if (greetOnFriend.get() && !greeting.get().isBlank()) send(name, greeting.get());
    }

    public boolean isIgnored(java.util.UUID peer) {
        return store.settingsOf(peer).isBlocked;
    }

    /**
     * Toggles ignoring someone.
     *
     * <p>Client mode only hides them here. Server mode also runs the server's own command, so
     * the message never reaches you - but it is visible to the server, which is why it is not
     * the default.
     */
    public void toggleIgnore(java.util.UUID peer) {
        var settings = store.settingsOf(peer);
        settings.isBlocked = !settings.isBlocked;
        store.saveSettings(peer, settings);

        String name = store.nameOf(peer);
        info(settings.isBlocked ? "Ignoring %s." : "No longer ignoring %s.", name);

        if (ignoreMode.get() == IgnoreMode.Server) {
            String command = ignoreCommand.get().trim();
            if (!command.startsWith("/")) command = "/" + command;
            ChatUtils.sendPlayerMsg(command + " " + name);
        }
    }

    /**
     * What to print for someone.
     *
     * <p>The stored last-known name first, then the tab list. Without the second the new
     * server section would be a column of UUID fragments: those players have no settings
     * file yet precisely because they have never been spoken to.
     */
    public String displayName(java.util.UUID peer) {
        var settings = store.settingsOf(peer);
        if (settings.lastName != null && !settings.lastName.isBlank()) return settings.lastName;

        if (mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(peer);
            if (info != null) return Profiles.nameOf(info.getProfile());
        }
        return peer.toString().substring(0, 8);
    }

    /** Everyone on the server right now, so the list can offer people never spoken to. */
    public java.util.List<java.util.UUID> onlinePlayers() {
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
        if (mc.getConnection() == null) return ids;

        for (var info : mc.getConnection().getOnlinePlayers()) {
            java.util.UUID id = Profiles.idOf(info.getProfile());
            if (mc.player == null || !id.equals(mc.player.getUUID())) ids.add(id);
        }
        return ids;
    }

    /** Whether that player is on the server right now, for the dot beside their name. */
    public boolean isOnline(java.util.UUID peer) {
        if (mc.getConnection() == null) return false;
        return mc.getConnection().getOnlinePlayerIds().contains(peer);
    }

    /** Sends a reply through the server's own command, and records it straight away. */
    public void send(String peer, String text) {
        if (mc.player == null) return;

        String command = sendCommand.get().trim();
        if (!command.startsWith("/")) command = "/" + command;

        ChatUtils.sendPlayerMsg(command + " " + peer + " " + text);

        // Not recorded here: every server this targets echoes your own whisper back, and the
        // outgoing patterns catch that echo. Writing it now as well would file every reply
        // twice - which the previous cut of this did.
    }

}
