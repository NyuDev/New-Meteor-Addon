package fr.nyuway.newaddon.utils.vc;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The shapes 2b2t.vc answers in, and the two conversions needed to read them out loud.
 *
 * <p>Plain classes with public fields rather than records, because Gson fills them by name and
 * a missing key then leaves a null instead of throwing. The API's own schema marks nothing as
 * required, so half an answer is a thing that happens.
 */
public final class VcTypes {

    private VcTypes() { }

    // --- responses ----------------------------------------------------------

    public static final class QueueData {
        public Integer prio;
        public Integer regular;
        public String time;
    }

    public static final class QueueEtaEquation {
        public Double factor;
        public Double pow;
    }

    public static final class PlayerStats {
        public Integer chatsCount;
        public Integer deathCount;
        public String firstSeen;
        public Integer joinCount;
        public Integer killCount;
        public String lastSeen;
        public Integer leaveCount;
        public Integer playtimeSeconds;
        public Integer playtimeSecondsMonth;
        public Boolean prio;
    }

    public static final class SeenResponse {
        public String firstSeen;
        public String lastSeen;
    }

    public static final class PlaytimeResponse {
        public Integer playtimeSeconds;
        public String uuid;
    }

    public static final class SessionTimeLimitResponse {
        public Integer hours;
    }

    public static final class TimeResponse {
        public String lastUpdated;
        public Integer worldTime;
    }

    public static final class WordCount {
        public Integer count;
    }

    public static final class TablistInfoEntry {
        public Boolean bot;
        public String playerName;
        public Boolean prio;
        public String uuid;
    }

    public static final class TablistInfoResponse {
        public Integer botCount;
        public Integer count;
        public String header;
        public Integer nonPrioCount;
        public List<TablistInfoEntry> players;
        public Integer prioCount;
    }

    public static final class PlayerChat {
        public String chat;
        public String playerName;
        public String time;
        public String uuid;
    }

    public static final class ChatSearchResponse {
        public List<PlayerChat> chats;
        public Integer pageCount;
        public Integer total;
    }

    public static final class Connection {
        public String connection;
        public String time;
    }

    public static final class ConnectionsResponse {
        public List<Connection> connections;
        public Integer pageCount;
        public Integer total;
    }

    public static final class Death {
        public String deathMessage;
        public String killerMob;
        public String killerPlayerName;
        public String killerPlayerUuid;
        public String time;
        public String victimPlayerName;
        public String victimPlayerUuid;
        public String weaponName;
    }

    public static final class DeathsResponse {
        public List<Death> deaths;
        public Integer pageCount;
        public Integer total;
    }

    public static final class KillsResponse {
        public List<Death> kills;
        public Integer pageCount;
        public Integer total;
    }

    public static final class PlayerDeathOrKillCount {
        public Integer count;
        public String playerName;
        public String uuid;
    }

    public static final class PlayerDeathOrKillCountResponse {
        public List<PlayerDeathOrKillCount> players;
    }

    public static final class PlayerPlaytimeSecondsData {
        public String playerName;
        public Long playtimeSeconds;
        public String uuid;
    }

    public static final class PlaytimeAllTimeResponse {
        public List<PlayerPlaytimeSecondsData> players;
    }

    public static final class PlayerPlaytimeDaysData {
        public String playerName;
        public Double playtimeDays;
        public String uuid;
    }

    public static final class PlaytimeMonthResponse {
        public List<PlayerPlaytimeDaysData> players;
    }

    public static final class NamedPlayer {
        public String playerName;
        public String uuid;
    }

    public static final class PriorityPlayersResponse {
        public List<NamedPlayer> players;
    }

    public static final class BotsMonthResponse {
        public List<NamedPlayer> players;
    }

    // --- reading them out ----------------------------------------------------

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    /**
     * A date the API gave us, as a day and how long ago that was.
     *
     * <p>Both, because neither answers on its own: "2 Apr 2019" does not say how long, and
     * "six years ago" does not say when.
     */
    public static String date(String iso) {
        Instant when = instant(iso);
        if (when == null) return iso == null ? "?" : iso;
        return STAMP.format(when) + " (" + ago(when) + ")";
    }

    /** How long ago, at the coarsest unit that still says something. */
    public static String ago(Instant when) {
        Duration d = Duration.between(when, Instant.now());
        if (d.isNegative()) return "just now";

        long days = d.toDays();
        if (days >= 365) return plural(days / 365, "year");
        if (days >= 30) return plural(days / 30, "month");
        if (days >= 1) return plural(days, "day");

        long hours = d.toHours();
        if (hours >= 1) return plural(hours, "hour");

        long minutes = d.toMinutes();
        return minutes >= 1 ? plural(minutes, "minute") : "just now";
    }

    /** Seconds of playtime as something a person reads: days and hours, or hours and minutes. */
    public static String playtime(long seconds) {
        if (seconds <= 0) return "none";

        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;

        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    /** A Minecraft world time in ticks as a clock face. Day starts at 6:00, tick zero. */
    public static String worldTime(int ticks) {
        int total = (int) (((ticks % 24000) + 24000 + 6000) % 24000);
        return String.format("%02d:%02d", total / 1000, (int) ((total % 1000) / 1000.0 * 60));
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException ignored) {
            // Not every field comes back with a zone on it.
        }
        try {
            return java.time.LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s") + " ago";
    }

    /** Null-safe integer, for the many fields the schema does not promise. */
    public static int or0(Integer value) {
        return value == null ? 0 : value;
    }
}
