package fr.nyuway.newaddon.modules.dm;

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

/**
 * The conversations, in memory and on disk.
 *
 * <h2>Why it is kept at all</h2>
 * A whisper scrolls out of chat in seconds and is gone at the next disconnect. Keeping it is
 * the whole point of a DM window: the value is not seeing the message once, it is still having
 * it tomorrow.
 *
 * <h2>Format</h2>
 * One tab-separated line per message - timestamp, direction, peer, text - appended as it
 * arrives. A log, not a document: an append survives a crash mid-write with at worst one torn
 * line, where rewriting a structured file loses everything. Tabs and newlines are escaped so a
 * message can never forge a second entry.
 */
public final class DmStore {

    /** Conversations kept in memory, most recently active last. */
    private final Map<String, List<DmMessage>> threads = new LinkedHashMap<>();

    private final int perThreadLimit;
    private Path file;

    public DmStore(int perThreadLimit) {
        this.perThreadLimit = perThreadLimit;
    }

    /** One line of a conversation. */
    public record DmMessage(long time, boolean incoming, String text) { }

    /**
     * Points the store at a file and loads what is there.
     *
     * <p>Kept apart from the Meteor config so it survives a profile reset, and out of the
     * folder any other DM mod uses so the two never read each other's half-format.
     */
    public void openFor(String serverName) {
        threads.clear();

        Path dir = FabricLoader.getInstance().getGameDir()
            .resolve("new-addon").resolve("messages");
        file = dir.resolve(safeName(serverName) + ".tsv");

        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) return;

            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 4);
                if (parts.length < 4) continue;
                try {
                    add(parts[2], new DmMessage(Long.parseLong(parts[0]),
                        "in".equals(parts[1]), unescape(parts[3])), false);
                } catch (NumberFormatException ignored) {
                    // A torn line from an interrupted write. Skip it; the rest is still good.
                }
            }
        } catch (IOException e) {
            NewAddon.LOG.warn("[messages] could not read {}: {}", file, e.toString());
        }
    }

    /** Records a message and appends it to disk. */
    public void record(String peer, boolean incoming, String text) {
        add(peer, new DmMessage(System.currentTimeMillis(), incoming, text), true);
    }

    private void add(String peer, DmMessage message, boolean persist) {
        List<DmMessage> thread = threads.remove(peer);
        if (thread == null) thread = new ArrayList<>();

        thread.add(message);
        while (thread.size() > perThreadLimit) thread.remove(0);

        // Re-inserted last so the conversation list reads newest-first without a sort.
        threads.put(peer, thread);

        if (persist) append(peer, message);
    }

    private void append(String peer, DmMessage message) {
        if (file == null) return;
        String line = message.time() + "\t" + (message.incoming() ? "in" : "out") + "\t"
            + escape(peer) + "\t" + escape(message.text()) + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            NewAddon.LOG.warn("[messages] could not write {}: {}", file, e.toString());
        }
    }

    /** Peers, most recently active first. */
    public List<String> peers() {
        List<String> names = new ArrayList<>(threads.keySet());
        java.util.Collections.reverse(names);
        return names;
    }

    public List<DmMessage> thread(String peer) {
        return threads.getOrDefault(peer, List.of());
    }

    public boolean isEmpty() {
        return threads.isEmpty();
    }

    /** Ensures a conversation exists so it can be opened before anything has been said. */
    public void touch(String peer) {
        threads.computeIfAbsent(peer, k -> new ArrayList<>());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
    }

    /** A server address is not a filename; anything unusual becomes an underscore. */
    private static String safeName(String server) {
        String cleaned = server == null || server.isBlank() ? "singleplayer" : server;
        return cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
