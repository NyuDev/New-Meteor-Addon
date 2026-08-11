package fr.nyuway.newaddon.utils;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.modules.TempFriends;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;

/**
 * Keeps friend and enemy exclusive, including for friends added outside this addon.
 *
 * <h2>Why a watcher and not just the write sites</h2>
 * {@link Enemies#add} already drops the friend, so the enemy side can never create the conflict.
 * The friend side can: {@code .friend add}, Meteor's own Friends tab and its API all write to
 * that list without firing anything to hook. So the only way someone ends up both is a friend
 * added elsewhere - which makes the rule here unambiguous rather than a guess. The newer fact is
 * always the friending, so the enemy is what gives way.
 *
 * <p>Subscribed at startup rather than owned by a module, because the rule should not depend on
 * which modules happen to be switched on. It runs once a second: a conflict is made by hand, and
 * nothing about it needs answering inside a tick.
 */
public final class Relations {

    /** Ticks between checks. Twenty is a second - invisible to a person, free to the game. */
    private static final int EVERY = 20;

    private static Relations instance;

    private int ticks;

    private Relations() { }

    /** Subscribes the watcher. Call once, from the addon's {@code onInitialize}. */
    public static void install() {
        if (instance != null) return;
        instance = new Relations();
        MeteorClient.EVENT_BUS.subscribe(instance);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (++ticks < EVERY) return;
        ticks = 0;

        // Reading Meteor's list and writing only ours, so nothing is modified while iterated.
        for (Friend friend : Friends.get()) {
            String name = friend.getName();
            if (Enemies.isEnemy(name)) Enemies.remove(name);
        }

        renames();
    }

    /**
     * Notices a friend who has changed their name, and writes the new one down.
     *
     * <p>Meteor keys a friend by UUID and copes with a rename on its own, but only when it
     * happens to refresh that friend from Mojang, which is rare. Everything downstream is keyed
     * by name - the enemy list, temporary friends, and above all the commands FriendSync sends
     * to other clients, which have no UUID to fall back on. So a friend who renames quietly
     * stops being a friend everywhere except in Meteor.
     *
     * <p>The tab list is the cheap way to see it: they are standing right there, under their new
     * name, with the same UUID. No request to Mojang, no waiting - one map lookup per friend,
     * once a second. Renaming the entry is all this does; FriendSync compares names each tick
     * and will send the removal and the addition by itself, because from where it sits that is
     * exactly what happened.
     */
    private void renames() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        boolean changedFriends = false;

        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            UUID id = Profiles.idOf(info.getProfile());
            String now = Profiles.nameOf(info.getProfile());

            String before = NameLedger.record(id, now);
            if (before == null) continue;

            NewAddon.LOG.info("[relations] {} is now {}", before, now);

            // Meteor's own entry, renamed in place. Its friend list is a plain list searched by
            // name, so changing the field is all there is to it - and FriendSync, which compares
            // names each tick, will send the removal and the addition of its own accord, which
            // from where it sits is exactly what happened.
            Friend friend = Friends.get().get(before);
            if (friend != null && Friends.get().get(now) == null) {
                friend.name = now;
                changedFriends = true;
            }

            // The other two lists are keyed by name as well, and a stale key in either is a
            // relationship attached to a name nobody has any more.
            if (Enemies.remove(before)) Enemies.add(now);
            TempFriends.rename(before, now);
        }

        if (changedFriends) Friends.get().save();
        NameLedger.flush();
    }
}
