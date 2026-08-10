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
 * <h2>Other clients</h2>
 * A bypass is a local, temporary thing - the other client has no idea a fight is on, and telling
 * it to drop fifty friends and add them again is fifty commands into chat for nothing. So
 * {@link FriendSync} is told to hold its tongue while this is running, and the list it sees
 * afterwards is the list it saw before.
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
        .description("Stop FriendSync sending anything while the bypass is on. A bypass is a " +
                     "local, temporary thing; the other client does not need fifty removals and " +
                     "fifty additions for a fight it cannot see.")
        .defaultValue(true)
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

    /** True while this module is the one changing the list, so the watcher ignores it. */
    private boolean ourChange;

    public FriendBypass() {
        super(NewAddon.CATEGORY, "friend-bypass",
            "Sets the friend list aside for a friendly fight, and puts it back after.");
    }

    /** Whether a bypass is running, for FriendSync to know to stay quiet. */
    public static boolean silencingSync() {
        FriendBypass module = Modules.get() == null ? null : Modules.get().get(FriendBypass.class);
        return module != null && module.isActive() && module.silenceSync.get();
    }

    @Override
    public void onActivate() {
        held.clear();
        expected.clear();

        Friends friends = Friends.get();
        List<Friend> all = new ArrayList<>();
        for (Friend friend : friends) all.add(friend);

        ourChange = true;
        for (Friend friend : all) {
            held.put(key(friend), friend);
            friends.remove(friend);
        }
        ourChange = false;

        if (announce.get()) info("Set aside %d friend(s) for the fight.", held.size());
        if (held.isEmpty() && announce.get()) info("Nothing to set aside; the list was empty.");
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

        if (announce.get()) info("Put %d friend(s) back.", back);

        held.clear();
        expected.clear();
    }

    @meteordevelopment.orbit.EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        if (restoreOnDeath.get() && mc.player != null && mc.player.isDeadOrDying()) {
            info("Died; putting the friend list back.");
            toggle();
            return;
        }

        watchForManualChanges();
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
        if (ourChange || held.isEmpty()) return;

        Set<String> now = new HashSet<>();
        for (Friend friend : Friends.get()) now.add(key(friend));

        // Only ever compared against the last look, so the work is a set build a tick over a
        // list that is nearly always tiny during a bypass.
        if (now.equals(expected)) return;
        expected.clear();
        expected.addAll(now);

        for (String name : now) {
            if (held.remove(name) != null && announce.get()) {
                info("%s was friended during the bypass; leaving them a friend.", name);
            }
        }
    }

    private static String key(Friend friend) {
        String name = friend.getName();
        return name == null ? String.valueOf((UUID) null) : name.toLowerCase();
    }
}
