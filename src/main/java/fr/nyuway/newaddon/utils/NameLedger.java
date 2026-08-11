package fr.nyuway.newaddon.utils;

import fr.nyuway.newaddon.NewAddon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who has been called what, kept by UUID.
 *
 * <h2>Why this exists at all</h2>
 * A UUID never changes and a name does, and everything that matters outside Meteor is keyed by
 * name: the enemy list, temporary friends, and the commands sent to other clients, which have no
 * UUID to fall back on. Meteor copes with a rename because it stores the UUID - but its
 * {@code Friend} keeps that UUID private, so there is no way to ask it who someone used to be.
 *
 * <p>So the addon keeps its own record. Every name seen against a UUID is written down, and a
 * name that disagrees with the last one is a rename - noticed from the tab list, where they are
 * standing under their new name with the same UUID, at the cost of a map lookup and no request
 * to anybody.
 *
 * <p>The history is worth having for its own sake, too: knowing that the person you are talking
 * to used to be someone you have heard of is exactly the sort of thing an anarchy server makes
 * you want to know.
 */
public final class NameLedger {

    private NameLedger() { }

    /** Names by UUID, oldest first, current last. */
    private static final Map<UUID, List<String>> NAMES = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;

    private static Path file() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("new-addon").resolve("names.txt");
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

                try {
                    UUID id = UUID.fromString(line.substring(0, eq).trim());
                    List<String> history = new ArrayList<>();
                    for (String name : line.substring(eq + 1).split(",")) {
                        if (!name.isBlank()) history.add(name.trim());
                    }
                    if (!history.isEmpty()) NAMES.put(id, history);
                } catch (IllegalArgumentException ignored) {
                    // A line edited into nonsense. Skip it, keep the rest.
                }
            }
        } catch (IOException e) {
            NewAddon.LOG.warn("[names] could not read {}: {}", f, e.toString());
        }
    }

    /**
     * Writes down what someone is called now.
     *
     * @return the name they had before, when this is a change; null when it is not news
     */
    public static String record(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) return null;
        ensureLoaded();

        List<String> history = NAMES.computeIfAbsent(id, k -> new ArrayList<>());
        if (!history.isEmpty() && history.get(history.size() - 1).equals(name)) return null;

        String previous = history.isEmpty() ? null : history.get(history.size() - 1);

        // A name reclaimed after a detour is moved to the end rather than repeated, so the
        // history reads as a sequence and not as a log.
        history.remove(name);
        history.add(name);

        // Keeping every name anyone ever had would grow without end for no benefit; the last
        // few are the ones a person recognises.
        while (history.size() > 6) history.remove(0);

        dirty = true;
        return previous;
    }

    /** Every name we have seen for them, oldest first, current last. Empty when unknown. */
    public static List<String> history(UUID id) {
        ensureLoaded();
        List<String> history = NAMES.get(id);
        return history == null ? List.of() : List.copyOf(history);
    }

    /** The names they used to have, newest first. Empty when they have only ever had one. */
    public static List<String> previousNames(UUID id) {
        List<String> history = history(id);
        if (history.size() < 2) return List.of();

        List<String> past = new ArrayList<>(history.subList(0, history.size() - 1));
        java.util.Collections.reverse(past);
        return past;
    }

    /** Writes the file, and only when something changed. Called on a timer, never per frame. */
    public static void flush() {
        if (!dirty) return;
        dirty = false;

        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<UUID, List<String>> e : NAMES.entrySet()) {
                sb.append(e.getKey()).append('=').append(String.join(",", e.getValue())).append('\n');
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            NewAddon.LOG.warn("[names] could not write {}: {}", f, e.toString());
        }
    }
}
