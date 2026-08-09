package fr.nyuway.newaddon.gui.live;

import fr.nyuway.newaddon.NewAddon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each window was left, kept across restarts.
 *
 * <p>The list and every conversation had their own remembered spot already, but only for as
 * long as the game was running. Dragging a window somewhere and sizing it to suit, then finding
 * it back in the middle at its default size the next evening, is the kind of thing that makes a
 * tool feel like it is not yours.
 *
 * <p>One line per window, {@code key=x,y,w,h}, in {@code meteor-client/livemessage/windows.txt}
 * beside the rest. Ours alone - upstream has no such file - so nothing is confused by it.
 *
 * <h2>When it touches the disk</h2>
 * Read once, on the first question asked of it. Written when a drag or a resize ends, which is
 * a thing a person does a few times a minute at worst. Never on a frame: a window list that
 * asked the filesystem while it was being dragged is exactly the bug that made dragging stutter,
 * and it is not worth making twice.
 */
public final class LivePlaces {

    private LivePlaces() { }

    private static final Map<String, int[]> PLACES = new LinkedHashMap<>();
    private static boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("livemessage").resolve("windows.txt");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        Path f = file();
        if (!Files.isRegularFile(f)) return;

        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String key = line.substring(0, eq).trim();
                String[] parts = line.substring(eq + 1).split(",");
                if (key.isEmpty() || parts.length != 4) continue;

                try {
                    PLACES.put(key, new int[]{
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()),
                        Integer.parseInt(parts[3].trim()),
                    });
                } catch (NumberFormatException ignored) {
                    // A line someone edited by hand into nonsense. Skip it, keep the rest.
                }
            }
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not read {}: {}", f, e.toString());
        }
    }

    /** Position and size last left for this window, as {x, y, w, h}, or null. */
    public static int[] get(String key) {
        ensureLoaded();
        return PLACES.get(key);
    }

    /** Remembers where a window was left, and writes it down. */
    public static void put(String key, int x, int y, int w, int h) {
        ensureLoaded();

        int[] was = PLACES.get(key);
        if (was != null && was[0] == x && was[1] == y && was[2] == w && was[3] == h) return;

        PLACES.put(key, new int[]{x, y, w, h});
        save();
    }

    private static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, int[]> e : PLACES.entrySet()) {
                int[] p = e.getValue();
                sb.append(e.getKey()).append('=')
                    .append(p[0]).append(',').append(p[1]).append(',')
                    .append(p[2]).append(',').append(p[3]).append('\n');
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not write {}: {}", f, e.toString());
        }
    }
}
