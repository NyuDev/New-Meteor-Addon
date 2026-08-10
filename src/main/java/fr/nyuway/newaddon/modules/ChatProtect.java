package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.Coords;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.util.List;

/**
 * ChatProtect - stops coordinates leaving the client by accident.
 *
 * <p>On an anarchy server a base is only a base until somebody types where it is. That mistake
 * is made in a hurry, in a whisper meant for one person, and it cannot be taken back once the
 * packet is gone. So the check happens on the way out and the message simply does not go.
 *
 * <h2>Refusing, not asking</h2>
 * There is no "are you sure" here on purpose. A confirmation is answered in the same second and
 * with the same haste that typed the coordinates; the only guard worth having is one that stops
 * the message and makes you turn something off to send it. That is a deliberate second thought
 * rather than a reflex.
 *
 * <p>Every route out is covered: the chat box, commands - so {@code /msg} is caught too - and
 * the reply box in the message window, which sends through the client rather than the chat
 * screen and would otherwise walk straight past this.
 */
public class ChatProtect extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> blockCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("block-coordinates")
        .description("Refuse to send a message that reads like a location.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> minNumbers = sgGeneral.add(new IntSetting.Builder()
        .name("numbers-in-a-row")
        .description("How many numbers standing together count as a coordinate. Two catches an " +
                     "x and z pair, which is how most coordinates are actually given.")
        .defaultValue(2).min(2).max(3).sliderRange(2, 3)
        .build());

    private final Setting<Integer> magnitude = sgGeneral.add(new IntSetting.Builder()
        .name("magnitude")
        .description("A number this big, ignoring its sign, is taken as a coordinate whatever " +
                     "else is around it. Smaller ones need an x, y or z in front of them, so " +
                     "\"I got 3 4 5\" is a sentence and \"x3 y4 z5\" is a location.")
        .defaultValue(100).min(1).max(1000000).sliderRange(10, 5000)
        .build());

    private final Setting<Boolean> checkChat = sgGeneral.add(new BoolSetting.Builder()
        .name("check-chat")
        .description("Check ordinary chat messages.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> checkCommands = sgGeneral.add(new BoolSetting.Builder()
        .name("check-commands")
        .description("Check anything starting with a slash, so /msg and /w are covered.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> checkWhispers = sgGeneral.add(new BoolSetting.Builder()
        .name("check-messages")
        .description("Check replies sent from the LiveMessage window. Those go out through the " +
                     "client rather than the chat box, so they need saying separately.")
        .defaultValue(true)
        .build());

    private final Setting<List<String>> allowed = sgGeneral.add(new StringListSetting.Builder()
        .name("allowed-commands")
        .description("Commands never checked, by their first word. Baritone and Meteor take " +
                     "coordinates as arguments and are talking to your own client, not to the " +
                     "server.")
        .defaultValue(List.of("#goto", "#goal", "#thisway", "#mine", "#elytra", "#path"))
        .build());

    public ChatProtect() {
        super(NewAddon.CATEGORY, "chat-protect",
            "Refuses to send messages that read like coordinates.");
    }

    private static ChatProtect get() {
        return Modules.get() == null ? null : Modules.get().get(ChatProtect.class);
    }

    /**
     * Whether this text may be sent, complaining in chat when it may not.
     *
     * <p>Public so the message window can ask before sending a reply of its own. Says what it
     * found and how to override it, because a message that vanishes without explanation is
     * indistinguishable from a client that has broken.
     *
     * @param whisper true when this came from the message window rather than the chat box
     */
    public static boolean allow(String text, boolean whisper) {
        ChatProtect module = get();
        if (module == null || !module.isActive() || !module.blockCoords.get()) return true;

        if (whisper && !module.checkWhispers.get()) return true;
        if (!whisper) {
            boolean command = text != null && text.startsWith("/");
            if (command && !module.checkCommands.get()) return true;
            if (!command && !module.checkChat.get()) return true;
        }

        if (module.exempt(text)) return true;

        String found = Coords.find(text, module.minNumbers.get(), module.magnitude.get());
        if (found == null) return true;

        module.warning("Not sent: \"%s\" reads like coordinates.", found);
        module.info("Switch chat-protect off if you meant to send it.");
        return false;
    }

    /** Whether this is one of the client-side commands that take coordinates by design. */
    private boolean exempt(String text) {
        if (text == null || text.isBlank()) return false;

        String first = text.trim().split("\\s+")[0].toLowerCase();
        for (String prefix : allowed.get()) {
            if (prefix != null && !prefix.isBlank() && first.equals(prefix.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The chat box and its command line.
     *
     * <p>Meteor's own event, so this sees everything the chat screen sends - which is the common
     * case and the one made in a hurry.
     */
    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (!allow(event.message, false)) event.cancel();
    }
}
