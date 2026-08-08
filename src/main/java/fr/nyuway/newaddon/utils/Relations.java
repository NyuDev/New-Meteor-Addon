package fr.nyuway.newaddon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.orbit.EventHandler;

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
    }
}
