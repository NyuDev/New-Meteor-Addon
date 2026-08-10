package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.gui.live.LiveCanvas;
import fr.nyuway.newaddon.gui.live.LiveScreen;
import fr.nyuway.newaddon.gui.live.LiveToasts;
import fr.nyuway.newaddon.modules.dm.DmPatterns;
import fr.nyuway.newaddon.modules.dm.LiveStore;
import fr.nyuway.newaddon.utils.Enemies;
import fr.nyuway.newaddon.utils.Profiles;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
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

    private final Setting<Integer> coldAfterDays = sgGeneral.add(new IntSetting.Builder()
        .name("cold-after-days")
        .description("Days without a word before a conversation drops out of Recent into its " +
                     "own section at the bottom. Someone who is on the server right now stays " +
                     "in the sections about being here instead. Zero keeps everything in Recent.")
        .defaultValue(14).min(0).max(365).sliderRange(0, 90)
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

    private final Setting<Boolean> notifyToast = sgPeople.add(new BoolSetting.Builder()
        .name("notify-toast")
        .description("Pop an advancement-style toast, top-right, when a message arrives while " +
                     "the window is closed. Shows who it is from and a preview of what they said.")
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
    private final LiveToasts toasts = new LiveToasts();

    /** Windows open this session; restored when the screen is reopened. Cleared by a restart. */
    private final java.util.Set<java.util.UUID> open = new java.util.HashSet<>();
    /** Conversations pinned to reopen after a restart too; the persisted subset of {@link #open}. */
    private final java.util.Set<java.util.UUID> pinned = new java.util.HashSet<>();

    private DmPatterns in;
    private DmPatterns out;
    private boolean keyHeld;
    private String openedFor;

    /**
     * Friend names as of the last tick, so a newly added one can be told apart from one already
     * a friend when the module turned on. Null until the first tick establishes that baseline.
     */
    private java.util.Set<String> knownFriends;

    /**
     * Names read out of the tab list this session, so someone who logs off in the middle of a
     * conversation does not turn back into eight characters of their UUID - which is also the
     * name a reply would then be addressed to.
     *
     * <p>Memory only, never written: a settings file means a conversation, and someone whose name
     * was merely drawn in a list is not one.
     */
    private final java.util.Map<java.util.UUID, String> seenNames = new java.util.HashMap<>();

    /**
     * The colour an enemy's name is drawn in, read fresh each time from Meteor's config tab where
     * it sits beside the friend colour. Not cached: changing a swatch there should show here on
     * the next frame, not the next time a window is opened.
     */
    public static int enemyColor() {
        return Enemies.color();
    }

    public LiveMessage() {
        super(NewAddon.CATEGORY, "live-message",
            "Keeps whispers as conversations, in a window, with history.");
    }

    @Override
    public void onActivate() {
        compilePatterns();
        openedFor = null;
        keyHeld = false;

        // Pinned conversations are the ones that survive a restart; seed the session's open
        // windows from them, so after a restart exactly the pinned ones reopen.
        pinned.clear();
        pinned.addAll(store.loadPinned());
        open.clear();
        open.addAll(pinned);

        knownFriends = null;

        if (!openKey.get().isSet()) {
            warning("No open-key bound; set one to reach the message window.");
        }
    }

    private void compilePatterns() {
        in = new DmPatterns(incoming.get(), (p, why) ->
            error("Incoming pattern rejected (%s): %s", why, p));
        out = new DmPatterns(outgoing.get(), (p, why) ->
            error("Outgoing pattern rejected (%s): %s", why, p));

        // Built-in formats stay on top of the settings, so a known server - 2b2t's "to name: msg"
        // - is still read when the saved settings hold an older default list.
        in.add(DmPatterns.DEFAULT_INCOMING, (p, why) -> { });
        out.add(DmPatterns.DEFAULT_OUTGOING, (p, why) -> { });

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

        syncFriends();
    }

    /**
     * Greets anyone newly friended, however that happened - our own button, Meteor's
     * {@code .friend add} command, or its Friends tab all end up in the same list, and none of
     * them fire an event to hook. Polling the list once a tick and diffing it is what a
     * greeting sent "even when added outside this menu" actually requires.
     */
    private void syncFriends() {
        // FriendBypass empties the list and fills it again. Every one of those returns looks
        // like a new friend from here, and greeting fifty people in a tick is fifty whispers -
        // which 2b2t answers by dropping the connection. The baseline is dropped instead, so
        // the list that comes back is the list that was there.
        if (FriendBypass.rearranging()) {
            knownFriends = null;
            return;
        }

        java.util.Set<String> names = new java.util.HashSet<>();
        for (var friend : meteordevelopment.meteorclient.systems.friends.Friends.get()) {
            names.add(friend.getName());
        }

        // The first tick only establishes the baseline - greeting everyone already a friend
        // when the module happened to turn on would not be a greeting, just noise.
        if (knownFriends != null && greetOnFriend.get() && !greeting.get().isBlank()) {
            for (String name : names) {
                if (!knownFriends.contains(name)) send(name, greeting.get());
            }
        }

        knownFriends = names;
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
        var settings = store.settingsOf(peer);
        boolean ignored = settings.isBlocked;
        boolean muted = settings.isMuted;

        boolean isNew = store.thread(peer).isEmpty();
        store.record(peer, hit.peer(), !isIncoming, hit.text(), selfId());

        // Counted whether or not the window is open, and cleared only by looking at the
        // conversation. A message that arrived while the screen was up but behind three other
        // windows has not been read either.
        if (isIncoming && !ignored) store.noteUnread(peer);

        // A conversation you started is one you are in. Writing to someone from the chat box
        // with a plain /msg used to leave the menu knowing nothing about it, so their window
        // was not there the next time it was opened - which is precisely when you want it.
        if (!isIncoming) open.add(peer);

        if (isIncoming && isNew && announce.get() && !ignored) {
            info("New conversation with %s.", hit.peer());
        }

        // Sound and toast share one gate - a message that arrived while you were playing - so
        // they always agree. Neither fires behind an open screen: the window itself is where a
        // message shows once you are looking, and a toast over your own inventory is noise.
        // Muting drops both while keeping the message: it is the notification that is turned
        // off, not the conversation.
        if (isIncoming && !ignored && !muted && mc.screen == null && mc.player != null) {
            if (notifySound.get()) {
                mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.6f);
            }
            if (notifyToast.get()) {
                toasts.push(peer, hit.peer(), hit.text(), colorOf(peer));
            }
        }

        // Always written to the game log, even when hidden from chat. Hiding a message from
        // the feed is a display choice; losing the record of it is not the same thing, and
        // the log is where you go when you need to know what was actually said.
        NewAddon.LOG.info("[messages] {} {}: {}",
            isIncoming ? "<-" : "->", hit.peer(), hit.text());

        if (hideFromChat.get() || ignored) event.cancel();
    }

    /**
     * Draws the toasts on the HUD.
     *
     * <p>Meteor fires this at the tail of the in-game HUD render, so it runs while you are
     * playing and not while a screen is up - which is exactly when a toast is wanted and the
     * window is not. The one draw-context split at 26.1 is hidden inside {@link LiveCanvas#of}.
     */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.screen != null) return;
        toasts.render(LiveCanvas.of(event), event.screenWidth);
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
        // An enemy first, and before the friend colour. The window is the thing you look at
        // while typing to someone; if they are on the enemy list that is the single most
        // important fact about them, and it was the one thing the frame did not say.
        if (isEnemy(peer)) return Enemies.color();

        if (friendColor.get() && isFriend(peer)) {
            var c = meteordevelopment.meteorclient.systems.config.Config.get().friendColor.get();
            return (c.r << 16) | (c.g << 8) | c.b;
        }
        return fr.nyuway.newaddon.gui.live.LiveColors.windowColor(store, peer);
    }

    /**
     * The colour a name is drawn in - the relationship, not the identity. A stranger is white, a
     * friend takes Meteor's friend colour, someone ignored goes red, and an enemy takes the enemy
     * colour, which wins over the rest since it is the one worth seeing first. Whether they are on
     * the server is carried by the head's ring instead, so the two cues never fight for the same one.
     */
    public int nameColor(java.util.UUID peer) {
        if (isEnemy(peer)) return Enemies.color();
        if (isIgnored(peer)) return 0xDC5050;
        if (isFriend(peer)) {
            var c = meteordevelopment.meteorclient.systems.config.Config.get().friendColor.get();
            return (c.r << 16) | (c.g << 8) | c.b;
        }
        return 0xFFFFFF;
    }

    /**
     * Whether a conversation is pinned - kept open even across a game restart, not just across
     * closing and reopening the screen (which every open window already survives).
     */
    public boolean isPinned(java.util.UUID peer) {
        return pinned.contains(peer);
    }

    public void setPinned(java.util.UUID peer, boolean value) {
        if (value) pinned.add(peer);
        else pinned.remove(peer);
        store.savePinned(pinned);
    }

    /** Messages from them not looked at yet, for the count beside their name. */
    public int unread(java.util.UUID peer) {
        return store.unread(peer);
    }

    /** Everyone with something unread, so the screen can open those conversations by itself. */
    public java.util.List<java.util.UUID> unreadPeers() {
        return store.withUnread();
    }

    /**
     * Marks a conversation read.
     *
     * <p>Called when the window is picked up - clicked in the list, or clicked on - and not when
     * the screen opens it by itself. A window that appeared on its own still carries its count,
     * so opening the menu shows you what came in rather than quietly clearing it.
     */
    public void markRead(java.util.UUID peer) {
        store.markRead(peer);
    }

    /**
     * Conversations whose window should come up showing the profile panel rather than the
     * messages, because that is what was asked for. Cleared by the window as it reads it, so it
     * is a request for the next open and not a state to get stuck in.
     */
    private final java.util.Set<java.util.UUID> showProfile = new java.util.HashSet<>();

    public void showProfileNext(java.util.UUID peer) {
        showProfile.add(peer);
    }

    /** Asked once by a window as it opens; true only for a window that was asked to show it. */
    public boolean takeProfileRequest(java.util.UUID peer) {
        return showProfile.remove(peer);
    }

    /** Notes a window as open, so reopening the screen brings it back for the rest of the session. */
    public void markOpen(java.util.UUID peer) {
        open.add(peer);
    }

    /** A window closed by its cross: gone from the session and unpinned, so it stays gone. */
    public void markClosed(java.util.UUID peer) {
        open.remove(peer);
        if (pinned.remove(peer)) store.savePinned(pinned);
    }

    /** The windows to reopen with the screen - everything open, which after a restart is the pins. */
    public java.util.List<java.util.UUID> openPeers() {
        return new java.util.ArrayList<>(open);
    }

    public boolean isFriend(java.util.UUID peer) {
        return meteordevelopment.meteorclient.systems.friends.Friends.get()
            .get(displayName(peer)) != null;
    }

    /**
     * Adds or removes the friend on Meteor's own list - the one everything else reads too.
     *
     * <p>Friending someone drops them from the enemy list, since the two are exclusive. It is
     * done here as well as in the watcher so the skull goes out on the same click as the heart
     * comes on, rather than a second later.
     */
    public void toggleFriend(java.util.UUID peer) {
        var friends = meteordevelopment.meteorclient.systems.friends.Friends.get();
        String name = displayName(peer);
        var existing = friends.get(name);

        if (existing != null) {
            friends.remove(existing);
            info("Removed %s from friends.", name);
            return;
        }

        friends.add(new meteordevelopment.meteorclient.systems.friends.Friend(name, peer));
        if (Enemies.remove(name)) info("Added %s to friends, and off the enemy list.", name);
        else info("Added %s to friends.", name);

        // Not greeted here: syncFriends() catches this add on the next tick along with any made
        // outside this window, so there is exactly one place a greeting is ever sent from.
    }

    /** Makes them a friend for now, or ends it. Their UUID goes on the entry when we have one. */
    public void toggleTempFriend(java.util.UUID peer) {
        String name = displayName(peer);

        if (TempFriends.isTemporary(name)) {
            TempFriends.remove(name);
            info("%s is not a friend any more.", name);
            return;
        }

        if (!TempFriends.add(name, peer)) info("%s is already a friend.", name);
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

        String name = displayName(peer);
        info(settings.isBlocked ? "Ignoring %s." : "No longer ignoring %s.", name);

        if (ignoreMode.get() == IgnoreMode.Server) {
            String command = ignoreCommand.get().trim();
            if (!command.startsWith("/")) command = "/" + command;
            ChatUtils.sendPlayerMsg(command + " " + name);
        }
    }

    /** Whether their notifications are muted - the sound and toast held while the messages stay. */
    public boolean isMuted(java.util.UUID peer) {
        return store.settingsOf(peer).isMuted;
    }

    /**
     * Toggles muting someone.
     *
     * <p>Unlike ignoring, nothing is hidden and nothing is recorded differently - a muted
     * conversation reads exactly as it would otherwise, only without the sound and the toast that
     * would otherwise announce it. It is for the person you still want to hear from, just not the
     * moment each line lands.
     */
    public void toggleMute(java.util.UUID peer) {
        var settings = store.settingsOf(peer);
        settings.isMuted = !settings.isMuted;
        store.saveSettings(peer, settings);
        info(settings.isMuted ? "Muted %s." : "Unmuted %s.", displayName(peer));
    }

    /** Whether this person is on the addon's enemy list, by their current name. */
    public boolean isEnemy(java.util.UUID peer) {
        return Enemies.isEnemy(displayName(peer));
    }

    /**
     * Adds or removes them from the enemy list the {@code .enemy} command and colours read.
     *
     * <p>Making someone an enemy unfriends them - {@link Enemies#add} sees to that, so every way
     * in agrees - and the message says so, because a heart going out on the far side of the
     * window is not something to leave the user to notice.
     */
    public void toggleEnemy(java.util.UUID peer) {
        String name = displayName(peer);
        if (Enemies.isEnemy(name)) {
            Enemies.remove(name);
            info("Removed %s from enemies.", name);
            return;
        }

        boolean wasFriend = isFriend(peer);
        Enemies.add(name);
        if (wasFriend) info("Added %s to enemies, and off the friend list.", name);
        else info("Added %s to enemies.", name);
    }

    /**
     * Who someone is - the one place that answers it, for printing and for addressing a whisper.
     *
     * <p>The tab list first, because for anyone online it is the current fact: a player who has
     * renamed keeps their UUID and their thread, and the name written down beside that thread is
     * whatever they were called last time. Only when they are not on the server does the stored
     * name answer, since then there is nothing better and it is at least who they were.
     *
     * <p>The UUID fragment at the end is a last resort and should not be reachable from anything
     * the user sees. It was: the store's own {@code nameOf} stops at the stored name, so opening a
     * conversation with someone never messaged showed eight hex characters and, worse, addressed
     * the reply to them.
     */
    public String displayName(java.util.UUID peer) {
        if (mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(peer);
            if (info != null) {
                String name = Profiles.nameOf(info.getProfile());
                if (name != null && !name.isBlank()) {
                    seenNames.put(peer, name);
                    return name;
                }
            }
        }

        String seen = seenNames.get(peer);
        if (seen != null) return seen;

        String last = store.lastNameOf(peer);
        if (last != null) return last;

        return peer.toString().substring(0, 8);
    }

    /**
     * Everyone actually loaded around us - in render distance, near enough to matter.
     *
     * <p>Read from the world's entities rather than the tab list, which is the difference: the
     * tab list is everyone on the server, this is everyone you could walk up to. Asked fresh
     * every time, because the whole point is that it changes as people arrive and leave.
     */
    public java.util.List<java.util.UUID> playersInRender() {
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
        if (mc.level == null) return ids;

        for (var player : mc.level.players()) {
            java.util.UUID id = player.getUUID();
            if (mc.player == null || !id.equals(mc.player.getUUID())) ids.add(id);
        }
        return ids;
    }

    /** How long without a word before a conversation counts as cold, in millis. 0 means never. */
    public long coldAfterMillis() {
        return coldAfterDays.get() * 86_400_000L;
    }

    /** How many are loaded around us, without building a list to count it. */
    public int renderCount() {
        return mc.level == null ? 0 : mc.level.players().size();
    }

    /** How many are on the server, without building a list to count it. */
    public int onlineCount() {
        return mc.getConnection() == null ? 0 : mc.getConnection().getOnlinePlayers().size();
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

    /**
     * Whether that player is on the server right now.
     *
     * <p>Asked by every open window every frame - it decides whether the reply box is usable -
     * so it goes through the tab list's own map rather than {@code getOnlinePlayerIds}, which is
     * declared as a plain Collection and promises nothing about what {@code contains} costs.
     * Looking a UUID up is one hash lookup on every version; walking five hundred of them, four
     * windows deep, at a hundred frames a second, is not.
     */
    public boolean isOnline(java.util.UUID peer) {
        return mc.getConnection() != null && mc.getConnection().getPlayerInfo(peer) != null;
    }

    /** Sends a reply through the server's own command, and records it straight away. */
    public void send(String peer, String text) {
        if (mc.player == null) return;

        String command = sendCommand.get().trim();
        if (!command.startsWith("/")) command = "/" + command;

        // Asked here rather than left to the chat event: this goes out through ChatUtils, which
        // talks to the connection directly and never touches the chat screen - so the guard on
        // the chat box does not see it, and a whisper is exactly where a base gets given away.
        if (!ChatProtect.allow(command + " " + peer + " " + text, true)) return;

        ChatUtils.sendPlayerMsg(command + " " + peer + " " + text);

        // Not recorded here: every server this targets echoes your own whisper back, and the
        // outgoing patterns catch that echo. Writing it now as well would file every reply
        // twice - which the previous cut of this did.
    }

}
