package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.modules.StasisPull;
import fr.nyuway.newaddon.utils.StasisBots;
import meteordevelopment.meteorclient.commands.Command;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code .stasis} - the bots, from chat.
 *
 * <p>Everything here can be done by editing the {@code bots} list in the module by hand; this
 * exists so that nobody has to. Creating a bot and setting one field at a time is what the
 * settings panel would offer if Meteor had a repeatable settings group, and a command is the
 * closest thing to it that works on twelve Meteor versions.
 *
 * <p>It also gives every bot a key: Meteor macros run a command, so anything past the six
 * keybind slots in the module can be bound to {@code .stasis pull &lt;label&gt;}.
 */
public class StasisCommand extends Command {

    public StasisCommand() {
        super("stasis", "Add, configure and fire stasis bots.");
    }

    private static final int OK = com.mojang.brigadier.Command.SINGLE_SUCCESS;

    @Override
    //? if >=26.1 {
    /*public void build(LiteralArgumentBuilder<net.minecraft.client.multiplayer.ClientSuggestionProvider> builder) {
    *///?} else {
    public void build(LiteralArgumentBuilder<net.minecraft.commands.SharedSuggestionProvider> builder) {
    //?}
        builder.then(literal("list").executes(ctx -> {
            StasisPull module = StasisPull.get();
            if (module == null) return OK;

            List<StasisBots.Bot> bots = module.parsed();
            if (bots.isEmpty()) {
                info("No bot list; the defaults in the module are the one bot.");
                return OK;
            }

            String preferred = module.defaultLabel();
            for (int i = 0; i < bots.size(); i++) {
                StasisBots.Bot bot = module.filled(bots.get(i));
                String missing = StasisBots.missing(bot);

                // The secret is never printed: this ends up in a screenshot eventually.
                info("%d. %s (%s)%s%s", i + 1, bot.label(), bot.mode().name().toLowerCase(),
                    bot.label().equalsIgnoreCase(preferred) ? " - default" : "",
                    missing == null ? "" : " - needs " + missing);
            }
            return OK;
        }));

        builder.then(literal("show")
            .then(argument("bot", StringArgumentType.word()).executes(ctx -> {
                StasisPull module = StasisPull.get();
                if (module == null) return OK;

                String label = StringArgumentType.getString(ctx, "bot");
                StasisBots.Bot bot = StasisBots.byLabel(module.parsed(), label);
                if (bot == null) {
                    error("No bot called %s.", label);
                    return OK;
                }

                StasisBots.Bot full = module.filled(bot);
                info("%s: mode=%s", full.label(), full.mode().name().toLowerCase());
                info("  say: %s", String.join(", ", full.messages()));

                switch (full.mode()) {
                    case Chat -> { }
                    case Whisper -> info("  to: %s (%s)", full.target(), full.command());
                    case Http -> info("  url: %s, secret: %s", full.target(),
                        full.secret().isBlank() ? "not set" : "set");
                }

                String missing = StasisBots.missing(full);
                if (missing != null) warning("  needs %s", missing);
                return OK;
            })));

        builder.then(literal("add")
            .then(argument("bot", StringArgumentType.word())
                .executes(ctx -> add(StringArgumentType.getString(ctx, "bot"), null))
                .then(argument("mode", StringArgumentType.word()).executes(ctx ->
                    add(StringArgumentType.getString(ctx, "bot"),
                        StringArgumentType.getString(ctx, "mode"))))));

        builder.then(literal("remove")
            .then(argument("bot", StringArgumentType.word()).executes(ctx -> {
                StasisPull module = StasisPull.get();
                if (module == null) return OK;

                String label = StringArgumentType.getString(ctx, "bot");
                List<StasisBots.Bot> bots = new ArrayList<>(module.parsed());

                StasisBots.Bot bot = StasisBots.byLabel(bots, label);
                if (bot == null) {
                    error("No bot called %s.", label);
                    return OK;
                }

                bots.remove(bot);
                module.store(bots);
                info("%s is gone.", bot.label());
                return OK;
            })));

        builder.then(literal("set")
            .then(argument("bot", StringArgumentType.word())
                .then(argument("field", StringArgumentType.word())
                    // Greedy, so a list of trigger words or a URL with spaces in it arrives whole.
                    .then(argument("value", StringArgumentType.greedyString()).executes(ctx -> {
                        StasisPull module = StasisPull.get();
                        if (module == null) return OK;

                        String label = StringArgumentType.getString(ctx, "bot");
                        String field = StringArgumentType.getString(ctx, "field");
                        String value = StringArgumentType.getString(ctx, "value");

                        List<StasisBots.Bot> bots = new ArrayList<>(module.parsed());
                        StasisBots.Bot bot = StasisBots.byLabel(bots, label);
                        if (bot == null) {
                            error("No bot called %s.", label);
                            return OK;
                        }

                        StasisBots.Bot changed = bot.with(field, value);
                        if (changed == null) {
                            error("No field called %s. Try: %s", field, StasisBots.FIELDS);
                            return OK;
                        }
                        if (field.equalsIgnoreCase("mode") && changed.mode() == null) {
                            error("Mode is chat, whisper or http.");
                            return OK;
                        }

                        bots.set(bots.indexOf(bot), changed);
                        module.store(bots);

                        // The value is echoed back for every field but the secret, where the
                        // whole point is that it does not appear anywhere it could be read.
                        info(field.equalsIgnoreCase("secret")
                            ? changed.label() + ": secret set."
                            : changed.label() + ": " + field + " is now " + value + ".");
                        return OK;
                    })))));

        builder.then(literal("pull")
            .executes(ctx -> {
                StasisPull module = StasisPull.get();
                if (module != null) module.pull();
                return OK;
            })
            .then(argument("bot", StringArgumentType.word()).executes(ctx -> {
                StasisPull module = StasisPull.get();
                if (module != null) module.pull(StringArgumentType.getString(ctx, "bot"));
                return OK;
            })));

        builder.then(literal("default")
            .then(argument("bot", StringArgumentType.word()).executes(ctx -> {
                StasisPull module = StasisPull.get();
                if (module == null) return OK;

                String label = StringArgumentType.getString(ctx, "bot");
                if (module.setDefault(label)) info("%s is the default bot.", label);
                else error("No bot called %s.", label);
                return OK;
            })));

        builder.executes(ctx -> {
            StasisPull module = StasisPull.get();
            if (module == null) return OK;

            String preferred = module.defaultLabel();
            info(preferred.isEmpty()
                ? "One bot, configured in the module's own settings."
                : "Default bot: " + preferred + ".");
            info("list, show <bot>, add <bot> [mode], set <bot> <field> <value>, remove <bot>, " +
                 "default <bot>, pull [bot]");
            return OK;
        });
    }

    /** Creates a bot with nothing but a label and possibly a mode; {@code set} fills the rest. */
    private int add(String label, String modeName) {
        StasisPull module = StasisPull.get();
        if (module == null) return OK;

        List<StasisBots.Bot> bots = new ArrayList<>(module.parsed());
        if (StasisBots.byLabel(bots, label) != null) {
            error("There is already a bot called %s.", label);
            return OK;
        }

        StasisBots.Mode mode = null;
        if (modeName != null) {
            mode = StasisBots.parseMode(modeName);
            if (mode == null) {
                error("Mode is chat, whisper or http.");
                return OK;
            }
        }

        StasisBots.Bot bot = new StasisBots.Bot(label, mode, List.of(), "", "", "");
        bots.add(bot);
        module.store(bots);

        info("Added %s.", label);

        // Said straight away rather than left to be discovered at the moment it is needed, which
        // is by definition the moment somebody is chasing you.
        String needs = StasisBots.missing(module.filled(bot));
        if (needs != null) warning("It still needs %s: .stasis set %s ...", needs, label);
        return OK;
    }
}
