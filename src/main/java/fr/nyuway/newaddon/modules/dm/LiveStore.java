package fr.nyuway.newaddon.modules.dm;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import fr.nyuway.newaddon.NewAddon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Livemessage's own storage, read and written in place.
 *
 * <h2>Why this format and not a better one</h2>
 * It is rebane2001's, and his history is already sitting in it - fifty-four conversations in
 * this instance alone. A neater format of my own would have meant that history staying
 * invisible and two mods writing two sets of files about the same conversations. Matching the
 * layout exactly is what lets this and the existing addon be open at once and agree:
 *
 * <pre>
 * meteor-client/livemessage/messages/&lt;peer-uuid&gt;.jsonl
 *     {"message":"...","sentByMe":true,"timestamp":1785901395303,"myUUID":"..."}
 * meteor-client/livemessage/settings/&lt;peer-uuid&gt;.json
 *     {"customColor":0,"lastName":"Qw0rds"}
 * meteor-client/livemessage/patterns/{from,to}Patterns.txt
 * </pre>
 *
 * <p>Conversations are keyed by the other player's UUID, not their name - the point of which
 * is that a rename does not split a thread in two, and a name taken over by someone else does
 * not merge two people into one. {@code lastName} is only what to print.
 */
public final class LiveStore {

    private static final Gson GSON = new Gson();

    /** One line of a conversation, field for field as it sits on disk. */
    public static final class Entry {
        public String message;
        public boolean sentByMe;
        public long timestamp;
        public String myUUID;
    }

    /** The per-peer file beside the messages. Upstream calls this ChatSettings. */
    public static final class PeerSettings {
        public boolean isFriend;
        public boolean isBlocked;
        public int customColor;
        public String lastName;
    }

    private final Path root;
    private final Map<UUID, PeerSettings> peers = new HashMap<>();
    private final Map<UUID, List<Entry>> loaded = new HashMap<>();

    public LiveStore() {
        root = FabricLoader.getInstance().getGameDir()
            .resolve("meteor-client").resolve("livemessage");
    }

    public Path folder() {
        return root;
    }

    /**
     * Reads the conversation list.
     *
     * <p>Only the small settings files are read here. The message files are opened when a
     * conversation is actually looked at: fifty-four of them, some thousands of lines long, is
     * not something to do on the tick a module is switched on.
     */
    public void loadIndex() {
        peers.clear();
        loaded.clear();

        Path dir = root.resolve("settings");
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                UUID id = uuidOf(p, ".json");
                if (id == null) return;
                peers.put(id, readSettings(p));
            });
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not list {}: {}", dir, e.toString());
        }

        // A conversation with no settings file still exists; upstream writes settings lazily.
        Path msgs = root.resolve("messages");
        if (!Files.isDirectory(msgs)) return;

        try (Stream<Path> files = Files.list(msgs)) {
            files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(p -> {
                UUID id = uuidOf(p, ".jsonl");
                if (id != null) peers.computeIfAbsent(id, k -> new PeerSettings());
            });
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not list {}: {}", msgs, e.toString());
        }
    }

    /** Peers, most recently written first, so the list reads like a chat client's. */
    public List<UUID> peers() {
        List<UUID> ids = new ArrayList<>(peers.keySet());
        ids.sort((a, b) -> Long.compare(lastWrite(b), lastWrite(a)));
        return ids;
    }

    public String nameOf(UUID id) {
        PeerSettings s = peers.get(id);
        return s != null && s.lastName != null && !s.lastName.isBlank()
            ? s.lastName : id.toString().substring(0, 8);
    }

    public PeerSettings settingsOf(UUID id) {
        return peers.computeIfAbsent(id, k -> new PeerSettings());
    }

    /** UUID of a conversation whose last known name matches, or null. */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, PeerSettings> e : peers.entrySet()) {
            PeerSettings s = e.getValue();
            if (s.lastName != null && s.lastName.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Messages of one conversation, read from disk the first time it is opened. */
    public List<Entry> thread(UUID id) {
        List<Entry> cached = loaded.get(id);
        if (cached != null) return cached;

        List<Entry> entries = new ArrayList<>();
        Path file = messageFile(id);

        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    try {
                        Entry entry = GSON.fromJson(line, Entry.class);
                        if (entry != null && entry.message != null) entries.add(entry);
                    } catch (JsonSyntaxException ignored) {
                        // A line torn by an interrupted write. Skip it, keep the rest - which
                        // is the whole reason this is one object per line and not one array.
                    }
                }
            } catch (IOException e) {
                NewAddon.LOG.warn("[livemessage] could not read {}: {}", file, e.toString());
            }
        }

        loaded.put(id, entries);
        return entries;
    }

    /** Appends a message, in the same shape upstream writes. */
    public void record(UUID peer, String name, boolean sentByMe, String text, UUID self) {
        Entry entry = new Entry();
        entry.message = text;
        entry.sentByMe = sentByMe;
        entry.timestamp = System.currentTimeMillis();
        entry.myUUID = self == null ? "" : self.toString();

        thread(peer).add(entry);

        try {
            Files.createDirectories(root.resolve("messages"));
            Files.writeString(messageFile(peer), GSON.toJson(entry) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not append {}: {}", messageFile(peer), e.toString());
        }

        // Keep the printed name current, the one thing that does change about a player.
        PeerSettings settings = settingsOf(peer);
        if (name != null && !name.equals(settings.lastName)) {
            settings.lastName = name;
            saveSettings(peer, settings);
        }
    }

    public void saveSettings(UUID peer, PeerSettings settings) {
        peers.put(peer, settings);
        Path file = root.resolve("settings").resolve(peer + ".json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not write {}: {}", file, e.toString());
        }
    }

    private PeerSettings readSettings(Path file) {
        try {
            PeerSettings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                PeerSettings.class);
            return s == null ? new PeerSettings() : s;
        } catch (IOException | JsonSyntaxException e) {
            return new PeerSettings();
        }
    }

    private Path messageFile(UUID id) {
        return root.resolve("messages").resolve(id + ".jsonl");
    }

    private long lastWrite(UUID id) {
        try {
            Path f = messageFile(id);
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Filename to UUID, or null when the file is not one of ours. */
    private static UUID uuidOf(Path file, String suffix) {
        String name = file.getFileName().toString();
        try {
            return UUID.fromString(name.substring(0, name.length() - suffix.length()));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }
}
