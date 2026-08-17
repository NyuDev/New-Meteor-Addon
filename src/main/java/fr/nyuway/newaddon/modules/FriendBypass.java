package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FriendBypass - takes everyone off the friend list for a friendly fight, and puts them back.
 *
 * <p>KillAura, aim assists and everything else that respects friends will not touch one, which
 * is right until the moment you have agreed to fight. Emptying the list by hand and rebuilding
 * it afterwards is how that gets done, and how a list of fifty people gets lost.
 *
 * <h2>What you do by hand wins</h2>
 * The list is not frozen while this is on. If you friend someone during a bypass, they are a
 * friend - switching the module off leaves them one. If you unfriend someone who was on the list
 * before, they stay off. Only the people this module removed, and who have not been touched
 * since, are put back.
 *
 * <p>That is done by watching for changes rather than assuming there are none: anything on the
 * list that this module did not put there was added by you, and anything missing that it was not
 * holding was removed by you. It costs a set comparison a tick and means the module can never
 * argue with the person using it.
 *
 * <h2>Only the people who are here</h2>
 * A friend list is years of people; a fight is the handful in front of you. Only friends who are
 * actually on the server are set aside, because they are the only entries that change anything -
 * and because every removal travels to the other clients and has to travel back afterwards.
 * Someone who logs in while the bypass is on is caught on the next tick.
 */
public class FriendBypass extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Say in chat how many were set aside and how many came back.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> silenceSync = sgGeneral.add(new BoolSetting.Builder()
        .name("silence-sync")
        .description("Keep the bypass to Meteor and tell no other client about it. Off, because " +
                     "the other clients protect friends too - a bypass they are not told about " +
                     "works in exactly one of the places it needs to. FriendSync paces what it " +
                     "sends, so a list of fifty leaves steadily rather than all at once.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> restoreOnDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-on-death")
        .description("Switch off and put everyone back when you die, since the fight is over " +
                     "either way and coming back with an empty friend list is how it gets lost.")
        .defaultValue(true)
        .build());

    /** Who this module removed, and what they were, so the same friend goes back. */
    private final Map<String, Friend> held = new LinkedHashMap<>();

    /** The list as this module last left it, to tell its own doing from the user's. */
    private final Set<String> expected = new HashSet<>();

    /**
     * People the user friended while the bypass was on, and who are therefore left alone.
     *
     * <p>Needed because the sweep runs every tick. Without it, friending someone mid-fight would
     * be undone on the very next tick by the same code that catches late arrivals: it looks for
     * friends who are online, and that is exactly what they now are.
     */
    private final Set<String> spared = new HashSet<>();

    /** True while this module is the one changing the list, so the watcher ignores it. */
    private boolean ourChange;

    public FriendBypass() {
        super(NewAddon.CATEGORY, "friend-bypass",
            "Sets the friend list aside for a friendly fight, and puts it back after.");
    }

    /**
     * Ticks the list still counts as being rearranged after the module switches off.
     *
     * <p>Every watcher of the friend list runs once a tick and compares against what it saw
     * last. The restore happens between two of those looks, so without a window on this side of
     * it the very next look sees fifty additions and does whatever it does about them - which
     * for LiveMessage is fifty whispers, and on 2b2t that is a kick before the fiftieth is sent.
     */
    private static final long SETTLE_MS = 3000;

    /** When the last restore finished, so the window above can be measured from it. */
    private static long finishedAt;

    /**
     * Whether the friend list is being rearranged by this module rather than by the user.
     *
     * <p>Anything that reacts to the list changing should ask this and reset its baseline
     * instead of acting. Not one flag per watcher: whoever adds the next one should not have to
     * know this module exists to avoid being the thing that gets someone kicked.
     */
    public static boolean rearranging() {
        FriendBypass module = Modules.get() == null ? null : Modules.get().get(FriendBypass.class);
        if (module != null && module.isActive()) return true;
        return System.currentTimeMillis() - finishedAt < SETTLE_MS;
    }

    /**
     * Whether FriendSync in particular should stay quiet.
     *
     * <p>Only when asked for. Note what this deliberately does not do: it does not stay true
     * after the module switches off. {@link #rearranging} does, and LiveMessage uses it to avoid
     * greeting fifty people at once - but FriendSync must see the restore, or the other clients
     * would be told about the removals and never about the friends coming back.
     */
    public static boolean silencingSync() {
        FriendBypass module = Modules.get() == null ? null : Modules.get().get(FriendBypass.class);
        return module != null && module.isActive() && module.silenceSync.get();
    }

    @Override
    public void onActivate() {
        held.clear();
        expected.clear();
        spared.clear();

        int set = setAsideOnlineFriends();
        snapshot();

        if (announce.get()) {
            info(set == 0 ? "Nobody on the friend list is here." : "Set aside " + set + " friend(s).");
        }
    }

    /**
     * Takes the friends who are actually here off the list, and only those.
     *
     * <p>A friend list is years of people; a fight is the handful standing in front of you.
     * Removing all of them means every one of those removals travels to the other clients and
     * every one has to travel back afterwards - minutes of chat about people who are not even
     * on the server. The only entries that change anything are the ones for players who are
     * here to be hit.
     *
     * <p>Run again every tick while the bypass is on, so somebody who logs in mid-fight is set
     * aside as well rather than being protected for arriving late.
     */
    private int setAsideOnlineFriends() {
        if (mc.getConnection() == null) return 0;

        Friends friends = Friends.get();
        List<Friend> here = new ArrayList<>();

        for (Friend friend : friends) {
            if (spared.contains(key(friend))) continue;
            if (mc.getConnection().getPlayerInfo(friend.getName()) != null) here.add(friend);
        }

        ourChange = true;
        for (Friend friend : here) {
            held.put(key(friend), friend);
            friends.remove(friend);
        }
        ourChange = false;

        return here.size();
    }

    @Override
    public void onDeactivate() {
        Friends friends = Friends.get();

        // Anyone friended by hand while the bypass was on stays a friend and is not counted as
        // being put back - they were never taken away.
        ourChange = true;
        int back = 0;
        for (Friend friend : held.values()) {
            if (friends.get(friend.getName()) == null) {
                friends.add(friend);
                back++;
            }
        }
        ourChange = false;

        // Stamped after the work, so the quiet window is measured from the moment the list
        // stopped moving rather than from the moment the module was switched off.
        finishedAt = System.currentTimeMillis();

        if (announce.get()) info("Put %d friend(s) back.", back);

        held.clear();
        expected.clear();
        spared.clear();
    }

    @meteordevelopment.orbit.EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        if (restoreOnDeath.get() && mc.player != null && mc.player.isDeadOrDying()) {
            info("Died; putting the friend list back.");
            toggle();
            return;
        }

        // Order matters. Anything the user did to the list is read first, so a friend added
        // between two ticks is recognised as theirs before the sweep below would take them
        // straight back off for the crime of being online.
        watchForManualChanges();

        // Somebody who logs in mid-fight is set aside too. Arriving late is not a reason to be
        // the one person in the fight that nothing will touch.
        int arrived = setAsideOnlineFriends();
        if (arrived > 0 && announce.get()) info("Set aside %d who just arrived.", arrived);

        snapshot();
    }

    /** Records the friend list as it now stands, which is the baseline for the next look. */
    private void snapshot() {
        expected.clear();
        for (Friend friend : Friends.get()) expected.add(key(friend));
    }

    /**
     * Notices anything the user did to the list and stops holding it against them.
     *
     * <p>Two things to catch. Someone added while the bypass is on is a friend the user wants,
     * so the module forgets it ever removed them - otherwise switching off would try to add a
     * friend who is already there, or worse, overwrite the one they made. And someone who was
     * held but has appeared some other way is the same case.
     */
    private void watchForManualChanges() {
        if (ourChange) return;

        Set<String> now = new HashSet<>();
        for (Friend friend : Friends.get()) now.add(key(friend));

        // Only ever compared against the last look, so the work is a set build a tick over a
        // list that is nearly always tiny during a bypass.
        if (now.equals(expected)) return;

        for (String name : now) {
            // Present now, absent when this module last looked: nothing here adds to the list,
            // so somebody else did - which is to say, the person playing.
            if (expected.contains(name)) continue;

            spared.add(name);
            boolean wasHeld = held.remove(name) != null;
            if (announce.get()) {
                info(wasHeld
                    ? "%s was friended during the bypass; leaving them a friend."
                    : "%s was friended during the bypass; leaving them alone.", name);
            }
        }
    }

    private static String key(Friend friend) {
        String name = friend.getName();
        return name == null ? String.valueOf((UUID) null) : name.toLowerCase();
    }
}
