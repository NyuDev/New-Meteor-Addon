package fr.nyuway.newaddon.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the text lines an older version of StasisPull kept its bots in.
 *
 * <p>The bots live in the settings panel now - one section each, with real controls. This is
 * only here so that upgrading does not silently empty a configuration somebody typed out: the
 * lines are read once and turned into sections, and after that nothing calls this.
 *
 * <p>Two shapes were shipped. Named fields:
 * <pre>home; mode=http; url=http://host:6969; secret=hunter2</pre>
 * and, before that, four positional fields:
 * <pre>home | http | http://host:6969 | hunter2</pre>
 */
public final class StasisBots {

    private StasisBots() { }

    /** How a bot is reached. */
    public enum Mode {
        /** Say a trigger word in public chat. */
        Chat,
        /** Whisper a trigger word to the bot. */
        Whisper,
        /** StasisBot's encrypted HTTP control channel. */
        Http
    }

    /**
     * One bot as an old config had it. Empty fields mean the line did not say.
     *
     * @param label    what it was called
     * @param mode     how to reach it, or null when the line did not say
     * @param messages trigger words
     * @param command  whisper command
     * @param target   the account to whisper, or the URL to call
     * @param secret   shared secret, HTTP only
     */
    public record Bot(String label, Mode mode, List<String> messages, String command,
                      String target, String secret) { }

    /** Reads the configured lines, skipping any that say nothing usable. */
    public static List<Bot> parse(List<String> lines) {
        List<Bot> bots = new ArrayList<>();
        if (lines == null) return bots;

        for (String line : lines) {
            Bot bot = parseOne(line);
            if (bot != null) bots.add(bot);
        }
        return bots;
    }

    /** One line, or null when there is no label to call it by. */
    public static Bot parseOne(String line) {
        if (line == null || line.isBlank()) return null;
        if (line.contains("|") && !line.contains("=")) return parseLegacy(line);

        String[] parts = line.split(";");
        String label = strip(parts[0]);
        if (label.isEmpty()) return null;

        Mode mode = null;
        List<String> messages = List.of();
        String command = "";
        String target = "";
        String secret = "";

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            if (eq < 0) continue;

            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();

            switch (key) {
                case "mode" -> mode = parseMode(value);
                case "say", "msg", "message", "messages" -> messages = splitList(value);
                case "cmd", "command" -> command = value;
                case "to", "bot", "name", "url", "endpoint", "host", "target" -> target = value;
                case "secret", "key", "password" -> secret = value;
                default -> { }
            }
        }

        return new Bot(label, mode, messages, command, target, secret);
    }

    /** {@code label | mode | target | secret}, as the first version of the list wrote it. */
    private static Bot parseLegacy(String line) {
        String[] parts = line.split("\\|", -1);
        String label = parts[0].trim();
        if (label.isEmpty()) return null;

        Mode mode = parts.length > 1 ? parseMode(parts[1]) : null;
        String target = parts.length > 2 ? parts[2].trim() : "";
        String secret = parts.length > 3 ? parts[3].trim() : "";

        return new Bot(label, mode, List.of(), "", target, secret);
    }

    private static String strip(String part) {
        String text = part.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("label=")) text = text.substring(6);
        return text.trim();
    }

    /** A mode by name, ignoring case, or null when it is not one. */
    public static Mode parseMode(String text) {
        if (text == null) return null;

        String wanted = text.trim();
        for (Mode mode : Mode.values()) {
            if (mode.name().equalsIgnoreCase(wanted)) return mode;
        }
        return null;
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        for (String piece : value.split(",")) {
            String word = piece.trim();
            if (!word.isEmpty()) out.add(word);
        }
        return out;
    }
}
