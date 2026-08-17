package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.modules.StasisPull;
import fr.nyuway.newaddon.utils.StasisBots;
import meteordevelopment.meteorclient.commands.Command;

import java.util.List;

/**
 * {@code .stasis} - the bots, from chat.
 *
 * <p>Six of them can have a key of their own in the module; this is for the seventh, for
 * checking which one is the default without opening the GUI, and for Meteor macros, which run
 * a command and so can bind anything this can do to any key.
 */
public class StasisCommand extends Command {

    public StasisCommand() {
        super("stasis", "Pull with a particular stasis bot, or pick the default one.");
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
                info("No bot list; the plain settings are being used.");
                return OK;
            }

            String preferred = module.defaultLabel();
            for (int i = 0; i < bots.size(); i++) {
                StasisBots.Bot bot = bots.get(i);
                // The secret is never printed: this ends up in a screenshot eventually.
                info("%d. %s (%s)%s", i + 1, bot.label(), bot.mode().name().toLowerCase(),
                    bot.label().equalsIgnoreCase(preferred) ? " - default" : "");
            }
            return OK;
        }));

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
            return OK;
        });
    }
}
