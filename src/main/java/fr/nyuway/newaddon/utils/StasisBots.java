package fr.nyuway.newaddon.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * The stasis bots you have, read out of one line each.
 *
 * <h2>Why a text line and not a settings group</h2>
 * Meteor's settings are a flat list: there is no repeatable group, so "several bots, each with a
 * mode and an address" cannot be expressed as controls. What it does have is an editable list of
 * strings, so a bot is one line, and the line is meant to be read by a person:
 *
 * <pre>
 * home | http | http://nyuway.fr:6969 | mysecret
 * spawn | whisper | Shasync
 * backup | chat
 * </pre>
 *
 * <p>Label first because that is what you refer to it by; everything after it is optional and
 * means whatever the mode needs. Blank fields and stray spaces are forgiven - the format exists
 * to be typed by hand at two in the morning.
 */
public final class StasisBots {

    private StasisBots() { }

    /** How a bot is reached. Matches StasisPull's own modes, by name, ignoring case. */
    public enum Mode { Chat, Whisper, Http }

    /**
     * @param label  what you call it, and what the command takes
     * @param mode   how to reach it
     * @param target the bot's name for a whisper, or the endpoint for HTTP; empty for chat
     * @param secret the shared secret, HTTP only
     */
    public record Bot(String label, Mode mode, String target, String secret) { }

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

        String[] parts = line.split("\\|", -1);
        String label = parts[0].trim();
        if (label.isEmpty()) return null;

        Mode mode = Mode.Chat;
        if (parts.length > 1) {
            String wanted = parts[1].trim();
            for (Mode candidate : Mode.values()) {
                if (candidate.name().equalsIgnoreCase(wanted)) {
                    mode = candidate;
                    break;
                }
            }
        }

        String target = parts.length > 2 ? parts[2].trim() : "";
        String secret = parts.length > 3 ? parts[3].trim() : "";

        return new Bot(label, mode, target, secret);
    }

    /**
     * The bot to use, by label.
     *
     * @param label what to look for; blank or unknown falls back to the default one
     * @param preferred the label marked default, which itself falls back to the first
     */
    public static Bot pick(List<Bot> bots, String label, String preferred) {
        if (bots.isEmpty()) return null;

        Bot found = byLabel(bots, label);
        if (found != null) return found;

        found = byLabel(bots, preferred);
        return found != null ? found : bots.get(0);
    }

    private static Bot byLabel(List<Bot> bots, String label) {
        if (label == null || label.isBlank()) return null;

        for (Bot bot : bots) {
            if (bot.label().equalsIgnoreCase(label.trim())) return bot;
        }
        return null;
    }

    /** Whether a bot has what its own mode needs to send anything. */
    public static boolean usable(Bot bot) {
        if (bot == null) return false;
        return switch (bot.mode()) {
            case Chat -> true;
            case Whisper -> !bot.target().isBlank();
            case Http -> !bot.target().isBlank() && !bot.secret().isBlank();
        };
    }
}
