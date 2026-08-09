package fr.nyuway.newaddon.utils.vc;

import fr.nyuway.newaddon.modules.ServerStats;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Markers - {@code {queue}} and friends, written into any text field and replaced on the way out.
 *
 * <p>Put {@code {queue}} on a sign or in a message and what is sent is the number. The point is
 * that the value is the one at the moment you send it, not the one at the moment you typed it,
 * which is what makes it worth having on a sign at all.
 *
 * <h2>Escaping</h2>
 * A backslash before the brace sends it as written: {@code \{queue}} arrives as {@code {queue}}.
 * There has to be a way to say the thing itself, or the feature quietly makes some sentences
 * impossible to type. A backslash before anything else is left alone, so Windows paths and the
 * rest survive untouched.
 *
 * <h2>Never waits</h2>
 * Every value comes from what the API client already has. A marker whose answer has not arrived
 * yet becomes {@code ?} and starts the request behind it - the next one you send will have it.
 * Sending a chat message is not a thing to block on a web request, however briefly.
 */
public final class Markers {

    private Markers() { }

    /** What a marker becomes when its answer has not arrived yet. */
    private static final String PENDING = "?";

    private static final Map<String, Supplier<String>> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("queue", () -> queue(q -> String.valueOf(VcTypes.or0(q.regular))));
        MARKERS.put("queue_prio", () -> queue(q -> String.valueOf(VcTypes.or0(q.prio))));
        MARKERS.put("eta", Markers::eta);

        MARKERS.put("online", () -> tablist(t -> String.valueOf(VcTypes.or0(t.count))));
        MARKERS.put("prio_count", () -> tablist(t -> String.valueOf(VcTypes.or0(t.prioCount))));
        MARKERS.put("bot_count", () -> tablist(t -> String.valueOf(VcTypes.or0(t.botCount))));

        MARKERS.put("mctime", () -> {
            VcTypes.TimeResponse t = VcApi.cached("/time", null, VcTypes.TimeResponse.class, 60_000L);
            return t == null ? PENDING : VcTypes.worldTime(VcTypes.or0(t.worldTime));
        });

        MARKERS.put("me", Markers::selfName);
        MARKERS.put("playtime", () -> self(s -> VcTypes.playtime(VcTypes.or0(s.playtimeSeconds))));
        MARKERS.put("playtime_month",
            () -> self(s -> VcTypes.playtime(VcTypes.or0(s.playtimeSecondsMonth))));
        MARKERS.put("firstseen", () -> self(s -> VcTypes.date(s.firstSeen)));
        MARKERS.put("lastseen", () -> self(s -> VcTypes.date(s.lastSeen)));
        MARKERS.put("deaths", () -> self(s -> String.valueOf(VcTypes.or0(s.deathCount))));
        MARKERS.put("kills", () -> self(s -> String.valueOf(VcTypes.or0(s.killCount))));
    }

    /** The marker names, for the command that lists them. */
    public static java.util.Set<String> names() {
        return MARKERS.keySet();
    }

    /** What one marker is worth right now, or {@code ?}. */
    public static String value(String name) {
        Supplier<String> marker = MARKERS.get(name);
        return marker == null ? null : marker.get();
    }

    /** Whether the text has anything worth expanding, so the common case costs one scan. */
    public static boolean present(String text) {
        return text != null && text.indexOf('{') >= 0;
    }

    /**
     * Replaces every marker in the text.
     *
     * <p>Scanned once, left to right. An unknown marker is left exactly as it was: it is far more
     * likely to be a brace someone meant to type than a marker misspelt, and eating it would be
     * the worse of the two mistakes.
     */
    public static String expand(String text) {
        if (!present(text)) return text;

        StringBuilder out = new StringBuilder(text.length() + 16);

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // A backslash before a marker means the marker itself. Before anything else it is
            // just a backslash, and stays one.
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int end = text.indexOf('}', i + 2);
                if (end != -1 && MARKERS.containsKey(text.substring(i + 2, end))) {
                    out.append(text, i + 1, end + 1);
                    i = end;
                    continue;
                }
                out.append(c);
                continue;
            }

            if (c != '{') {
                out.append(c);
                continue;
            }

            int end = text.indexOf('}', i + 1);
            if (end == -1) {
                out.append(c);
                continue;
            }

            String name = text.substring(i + 1, end);
            Supplier<String> marker = MARKERS.get(name);
            if (marker == null) {
                out.append(c);
                continue;
            }

            out.append(marker.get());
            i = end;
        }

        return out.toString();
    }

    // --- the values ---------------------------------------------------------

    private static String queue(java.util.function.Function<VcTypes.QueueData, String> read) {
        VcTypes.QueueData q = VcApi.cached("/queue", null, VcTypes.QueueData.class,
            ServerStats.queueTtl());
        return q == null ? PENDING : read.apply(q);
    }

    private static String tablist(
        java.util.function.Function<VcTypes.TablistInfoResponse, String> read) {

        VcTypes.TablistInfoResponse t = VcApi.cached("/tablist/info", null,
            VcTypes.TablistInfoResponse.class, 30_000L);
        return t == null ? PENDING : read.apply(t);
    }

    private static String self(java.util.function.Function<VcTypes.PlayerStats, String> read) {
        String name = selfName();
        if (name.equals(PENDING)) return PENDING;

        VcTypes.PlayerStats stats = VcApi.cached("/stats/player",
            VcApi.params("playerName", name), VcTypes.PlayerStats.class, ServerStats.playerTtl());
        return stats == null ? PENDING : read.apply(stats);
    }

    private static String selfName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return PENDING;

        // Through Profiles: GameProfile became a record at 1.21.10 and getName() went with it.
        String name = fr.nyuway.newaddon.utils.Profiles.nameOf(mc.player.getGameProfile());
        return name == null || name.isBlank() ? PENDING : name;
    }

    private static String eta() {
        VcTypes.QueueData q = VcApi.cached("/queue", null, VcTypes.QueueData.class,
            ServerStats.queueTtl());
        if (q == null) return PENDING;

        VcTypes.QueueEtaEquation e = VcApi.cached("/queue/eta-equation", null,
            VcTypes.QueueEtaEquation.class, 60 * 60_000L);
        if (e == null || e.factor == null || e.pow == null) return PENDING;

        int position = VcTypes.or0(q.regular);
        if (position <= 0) return "none";
        return VcTypes.playtime((long) (e.factor * Math.pow(position, e.pow)));
    }
}
