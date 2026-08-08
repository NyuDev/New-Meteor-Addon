package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
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

    private final Setting<List<String>> onSync = sgGeneral.add(new StringListSetting.Builder()
        .name("on-sync")
        .description("Commands that make the other client re-read Meteor's whole friend list. " +
                     "Sent after joining a world and after every change, since a full sync " +
                     "cannot drift the way a missed add can.")
        .defaultValue(List.of(";friend sync meteor"))
        .build());

    private final Setting<Boolean> syncOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("sync-on-join")
        .description("Send the sync commands a few seconds after joining a world, so a client " +
                     "opened after the list changed catches up.")
        .defaultValue(true)
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

    /** Ticks in the current world, or -1 when out of one. Counts only as far as the join sync. */
    private int inWorld = -1;

    public FriendSync() {
        super(NewAddon.CATEGORY, "friend-sync",
            "Syncs Meteor's friend list to other clients through chat commands.");
    }

    @Override
    public void onActivate() {
        known = null;
        inWorld = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            // Left the world. The baseline goes with it: the list can be edited between sessions,
            // and diffing against a stale one would fire a change that never happened.
            known = null;
            inWorld = -1;
            return;
        }

        if (inWorld < JOIN_DELAY) {
            inWorld++;
            if (inWorld == JOIN_DELAY && syncOnJoin.get()) sync();
        }

        Set<String> names = new HashSet<>();
        for (var friend : Friends.get()) names.add(friend.getName());

        boolean changed = false;
        if (known != null) {
            for (String name : names) {
                if (!known.contains(name)) {
                    fire(onAdd.get(), name);
                    changed = true;
                }
            }
            for (String name : known) {
                if (!names.contains(name)) {
                    fire(onRemove.get(), name);
                    changed = true;
                }
            }
        }
        known = names;

        // After the adds and removes, not instead of them: whichever end the other client
        // understands, it ends up with the same list.
        if (changed) sync();
    }

    /** Sends the sync commands. They carry no name - the point of them is the whole list. */
    private void sync() {
        fire(onSync.get(), "");
    }

    private void fire(List<String> templates, String name) {
        for (String template : templates) run(template, name);
    }

    /**
     * Sends one rendered command.
     *
     * <p>A Meteor command goes through Meteor, never the server: sending {@code .friend add} to
     * chat would broadcast it. Everything else is a message for another client and is sent as
     * one.
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

        ChatUtils.sendPlayerMsg(cmd);
        if (log.get()) info("Sent %s", cmd);
    }
}
