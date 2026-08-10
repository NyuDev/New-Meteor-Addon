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
        /** Muted: their messages still show and are kept, only the sound and toast are held. */
        public boolean isMuted;
        /**
         * Messages from them not looked at yet.
         *
         * <p>Counted as they arrive rather than worked out by comparing timestamps against a
         * last-read mark. The mark would be tidier, but answering "how many" from it means
         * reading the conversation off disk, and the buddy list asks once per row per frame -
         * which is the shape of the bug that made dragging the window stutter.
         *
         * <p>An extra key in upstream's settings file. Its reader ignores what it does not
         * know, so the two ports still coexist.
         */
        public int unread;
    }

    private final Path root;
    private final Map<UUID, PeerSettings> peers = new HashMap<>();
    private final Map<UUID, List<Entry>> loaded = new HashMap<>();

    /**
     * When each conversation was last written, read once rather than asked of the disk.
     *
     * <p>Ordering used to call {@code Files.getLastModifiedTime} from inside the sort
     * comparator, and the list is rebuilt every frame: fifty-four conversations is about
     * three hundred comparisons, so six hundred filesystem calls per frame and better than
     * thirty thousand a second. That is where the dragging stutter came from.
     */
    private final Map<UUID, Long> lastActivity = new HashMap<>();

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
        lastActivity.clear();

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
                if (id == null) return;
                peers.computeIfAbsent(id, k -> new PeerSettings());

                // The one stat per file, taken here and never again.
                try {
                    lastActivity.put(id, Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                    lastActivity.put(id, 0L);
                }
            });
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not list {}: {}", msgs, e.toString());
        }
    }

    /**
     * Conversations, most recently written first, so the list reads like a chat client's. Keyed
     * off the message files, not the settings files - a settings file can exist for someone a
     * bundled port merely saw, and those are not conversations and have no place in the list.
     */
    public List<UUID> peers() {
        List<UUID> ids = new ArrayList<>(lastActivity.keySet());
        ids.sort((a, b) -> Long.compare(
            lastActivity.getOrDefault(b, 0L), lastActivity.getOrDefault(a, 0L)));
        return ids;
    }

    /**
     * The last name written down for someone, or null when nothing has been.
     *
     * <p>Null rather than a stand-in on purpose. This used to hand back the first eight
     * characters of the UUID, which reads like a name, was drawn as one, and was addressed a
     * whisper as one - so a conversation with anybody never messaged before went out to a player
     * who does not exist. Who someone is now is a question for {@code LiveMessage.displayName},
     * which can see the tab list; all this knows is who they were.
     */
    public String lastNameOf(UUID id) {
        PeerSettings s = peers.get(id);
        return s != null && s.lastName != null && !s.lastName.isBlank() ? s.lastName : null;
    }

    public PeerSettings settingsOf(UUID id) {
        return peers.computeIfAbsent(id, k -> new PeerSettings());
    }

    /** How many conversations there are, without sorting them to find out. */
    public int peerCount() {
        return lastActivity.size();
    }

    /** Messages from them not looked at yet. */
    public int unread(UUID id) {
        PeerSettings s = peers.get(id);
        return s == null ? 0 : s.unread;
    }

    /** Anyone with something unread, newest conversation first. */
    public List<UUID> withUnread() {
        List<UUID> out = new ArrayList<>();
        for (UUID id : peers()) {
            if (unread(id) > 0) out.add(id);
        }
        return out;
    }

    /** One more from them, waiting to be looked at. */
    public void noteUnread(UUID id) {
        PeerSettings s = settingsOf(id);
        s.unread++;
        saveSettings(id, s);
    }

    /** They have been looked at. Writes nothing when there was nothing to clear. */
    public void markRead(UUID id) {
        PeerSettings s = settingsOf(id);
        if (s.unread == 0) return;
        s.unread = 0;
        saveSettings(id, s);
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
        lastActivity.put(peer, entry.timestamp);

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

    /** Conversations pinned to reopen after a restart. Ours alone, kept beside upstream's files. */
    public java.util.Set<UUID> loadPinned() {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        Path file = root.resolve("pinned.txt");
        if (!Files.isRegularFile(file)) return out;

        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                try {
                    out.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    // A stray line; skip it and keep the rest.
                }
            }
        } catch (IOException e) {
            NewAddon.LOG.warn("[livemessage] could not read {}: {}", file, e.toString());
        }
        return out;
    }

    public void savePinned(java.util.Collection<UUID> pinned) {
        Path file = root.resolve("pinned.txt");
        try {
            Files.createDirectories(root);
            StringBuilder sb = new StringBuilder();
            for (UUID id : pinned) sb.append(id).append('\n');
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
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
