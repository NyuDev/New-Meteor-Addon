package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.Coords;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ChatProtect - what leaves the client, and what is worth reading of what arrives.
 *
 * <h2>Going out</h2>
 * A base is only a base until somebody types where it is, and that mistake is made in a hurry,
 * in a whisper meant for one person, and cannot be taken back once the packet is gone. So the
 * message does not go. There is no "are you sure": a confirmation gets answered in the same
 * second and with the same haste that typed the coordinates, and the only guard worth having is
 * one you have to switch off deliberately.
 *
 * <p>The word list is the same idea pointed elsewhere - a stream where certain things must not
 * be said out loud is a place where a filter earns its keep - and links are their own setting
 * because pasting one is rarely what you meant to do in a public anarchy chat.
 *
 * <h2>Coming in</h2>
 * The advertisement filter, which is the other half. People sell things in chat, in volume, with
 * a vocabulary that barely changes; hiding it is a display choice and costs nothing.
 *
 * <h2>Nothing happens while this is off</h2>
 * Every check goes through one place that asks whether the module is active before anything
 * else. Meteor only delivers events to a module while it is on - with one exception it is worth
 * knowing about: toggling a module off while the world is not loaded leaves it subscribed to the
 * event bus, so its handlers keep firing with the module marked inactive. The active check here
 * is what makes that harmless, and it is checked first, always.
 */
public class ChatProtect extends Module {

    private final SettingGroup sgOut = settings.getDefaultGroup();
    private final SettingGroup sgWords = settings.createGroup("Words and links");
    private final SettingGroup sgAds = settings.createGroup("Advertisements");

    // --- coordinates ---------------------------------------------------------

    private final Setting<Boolean> blockCoords = sgOut.add(new BoolSetting.Builder()
        .name("block-coordinates")
        .description("Refuse to send a message that reads like a location.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> minNumbers = sgOut.add(new IntSetting.Builder()
        .name("numbers-in-a-row")
        .description("How many numbers standing together count as a coordinate. Two catches an " +
                     "x and z pair, which is how most coordinates are actually given.")
        .defaultValue(2).min(2).max(3).sliderRange(2, 3)
        .visible(blockCoords::get)
        .build());

    private final Setting<Integer> magnitude = sgOut.add(new IntSetting.Builder()
        .name("magnitude")
        .description("A number this big, ignoring its sign, is taken as a coordinate whatever " +
                     "else is around it. Smaller ones need an x, y or z in front of them, so " +
                     "\"I got 3 4 5\" is a sentence and \"x3 y4 z5\" is a location.")
        .defaultValue(100).min(1).max(1000000).sliderRange(10, 5000)
        .visible(blockCoords::get)
        .build());

    private final Setting<Boolean> checkChat = sgOut.add(new BoolSetting.Builder()
        .name("check-chat")
        .description("Check ordinary chat messages.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> checkCommands = sgOut.add(new BoolSetting.Builder()
        .name("check-commands")
        .description("Check anything starting with a slash, so /msg and /w are covered.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> checkWhispers = sgOut.add(new BoolSetting.Builder()
        .name("check-messages")
        .description("Check replies sent from the LiveMessage window. Those go out through the " +
                     "client rather than the chat box, so they need saying separately.")
        .defaultValue(true)
        .build());

    private final Setting<List<String>> allowedPrefixes = sgOut.add(new StringListSetting.Builder()
        .name("client-prefixes")
        .description("Messages starting with any of these never reach the server, so they are " +
                     "never checked. Baritone's # and Meteor's own prefix are handled already; " +
                     "this is for anything else you have installed.")
        .defaultValue(List.of())
        .build());

    // --- words and links -----------------------------------------------------

    private final Setting<Boolean> blockWords = sgWords.add(new BoolSetting.Builder()
        .name("block-words")
        .description("Refuse to send a message containing one of the words below. For a stream, " +
                     "where some things must not be said out loud and the mistake is permanent.")
        .defaultValue(false)
        .build());

    private final Setting<List<String>> words = sgWords.add(new StringListSetting.Builder()
        .name("blocked-words")
        .description("Matched anywhere in the message, ignoring case. Yours to fill in: what " +
                     "must not be said depends entirely on where you are saying it.")
        .defaultValue(List.of())
        .visible(blockWords::get)
        .build());

    private final Setting<Boolean> blockLinks = sgWords.add(new BoolSetting.Builder()
        .name("block-links")
        .description("Refuse to send a message containing a web address or an invite link.")
        .defaultValue(false)
        .build());

    // --- advertisements ------------------------------------------------------

    private final Setting<Boolean> blockAds = sgAds.add(new BoolSetting.Builder()
        .name("hide-advertisements")
        .description("Hide incoming chat that matches the patterns below. People sell things in " +
                     "chat in volume, with a vocabulary that barely changes.")
        .defaultValue(false)
        .build());

    private final Setting<List<String>> adPatterns = sgAds.add(new StringListSetting.Builder()
        .name("ad-patterns")
        .description("Matched anywhere in an incoming message, ignoring case.")
        .defaultValue(List.of(
            "discord.gg", "discord.com", "/invite/", ".store", ".shop",
            "cheapest price", "cheapest kit", "cheap price", "cheap kit",
            "use code", "at checkout", "join now", "% off", "buy now",
            "rusherhack.org", "nox2b"))
        .visible(blockAds::get)
        .build());

    private final Setting<Boolean> debug = sgOut.add(new BoolSetting.Builder()
        .name("debug")
        .description("Write every decision to the game log, with whether this module was even " +
                     "on at the time. For settling an argument about what blocked something.")
        .defaultValue(false)
        .build());

    /** A web address in any of the forms people actually paste. */
    private static final Pattern LINK = Pattern.compile(
        "(?i)\\b(?:https?://|www\\.)\\S+|\\b[\\w-]+\\.(?:com|net|org|gg|io|store|shop|xyz|me|tv)\\b");

    public ChatProtect() {
        super(NewAddon.CATEGORY, "chat-protect",
            "Keeps coordinates, words and links from leaving, and hides advertisements.");
    }

    private static ChatProtect get() {
        return Modules.get() == null ? null : Modules.get().get(ChatProtect.class);
    }

    /**
     * Whether this text may be sent, saying why in chat when it may not.
     *
     * <p>Public so the message window can ask before sending a reply of its own, which does not
     * pass through the chat screen and so is not covered by the event below.
     *
     * @param whisper true when this came from the message window rather than the chat box
     */
    public static boolean allow(String text, boolean whisper) {
        ChatProtect module = get();

        // First, and before anything is even looked at. Nothing this module does happens while
        // it is switched off - not the checking, not the message, not the log line.
        if (module == null || !module.isActive()) return true;

        return module.judge(text, whisper);
    }

    private boolean judge(String text, boolean whisper) {
        if (text == null || text.isBlank()) return true;

        if (!whisper) {
            boolean command = text.startsWith("/");
            if (command && !checkCommands.get()) return log(text, true, "commands not checked");
            if (!command && !checkChat.get()) return log(text, true, "chat not checked");
        } else if (!checkWhispers.get()) {
            return log(text, true, "messages not checked");
        }

        if (staysLocal(text)) return log(text, true, "client command, never reaches the server");

        if (blockCoords.get()) {
            String found = Coords.find(text, minNumbers.get(), magnitude.get());
            if (found != null) return refuse(text, "\"" + found + "\" reads like coordinates");
        }

        if (blockWords.get()) {
            String lower = text.toLowerCase();
            for (String word : words.get()) {
                if (word != null && !word.isBlank() && lower.contains(word.trim().toLowerCase())) {
                    return refuse(text, "it contains \"" + word.trim() + "\"");
                }
            }
        }

        if (blockLinks.get() && LINK.matcher(text).find()) {
            return refuse(text, "it contains a link");
        }

        return log(text, true, "nothing to object to");
    }

    /** A message that never leaves this client is not this module's business. */
    private boolean staysLocal(String text) {
        String trimmed = text.trim();

        // Baritone takes coordinates as arguments by design and is talking to your own client.
        if (trimmed.startsWith("#")) return true;

        String prefix = Config.get().prefix.get();
        if (prefix != null && !prefix.isEmpty() && trimmed.startsWith(prefix)) return true;

        for (String own : allowedPrefixes.get()) {
            if (own != null && !own.isBlank() && trimmed.startsWith(own.trim())) return true;
        }
        return false;
    }

    private boolean refuse(String text, String why) {
        warning("Not sent: %s.", why);
        info("Switch chat-protect off if you meant to send it.");
        log(text, false, why);
        return false;
    }

    /** @return the verdict, so callers can end a line with this and read as an answer */
    private boolean log(String text, boolean allowed, String why) {
        if (debug.get()) {
            NewAddon.LOG.info("[chat-protect] active={} {} \"{}\" ({})",
                isActive(), allowed ? "allowed" : "BLOCKED", text, why);
        }
        return allowed;
    }

    /** The chat box and its command line, which is the common case and the hurried one. */
    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (!allow(event.message, false)) event.cancel();
    }

    /** Incoming chat, where the advertisements are. */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!blockAds.get()) return;

        String line = event.getMessage().getString().toLowerCase();
        for (String pattern : adPatterns.get()) {
            if (pattern != null && !pattern.isBlank()
                && line.contains(pattern.trim().toLowerCase())) {
                event.cancel();
                return;
            }
        }
    }
}
