package fr.nyuway.newaddon.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stasis bots you have, one line each, each with its own everything.
 *
 * <h2>Why a text line and not a settings group</h2>
 * Meteor's settings are a flat list: there is no repeatable group, so "several bots, each with
 * its own mode, its own trigger words and its own endpoint" cannot be expressed as controls.
 * What it does have is an editable list of strings, so a bot is one line - and the line is
 * written to be read by a person, not parsed by one:
 *
 * <pre>
 * home; mode=http; url=http://nyuway.fr:6969; secret=hunter2
 * base; mode=whisper; to=Shasync; cmd=/msg; say=!home, !tp
 * spawn; mode=chat; say=!spawn
 * </pre>
 *
 * <p>Label first, then named fields in any order. A bot carries its <em>whole</em>
 * configuration: the mode is per bot because one pearl might be answered by a bot on a server
 * you can whisper and another by StasisBot's HTTP channel, and the trigger words are per bot
 * because two bots listening for the same word both fire. Anything left out falls back to the
 * module's own settings, which is what makes a one-line bot legal.
 *
 * <p>Fields, with the aliases each accepts:
 * <ul>
 *   <li>{@code mode} - chat, whisper or http</li>
 *   <li>{@code say} ({@code msg}, {@code message}, {@code messages}) - trigger words, comma
 *       separated; one is picked at random per pull</li>
 *   <li>{@code to} ({@code bot}, {@code name}) - the account a whisper goes to</li>
 *   <li>{@code cmd} ({@code command}) - the whisper command, for servers that do not use
 *       {@code /msg}</li>
 *   <li>{@code url} ({@code endpoint}, {@code host}) - the HTTP control server</li>
 *   <li>{@code secret} ({@code key}, {@code password}) - the shared secret, HTTP only</li>
 * </ul>
 *
 * <p>The older positional form, {@code label | mode | target | secret}, is still read, so a
 * config written against the first version of this keeps working.
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
     * One bot, and everything about it.
     *
     * <p>Empty fields mean "not said here", not "empty": the module fills them from its own
     * settings before the bot is used. That is the difference between a bot that inherits the
     * default trigger word and one that has none.
     *
     * @param label     what you call it, and what the command takes
     * @param mode      how to reach it, or null to inherit
     * @param messages  its own trigger words, or empty to inherit
     * @param command   its own whisper command, or empty to inherit
     * @param target    the account to whisper, or the URL to call
     * @param secret    the shared secret, HTTP only
     */
    public record Bot(String label, Mode mode, List<String> messages, String command,
                      String target, String secret) {

        /** A copy with one field replaced, or null when there is no such field. */
        public Bot with(String field, String value) {
            String name = field.toLowerCase(Locale.ROOT);
            return switch (name) {
                case "mode" -> new Bot(label, parseMode(value), messages, command, target, secret);
                case "say", "msg", "message", "messages" ->
                    new Bot(label, mode, splitList(value), command, target, secret);
                case "cmd", "command" -> new Bot(label, mode, messages, value.trim(), target, secret);
                case "to", "bot", "name", "url", "endpoint", "host", "target" ->
                    new Bot(label, mode, messages, command, value.trim(), secret);
                case "secret", "key", "password" ->
                    new Bot(label, mode, messages, command, target, value.trim());
                default -> null;
            };
        }
    }

    /** The field names {@code .stasis set} takes, for the message that lists them. */
    public static final String FIELDS = "mode, say, to, url, cmd, secret";

    // --- reading -------------------------------------------------------------

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

        // The first version of this took four fields separated by bars and nothing else. A line
        // in that shape still means what it meant then.
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
            if (part.isEmpty()) continue;

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

    /** {@code label | mode | target | secret}, as the first version of this wrote it. */
    private static Bot parseLegacy(String line) {
        String[] parts = line.split("\\|", -1);
        String label = parts[0].trim();
        if (label.isEmpty()) return null;

        Mode mode = parts.length > 1 ? parseMode(parts[1]) : null;
        String target = parts.length > 2 ? parts[2].trim() : "";
        String secret = parts.length > 3 ? parts[3].trim() : "";

        return new Bot(label, mode, List.of(), "", target, secret);
    }

    /** Strips an optional {@code label=} prefix, so both spellings of the first field work. */
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

    // --- writing -------------------------------------------------------------

    /**
     * Back to one line.
     *
     * <p>Only what the bot actually says is written, so a bot that inherits everything stays the
     * one word it was typed as rather than growing four empty fields the first time it is edited.
     */
    public static String write(Bot bot) {
        StringBuilder out = new StringBuilder(bot.label());

        if (bot.mode() != null) out.append("; mode=").append(bot.mode().name().toLowerCase(Locale.ROOT));
        if (!bot.messages().isEmpty()) out.append("; say=").append(String.join(", ", bot.messages()));
        if (!bot.command().isBlank()) out.append("; cmd=").append(bot.command());

        if (!bot.target().isBlank()) {
            out.append(bot.mode() == Mode.Http ? "; url=" : "; to=").append(bot.target());
        }
        if (!bot.secret().isBlank()) out.append("; secret=").append(bot.secret());

        return out.toString();
    }

    /** The lines for a whole list, ready to hand back to the setting. */
    public static List<String> write(List<Bot> bots) {
        List<String> lines = new ArrayList<>(bots.size());
        for (Bot bot : bots) lines.add(write(bot));
        return lines;
    }

    // --- picking -------------------------------------------------------------

    /**
     * The bot to use, by label.
     *
     * @param label     what to look for; blank or unknown falls back to the default one
     * @param preferred the label marked default, which itself falls back to the first
     */
    public static Bot pick(List<Bot> bots, String label, String preferred) {
        if (bots.isEmpty()) return null;

        Bot found = byLabel(bots, label);
        if (found != null) return found;

        found = byLabel(bots, preferred);
        return found != null ? found : bots.get(0);
    }

    /** The bot with exactly that label, or null. */
    public static Bot byLabel(List<Bot> bots, String label) {
        if (label == null || label.isBlank()) return null;

        for (Bot bot : bots) {
            if (bot.label().equalsIgnoreCase(label.trim())) return bot;
        }
        return null;
    }

    /** Whether a bot has what its own mode needs to send anything. */
    public static boolean usable(Bot bot) {
        return missing(bot) == null;
    }

    /** What a bot is missing, for a message that says so. Null when it is fine. */
    public static String missing(Bot bot) {
        if (bot == null) return "a bot";

        Mode mode = bot.mode() == null ? Mode.Chat : bot.mode();
        return switch (mode) {
            case Chat -> bot.messages().isEmpty() ? "a trigger word (say=)" : null;
            case Whisper -> {
                if (bot.target().isBlank()) yield "an account to whisper (to=)";
                yield bot.messages().isEmpty() ? "a trigger word (say=)" : null;
            }
            case Http -> {
                if (bot.target().isBlank()) yield "an endpoint (url=)";
                yield bot.secret().isBlank() ? "a secret (secret=)" : null;
            }
        };
    }
}
