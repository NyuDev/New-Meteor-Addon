package fr.nyuway.newaddon.utils;

import fr.nyuway.newaddon.NewAddon;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The addon's own enemy list, since Meteor has friends but no opposite.
 *
 * <p>Kept in {@code meteor-client/new-addon/enemies.txt}, one per line, as {@code Name=uuid} -
 * or a bare name for anyone whose id is not known yet, which is also the shape the file had
 * before ids were kept at all, so an older list reads unchanged.
 *
 * <h2>Why both a name and an id</h2>
 * A name is what you have when you add somebody: it is what the {@code .enemy} command is given,
 * what chat carries, and what makes "mark someone I have never met" work at all. An id is what
 * <em>stays</em>. Keeping only the name means an enemy who changes theirs quietly stops being
 * one, and the entry sits in the file under a name nobody has - which looks exactly like the
 * list being ignored, since the list plainly still has them in it.
 *
 * <p>So the id is learned rather than required. {@link #learn} is called for everyone in the tab
 * list once a second: the first time an enemy is seen their id is attached to the entry, and
 * from then on it is the id that decides. A rename after that is followed automatically, and the
 * entry is rewritten under the new name.
 *
 * <p>It also settles the other half of the same problem: a name that has been taken over by
 * somebody else. Once the id is known, an entry answers for that id and nobody else - so the
 * stranger who signs up under a dropped name is not treated as the person who used to have it.
 *
 * <p>Static and loaded once: the command, the tab list, the nametags and the message window all
 * ask the same question, and a colour decided per player per frame cannot afford a file read.
 *
 * <h2>Friend or enemy, never both</h2>
 * {@link #add} takes them off Meteor's friend list, and {@link Relations} takes a new friend off
 * this one. The two lists are the same question asked twice, so an answer of yes to both is not
 * a state worth being able to reach - it would only mean the client has to guess which colour
 * you meant. Someone is a friend, an enemy, or neither.
 */
public final class Enemies {

    private Enemies() { }

    /**
     * The colour enemies are drawn in, added to Meteor's own config tab beside its
     * {@code friend-color} rather than kept as a module setting.
     *
     * <p>That is where you already go to choose the friend colour, so it is where you would look
     * for its opposite; and it is a client-wide fact about a person, not a preference of one
     * module's window. Registered rather than declared because the group belongs to Meteor.
     */
    private static Setting<SettingColor> colorSetting;

    /** The orange-red used until the setting exists, kept clear of the red an ignore is drawn in. */
    private static final int FALLBACK = 0xFF6B3D;

    /**
     * Adds {@code enemy-color} to Meteor's config tab. Call once, from the addon's
     * {@code onInitialize}.
     *
     * <p>Timing is the whole reason this is a method and not a field initialiser. Meteor loads
     * the config twice: once inside {@code Systems.init()}, which happens before any addon
     * exists, and again in {@code Systems.load()} after every addon's {@code onInitialize()} has
     * run - and {@code System.load} re-reads the file each time rather than remembering it did.
     * That second pass is what fills this setting in, so registering from {@code onInitialize} is
     * both early enough to be remembered and the only place it can be done. Registered any later
     * it would draw fine and forget its value on every restart.
     */
    public static void registerColorSetting() {
        if (colorSetting != null) return;

        SettingGroup visual = Config.get().settings.getGroup("Visual");
        if (visual == null) {
            // Meteor renamed or dropped the group. The colour falls back to the constant rather
            // than the addon failing to load over a swatch.
            NewAddon.LOG.warn("[enemies] no Visual group in Meteor's config; enemy-color not added");
            return;
        }

        colorSetting = visual.add(new ColorSetting.Builder()
            .name("enemy-color")
            .description("The color used to show enemies.")
            .defaultValue(new SettingColor(255, 107, 61))
            .build());

        // Meteor registers its own colour settings for rainbow in Systems.init(), which has
        // already run by the time an addon exists - so this one has to say so itself, or ticking
        // rainbow on it would be a switch that does nothing while the friend colour's works.
        RainbowColors.addSetting(colorSetting);
    }

    /** The enemy colour, packed 0xRRGGBB the way the windows want it. */
    public static int color() {
        if (colorSetting == null) return FALLBACK;
        SettingColor c = colorSetting.get();
        return (c.r << 16) | (c.g << 8) | c.b;
    }

    // --- the list ------------------------------------------------------------

    /** One enemy. Both fields move: a rename changes the name, and the id arrives later. */
    private static final class Entry {
        private volatile String name;
        private volatile UUID id;

        private Entry(String name, UUID id) {
            this.name = name;
            this.id = id;
        }
    }

    /**
     * In the order they were added, which is the order {@link #names} reports.
     *
     * <h2>Why the concurrent collections</h2>
     * This is asked from the render thread while it draws a tab list and written from the game
     * thread when an id is learned, and a plain HashMap read on one thread while another writes
     * it has no guarantee of showing what was written - not late, but possibly never, and
     * per-entry. An enemy list where some entries answer and others do not, with no pattern
     * anybody could name, is what that looks like from the outside. The list is small and the
     * writes are rare, so correctness here costs nothing worth measuring.
     */
    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    /** By lowercased name, for the lookup that happens while a list is being drawn. */
    private static final Map<String, Entry> byName = new ConcurrentHashMap<>();

    /** By id, for the same lookup when the caller has something better than a name. */
    private static final Map<UUID, Entry> byId = new ConcurrentHashMap<>();

    /**
     * Volatile, and set only once the file has actually been read.
     *
     * <p>Marking it loaded first would let a second thread walk straight past a load that has
     * not finished and read an empty list - once, silently, and with no way to tell afterwards.
     */
    private static volatile boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("new-addon").resolve("enemies.txt");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        load();
    }

    private static synchronized void load() {
        if (loaded) return;

        Path f = file();
        if (!Files.isRegularFile(f)) {
            loaded = true;
            return;
        }

        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.isEmpty()) continue;

                // Name=uuid, or a bare name from a list written before ids were kept.
                String name = text;
                UUID id = null;

                int eq = text.indexOf('=');
                if (eq > 0) {
                    name = text.substring(0, eq).trim();
                    try {
                        id = UUID.fromString(text.substring(eq + 1).trim());
                    } catch (IllegalArgumentException ignored) {
                        // Edited into nonsense. The name is the part that matters; keep it.
                    }
                }

                if (!name.isEmpty()) put(new Entry(name, id));
            }
        } catch (IOException e) {
            NewAddon.LOG.warn("[enemies] could not read {}: {}", f, e.toString());
        }

        // Last, so nobody can see the flag before the entries it is promising.
        loaded = true;
    }

    /** Files an entry under both keys. Ignores a duplicate rather than shadowing one. */
    private static boolean put(Entry entry) {
        if (byName.containsKey(key(entry.name))) return false;
        if (entry.id != null && byId.containsKey(entry.id)) return false;

        entries.add(entry);
        byName.put(key(entry.name), entry);
        if (entry.id != null) byId.put(entry.id, entry);
        return true;
    }

    private static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());

            StringBuilder sb = new StringBuilder();
            for (Entry entry : entries) {
                sb.append(entry.name);
                if (entry.id != null) sb.append('=').append(entry.id);
                sb.append('\n');
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            NewAddon.LOG.warn("[enemies] could not write {}: {}", f, e.toString());
        }
    }

    // --- asking --------------------------------------------------------------

    /** Whether that name is an enemy. Use {@link #isEnemy(UUID, String)} when an id is at hand. */
    public static boolean isEnemy(String name) {
        if (name == null || name.isEmpty()) return false;
        ensureLoaded();
        return byName.containsKey(key(name));
    }

    /** Whether that player is an enemy, by id alone. */
    public static boolean isEnemy(UUID id) {
        if (id == null) return false;
        ensureLoaded();
        return byId.containsKey(id);
    }

    /**
     * Whether that player is an enemy - the question every colour actually wants to ask.
     *
     * <p>The id wins where there is one. It survives a rename, and it is what stops a name that
     * has since been taken by somebody else from carrying the mark: an entry whose id is known
     * answers for that id, so a stranger under the same name is a stranger.
     */
    public static boolean isEnemy(UUID id, String name) {
        ensureLoaded();

        if (id != null) {
            Entry byUuid = byId.get(id);
            if (byUuid != null) return true;
        }

        if (name == null || name.isEmpty()) return false;

        Entry entry = byName.get(key(name));
        if (entry == null) return false;

        // Same name, and we know the enemy's id is a different one. Not them.
        return entry.id == null || id == null || entry.id.equals(id);
    }

    // --- learning ------------------------------------------------------------

    /**
     * Ties an id to an enemy, and follows a rename.
     *
     * <p>Called for everyone in the tab list once a second. Two map lookups per player, and it
     * writes only on the tick where something actually changed - which is the first time an
     * enemy is seen, and then never again unless they rename.
     */
    public static void learn(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) return;
        ensureLoaded();

        Entry known = byId.get(id);
        if (known != null) {
            if (known.name.equalsIgnoreCase(name)) return;

            // Renamed. The entry follows them; keeping the old name would leave a line in the
            // file that matches nobody and an enemy who is no longer marked.
            NewAddon.LOG.info("[enemies] {} is now {}", known.name, name);
            byName.remove(key(known.name));
            known.name = name;
            byName.put(key(name), known);
            save();
            return;
        }

        Entry named = byName.get(key(name));
        if (named == null || named.id != null) return;

        named.id = id;
        byId.put(id, named);
        save();
    }

    // --- changing ------------------------------------------------------------

    /**
     * Makes someone an enemy, and stops them being a friend.
     *
     * <p>The friend is removed here rather than at the call sites so that every route in - the
     * command, the window's skull button, anything added later - cannot forget to. Meteor's list
     * is the one the rest of the client reads, so dropping them there is what actually makes the
     * two exclusive rather than merely making this window pretend they are.
     *
     * @return true when the name was not already an enemy
     */
    public static boolean add(String name) {
        return add(name, null);
    }

    /**
     * Makes someone an enemy, with their id when it is known.
     *
     * @param id their UUID, or null to have it learned the next time they are seen
     * @return true when they were not already an enemy
     */
    public static boolean add(String name, UUID id) {
        if (name == null || name.isBlank()) return false;
        ensureLoaded();

        if (!put(new Entry(name.trim(), id))) return false;

        save();
        unfriend(name);

        // The ally tag says why somebody is a friend, and they have just stopped being one.
        // Done here rather than left to Relations so the colour changes with the click.
        Allies.remove(id, name);
        return true;
    }

    /**
     * Takes someone off Meteor's friend list, if they were on it.
     *
     * @return true when a friend was actually removed
     */
    public static boolean unfriend(String name) {
        Friend friend = Friends.get().get(name);
        if (friend == null) return false;
        Friends.get().remove(friend);
        return true;
    }

    /** @return true when the name was an enemy and is now removed */
    public static boolean remove(String name) {
        if (name == null || name.isEmpty()) return false;
        ensureLoaded();
        return drop(byName.get(key(name)));
    }

    /** The same, for a caller holding an id - so a rename cannot leave an entry unremovable. */
    public static boolean remove(UUID id, String name) {
        ensureLoaded();

        Entry entry = id == null ? null : byId.get(id);
        if (entry == null && name != null && !name.isEmpty()) entry = byName.get(key(name));
        return drop(entry);
    }

    private static boolean drop(Entry entry) {
        if (entry == null) return false;

        entries.remove(entry);
        byName.remove(key(entry.name));
        if (entry.id != null) byId.remove(entry.id);

        save();
        return true;
    }

    public static void clear() {
        ensureLoaded();
        entries.clear();
        byName.clear();
        byId.clear();
        save();
    }

    /** The enemies, in the case they were added and the order they were added in. */
    public static List<String> names() {
        ensureLoaded();

        List<String> out = new ArrayList<>(entries.size());
        for (Entry entry : entries) out.add(entry.name);
        return Collections.unmodifiableList(out);
    }

    /** Whether their id is known yet, for a list that says how solid each entry is. */
    public static boolean isKnown(String name) {
        if (name == null || name.isEmpty()) return false;
        ensureLoaded();

        Entry entry = byName.get(key(name));
        return entry != null && entry.id != null;
    }

    /** Their id, or null when nobody by that name has been seen yet. */
    public static UUID idOf(String name) {
        if (name == null || name.isEmpty()) return null;
        ensureLoaded();

        Entry entry = byName.get(key(name));
        return entry == null ? null : entry.id;
    }
}
