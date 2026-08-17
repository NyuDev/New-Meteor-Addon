package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.Profiles;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TempFriends - a friend for now, not for ever.
 *
 * <p>Somebody you have just met and are about to do something with is a friend for the next
 * hour and not a friend for the next year, but a friend list has only one kind of entry. The
 * result is a list that fills with people you cannot place, and you stop trusting the colour.
 *
 * <h2>When a temporary friend stops being one</h2>
 * On leaving the server, and on closing the game - which is why the list is written down: a
 * client that crashes must not leave strangers on the friend list for ever, so the record is
 * read at startup and anyone still on it is taken off. And when they have been out of render
 * long enough to have gone somewhere else, which is the case this is actually for: you fought
 * beside someone, they flew off, and an hour later the friendship is a fiction.
 *
 * <p>Taking someone off by hand ends it too, and adding them permanently keeps them: the list
 * here is only a note of who was added temporarily, and anything you do yourself outranks it.
 */
public class TempFriends extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> absence = sgGeneral.add(new IntSetting.Builder()
        .name("forget-after")
        .description("Minutes out of render before a temporary friend is dropped. The clock " +
                     "restarts every time they are seen again, so someone who stays around " +
                     "stays a friend.")
        .defaultValue(15).min(1).max(240).sliderRange(1, 60)
        .build());

    private final Setting<Integer> maximum = sgGeneral.add(new IntSetting.Builder()
        .name("maximum-minutes")
        .description("Longest a temporary friend lasts however often they are seen. Zero means " +
                     "no limit, and only leaving or losing sight of them ends it.")
        .defaultValue(0).min(0).max(1440).sliderRange(0, 240)
        .build());

    private final Setting<Boolean> dropOnLeave = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-on-leave")
        .description("Drop them all when you leave the server. A friendship made for one session " +
                     "is over when the session is.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Say in chat when one is added and when one runs out.")
        .defaultValue(true)
        .build());

    /** One temporary friendship: when it started, and when they were last in render. */
    private record Temp(long addedAt, long seenAt) { }

    /** Keyed by lowercased name, which is what Meteor's friend list is keyed by too. */
    private static final Map<String, Temp> TEMPS = new HashMap<>();

    private static Path file() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("new-addon").resolve("temp-friends.txt");
    }

    public TempFriends() {
        super(NewAddon.CATEGORY, "temp-friends",
            "Friends for this session, dropped when they leave or when you do.");
    }

    private static TempFriends get() {
        return Modules.get() == null ? null : Modules.get().get(TempFriends.class);
    }

    // --- the list ------------------------------------------------------------

    /** Whether that name is a friend only for now. */
    public static boolean isTemporary(String name) {
        return name != null && TEMPS.containsKey(name.toLowerCase());
    }

    /**
     * Makes someone a friend for now.
     *
     * @return false when they were already a friend, temporary or otherwise - there is nothing
     *         to add, and quietly downgrading a real friendship to a temporary one would be a
     *         surprise nobody asked for
     */
    public static boolean add(String name, UUID id) {
        TempFriends module = get();

        // Refused outright while the module is off. Nothing would ever expire it - the timers
        // live in a tick handler Meteor only runs for an active module - so what you would get
        // is an ordinary friend under a name that says otherwise, which is worse than a no.
        if (module == null || !module.isActive()) {
            say(module, "Switch temp-friends on first; nothing would ever un-friend them.");
            return false;
        }

        if (name == null || name.isBlank()) return false;

        Friends friends = Friends.get();
        if (friends.get(name) != null && !isTemporary(name)) {
            say(module, name + " is already a friend for good; leaving it that way.");
            return false;
        }

        long now = System.currentTimeMillis();
        TEMPS.put(name.toLowerCase(), new Temp(now, now));

        // Meteor refuses a name it already holds and a name with a space in it, and says so by
        // returning false. Worth knowing about: silence here was indistinguishable from a dead
        // button, which is exactly what it looked like.
        if (friends.get(name) == null && !friends.add(new Friend(name, id))) {
            TEMPS.remove(name.toLowerCase());
            say(module, "Meteor would not add " + name + " to the friend list.");
            return false;
        }

        save();
        if (module.announce.get()) module.info("%s is a friend for now.", name);
        return true;
    }

    /** Says why nothing happened. A button that does nothing without a word reads as broken. */
    private static void say(TempFriends module, String why) {
        if (module != null) module.warning(why);
        else NewAddon.LOG.warn("[temp-friends] {}", why);
    }

    /** Ends it, taking them off the friend list with it. */
    public static boolean remove(String name) {
        if (name == null || TEMPS.remove(name.toLowerCase()) == null) return false;

        Friend friend = Friends.get().get(name);
        if (friend != null) Friends.get().remove(friend);
        save();
        return true;
    }

    /** Everyone currently a friend only for now. */
    public static List<String> names() {
        return new ArrayList<>(TEMPS.keySet());
    }

    /**
     * Follows someone who has renamed, keeping the clock they were already on.
     *
     * <p>Without this the entry sits under a name nobody has, so it is never seen in render
     * again, never expires, and the friendship it stands for outlives the session it was made
     * for. Both timers carry over: renaming is not a reason to start the fifteen minutes again.
     */
    public static void rename(String before, String now) {
        if (before == null || now == null) return;

        Temp temp = TEMPS.remove(before.toLowerCase());
        if (temp == null) return;

        TEMPS.put(now.toLowerCase(), temp);
        save();
    }

    // --- expiry --------------------------------------------------------------

    @Override
    public void onActivate() {
        load();
        // Anything left in the file is from a session that ended without tidying up - a crash,
        // or the game being closed. Those friendships are over by definition.
        if (!TEMPS.isEmpty()) {
            int dropped = dropAll();
            if (dropped > 0 && announce.get()) {
                info("Dropped %d temporary friend(s) left over from last time.", dropped);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            if (dropOnLeave.get() && !TEMPS.isEmpty()) dropAll();
            return;
        }

        if (TEMPS.isEmpty()) return;

        // Seen means loaded, not merely on the server: the point is that they are still around.
        long now = System.currentTimeMillis();
        for (var player : mc.level.players()) {
            String key = Profiles.nameOf(player.getGameProfile());
            if (key == null) continue;

            Temp temp = TEMPS.get(key.toLowerCase());
            if (temp != null) TEMPS.put(key.toLowerCase(), new Temp(temp.addedAt(), now));
        }

        long gone = absence.get() * 60_000L;
        long cap = maximum.get() * 60_000L;

        for (String key : new ArrayList<>(TEMPS.keySet())) {
            Temp temp = TEMPS.get(key);
            if (temp == null) continue;

            // Taken off by hand: the note is stale, and the person's own decision stands.
            if (Friends.get().get(key) == null) {
                TEMPS.remove(key);
                save();
                continue;
            }

            boolean tooLongAway = now - temp.seenAt() > gone;
            boolean tooLongAtAll = cap > 0 && now - temp.addedAt() > cap;
            if (!tooLongAway && !tooLongAtAll) continue;

            remove(key);
            if (announce.get()) {
                info("%s is no longer a friend: %s.", key,
                    tooLongAway ? "out of sight too long" : "time is up");
            }
        }
    }

    @Override
    public void onDeactivate() {
        dropAll();
    }

    private int dropAll() {
        int count = 0;
        for (String key : new ArrayList<>(TEMPS.keySet())) {
            if (remove(key)) count++;
        }
        TEMPS.clear();
        save();
        return count;
    }

    // --- the record ----------------------------------------------------------

    private static void load() {
        TEMPS.clear();
        Path f = file();
        if (!Files.isRegularFile(f)) return;

        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String name = line.trim();
                if (!name.isEmpty()) TEMPS.put(name.toLowerCase(), new Temp(0, 0));
            }
        } catch (IOException e) {
            NewAddon.LOG.warn("[temp-friends] could not read {}: {}", f, e.toString());
        }
    }

    private static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, String.join("\n", TEMPS.keySet()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            NewAddon.LOG.warn("[temp-friends] could not write {}: {}", f, e.toString());
        }
    }
}
