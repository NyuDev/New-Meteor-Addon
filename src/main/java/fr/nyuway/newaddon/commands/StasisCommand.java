package fr.nyuway.newaddon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.nyuway.newaddon.modules.StasisPull;
import meteordevelopment.meteorclient.commands.Command;

import java.util.List;

/**
 * {@code .stasis} - the bots, from chat.
 *
 * <p>Configuring one is the module's own settings panel: a section each, with real controls.
 * This is for the things you want without opening a GUI - which one is which, and firing a
 * particular one. It also gives a bot past the eighth a key, since a Meteor macro runs a command.
 */
public class StasisCommand extends Command {

    public StasisCommand() {
        super("stasis", "List your stasis bots, fire one, or pick the default.");
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

            List<String> names = module.names();
            for (int i = 0; i < names.size(); i++) {
                info("%d. %s", i + 1, module.describe(names.get(i)));
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

                String name = StringArgumentType.getString(ctx, "bot");
                if (module.setDefault(name)) info("%s is the default bot.", name);
                else error("No bot called %s.", name);
                return OK;
            })));

        builder.executes(ctx -> {
            info("list, pull [bot], default <bot>. Bots are configured in the module.");
            return OK;
        });
    }
}
