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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The addon's own enemy list, since Meteor has friends but no opposite.
 *
 * <p>Kept as plain names, one per line, in {@code meteor-client/new-addon/enemies.txt} - the
 * same shape as the pinned list, and for the same reason: a name is what the {@code .enemy}
 * command is given and what the chat carries, so keying on it is what makes "add someone I have
 * never spoken to" work at all. Comparison is case-insensitive, as Minecraft logins are, while
 * the case a name was added with is what gets shown back.
 *
 * <p>Static and loaded once: both the command and the message window ask the same question -
 * is this person an enemy - and a colour decided per row per frame cannot afford a file read.
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

    /** Lowercased names, for the O(1) test that runs while the list is drawn. */
    private static final Set<String> lower = new HashSet<>();
    /** Names in the case they were added, insertion order, for {@link #names}. */
    private static final List<String> display = new ArrayList<>();
    private static boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("new-addon").resolve("enemies.txt");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String name = line.trim();
                if (name.isEmpty()) continue;
                if (lower.add(name.toLowerCase())) display.add(name);
            }
        } catch (IOException e) {
            NewAddon.LOG.warn("[enemies] could not read {}: {}", f, e.toString());
        }
    }

    private static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            for (String name : display) sb.append(name).append('\n');
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            NewAddon.LOG.warn("[enemies] could not write {}: {}", f, e.toString());
        }
    }

    public static boolean isEnemy(String name) {
        if (name == null || name.isEmpty()) return false;
        ensureLoaded();
        return lower.contains(name.toLowerCase());
    }

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
        if (name == null || name.isBlank()) return false;
        ensureLoaded();
        if (!lower.add(name.toLowerCase())) return false;
        display.add(name);
        save();
        unfriend(name);
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
        if (!lower.remove(name.toLowerCase())) return false;
        display.removeIf(n -> n.equalsIgnoreCase(name));
        save();
        return true;
    }

    public static void clear() {
        ensureLoaded();
        lower.clear();
        display.clear();
        save();
    }

    /** The enemies, in the case they were added and the order they were added in. */
    public static List<String> names() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(display));
    }
}
