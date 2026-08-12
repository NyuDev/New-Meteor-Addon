package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.Enemies;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FriendSync - keeps a friend list the same across the clients you switch between, over chat.
 *
 * <p>Meteor is where this runs, but its friends are not the only ones: add someone here and the
 * other client you use knows nothing about it. There is no shared file to write and no event to
 * hook, so the one channel every client already reads is the one used - chat. Adding or removing
 * a friend on Meteor's list fires the command templates below, {@code {name}} filled in, so a
 * second client watching its own command prefix picks the change up.
 *
 * <h2>Why the sync runs itself</h2>
 * The other client can also be told to read Meteor's whole list at once, which is a better answer
 * than any add or remove: it cannot drift, and it fixes a list that already has. So it is not
 * something to put behind a key and remember to press - it runs on its own, once shortly after
 * joining a world and again after every change. The add and remove commands still go out, because
 * they cost nothing and a client that only understands those is still kept in step.
 *
 * <h2>What is sent where</h2>
 * A template beginning with Meteor's own command prefix is run locally through Meteor rather
 * than typed into chat, so it never leaks to the server - it is a client command, and the
 * server has no business seeing it. Anything else is sent to chat as written, for whatever
 * other client is meant to read it. The defaults are the Mio commands; empty a list to send
 * nothing of that kind.
 *
 * <h2>Why the list is polled</h2>
 * A friend added through Meteor's own tab or its {@code .friend} command has to count too, and
 * none of those fire an event. Diffing the list each tick is what catches an add however it was
 * made, the same approach LiveMessage's greeting uses.
 */
public class FriendSync extends Module {

    /** Ticks in a world before the join sync goes out - long enough to be past a login queue. */
    private static final int JOIN_DELAY = 60;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> onAdd = sgGeneral.add(new StringListSetting.Builder()
        .name("on-add")
        .description("Commands sent when a friend is added. {name} becomes the player. One that " +
                     "starts with Meteor's command prefix is run locally; anything else is sent " +
                     "to chat for another client to read.")
        .defaultValue(List.of(";friend add {name}"))
        .build());

    private final Setting<List<String>> onRemove = sgGeneral.add(new StringListSetting.Builder()
        .name("on-remove")
        .description("Commands sent when a friend is removed. {name} becomes the player.")
        .defaultValue(List.of(";friend remove {name}"))
        .build());

    private final Setting<List<String>> onEnemyAdd = sgGeneral.add(new StringListSetting.Builder()
        .name("on-enemy-add")
        .description("Commands sent when someone is added to the enemy list. {name} becomes " +
                     "the player.")
        .defaultValue(List.of(";enemy add {name}"))
        .build());

    private final Setting<List<String>> onEnemyRemove = sgGeneral.add(new StringListSetting.Builder()
        .name("on-enemy-remove")
        .description("Commands sent when someone is taken off the enemy list.")
        .defaultValue(List.of(";enemy remove {name}"))
        .build());

    private final Setting<List<String>> onSync = sgGeneral.add(new StringListSetting.Builder()
        .name("on-sync")
        .description("Commands that make the other client re-read Meteor's whole friend list. " +
                     "Sent once after joining a world, and only then: a sync of this kind adds " +
                     "what it finds and cannot know about anyone you removed, so a removal has " +
                     "to be sent as its own command.")
        .defaultValue(List.of(";friend sync meteor"))
        .build());

    private final Setting<Boolean> syncOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("sync-on-join")
        .description("Send the sync commands a few seconds after joining a world, so a client " +
                     "opened after the list changed catches up.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> sendInterval = sgGeneral.add(new IntSetting.Builder()
        .name("send-interval")
        .description("Ticks between two commands sent to chat. A bypass empties a friend list " +
                     "of fifty in one go, and fifty chat messages in one tick is the server " +
                     "closing the connection. Twenty ticks is one a second.")
        .defaultValue(15).min(2).max(100).sliderRange(5, 40)
        .build());

    private final Setting<Boolean> log = sgGeneral.add(new BoolSetting.Builder()
        .name("log")
        .description("Say in chat which sync commands were sent. Off by default: this now runs " +
                     "on its own, and something automatic should not narrate itself.")
        .defaultValue(false)
        .build());

    /**
     * Friend names as of the last tick, so an add and a remove can be told apart. Null until the
     * first tick sets the baseline, so turning the module on does not resend the whole list.
     */
    private Set<String> known;
    /** The same, for the addon's own enemy list, which changes by the same routes. */
    private Set<String> knownEnemies;

    /** Ticks in the current world, or -1 when out of one. Counts only as far as the join sync. */
    private int inWorld = -1;

    public FriendSync() {
        super(NewAddon.CATEGORY, "friend-sync",
            "Syncs Meteor's friend list to other clients through chat commands.");
    }

    @Override
    public void onActivate() {
        known = null;
        knownEnemies = null;
        inWorld = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            // Left the world. The baseline goes with it: the list can be edited between sessions,
            // and diffing against a stale one would fire a change that never happened. Anything
            // still queued was for a server we are no longer on.
            outbox.clear();
            sendCooldown = 0;
            known = null;
            knownEnemies = null;
            inWorld = -1;
            return;
        }

        if (inWorld < JOIN_DELAY) {
            inWorld++;
            if (inWorld == JOIN_DELAY && syncOnJoin.get()) sync();
        }

        // Before anything is queued, so a burst leaves at a steady rate rather than in whatever
        // gaps the rest of this leaves it.
        drain();

        // A bypass is only silent if you ask for it to be. It empties the friend list so that
        // everything which protects a friend stops - and the other clients protect friends too,
        // which is the whole reason they are being told anything at all. Keeping it to Meteor
        // makes the bypass work in exactly one of the places it needs to.
        if (FriendBypass.silencingSync()) {
            known = null;
            return;
        }

        Set<String> names = new HashSet<>();
        for (var friend : Friends.get()) names.add(friend.getName());
        diff(known, names, onAdd.get(), onRemove.get(), FRIEND_ADD, FRIEND_REMOVE);
        known = names;

        Set<String> enemies = new HashSet<>(Enemies.names());
        diff(knownEnemies, enemies, onEnemyAdd.get(), onEnemyRemove.get(),
            ENEMY_ADD, ENEMY_REMOVE);
        knownEnemies = enemies;
    }

    /**
     * Fires a command for everything that appeared or disappeared since last tick.
     *
     * <p>No sync afterwards, deliberately. A sync tells the other client to read the list and
     * take what is in it, which adds but never removes - so following a removal with one would
     * either do nothing or, on a client that syncs both ways, put back what was just taken off.
     * The per-name commands are what a change is actually sent as.
     *
     * @param before the previous set, or null on the first tick, when there is only a baseline
     *               to establish and nothing to announce
     */
    private void diff(Set<String> before, Set<String> now, List<String> added, List<String> removed,
                      int addKind, int removeKind) {
        if (before == null) return;

        for (String name : now) {
            if (!before.contains(name)) {
                currentKind = addKind;
                fire(added, name);
            }
        }
        for (String name : before) {
            if (!now.contains(name)) {
                currentKind = removeKind;
                fire(removed, name);
            }
        }
        currentKind = SYNC;
    }

    /**
     * Sends the sync commands. They carry no name - the point of them is the whole list.
     *
     * <p>Only on joining. It is the right thing for catching a client up with what it missed
     * while it was closed, and the wrong thing for a change, which the add and remove commands
     * carry exactly.
     */
    private void sync() {
        fire(onSync.get(), "");
    }

    private void fire(List<String> templates, String name) {
        for (String template : templates) run(template, name);
    }

    /**
     * Sends one rendered command, or queues it.
     *
     * <p>A Meteor command goes through Meteor, never the server: sending {@code .friend add} to
     * chat would broadcast it. Those run at once, because they cost nothing. Everything else is
     * a chat packet and joins the queue.
     */
    private void run(String template, String name) {
        if (template == null) return;
        String cmd = template.replace("{name}", name).trim();
        if (cmd.isEmpty()) return;

        String prefix = Config.get().prefix.get();
        if (prefix != null && !prefix.isEmpty() && cmd.startsWith(prefix)) {
            try {
                Commands.dispatch(cmd.substring(prefix.length()));
                if (log.get()) info("Ran %s", cmd);
            } catch (Exception e) {
                // An unknown command - most likely one meant for a client that is not Meteor,
                // written with Meteor's prefix by mistake. Dropped rather than leaked to chat.
                if (log.get()) warning("Could not run %s: %s", cmd, e.getMessage());
            }
            return;
        }

        queue(cmd, name);
    }

    // --- the queue -----------------------------------------------------------

    /**
     * One command waiting to go out, with enough about it to know its own opposite.
     *
     * @param kind which template list it came from, so an add can recognise a remove
     * @param name who it is about
     */
    private record Pending(String command, int kind, String name) { }

    private static final int FRIEND_ADD = 0;
    private static final int FRIEND_REMOVE = 1;
    private static final int ENEMY_ADD = 2;
    private static final int ENEMY_REMOVE = 3;
    private static final int SYNC = 4;

    private final java.util.ArrayDeque<Pending> outbox = new java.util.ArrayDeque<>();
    private int sendCooldown;
    /** Which list the command being rendered came from, since run() only sees the text. */
    private int currentKind = SYNC;

    /**
     * Puts a command in the queue, unless it undoes one already in it.
     *
     * <p>A friend list of fifty emptied by a bypass is fifty chat messages, and fifty chat
     * messages in one tick is 2b2t closing the connection. So they leave one at a time, paced.
     *
     * <p>And a command that cancels one still waiting is not sent at all: switch the bypass on
     * and straight off again and the removals never left, so the additions have nothing to
     * announce. Without that, a moment's indecision is a hundred messages about a state that
     * never changed.
     */
    private void queue(String cmd, String name) {
        int opposite = switch (currentKind) {
            case FRIEND_ADD -> FRIEND_REMOVE;
            case FRIEND_REMOVE -> FRIEND_ADD;
            case ENEMY_ADD -> ENEMY_REMOVE;
            case ENEMY_REMOVE -> ENEMY_ADD;
            default -> -1;
        };

        if (opposite != -1) {
            for (var it = outbox.iterator(); it.hasNext(); ) {
                Pending pending = it.next();
                if (pending.kind() == opposite && pending.name().equalsIgnoreCase(name)) {
                    it.remove();
                    if (log.get()) info("Dropped %s; it cancels one still waiting.", cmd);
                    return;
                }
            }
        }

        outbox.add(new Pending(cmd, currentKind, name));
    }

    /**
     * Lets one command out per interval.
     *
     * <p>Called every tick from the module's own tick handler, before anything else queues more,
     * so a burst drains at a steady rate rather than in the gaps between other work.
     */
    private void drain() {
        if (outbox.isEmpty()) return;

        if (sendCooldown > 0) {
            sendCooldown--;
            return;
        }

        Pending next = outbox.poll();
        if (next == null) return;

        ChatUtils.sendPlayerMsg(next.command());
        sendCooldown = sendInterval.get();

        if (log.get()) {
            info("Sent %s%s", next.command(),
                outbox.isEmpty() ? "" : " (" + outbox.size() + " to go)");
        }
    }
}
