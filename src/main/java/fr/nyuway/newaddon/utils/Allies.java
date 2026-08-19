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
 * Allies - the people a group you are in is at peace with.
 *
 * <h2>An ally is a friend, with a reason</h2>
 * Not a third relationship: an ally <em>is</em> on Meteor's friend list, and everything that
 * protects a friend protects them, because that is the point of the arrangement. What the list
 * here adds is why. A friend is somebody you know; an ally is somebody your group has an
 * agreement with, who you may never have spoken to and may not speak to again. Both mean do not
 * shoot. They do not mean the same thing when you are deciding whether to say where you are.
 *
 * <p>So {@link #add} friends them as well, and taking the tag off leaves the friendship - the
 * opposite of an ally is a plain friend, not a stranger. Being unfriended by any route drops
 * the tag, since a tag on somebody who is not a friend is a claim about nothing; {@link
 * Relations} does that once a second, and {@link Enemies#add} does it on the spot.
 *
 * <p>The colour is Meteor's friend colour, darker - the same family, because the answer to "can
 * I shoot" is the same, and darker because the answer to "do I trust them with a location" is
 * not. It sits in Meteor's config tab beside {@code friend-color} and {@code enemy-color}.
 *
 * <p>Everything else is {@link Enemies}, down to the file format: {@code Name=uuid} per line in
 * {@code meteor-client/new-addon/allies.txt}, a bare name until the id is learned, ids attached
 * from the tab list once a second, and concurrent collections because the render thread reads
 * this while the game thread writes it.
 */
public final class Allies {

    private Allies() { }

    /**
     * The colour allies are drawn in, in Meteor's config tab beside the friend and enemy ones.
     *
     * <p>Registered rather than declared because the group belongs to Meteor, and from the
     * addon's {@code onInitialize} because that is the one moment early enough for Meteor's
     * second config pass to remember it - see {@link Enemies#registerColorSetting}.
     */
    private static Setting<SettingColor> colorSetting;

    /** Meteor's default friend green at a bit over half, used until the setting exists. */
    private static final int FALLBACK = 0x009164;

    /** Adds {@code ally-color} to Meteor's config tab. Call once, from {@code onInitialize}. */
    public static void registerColorSetting() {
        if (colorSetting != null) return;

        SettingGroup visual = Config.get().settings.getGroup("Visual");
        if (visual == null) {
            NewAddon.LOG.warn("[allies] no Visual group in Meteor's config; ally-color not added");
            return;
        }

        colorSetting = visual.add(new ColorSetting.Builder()
            .name("ally-color")
            .description("The color used to show allies - the friend colour, darker.")
            .defaultValue(new SettingColor(0, 145, 100))
            .build());

        RainbowColors.addSetting(colorSetting);
    }

    /** The ally colour, packed 0xRRGGBB the way the windows want it. */
    public static int color() {
        if (colorSetting == null) return FALLBACK;
        SettingColor c = colorSetting.get();
        return (c.r << 16) | (c.g << 8) | c.b;
    }

    // --- the list ------------------------------------------------------------

    /** One ally. Both fields move: a rename changes the name, and the id arrives later. */
    private static final class Entry {
        private volatile String name;
        private volatile UUID id;

        private Entry(String name, UUID id) {
            this.name = name;
            this.id = id;
        }
    }

    /** In the order they were added, which is the order {@link #names} reports. */
    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    /** By lowercased name, for the lookup that happens while a list is being drawn. */
    private static final Map<String, Entry> byName = new ConcurrentHashMap<>();

    /** By id, for the same lookup when the caller has something better than a name. */
    private static final Map<UUID, Entry> byId = new ConcurrentHashMap<>();

    /** Volatile, and set only once the file has actually been read. */
    private static volatile boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("new-addon").resolve("allies.txt");
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
            NewAddon.LOG.warn("[allies] could not read {}: {}", f, e.toString());
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
            NewAddon.LOG.warn("[allies] could not write {}: {}", f, e.toString());
        }
    }

    // --- asking --------------------------------------------------------------

    /** Whether that name is an ally. Use {@link #isAlly(UUID, String)} when an id is at hand. */
    public static boolean isAlly(String name) {
        if (name == null || name.isEmpty()) return false;
        ensureLoaded();
        return byName.containsKey(key(name));
    }

    /** Whether that player is an ally, by id alone. */
    public static boolean isAlly(UUID id) {
        if (id == null) return false;
        ensureLoaded();
        return byId.containsKey(id);
    }

    /**
     * Whether that player is an ally - the question every colour actually wants to ask.
     *
     * <p>The id wins where there is one, for the reasons {@link Enemies#isEnemy(UUID, String)}
     * gives: it survives a rename, and it stops a name somebody else has since taken from
     * carrying an agreement made with the person who used to have it.
     */
    public static boolean isAlly(UUID id, String name) {
        ensureLoaded();

        if (id != null && byId.containsKey(id)) return true;
        if (name == null || name.isEmpty()) return false;

        Entry entry = byName.get(key(name));
        if (entry == null) return false;

        return entry.id == null || id == null || entry.id.equals(id);
    }

    // --- learning ------------------------------------------------------------

    /** Ties an id to an ally, and follows a rename. Called for the tab list once a second. */
    public static void learn(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) return;
        ensureLoaded();

        Entry known = byId.get(id);
        if (known != null) {
            if (known.name.equalsIgnoreCase(name)) return;

            NewAddon.LOG.info("[allies] {} is now {}", known.name, name);
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

    public static boolean add(String name) {
        return add(name, null);
    }

    /**
     * Marks somebody an ally, friending them if they are not one already.
     *
     * <p>The friending is the substance of it - an ally who is not on the friend list is a note
     * nothing reads, and everything that decides whether to hit somebody asks Meteor, not this.
     *
     * @param id their UUID, or null to have it learned the next time they are seen
     * @return true when they were not already an ally
     */
    public static boolean add(String name, UUID id) {
        if (name == null || name.isBlank()) return false;
        ensureLoaded();

        String trimmed = name.trim();
        if (!put(new Entry(trimmed, id))) return false;

        save();

        // Both sides of the same fact. An enemy cannot be an ally, and the enemy list is checked
        // first everywhere, so leaving them on it would make the tag invisible as well as wrong.
        Enemies.remove(id, trimmed);
        if (Friends.get().get(trimmed) == null) Friends.get().add(new Friend(trimmed, id));

        return true;
    }

    /**
     * Drops the tag, and leaves the friendship.
     *
     * <p>The opposite of an ally is a plain friend, not a stranger: {@code .friends remove} is
     * how somebody stops being protected, and quietly doing that here would make this a much
     * more dangerous command than it reads as.
     *
     * @return true when the name was an ally and is now not
     */
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

    /** The allies, in the case they were added and the order they were added in. */
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
}
